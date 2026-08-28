package ru.pleeey.bwsutil.client.match;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.DyeColor;
import ru.pleeey.bwsutil.util.TeamColors;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reconstructs match state from server chat.
 *
 * <p>The block scanner can only report beds within its scan radius, so a team whose base sits
 * across the map stays {@code UNK} in the power table for the whole game. Chat announcements
 * carry that information at any distance, which is the main reason this class exists — the
 * visible event log is the secondary benefit.</p>
 *
 * <p>Chat wording is server-specific, so matching is keyword-based rather than exact, and covers
 * common English and Russian phrasings. A missed line degrades to the previous behaviour
 * (scanner-only knowledge); it never produces a wrong "alive" verdict, since chat can only ever
 * mark a bed as destroyed.</p>
 */
public final class MatchTracker {

    /** Cap on retained events; only the last few are ever drawn, the rest are history headroom. */
    private static final int MAX_EVENTS = 12;
    private static final int MAX_VISIBLE_EVENTS = 3;

    /** Bed destruction: the keyword and the verb may appear in either order, in either language. */
    private static final Pattern BED_DESTROYED = Pattern.compile(
        "(bed\b.{0,80}(destroy|destruct|broke|broken))"
            + "|((destroy|destruct|broke|broken).{0,80}\bbed)"
            + "|(кроват.{0,80}(разруш|сломан|уничтож|снес))"
            + "|((разруш|сломан|уничтож|снес).{0,80}кроват)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    private static final Pattern TEAM_ELIMINATED = Pattern.compile(
        "(team.{0,40}eliminat)|(eliminat.{0,40}team)|(команда.{0,40}(выбыл|поверже|уничтожен))"
            + "|((выбыл|поверже).{0,40}команда)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    public enum BedState { UNKNOWN, DESTROYED }

    /** One entry of the visible log. {@code matchTimeMs} is measured from map entry, not wall clock. */
    private record MatchEvent(long matchTimeMs, String text, int argb) {}

    private static final Map<DyeColor, BedState> bedStateByColor = new EnumMap<>(DyeColor.class);
    private static final Set<DyeColor> eliminatedTeams = new LinkedHashSet<>();
    private static final List<MatchEvent> events = new ArrayList<>();

    private static long matchStartedAtMs = System.currentTimeMillis();

    private MatchTracker() {}

    /** Clears everything for a new map. Called on logout and level unload. */
    public static void reset() {
        bedStateByColor.clear();
        eliminatedTeams.clear();
        events.clear();
        matchStartedAtMs = System.currentTimeMillis();
    }

    /**
     * Chat-confirmed bed state for a team colour.
     *
     * <p>Returns {@link BedState#UNKNOWN} unless a destruction was actually announced — this
     * never asserts that a bed is intact, only that it is gone.</p>
     */
    public static BedState bedState(DyeColor color) {
        if (color == null) return BedState.UNKNOWN;
        return bedStateByColor.getOrDefault(color, BedState.UNKNOWN);
    }

    public static boolean isEliminated(DyeColor color) {
        return color != null && eliminatedTeams.contains(color);
    }

    public static boolean hasEvents() {
        return !events.isEmpty();
    }

    /** One rendered row of the match log. */
    public record LogLine(String time, String text, int argb) {}

    /** Last few events, newest last. */
    public static List<LogLine> recentLines() {
        int from = Math.max(0, events.size() - MAX_VISIBLE_EVENTS);
        List<LogLine> out = new ArrayList<>(MAX_VISIBLE_EVENTS);
        for (MatchEvent e : events.subList(from, events.size())) {
            out.add(new LogLine(formatTime(e.matchTimeMs()), e.text(), e.argb()));
        }
        return out;
    }

    /** Entry point from the chat event handler. */
    public static void onChatMessage(Component message) {
        if (message == null) return;
        String plain = message.getString();
        if (plain.isEmpty() || plain.length() > 400) return;

        boolean bedDestroyed = BED_DESTROYED.matcher(plain).find();
        boolean eliminated = TEAM_ELIMINATED.matcher(plain).find();
        if (!bedDestroyed && !eliminated) return;

        DyeColor color = resolveColor(message, plain);
        long at = System.currentTimeMillis() - matchStartedAtMs;

        if (bedDestroyed) {
            if (color != null) bedStateByColor.put(color, BedState.DESTROYED);
            addEvent(at, "BED \u2717 " + TeamColors.shortLabel(color), TeamColors.dyeArgb(color));
        } else {
            if (color != null) {
                eliminatedTeams.add(color);
                // A team cannot be eliminated with its bed intact.
                bedStateByColor.put(color, BedState.DESTROYED);
            }
            addEvent(at, "OUT \u2014 " + TeamColors.shortLabel(color), TeamColors.dyeArgb(color));
        }
    }

    private static void addEvent(long matchTimeMs, String text, int argb) {
        // Servers often send the same announcement twice (title + chat); collapse the repeat.
        if (!events.isEmpty()) {
            MatchEvent last = events.get(events.size() - 1);
            if (last.text().equals(text) && matchTimeMs - last.matchTimeMs() < 3_000L) return;
        }
        events.add(new MatchEvent(matchTimeMs, text, argb));
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    /**
     * Finds which team a chat line is about.
     *
     * <p>Styling is tried first: servers almost always colour the team name, and that survives
     * translation and custom wording. Keyword lookup is the fallback, and is deliberately second
     * because a player nickname containing a colour word would otherwise win.</p>
     */
    private static DyeColor resolveColor(Component message, String plain) {
        Set<DyeColor> styled = collectStyledColors(message);
        if (styled.size() == 1) return styled.iterator().next();

        DyeColor byName = TeamColors.fromName(plain);
        if (byName != null) return byName;

        // Several colours in one line and no keyword: prefer the first non-neutral one.
        for (DyeColor c : styled) {
            if (c != DyeColor.WHITE && c != DyeColor.GRAY && c != DyeColor.LIGHT_GRAY) return c;
        }
        return null;
    }

    private static Set<DyeColor> collectStyledColors(Component message) {
        Set<DyeColor> found = new LinkedHashSet<>();
        message.visit((Style style, String text) -> {
            if (!text.isBlank()) {
                DyeColor dye = TeamColors.fromTextColor(style.getColor());
                if (dye != null) found.add(dye);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found;
    }

    private static String formatTime(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", totalSec / 60L, totalSec % 60L);
    }
}
