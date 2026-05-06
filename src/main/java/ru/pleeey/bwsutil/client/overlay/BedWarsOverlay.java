package ru.pleeey.bwsutil.client.overlay;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

import java.util.*;

public final class BedWarsOverlay {

    private static boolean enabled = false;

    public static void toggleEnabled() {
        enabled = !enabled;
        resetCache();
    }
    public static boolean isEnabled()  { return enabled; }

    private BedWarsOverlay() {}

    // ── Константы панели ─────────────────────────────────────────────────────

    private static final int PANEL_W     = 208;
    private static final int ROW_H       = 13;
    private static final int PAD         = 6;
    private static final int MAX_ENEMIES = 6;
    private static final int MAX_TEAM    = 4;
    private static final int DANGER_M    = 15;
    private static final long HUD_CACHE_TTL_MS = 30_000L;

    // ── Кровати: кеш результатов сканирования ───────────────────────────────

    /** Информация о найденной кровати. */
    private record BedInfo(BlockPos pos, boolean alive, int defScore) {}
    private record DefenseSample(int score, boolean complete) {}

    /** DyeColor → информация о кровати (null = ни разу не видели в радиусе). */
    private static final Map<DyeColor, BedInfo> bedData = new LinkedHashMap<>();

    private static int bedScanTick = 0;
    private static final int SCAN_EVERY = 60;  // каждые 3 секунды
    private static final int SCAN_RADIUS = 128; // блоков по XZ
    private static final int SCAN_Y      = 48;  // блоков по Y
    private static final int SCAN_BUDGET_PER_TICK = 14_000; // сколько блоков проверяем за тик

    private static final class BedScanState {
        int minX, maxX, minZ, maxZ, minY, maxY;
        int x, z, y;
        boolean active;
        BlockPos center = BlockPos.ZERO;
    }

    private static final BedScanState scanState = new BedScanState();

    // ── Данные одной команды для таблицы сил ─────────────────────────────────

    private record TeamStat(
        PlayerTeam team,
        String     name,
        int        nameColor,
        int        playerCount,
        int        score,
        String     bestArmor,
        boolean    isMyTeam,
        BedInfo    bed          // null = кровать не обнаружена в радиусе
    ) {}

    private record PlayerView(
        String name,
        int nameColor,
        String arrow,
        String moveArrow,
        int hpValue,
        float hpPct,
        String distTxt,
        int distM,
        String armor,
        boolean aiming,
        double etaSec,
        double threatScore
    ) {}

    private static List<PlayerView> cachedEnemyViews = new ArrayList<>();
    private static List<PlayerView> cachedTeamViews  = new ArrayList<>();
    private static long cachedPlayersAtMs = 0L;
    private static long lastBedScanCompletedAtMs = 0L;

    private static List<TeamStat> cachedTeamStats = new ArrayList<>();
    private static long cachedTeamStatsAtMs = 0L;

    private static void resetCache() {
        cachedEnemyViews = new ArrayList<>();
        cachedTeamViews = new ArrayList<>();
        cachedPlayersAtMs = 0L;

        cachedTeamStats = new ArrayList<>();
        cachedTeamStatsAtMs = 0L;

        bedData.clear();
        bedScanTick = 0;
        scanState.active = false;
        scanState.center = BlockPos.ZERO;
        lastBedScanCompletedAtMs = 0L;
    }

