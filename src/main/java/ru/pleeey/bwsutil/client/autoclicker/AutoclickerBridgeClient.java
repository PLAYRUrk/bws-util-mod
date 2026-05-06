package ru.pleeey.bwsutil.client.autoclicker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutoclickerBridgeClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 25566;
    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final int RECONNECT_DELAY_MS = 2_000;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Object SEND_LOCK = new Object();

    private static volatile Socket socket;
    private static volatile BufferedWriter writer;
    private static volatile BufferedReader reader;

    private static final Pattern ACTIVE_FIELD = Pattern.compile("\"active\"\\s*:\\s*(true|false)");
    private static final Pattern LMB_FIELD = Pattern.compile("\"lmb_enabled\"\\s*:\\s*(true|false)");
    private static final Pattern RMB_FIELD = Pattern.compile("\"rmb_enabled\"\\s*:\\s*(true|false)");
    private static final Object STATE_LOCK = new Object();

    private static volatile Boolean lastKnownActive = true;
    private static volatile Boolean lastKnownLmbEnabled = true;
    private static volatile Boolean lastKnownRmbEnabled = true;
    // User-intended channel state (must survive temporary suppression by the mod).
    private static volatile Boolean desiredLmbEnabled = true;
    private static volatile Boolean desiredRmbEnabled = true;
    private static volatile Boolean sentActive;
    private static volatile Boolean sentLmbEnabled;
    private static volatile Boolean sentRmbEnabled;
    private static volatile Boolean pendingLmbCommand;
    private static volatile Boolean pendingRmbCommand;
    private static volatile long pendingLmbAtMs;
    private static volatile long pendingRmbAtMs;

    private static volatile boolean suppressLmbByMod;
    private static volatile boolean suppressRmbByMod;
    private static volatile Boolean lmbBeforeSuppression;
    private static volatile Boolean rmbBeforeSuppression;
    private static volatile long lastSuppressionPulseAtMs;
    private static volatile long lastUnsuppressedReconcileAtMs;
    private static final long STATUS_STALE_GUARD_MS = 900L;

    private AutoclickerBridgeClient() {}

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        RUNNING.set(true);
        Thread thread = new Thread(AutoclickerBridgeClient::runConnectionLoop, "bws-util-autoclicker-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    public static void setInputSuppression(boolean suppressLmb, boolean suppressRmb) {
        if (!RUNNING.get()) {
            return;
        }
        synchronized (STATE_LOCK) {
            if (!suppressLmbByMod && suppressLmb) {
                lmbBeforeSuppression = getDesiredLmbEnabled();
            }
            if (!suppressRmbByMod && suppressRmb) {
                rmbBeforeSuppression = getDesiredRmbEnabled();
            }
            suppressLmbByMod = suppressLmb;
            suppressRmbByMod = suppressRmb;

            if (suppressLmbByMod) {
                sendLmbControl(false);
            } else {
                Boolean shouldRestoreLmb = lmbBeforeSuppression != null
                    ? lmbBeforeSuppression
                    : getDesiredLmbEnabled();
                lmbBeforeSuppression = null;
                if (shouldRestoreLmb != null) {
                    sendLmbControl(shouldRestoreLmb);
                }
            }

            if (suppressRmbByMod) {
                sendRmbControl(false);
            } else {
                Boolean shouldRestoreRmb = rmbBeforeSuppression != null
                    ? rmbBeforeSuppression
                    : getDesiredRmbEnabled();
                rmbBeforeSuppression = null;
                if (shouldRestoreRmb != null) {
                    sendRmbControl(shouldRestoreRmb);
                }
            }

            // Anti-stuck guard: once suppression is fully released, force channels
            // back to desired state even if an earlier transition was missed.
            if (!suppressLmbByMod && !suppressRmbByMod) {
                reconcileChannelsToDesired();
            }
        }
    }

    public static void tickSuppressionPulse() {
        if (!RUNNING.get()) {
            return;
        }
        if (!suppressLmbByMod && !suppressRmbByMod) {
            tickUnsuppressedReconcile();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSuppressionPulseAtMs < 75L) {
            return;
        }
        lastSuppressionPulseAtMs = now;
        if (suppressLmbByMod) {
            forceLmbDisabled();
        }
        if (suppressRmbByMod) {
            forceRmbDisabled();
        }
    }

    private static void tickUnsuppressedReconcile() {
        long now = System.currentTimeMillis();
        if (now - lastUnsuppressedReconcileAtMs < 250L) {
            return;
        }
        lastUnsuppressedReconcileAtMs = now;
        synchronized (STATE_LOCK) {
            if (suppressLmbByMod || suppressRmbByMod) {
                return;
            }
            reconcileChannelsToDesired();
        }
    }

    public static void forceLmbSuppressionNow() {
        if (!RUNNING.get()) {
            return;
        }
        forceLmbDisabled();
    }

    private static void runConnectionLoop() {
        while (RUNNING.get()) {
            try {
                connect();
                readLoop();
            } catch (Exception ignored) {
            } finally {
                closeSocket();
            }

            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);

        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

        socket = s;
        writer = w;
        reader = r;

        // New connection must always receive current active override at least once.
        sentActive = null;
        sentLmbEnabled = null;
        sentRmbEnabled = null;

        // Important: when we connect while suppression is already active (e.g. GUI is open),
        // we need an accurate snapshot of AUT-CLK current lmb/rmb enabled state.
        // AUT-CLK sends "ac_status" periodically, but it may not have arrived yet.
        // We do a short warm-up read here to parse at least one "ac_status" before we force-disable.
        warmUpReadStatusLine(s, 650);

        if (suppressLmbByMod) {
            lmbBeforeSuppression = getDesiredLmbEnabled();
            sendLmbControl(false);
        }
        if (suppressRmbByMod) {
            rmbBeforeSuppression = getDesiredRmbEnabled();
            sendRmbControl(false);
        }
    }

    private static void warmUpReadStatusLine(Socket socket, long timeoutMs) {
        if (reader == null) return;
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            socket.setSoTimeout(80);
            while (System.currentTimeMillis() < deadline) {
                String line = reader.readLine();
                if (line == null) break;
                if (line.contains("\"type\":\"ac_status\"") || line.contains("\"type\": \"ac_status\"")) {
                    parseStatusActive(line);
                    return;
                }
                // ignore other message types (ping etc) during warm-up
            }
        } catch (Exception ignored) {
            // ignore timeouts / partial reads
        } finally {
            try {
                socket.setSoTimeout(0);
            } catch (Exception ignored) {
            }
        }
    }

    private static void parseStatusActive(String line) {
        Matcher activeMatcher = ACTIVE_FIELD.matcher(line);
        if (activeMatcher.find()) {
            lastKnownActive = Boolean.parseBoolean(activeMatcher.group(1));
        }
        Matcher lmbMatcher = LMB_FIELD.matcher(line);
        if (lmbMatcher.find()) {
            boolean lmb = Boolean.parseBoolean(lmbMatcher.group(1));
            if (isConflictingPendingLmb(lmb)) {
                // Ignore stale status that races with our latest control command.
            } else {
            lastKnownLmbEnabled = lmb;
            // While suppressed we intentionally force false; do not treat it as user's desired state.
            if (!suppressLmbByMod || lmb) {
                desiredLmbEnabled = lmb;
            }
                if (pendingLmbCommand != null && pendingLmbCommand == lmb) {
                    pendingLmbCommand = null;
                }
            }
        }
        Matcher rmbMatcher = RMB_FIELD.matcher(line);
        if (rmbMatcher.find()) {
            boolean rmb = Boolean.parseBoolean(rmbMatcher.group(1));
            if (isConflictingPendingRmb(rmb)) {
                // Ignore stale status that races with our latest control command.
            } else {
            lastKnownRmbEnabled = rmb;
            if (!suppressRmbByMod || rmb) {
                desiredRmbEnabled = rmb;
            }
                if (pendingRmbCommand != null && pendingRmbCommand == rmb) {
                    pendingRmbCommand = null;
                }
            }
        }
    }

    private static void readLoop() throws IOException {
        String line;
        while (RUNNING.get() && (line = reader.readLine()) != null) {
            if (line.contains("\"type\":\"ping\"") || line.contains("\"type\": \"ping\"")) {
                sendRaw("{\"type\":\"pong\"}");
                continue;
            }
            if (line.contains("\"type\":\"ac_status\"") || line.contains("\"type\": \"ac_status\"")) {
                readStatusActive(line);
            }
        }
    }

    private static void readStatusActive(String line) {
        parseStatusActive(line);

        if (suppressLmbByMod && Boolean.TRUE.equals(lastKnownLmbEnabled)) {
            sendLmbControl(false);
        }
        if (suppressRmbByMod && Boolean.TRUE.equals(lastKnownRmbEnabled)) {
            sendRmbControl(false);
        }
    }

    private static void sendActiveControl(boolean active) {
        if (sentActive != null && sentActive == active) {
            return;
        }
        String payload = "{\"type\":\"ac_control\",\"active\":" + active + "}";
        sendRaw(payload);
    }

    private static void sendLmbControl(boolean enabled) {
        if (sentLmbEnabled != null && sentLmbEnabled == enabled) {
            return;
        }
        String payload = "{\"type\":\"ac_control\",\"lmb_enabled\":" + enabled + "}";
        sendRaw(payload);
        lastKnownLmbEnabled = enabled;
        pendingLmbCommand = enabled;
        pendingLmbAtMs = System.currentTimeMillis();
    }

    private static void sendRmbControl(boolean enabled) {
        if (sentRmbEnabled != null && sentRmbEnabled == enabled) {
            return;
        }
        String payload = "{\"type\":\"ac_control\",\"rmb_enabled\":" + enabled + "}";
        sendRaw(payload);
        lastKnownRmbEnabled = enabled;
        pendingRmbCommand = enabled;
        pendingRmbAtMs = System.currentTimeMillis();
    }

    private static void forceLmbDisabled() {
        sendRaw("{\"type\":\"ac_control\",\"lmb_enabled\":false}");
        sentLmbEnabled = false;
        lastKnownLmbEnabled = false;
    }

    private static void forceRmbDisabled() {
        sendRaw("{\"type\":\"ac_control\",\"rmb_enabled\":false}");
        sentRmbEnabled = false;
        lastKnownRmbEnabled = false;
    }

    private static Boolean getEffectiveLmbEnabled() {
        return sentLmbEnabled != null ? sentLmbEnabled : lastKnownLmbEnabled;
    }

    private static Boolean getEffectiveRmbEnabled() {
        return sentRmbEnabled != null ? sentRmbEnabled : lastKnownRmbEnabled;
    }

    private static Boolean getDesiredLmbEnabled() {
        return desiredLmbEnabled != null ? desiredLmbEnabled : getEffectiveLmbEnabled();
    }

    private static Boolean getDesiredRmbEnabled() {
        return desiredRmbEnabled != null ? desiredRmbEnabled : getEffectiveRmbEnabled();
    }

    private static boolean isConflictingPendingLmb(boolean incoming) {
        if (pendingLmbCommand == null) return false;
        long age = System.currentTimeMillis() - pendingLmbAtMs;
        if (age > STATUS_STALE_GUARD_MS) {
            pendingLmbCommand = null;
            return false;
        }
        return pendingLmbCommand != incoming;
    }

    private static boolean isConflictingPendingRmb(boolean incoming) {
        if (pendingRmbCommand == null) return false;
        long age = System.currentTimeMillis() - pendingRmbAtMs;
        if (age > STATUS_STALE_GUARD_MS) {
            pendingRmbCommand = null;
            return false;
        }
        return pendingRmbCommand != incoming;
    }

    private static void reconcileChannelsToDesired() {
        Boolean desiredLmb = getDesiredLmbEnabled();
        Boolean desiredRmb = getDesiredRmbEnabled();
        if (desiredLmb != null) {
            sendLmbControl(desiredLmb);
        }
        if (desiredRmb != null) {
            sendRmbControl(desiredRmb);
        }
    }

    private static void sendRaw(String line) {
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        synchronized (SEND_LOCK) {
            try {
                w.write(line);
                w.newLine();
                w.flush();
                if (line.contains("\"type\":\"ac_control\"")) {
                    if (line.contains("\"active\":true")) {
                        sentActive = true;
                    } else if (line.contains("\"active\":false")) {
                        sentActive = false;
                    }
                    if (line.contains("\"lmb_enabled\":true")) {
                        sentLmbEnabled = true;
                    } else if (line.contains("\"lmb_enabled\":false")) {
                        sentLmbEnabled = false;
                    }
                    if (line.contains("\"rmb_enabled\":true")) {
                        sentRmbEnabled = true;
                    } else if (line.contains("\"rmb_enabled\":false")) {
                        sentRmbEnabled = false;
                    }
                }
            } catch (IOException ignored) {
                closeSocket();
            }
        }
    }

    private static void closeSocket() {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        reader = null;
        writer = null;
        socket = null;
    }
}