    // ── Периодическое сканирование кроватей (вызывается из ClientGameEvents) ─

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (scanState.active) {
            continueScanBeds(mc);
        } else if (++bedScanTick >= SCAN_EVERY) {
            bedScanTick = 0;
            startScanBeds(mc.player);
            continueScanBeds(mc);
        }
    }

    private static void startScanBeds(LocalPlayer player) {
        BlockPos center = player.blockPosition();
        scanState.center = center;
        scanState.minX = center.getX() - SCAN_RADIUS;
        scanState.maxX = center.getX() + SCAN_RADIUS;
        scanState.minZ = center.getZ() - SCAN_RADIUS;
        scanState.maxZ = center.getZ() + SCAN_RADIUS;
        scanState.minY = Math.max(-64, center.getY() - SCAN_Y);
        scanState.maxY = Math.min(320, center.getY() + SCAN_Y);
        scanState.x = scanState.minX;
        scanState.z = scanState.minZ;
        scanState.y = scanState.minY;
        scanState.active = true;
    }

    private static void continueScanBeds(Minecraft mc) {
        if (!scanState.active) return;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        int processed = 0;

        while (scanState.active && processed < SCAN_BUDGET_PER_TICK) {
            mpos.set(scanState.x, scanState.y, scanState.z);
            if (mc.level.isLoaded(mpos)) {
                BlockState bs = mc.level.getBlockState(mpos);
                if (bs.getBlock() instanceof BedBlock bed
                        && bs.getValue(BedBlock.PART) == BedPart.HEAD) {
                    DyeColor color = bed.getColor();
                    BlockPos pos = mpos.immutable();
                    BedInfo prev = bedData.get(color);
                    DefenseSample sample = calcDefense(mc, mpos);
                    int def = sample.score();
                    if (prev != null && prev.alive() && prev.pos().equals(pos)) {
                        if (!sample.complete()) {
                            def = prev.defScore(); // keep last stable value on incomplete chunk sample
                        } else {
                            def = stabilizeDefense(prev.defScore(), def);
                        }
                    }
                    bedData.put(color, new BedInfo(pos, true, def));
                }
            }
            processed++;

            scanState.y++;
            if (scanState.y > scanState.maxY) {
                scanState.y = scanState.minY;
                scanState.z++;
                if (scanState.z > scanState.maxZ) {
                    scanState.z = scanState.minZ;
                    scanState.x++;
                    if (scanState.x > scanState.maxX) {
                        scanState.active = false;
                    }
                }
            }
        }

        if (!scanState.active) {
            finalizeScan(mc, scanState.center);
            lastBedScanCompletedAtMs = System.currentTimeMillis();
        }
    }

    private static void finalizeScan(Minecraft mc, BlockPos center) {
        // Для ранее найденных кроватей, которые теперь в радиусе — проверяем, не снесли ли
        for (Map.Entry<DyeColor, BedInfo> entry : bedData.entrySet()) {
            BedInfo info = entry.getValue();
            if (!info.alive()) continue;
            if (!inScanRange(center, info.pos())) continue;
            BlockState bs = mc.level.getBlockState(info.pos());
            if (!(bs.getBlock() instanceof BedBlock)) {
                bedData.put(entry.getKey(), new BedInfo(info.pos(), false, 0));
            }
        }
    }

    /**
     * Оценивает защиту вокруг кровати: сканирует 5×5×4 зону над позицией.
     * Blast resistance → очки: воздух=0, вул=1, камень/терракота=2, endstone=3, обсидиан=8.
     */
    private static DefenseSample calcDefense(Minecraft mc, BlockPos bedHead) {
        int score = 0;
        int unknown = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 1; dy <= 4; dy++) {
                    p.set(bedHead.getX() + dx, bedHead.getY() + dy, bedHead.getZ() + dz);
                    if (!mc.level.isLoaded(p)) {
                        unknown++;
                        continue;
                    }
                    BlockState bs = mc.level.getBlockState(p);
                    if (bs.isAir()) continue;
                    float blastRes = bs.getBlock().getExplosionResistance();
                    if      (blastRes < 1f)    score += 1;   // вул, стекло
                    else if (blastRes < 10f)   score += 2;   // камень, терракота, бетон, энд-камень
                    else if (blastRes < 1200f) score += 3;   // прочные блоки
                    else                       score += 8;   // обсидиан
                }
            }
        }
        // If too many blocks are not loaded, do not trust this sample fully.
        boolean complete = unknown <= 8;
        return new DefenseSample(score, complete);
    }

    private static int stabilizeDefense(int prev, int current) {
        if (current >= prev) return current;
        // Limit abrupt down-jumps caused by transient chunk streaming.
        int maxDropPerScan = 14;
        return Math.max(current, prev - maxDropPerScan);
    }

    private static boolean inScanRange(BlockPos center, BlockPos pos) {
        return Math.abs(pos.getX() - center.getX()) <= SCAN_RADIUS
            && Math.abs(pos.getZ() - center.getZ()) <= SCAN_RADIUS
            && Math.abs(pos.getY() - center.getY()) <= SCAN_Y;
    }

    // ── Основной рендер ──────────────────────────────────────────────────────

    public static void render(GuiGraphics g, float partialTick) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (mc.options.hideGui) return;

        LocalPlayer self = mc.player;
        PlayerTeam myTeam = mc.level.getScoreboard().getPlayersTeam(self.getScoreboardName());

        List<AbstractClientPlayer> enemies   = new ArrayList<>();
        List<AbstractClientPlayer> teammates = new ArrayList<>();

        for (Player raw : mc.level.players()) {
            if (!(raw instanceof AbstractClientPlayer p)) continue;
            if (p == self || p.isSpectator() || !p.isAlive()) continue;
            PlayerTeam t = mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName());
            ((myTeam != null && myTeam.equals(t)) ? teammates : enemies).add(p);
        }

        // Heuristic for solo/1v8 modes: some servers have imperfect scoreboard team mapping.
        // If it's clearly "one vs many", hide TEAM section to avoid showing wrong allies.
        if (enemies.size() >= 6 && teammates.size() <= 4) {
            teammates.clear();
        }

        // Stable order prevents row jumping/flicker when enemies start/stop drawing bow.
        enemies.sort(Comparator
            .comparingDouble(self::distanceTo)
            .thenComparing(p -> p.getUUID().toString()));
        teammates.sort(Comparator.comparingDouble(self::distanceTo));

        List<PlayerView> liveEnemies = buildPlayerViews(mc, self, enemies);
        List<PlayerView> liveTeam    = buildPlayerViews(mc, self, teammates);
        if (!liveEnemies.isEmpty()) {
            cachedEnemyViews = new ArrayList<>(liveEnemies);
            cachedPlayersAtMs = System.currentTimeMillis();
        }
        if (!liveTeam.isEmpty()) {
            cachedTeamViews = new ArrayList<>(liveTeam);
            cachedPlayersAtMs = System.currentTimeMillis();
        }

        boolean useCachedPlayers = (liveEnemies.isEmpty() && liveTeam.isEmpty()) && cacheFresh(cachedPlayersAtMs);
        List<PlayerView> enemyViews = useCachedPlayers ? cachedEnemyViews : liveEnemies;
        List<PlayerView> teamViews  = useCachedPlayers ? cachedTeamViews : liveTeam;

        PlayerView priorityTarget = pickPriorityTarget(enemyViews);
        boolean danger = priorityTarget != null
            && (priorityTarget.distM() < DANGER_M || priorityTarget.aiming() || priorityTarget.etaSec() <= 2.5);

        List<TeamStat> liveTeamStats = buildTeamStats(mc, self, enemies, teammates, myTeam);
        // Cache only meaningful table snapshots (2+ teams), otherwise keep previous full snapshot.
        if (liveTeamStats.size() >= 2) {
            cachedTeamStats = new ArrayList<>(liveTeamStats);
            cachedTeamStatsAtMs = System.currentTimeMillis();
        }
        boolean useCachedTeamStats = liveTeamStats.size() < 2 && cacheFresh(cachedTeamStatsAtMs);
        List<TeamStat> teamStats = useCachedTeamStats ? cachedTeamStats : liveTeamStats;
        boolean showTable = teamStats.size() >= 2;

        int rows = 1   // заголовок
            + (enemyViews.isEmpty() ? 0 : 1 + Math.min(enemyViews.size(), MAX_ENEMIES))
            + (teamViews.isEmpty()  ? 0 : 1 + Math.min(teamViews.size(),  MAX_TEAM))
            + (showTable ? 1 + teamStats.size() : 0);

        int panelX = 6;
        int panelY = 6;
        int panelH = rows * ROW_H + PAD * 2;

        // Keep panel readable without blocking too much screen.
        g.fill(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY + panelH + 1, 0x22000000);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0x11000000);

        int y = panelY + PAD;

        // Заголовок
        String title = "◉ BEDWARS";
        int titleX = panelX + PAD;
        txt(g, mc, title, titleX, y, 0xFFFFFFFF);
        boolean useCacheAny = useCachedPlayers || useCachedTeamStats;
        String freshness = freshnessLabel(useCacheAny);
        int rightEdgeX = panelX + PANEL_W - PAD;

        int freshnessX = titleX + mc.font.width(title) + 8;
        int gap = 4;
        int charW = Math.max(1, mc.font.width("m"));

        // Hint on the right may collide with freshness; shorten one of them to keep a clean header line.
        String hint = "";
        if (!enemyViews.isEmpty()) {
            hint = enemyViews.size() + " hostile" + (useCachedPlayers ? " [cache]" : "");
        }

        int freshnessMinX = titleX + mc.font.width(title) + 2;
        freshnessX = Math.max(freshnessX, freshnessMinX);

        if (!hint.isEmpty()) {
            int hintW = mc.font.width(hint);
            int hintX = rightEdgeX - hintW;
            int freshnessRightLimit = hintX - gap;

            int availFreshW = freshnessRightLimit - freshnessX;
            if (availFreshW <= 0) {
                freshness = cap(freshness, Math.max(3, 3)); // fallback; prevents overlap
            } else if (mc.font.width(freshness) > availFreshW) {
                int maxCharsFresh = Math.max(3, availFreshW / charW);
                freshness = cap(freshness, maxCharsFresh);
            }

            // Recompute after possible shortening.
            int freshnessW = mc.font.width(freshness);
            int availHintW = rightEdgeX - (freshnessX + freshnessW + gap);
            if (availHintW <= 0) {
                hint = "";
            } else if (mc.font.width(hint) > availHintW) {
                int maxCharsHint = Math.max(3, availHintW / charW);
                hint = cap(hint, maxCharsHint);
            }
        }

        if (!freshness.isEmpty()) {
            txt(g, mc, freshness, freshnessX, y, freshnessColor(useCacheAny));
        }
        if (!hint.isEmpty()) {
            txt(g, mc, hint, rightEdgeX - mc.font.width(hint), y, 0xFFFF5555);
        }
        y += ROW_H;

        if (danger) drawCenteredDanger(g, mc, priorityTarget);

        // Враги
        if (!enemyViews.isEmpty()) {
            txt(g, mc, "ENEMIES (" + enemyViews.size() + ")", panelX + PAD, y, 0xFFFF5555);
            y += ROW_H;
            for (int i = 0; i < Math.min(enemyViews.size(), MAX_ENEMIES); i++) {
                playerRow(g, mc, enemyViews.get(i), panelX + PAD, y, panelX + PANEL_W - PAD);
                y += ROW_H;
            }
        }

        // Союзники
        if (!teamViews.isEmpty()) {
            txt(g, mc, "TEAM (" + teamViews.size() + ")", panelX + PAD, y, 0xFF55FF55);
            y += ROW_H;
            for (int i = 0; i < Math.min(teamViews.size(), MAX_TEAM); i++) {
                playerRow(g, mc, teamViews.get(i), panelX + PAD, y, panelX + PANEL_W - PAD);
                y += ROW_H;
            }
        }

        // Таблица командных сил
        if (showTable) {
            drawTeamTable(g, mc, teamStats, panelX + PAD, y, PANEL_W - PAD * 2);
        }
    }

    // ── Строка игрока ────────────────────────────────────────────────────────

    private static void playerRow(GuiGraphics g, Minecraft mc, PlayerView row, int x, int y, int rightX) {
        int arrowCol = row.distM() < DANGER_M ? 0xFFFF5555 : 0xFFCCCCCC;
        int distCol  = row.distM() < DANGER_M ? 0xFFFF5555 : row.distM() < 30 ? 0xFFFFAA00 : 0xFFAAAAAA;

        txt(g, mc, row.arrow(), x, y, arrowCol);
        txt(g, mc, row.name() + (row.aiming() ? "!" : ""), x + 8, y,
            row.aiming() ? 0xFFFF4444 : row.nameColor());

        String armorTxt = row.armor().isEmpty() ? "" : "[" + row.armor() + "]";
        String moveTxt = row.moveArrow();
        String distTxt = row.distTxt();

        int cursor = rightX;
        if (!armorTxt.isEmpty()) {
            cursor -= mc.font.width(armorTxt);
            txt(g, mc, armorTxt, cursor, y, armorColor(row.armor()));
            cursor -= 4;
        }
        if (!moveTxt.isEmpty()) {
            cursor -= mc.font.width(moveTxt);
            txt(g, mc, moveTxt, cursor, y, 0xFFAAAAFF);
            cursor -= 4;
        }

        // Ensure distance text doesn't overlap the left-side name/arrow area.
        int distMaxW = Math.max(0, cursor - (x + 84));
        if (mc.font.width(distTxt) > distMaxW) {
            // Prefer keeping base distance visible; drop ETA suffix first.
            int etaIdx = distTxt.indexOf(" 0.");
            if (etaIdx > 0) distTxt = distTxt.substring(0, etaIdx).trim();
            if (mc.font.width(distTxt) > distMaxW) {
                distTxt = cap(distTxt, Math.max(4, distMaxW / Math.max(1, mc.font.width("m"))));
            }
        }
        cursor -= mc.font.width(distTxt);
        txt(g, mc, distTxt, cursor, y, distCol);
    }

    private static List<PlayerView> buildPlayerViews(Minecraft mc, LocalPlayer self, List<AbstractClientPlayer> players) {
        List<PlayerView> rows = new ArrayList<>(players.size());
        for (AbstractClientPlayer p : players) {
            double dist  = self.distanceTo(p);
            float hp     = p.getHealth();
            float maxHp  = Math.max(1f, p.getMaxHealth());
            float hpPct  = hp / maxHp;
            double dy    = p.getY() - self.getY();
            double eta   = etaToContact(self, p, dist);
            String dyStr = dy > 2.5 ? "+" + (int) dy : dy < -2.5 ? "" + (int) dy : "";
            String etaTxt = (eta > 0 && eta < 20.0) ? " " + String.format(Locale.ROOT, "%.1fs", eta) : "";
            rows.add(new PlayerView(
                cap(p.getName().getString(), 6),
                nameColor(mc, p),
                dir(self, p),
                moveDir(p),
                Math.round(hp),
                hpPct,
                (int) dist + "m" + dyStr + etaTxt,
                (int) dist,
                armorChar(p),
                isAiming(p),
                eta,
                threatScore(self, p, dist, hpPct)
            ));
        }
        return rows;
    }

    private static boolean cacheFresh(long ts) {
        return ts > 0L && (System.currentTimeMillis() - ts) <= HUD_CACHE_TTL_MS;
    }

    private static void drawCenteredDanger(GuiGraphics g, Minecraft mc, PlayerView closest) {
        String aim = closest.aiming() ? " BOW" : "";
        String eta = (closest.etaSec() > 0 && closest.etaSec() < 20.0)
            ? " ETA " + String.format(Locale.ROOT, "%.1fs", closest.etaSec()) : "";
        String msg = closest.arrow() + " " + cap(closest.name(), 12) + " " + closest.distM() + "m" + aim + eta;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int x = (sw - mc.font.width(msg)) / 2;
        int y = sh / 2 - 34;

        long t0 = System.currentTimeMillis();
        int bg = ((t0 / 350) % 2 == 0) ? 0xAA7A0000 : 0xAA5A0000;
        int fc = ((t0 / 350) % 2 == 0) ? 0xFFFFFF55 : 0xFFFFFFFF;

        g.fill(x - 4, y - 2, x + mc.font.width(msg) + 4, y + 10, bg);
        txt(g, mc, msg, x, y, fc);
    }

    private static PlayerView pickPriorityTarget(List<PlayerView> enemies) {
        if (enemies.isEmpty()) return null;
        PlayerView best = null;
        for (PlayerView pv : enemies) {
            if (best == null || pv.threatScore() > best.threatScore()) best = pv;
        }
        return best;
    }

    private static double threatScore(LocalPlayer self, AbstractClientPlayer p, double dist, float hpPct) {
        double score = 0.0;
        score += Math.max(0.0, 35.0 - dist) * 2.2;
        score += isAiming(p) ? 28.0 : 0.0;
        score += (1.0 - hpPct) * 6.0;
        score += armorScoreOf(armorChar(p)) * 2.5;
        double eta = etaToContact(self, p, dist);
        if (eta > 0 && eta < 8.0) score += (8.0 - eta) * 3.0;
        return score;
    }

    private static double etaToContact(LocalPlayer self, AbstractClientPlayer p, double dist) {
        double vx = p.getX() - p.xo;
        double vz = p.getZ() - p.zo;
        double rx = self.getX() - p.getX();
        double rz = self.getZ() - p.getZ();
        double rlen = Math.sqrt(rx * rx + rz * rz);
        if (rlen < 1.0e-6) return 0.0;
        double nx = rx / rlen;
        double nz = rz / rlen;
        double closingPerTick = vx * nx + vz * nz;
        if (closingPerTick <= 0.02) return Double.POSITIVE_INFINITY;
        return (dist / closingPerTick) / 20.0;
    }

    private static String moveDir(AbstractClientPlayer p) {
        double vx = p.getX() - p.xo;
        double vz = p.getZ() - p.zo;
        double speedSq = vx * vx + vz * vz;
        if (speedSq < 0.0009) return "•";
        double yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-vx, vz)));
        double abs = Math.abs(yaw);
        if (abs <= 22.5) return "↑";
        if (abs >= 157.5) return "↓";
        return yaw < 0
            ? (abs <= 67.5 ? "↗" : abs <= 112.5 ? "→" : "↘")
            : (abs <= 67.5 ? "↖" : abs <= 112.5 ? "←" : "↙");
    }

    private static String freshnessLabel(boolean usedCache) {
        long ageMs = lastBedScanCompletedAtMs <= 0L ? -1L : (System.currentTimeMillis() - lastBedScanCompletedAtMs);
        String scan = scanState.active ? "SCAN…" : (ageMs < 0 ? "NOSCAN" : ("S" + (ageMs / 1000) + "s"));
        return usedCache ? ("CACHE " + scan) : ("LIVE " + scan);
    }

    private static int freshnessColor(boolean usedCache) {
        if (scanState.active) return 0xFF55FFFF;
        return usedCache ? 0xFFFFAA55 : 0xFF55FF55;
    }

    // ── Таблица командных сил ─────────────────────────────────────────────────

    private static void drawTeamTable(GuiGraphics g, Minecraft mc,
                                       List<TeamStat> stats,
                                       int x, int y, int w) {
        txt(g, mc, "TEAM POWER", x, y, 0xFFFFAA00);
        y += ROW_H;

        int maxScore = stats.stream().mapToInt(TeamStat::score).max().orElse(1);
        TeamStat weakest  = stats.stream().filter(s -> !s.isMyTeam())
            .min(Comparator.comparingInt(TeamStat::score)).orElse(null);
        TeamStat strongest = stats.stream().filter(s -> !s.isMyTeam())
            .max(Comparator.comparingInt(TeamStat::score)).orElse(null);

        for (TeamStat ts : stats) {
            // Цветной квадрат команды 5×5px
            g.fill(x, y + 2, x + 6, y + 7, 0xFF000000);
            g.fill(x + 1, y + 3, x + 5, y + 7, 0xFF000000 | (ts.nameColor() & 0x00FFFFFF));

            // Имя
            txt(g, mc, ts.name(), x + 8, y, ts.nameColor());

            // Число игроков
            txt(g, mc, ts.playerCount() + "p", x + 52, y, 0xFFAAAAAA);

            // Полоска силы 30×5px
            int bx    = x + 68;
            int by    = y + 2;
            int bw    = 30;
            int bh    = 5;
            int bfill = maxScore > 0 ? (int)((long) ts.score() * bw / maxScore) : 0;
            int barCol = ts.isMyTeam() ? 0xFF55AA55
                : (ts == strongest ? 0xFFFF5555
                :  (ts == weakest  ? 0xFF55FF55 : 0xFFFFAA00));
            g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, 0xFF000000);
            g.fill(bx, by, bx + bw, by + bh, 0x33FFFFFF);
            if (bfill > 0) g.fill(bx, by, bx + bfill, by + bh, barCol);

            // Кровать: символ + защита
            drawBedStatus(g, mc, ts, x + 100, y);

            // Тег справа
            String tag;
            int tagColor;
            if (ts.isMyTeam()) {
                tag = "(you)"; tagColor = 0xFF55FF55;
            } else if (ts == weakest && ts != strongest) {
                tag = "→HUNT"; tagColor = 0xFF55FF55;
            } else if (ts == strongest) {
                tag = "THRT";  tagColor = 0xFFFF5555;
            } else {
                tag = "";      tagColor = 0;
            }
            if (!tag.isEmpty()) {
                txt(g, mc, tag, x + w - mc.font.width(tag), y, tagColor);
            }

            y += ROW_H;
        }
    }

    /**
     * Рисует статус кровати: символ (✔/✗/?) + код защиты (OBS/END/TER/WOL/---).
     * Располагается в 58px → x+58.
     */
    private static void drawBedStatus(GuiGraphics g, Minecraft mc, TeamStat ts, int x, int y) {
        BedInfo bed = ts.bed();
        String  sym;
        int     symCol;
        String  defTxt;
        int     defCol;

        if (bed == null) {
            sym    = "?";     symCol = 0xFF777777;
            defTxt = "UNK";   defCol = 0xFF777777;
        } else if (bed.alive()) {
            sym    = "✔";  symCol = 0xFF55FF55;   // ✔
            defTxt = defCode(bed.defScore());
            defCol = defColor(bed.defScore());
        } else {
            sym    = "✗";  symCol = 0xFFFF5555;   // ✗
            defTxt = "";        defCol = 0;
        }

        txt(g, mc, sym, x, y, symCol);
        if (!defTxt.isEmpty()) txt(g, mc, defTxt, x + 10, y, defCol);
        if (bed != null && bed.alive()) {
            String chance = breachChanceCode(bed.defScore(), ts.playerCount());
            txt(g, mc, chance, x + 40, y, breachChanceColor(chance));
        }
    }

    // ── Агрегация данных команд ──────────────────────────────────────────────

    private static List<TeamStat> buildTeamStats(Minecraft mc,
                                                  LocalPlayer self,
                                                  List<AbstractClientPlayer> enemies,
                                                  List<AbstractClientPlayer> teammates,
                                                  PlayerTeam myTeam) {
        Map<PlayerTeam, List<AbstractClientPlayer>> enemyGroups = new LinkedHashMap<>();
        for (AbstractClientPlayer p : enemies) {
            PlayerTeam t = mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName());
            enemyGroups.computeIfAbsent(t, k -> new ArrayList<>()).add(p);
        }

        List<TeamStat> stats = new ArrayList<>();

        for (Map.Entry<PlayerTeam, List<AbstractClientPlayer>> e : enemyGroups.entrySet()) {
            PlayerTeam t  = e.getKey();
            List<AbstractClientPlayer> pl = e.getValue();
            int nc = teamDisplayColor(mc, t);
            stats.add(new TeamStat(t, t != null ? cap(t.getName(), 7) : "?",
                nc, pl.size(), computeScore(pl), bestArmorIn(pl), false,
                bedForTeam(t)));
        }

        if (!teammates.isEmpty() || myTeam != null) {
            List<AbstractClientPlayer> mine = new ArrayList<>(teammates);
            if (self instanceof AbstractClientPlayer acp) mine.add(acp);
            int nc = teamDisplayColor(mc, myTeam);
            stats.add(new TeamStat(myTeam, myTeam != null ? cap(myTeam.getName(), 7) : "YOU",
                nc, mine.size(), computeScore(mine), bestArmorIn(mine), true,
                bedForTeam(myTeam)));
        }

        stats.sort(Comparator.comparingInt(TeamStat::score).reversed());
        return stats;
    }

    private static BedInfo bedForTeam(PlayerTeam team) {
        Minecraft mc = Minecraft.getInstance();
        if (team == null || mc.level == null) return null;

        // Primary mapping: team color -> bed dye.
        DyeColor dye = teamColorToDye(team);
        if (dye != null) {
            BedInfo direct = bedData.get(dye);
            if (direct != null) return direct;
        }

        // Additional mapping: some servers use nearby palette variants for team colors.
        for (DyeColor alt : teamColorAlternatives(team)) {
            BedInfo byAlt = bedData.get(alt);
            if (byAlt != null) return byAlt;
        }

        // Fallback for servers with custom scoreboard formatting:
        // pick the closest known bed to the members of this team.
        return inferBedByTeamPlayers(mc, team);
    }

    private static BedInfo inferBedByTeamPlayers(Minecraft mc, PlayerTeam team) {
        List<AbstractClientPlayer> teamPlayers = new ArrayList<>();
        for (Player raw : mc.level.players()) {
            if (!(raw instanceof AbstractClientPlayer p)) continue;
            if (!p.isAlive() || p.isSpectator()) continue;
            PlayerTeam t = mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName());
            if (team.equals(t)) teamPlayers.add(p);
        }
        if (teamPlayers.isEmpty()) return null;

        BedInfo best = null;
        double bestAvg = Double.MAX_VALUE;

        // Deduplicate beds by position because bedData is keyed by dye.
        Map<BlockPos, BedInfo> uniqueBeds = new LinkedHashMap<>();
        for (BedInfo info : bedData.values()) {
            if (info == null) continue;
            uniqueBeds.putIfAbsent(info.pos(), info);
        }

        for (BedInfo info : uniqueBeds.values()) {
            double sumSq = 0.0;
            for (AbstractClientPlayer p : teamPlayers) {
                sumSq += p.blockPosition().distSqr(info.pos());
            }
            double avgSq = sumSq / teamPlayers.size();
            if (avgSq < bestAvg) {
                bestAvg = avgSq;
                best = info;
            }
        }

        // Ignore clearly unrelated beds.
        return bestAvg <= (220.0 * 220.0) ? best : null;
    }

    private static int computeScore(List<AbstractClientPlayer> players) {
        int total = 0;
        for (AbstractClientPlayer p : players) {
            float hpPct = p.getHealth() / Math.max(1f, p.getMaxHealth());
            total += (int)(hpPct * 100) + armorScoreOf(armorChar(p)) * 15;
        }
        return total;
    }

    private static String bestArmorIn(List<AbstractClientPlayer> players) {
        int best = 0; String bestTier = "";
        for (AbstractClientPlayer p : players) {
            String a = armorChar(p);
            int s = armorScoreOf(a);
            if (s > best) { best = s; bestTier = a; }
        }
        return bestTier;
    }

    // ── Защита кровати ────────────────────────────────────────────────────────

    private static String defCode(int score) {
        if (score == 0)   return "---";
        if (score < 25)   return "WOL";   // вул/дерево (слабая)
        if (score < 70)   return "TER";   // терракота/бетон (средняя)
        if (score < 140)  return "END";   // энд-камень (сильная)
        return "OBS";                     // обсидиан (максимальная)
    }

    private static int defColor(int score) {
        if (score == 0)  return 0xFF555555;
        if (score < 25)  return 0xFFAA7733;
        if (score < 70)  return 0xFFFFAA00;
        if (score < 140) return 0xFF55FF55;
        return 0xFF55FFFF;
    }

    private static String breachChanceCode(int defScore, int defenders) {
        int adjusted = defScore + defenders * 10;
        if (adjusted < 35) return "EASY";
        if (adjusted < 90) return "MID";
        return "HARD";
    }

    private static int breachChanceColor(String code) {
        return switch (code) {
            case "EASY" -> 0xFF55FF55;
            case "MID" -> 0xFFFFAA00;
            default -> 0xFFFF5555;
        };
    }

    // ── ChatFormatting → DyeColor (для сопоставления команды и кровати) ─────

    private static DyeColor teamColorToDye(PlayerTeam team) {
        if (team == null) return null;
        // 1) Primary source: explicit team formatting color.
        DyeColor byFormatting = chatFormattingToDye(team.getColor());
        if (byFormatting != null) return byFormatting;

        // 2) Fallback: style color in team display/prefix (many servers set only this).
        DyeColor byDisplayStyle = textColorToDye(team.getDisplayName().getStyle().getColor());
        if (byDisplayStyle != null) return byDisplayStyle;

        DyeColor byPrefixStyle = textColorToDye(team.getPlayerPrefix().getStyle().getColor());
        if (byPrefixStyle != null) return byPrefixStyle;

        // 3) Last fallback: parse team name keywords (Red, Blue, Aqua, etc.).
        return nameToDye(team.getName());
    }

    private static DyeColor chatFormattingToDye(ChatFormatting fmt) {
        if (fmt == null) return null;
        return switch (fmt) {
            case RED, DARK_RED       -> DyeColor.RED;
            case BLUE, DARK_BLUE     -> DyeColor.BLUE;
            case GREEN, DARK_GREEN   -> DyeColor.GREEN;
            case YELLOW              -> DyeColor.YELLOW;
            case AQUA, DARK_AQUA     -> DyeColor.CYAN;
            case WHITE               -> DyeColor.WHITE;
            case LIGHT_PURPLE        -> DyeColor.PINK;
            case DARK_PURPLE         -> DyeColor.PURPLE;
            case GOLD                -> DyeColor.ORANGE;
            case GRAY                -> DyeColor.LIGHT_GRAY;
            case DARK_GRAY           -> DyeColor.GRAY;
            case BLACK               -> DyeColor.BLACK;
            default                  -> null;
        };
    }

    private static DyeColor textColorToDye(TextColor tc) {
        if (tc == null) return null;
        int rgb = tc.getValue() & 0x00FFFFFF;

        DyeColor best = null;
        int bestDist = Integer.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            int c = dye.getMapColor().col;
            int dr = ((rgb >> 16) & 0xFF) - ((c >> 16) & 0xFF);
            int dg = ((rgb >> 8) & 0xFF) - ((c >> 8) & 0xFF);
            int db = (rgb & 0xFF) - (c & 0xFF);
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = dye;
            }
        }
        // Safety threshold: avoid random mismatches on neutral colors.
        return bestDist <= 14000 ? best : null;
    }

    private static DyeColor nameToDye(String rawName) {
        if (rawName == null) return null;
        String n = rawName.toLowerCase(Locale.ROOT);
        if (n.contains("lime"))   return DyeColor.LIME;
        if (n.contains("red"))    return DyeColor.RED;
        if (n.contains("blue"))   return DyeColor.BLUE;
        if (n.contains("green"))  return DyeColor.GREEN;
        if (n.contains("yellow")) return DyeColor.YELLOW;
        if (n.contains("teal"))   return DyeColor.CYAN;
        if (n.contains("cyan"))   return DyeColor.CYAN;
        if (n.contains("lightblue")) return DyeColor.LIGHT_BLUE;
        if (n.contains("aqua"))   return DyeColor.CYAN;
        if (n.contains("white"))  return DyeColor.WHITE;
        if (n.contains("pink"))   return DyeColor.PINK;
        if (n.contains("gray") || n.contains("grey")) return DyeColor.GRAY;
        if (n.contains("orange")) return DyeColor.ORANGE;
        if (n.contains("purple")) return DyeColor.PURPLE;
        if (n.contains("black"))  return DyeColor.BLACK;
        return null;
    }

    private static List<DyeColor> teamColorAlternatives(PlayerTeam team) {
        if (team == null) return List.of();
        ChatFormatting fmt = team.getColor();
        if (fmt == null) return List.of();
        return switch (fmt) {
            case GREEN, DARK_GREEN -> List.of(DyeColor.GREEN, DyeColor.LIME);
            case AQUA, DARK_AQUA   -> List.of(DyeColor.CYAN, DyeColor.LIGHT_BLUE);
            case BLUE, DARK_BLUE   -> List.of(DyeColor.BLUE, DyeColor.LIGHT_BLUE);
            case GRAY, DARK_GRAY   -> List.of(DyeColor.GRAY, DyeColor.LIGHT_GRAY);
            case WHITE             -> List.of(DyeColor.WHITE, DyeColor.LIGHT_GRAY);
            default                -> List.of();
        };
    }

    // ── Вспомогательные методы ───────────────────────────────────────────────

    private static void txt(GuiGraphics g, Minecraft mc, String s, int x, int y, int color) {
        g.drawString(mc.font, s, x + 1, y + 1, 0x88000000, false);
        g.drawString(mc.font, s, x, y, color, false);
    }

    private static String dir(LocalPlayer self, AbstractClientPlayer target) {
        Vec3 forward = self.getLookAngle();
        forward = new Vec3(forward.x, 0.0, forward.z);
        if (forward.lengthSqr() < 1.0e-6) return "↑";
        forward = forward.normalize();

        Vec3 to = target.position().subtract(self.position());
        to = new Vec3(to.x, 0.0, to.z);
        if (to.lengthSqr() < 1.0e-6) return "↑";
        to = to.normalize();

        // Right vector in XZ plane.
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        double front = forward.dot(to);
        double side = right.dot(to);

        if (front >= 0.9239) return "↑";   // <= 22.5°
        if (front <= -0.9239) return "↓";  // >= 157.5°
        if (Math.abs(side) >= 0.9239) return side > 0 ? "→" : "←";

        if (front > 0) return side > 0 ? "↗" : "↖";
        return side > 0 ? "↘" : "↙";
    }

    private static boolean isAiming(AbstractClientPlayer p) {
        return p.isUsingItem() && (p.getUseItem().getItem() instanceof BowItem);
    }

    private static String armorChar(AbstractClientPlayer p) {
        String best = "";
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            String tier = armorTier(p.getItemBySlot(slot));
            if (armorScoreOf(tier) > armorScoreOf(best)) {
                best = tier;
            }
        }
        return best;
    }

    private static String armorTier(ItemStack item) {
        if (item == null || item.isEmpty()) return "";
        Identifier id = BuiltInRegistries.ITEM.getKey(item.getItem());
        if (id == null) return "";
        String path = id.getPath();
        if (path.startsWith("netherite")) return "N";
        if (path.startsWith("diamond"))   return "D";
        if (path.startsWith("iron"))      return "I";
        if (path.startsWith("chainmail")) return "C";
        if (path.startsWith("gold"))      return "G";
        if (path.startsWith("leather"))   return "L";
        return "";
    }

    private static int armorScoreOf(String t) {
        return switch (t) {
            case "N" -> 5; case "D" -> 4; case "I" -> 3;
            case "C", "G" -> 2; case "L" -> 1; default -> 0;
        };
    }

    private static int armorColor(String t) {
        return switch (t) {
            case "N" -> 0xFFAA55FF; case "D" -> 0xFF55FFFF; case "I" -> 0xFFCCCCCC;
            case "C" -> 0xFF777777; case "G" -> 0xFFFFAA00; case "L" -> 0xFFAA8833;
            default  -> 0xFFFFFFFF;
        };
    }

    private static int teamDisplayColor(Minecraft mc, PlayerTeam team) {
        if (team != null) {
            ChatFormatting fmt = team.getColor();
            if (fmt.getColor() != null) return 0xFF000000 | fmt.getColor();
        }
        return 0xFFFFFFFF;
    }

    private static int nameColor(Minecraft mc, AbstractClientPlayer p) {
        PlayerTeam team = mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName());
        if (team != null) {
            ChatFormatting fmt = team.getColor();
            if (fmt.getColor() != null) return 0xFF000000 | fmt.getColor();
        }
        Style style = p.getDisplayName().getStyle();
        TextColor tc = style.getColor();
        if (tc != null) return 0xFF000000 | tc.getValue();
        return 0xFFFFFFFF;
    }

    private static String cap(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "…";
    }
}
