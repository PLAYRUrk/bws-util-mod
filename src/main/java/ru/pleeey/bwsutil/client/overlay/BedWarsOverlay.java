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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import ru.pleeey.bwsutil.config.ScopeConfig;

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
    private static final int THREAT_ROW_SHIFT_X = 14;
    private static final int THREAT_ROW_EXTRA_BG = 26;
    private static final long HUD_CACHE_TTL_MS = 30_000L;
    private static final long HUD_LIVE_RECOMPUTE_INTERVAL_MS = 120L;
    private static final double ETA_SMOOTH_ALPHA = 0.22;
    private static final double ETA_MAX_STEP_PER_TICK = 0.45;
    private static final int RADAR_SIZE = 92;
    private static final int RADAR_RANGE_BLOCKS_DEFAULT = 48;
    private static final int RADAR_RANGE_BLOCKS_MIN = 24;
    private static final int RADAR_RANGE_BLOCKS_MAX = 96;
    private static final int RADAR_RANGE_BLOCKS_STEP = 4;
    private static final int[] RADAR_RANGE_MARKS = new int[] {10, 20, 30};
    private static final int RADAR_Y_RANGE = 10;
    private static final long RADAR_CONSTRUCTIONS_CACHE_MS = 420L;
    private static final int RADAR_CACHE_MOVE_THRESHOLD = 4;
    private static final int RADAR_MAX_CONSTRUCTION_MARKS = 180;
    private static final long FIREBALL_SOUND_COOLDOWN_MS = 900L;
    private static final long VOID_SOUND_COOLDOWN_MS = 1_200L;
    private static final double RETREAT_SCAN_RADIUS = 26.0;
    private static final int RETREAT_SAMPLE_DIRECTIONS = 12;
    private static final double RETREAT_SAMPLE_DISTANCE = 7.0;
    private static final double RETREAT_SAMPLE_DISTANCE_FAR = 12.0;
    private static int radarRangeBlocks = RADAR_RANGE_BLOCKS_DEFAULT;

    // ── Кровати: кеш результатов сканирования ───────────────────────────────

    /** Информация о найденной кровати. */
    private record BedInfo(BlockPos pos, DyeColor color, boolean alive, int defScore) {}
    private record DefenseSample(int score, boolean complete) {}

    /** DyeColor → информация о кровати (null = ни разу не видели в радиусе). */
    private static final Map<DyeColor, BedInfo> bedData = new LinkedHashMap<>();
    /** Состояние кроватей по позиции (нужно, чтобы разные кровати не "слипались"). */
    private static final Map<BlockPos, BedInfo> bedDataByPos = new LinkedHashMap<>();

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
    private record RadarConstructionSample(int dx, int dz, int color) {}

    private static List<PlayerView> cachedEnemyViews = new ArrayList<>();
    private static List<PlayerView> cachedTeamViews  = new ArrayList<>();
    private static long cachedPlayersAtMs = 0L;
    private static long lastHudLiveRecomputeAtMs = 0L;
    private static long lastBedScanCompletedAtMs = 0L;
    private static final Map<UUID, Double> etaSmoothedByPlayer = new HashMap<>();
    private static final Map<UUID, Long> etaSeenAtMs = new HashMap<>();

    private static List<TeamStat> cachedTeamStats = new ArrayList<>();
    private static long cachedTeamStatsAtMs = 0L;
    private static List<RadarConstructionSample> cachedRadarConstructions = new ArrayList<>();
    private static long cachedRadarConstructionsAtMs = 0L;
    private static int radarCacheCenterX;
    private static int radarCacheCenterY;
    private static int radarCacheCenterZ;
    private static int radarCacheRangeBlocks = RADAR_RANGE_BLOCKS_DEFAULT;
    private static String fireballAlertText = "";
    private static int fireballAlertColor = 0xFFFF5555;
    private static boolean fireballAlertDanger;
    private static String bridgeAlertText = "";
    private static int bridgeAlertColor = 0xFF55FF55;
    private static boolean bridgeAlertDanger;
    private static String retreatAlertText = "";
    private static int retreatAlertColor = 0xFFFF6666;
    private static boolean retreatAlertDanger;
    private static long lastFireballWarnAtMs;
    private static long lastVoidWarnAtMs;
    private static String cachedFreshnessLabel = "LIVE";

    private static void resetCache() {
        cachedEnemyViews = new ArrayList<>();
        cachedTeamViews = new ArrayList<>();
        cachedPlayersAtMs = 0L;
        lastHudLiveRecomputeAtMs = 0L;

        cachedTeamStats = new ArrayList<>();
        cachedTeamStatsAtMs = 0L;

        bedData.clear();
        bedDataByPos.clear();
        bedScanTick = 0;
        scanState.active = false;
        scanState.center = BlockPos.ZERO;
        lastBedScanCompletedAtMs = 0L;
        radarRangeBlocks = RADAR_RANGE_BLOCKS_DEFAULT;
        etaSmoothedByPlayer.clear();
        etaSeenAtMs.clear();
        cachedRadarConstructions = new ArrayList<>();
        cachedRadarConstructionsAtMs = 0L;
        fireballAlertText = "";
        bridgeAlertText = "";
        fireballAlertDanger = false;
        bridgeAlertDanger = false;
        retreatAlertText = "";
        retreatAlertDanger = false;
        lastFireballWarnAtMs = 0L;
        lastVoidWarnAtMs = 0L;
        cachedFreshnessLabel = "LIVE";
    }

    public static void increaseRadarScale() {
        // Larger scale (zoom-in) = smaller world range.
        radarRangeBlocks = Math.max(RADAR_RANGE_BLOCKS_MIN, radarRangeBlocks - RADAR_RANGE_BLOCKS_STEP);
        cachedRadarConstructionsAtMs = 0L;
    }

    public static void decreaseRadarScale() {
        // Smaller scale (zoom-out) = larger world range.
        radarRangeBlocks = Math.min(RADAR_RANGE_BLOCKS_MAX, radarRangeBlocks + RADAR_RANGE_BLOCKS_STEP);
        cachedRadarConstructionsAtMs = 0L;
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
                    BedInfo prev = bedDataByPos.get(pos);
                    DefenseSample sample = calcDefense(mc, mpos);
                    int def = sample.score();
                    if (prev != null && prev.alive() && prev.pos().equals(pos)) {
                        if (!sample.complete()) {
                            def = prev.defScore(); // keep last stable value on incomplete chunk sample
                        } else {
                            def = stabilizeDefense(prev.defScore(), def);
                        }
                    }
                    BedInfo next = new BedInfo(pos, color, true, def);
                    bedDataByPos.put(pos, next);
                    // Fast color index is refreshed each scan; this keeps interim reads valid.
                    bedData.put(color, next);
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
        // Для ранее найденных кроватей, которые теперь в радиусе — проверяем, не снесли ли.
        for (Map.Entry<BlockPos, BedInfo> entry : bedDataByPos.entrySet()) {
            BedInfo info = entry.getValue();
            if (!info.alive()) continue;
            if (!inScanRange(center, info.pos())) continue;
            BlockState bs = mc.level.getBlockState(info.pos());
            if (!(bs.getBlock() instanceof BedBlock)) {
                bedDataByPos.put(entry.getKey(), new BedInfo(info.pos(), info.color(), false, 0));
            }
        }
        rebuildBedColorIndex(center);
    }

    private static void rebuildBedColorIndex(BlockPos center) {
        bedData.clear();
        for (BedInfo info : bedDataByPos.values()) {
            BedInfo prev = bedData.get(info.color());
            if (prev == null) {
                bedData.put(info.color(), info);
                continue;
            }
            // Prefer alive entries; for equal status, prefer the one closer to current scan center.
            if (info.alive() && !prev.alive()) {
                bedData.put(info.color(), info);
                continue;
            }
            if (info.alive() == prev.alive()) {
                if (info.pos().distSqr(center) < prev.pos().distSqr(center)) {
                    bedData.put(info.color(), info);
                }
            }
        }
    }

    /**
     * Оценивает тип защиты по непосредственной оболочке кровати:
     * только боковые стороны + верх (без нижнего слоя).
     * Если покрытие слабое, считаем, что выраженной защиты нет ("---").
     */
    private static DefenseSample calcDefense(Minecraft mc, BlockPos bedHead) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        Set<BlockPos> bedParts = new LinkedHashSet<>();
        bedParts.add(bedHead.immutable());
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (ox == 0 && oy == 0 && oz == 0) continue;
                    p.set(bedHead.getX() + ox, bedHead.getY() + oy, bedHead.getZ() + oz);
                    if (!mc.level.isLoaded(p)) continue;
                    if (mc.level.getBlockState(p).getBlock() instanceof BedBlock) {
                        bedParts.add(p.immutable());
                    }
                }
            }
        }

        int[] tierCounts = new int[5]; // 1..4
        int unknown = 0;
        int shellSlots = 0;
        int occupiedSlots = 0;
        int[][] dirs = {
            { 1, 0, 0 }, { -1, 0, 0 },
            { 0, 0, 1 }, { 0, 0, -1 },
            { 0, 1, 0 }
        };
        Set<BlockPos> shell = new HashSet<>();
        for (BlockPos part : bedParts) {
            for (int[] d : dirs) {
                BlockPos pos = part.offset(d[0], d[1], d[2]);
                if (bedParts.contains(pos)) continue;
                if (!shell.add(pos)) continue;
                shellSlots++;
                if (!mc.level.isLoaded(pos)) {
                    unknown++;
                    continue;
                }
                BlockState bs = mc.level.getBlockState(pos);
                if (bs.isAir() || bs.getBlock() instanceof BedBlock) continue;
                occupiedSlots++;
                int tier = defenseTierByResistance(bs.getBlock().getExplosionResistance());
                tierCounts[tier]++;
            }
        }

        // Not enough direct shell coverage => treat as no defense, avoid stale "WOL".
        if (shellSlots == 0) {
            return new DefenseSample(0, true);
        }
        double coverage = occupiedSlots / (double) shellSlots;
        if (coverage < 0.60) {
            return new DefenseSample(0, true);
        }

        int bestTier = 1;
        for (int t = 2; t <= 4; t++) {
            if (tierCounts[t] > tierCounts[bestTier]) bestTier = t;
        }
        // If shell is too mixed, keep conservative classification.
        if (tierCounts[bestTier] < Math.max(2, (int) Math.ceil(occupiedSlots * 0.55))) {
            return new DefenseSample(0, true);
        }
        int score = switch (bestTier) {
            case 4 -> 180;
            case 3 -> 110;
            case 2 -> 55;
            default -> 18;
        };
        return new DefenseSample(score, unknown <= 2);
    }

    private static int defenseTierByResistance(float blastRes) {
        if (blastRes < 1f) return 1;
        if (blastRes < 10f) return 2;
        if (blastRes < 1200f) return 3;
        return 4;
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

        long now = System.currentTimeMillis();
        boolean shouldRecomputeLive =
            (now - lastHudLiveRecomputeAtMs) >= HUD_LIVE_RECOMPUTE_INTERVAL_MS
                || lastHudLiveRecomputeAtMs == 0L;

        if (shouldRecomputeLive) {
            cachedEnemyViews = new ArrayList<>(buildPlayerViews(mc, self, enemies));
            cachedTeamViews = new ArrayList<>(buildPlayerViews(mc, self, teammates));
            cachedPlayersAtMs = now;
        }
        if (shouldRecomputeLive) {
            lastHudLiveRecomputeAtMs = now;
            refreshContextHelpers(mc, self, now);
        }

        boolean useCachedPlayers = !shouldRecomputeLive && cacheFresh(cachedPlayersAtMs);
        List<PlayerView> enemyViews = cachedEnemyViews;
        List<PlayerView> teamViews  = cachedTeamViews;

        PlayerView priorityTarget = pickPriorityTarget(enemyViews);
        boolean danger = priorityTarget != null
            && (priorityTarget.distM() < DANGER_M || priorityTarget.aiming() || priorityTarget.etaSec() <= 2.5);

        if (shouldRecomputeLive) {
            cachedTeamStats = new ArrayList<>(buildTeamStats(mc, self, enemies, teammates, myTeam));
            cachedTeamStatsAtMs = now;
        }
        boolean useCachedTeamStats = !shouldRecomputeLive && cacheFresh(cachedTeamStatsAtMs);
        List<TeamStat> teamStats = cachedTeamStats;
        boolean showTable = !teamStats.isEmpty();

        if (shouldRecomputeLive) {
            boolean useCacheAnySnapshot = useCachedPlayers || useCachedTeamStats;
            cachedFreshnessLabel = computeFreshnessLabel(useCacheAnySnapshot);
        }

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
        String freshness = freshnessLabel();
        int rightEdgeX = panelX + PANEL_W - PAD;

        int freshnessX = titleX + mc.font.width(title) + 8;
        int gap = 4;
        int charW = Math.max(1, mc.font.width("m"));

        // Hint on the right may collide with freshness; shorten one of them to keep a clean header line.
        String hint = "";
        if (!enemyViews.isEmpty()) {
            hint = enemyViews.size() + " hostile";
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
        drawFireballCrosshairWarning(g, mc);
        drawSafeRetreatVector(g, mc);

        // Враги
        if (!enemyViews.isEmpty()) {
            txt(g, mc, "ENEMIES (" + enemyViews.size() + ")", panelX + PAD, y, 0xFFFF5555);
            y += ROW_H;
            for (int i = 0; i < Math.min(enemyViews.size(), MAX_ENEMIES); i++) {
                PlayerView row = enemyViews.get(i);
                boolean emphasize = isProjectedPriorityThreat(row, priorityTarget);
                int rowShift = emphasize ? THREAT_ROW_SHIFT_X : 0;
                if (emphasize) {
                    drawThreatRowCard(g, panelX, y);
                }
                playerRow(g, mc, row, panelX + PAD + rowShift, y, panelX + PANEL_W - PAD + rowShift);
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

        drawTacticalRadar(g, mc, self, enemies);
        drawContextHelpers(g, mc);
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
        long now = System.currentTimeMillis();
        for (AbstractClientPlayer p : players) {
            double dist  = self.distanceTo(p);
            float hp     = p.getHealth();
            float maxHp  = Math.max(1f, p.getMaxHealth());
            float hpPct  = hp / maxHp;
            double dy    = p.getY() - self.getY();
            double etaRaw = etaToContact(self, p, dist);
            double eta   = smoothEta(p.getUUID(), etaRaw, now);
            String dyStr = dy > 2.5 ? "+" + (int) dy : dy < -2.5 ? "" + (int) dy : "";
            String etaTxt = (eta > 0 && eta < 20.0) ? " " + formatEta(eta) : "";
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
        purgeOldEta(now);
        return rows;
    }

    private static double smoothEta(UUID playerId, double etaRaw, long nowMs) {
        if (!Double.isFinite(etaRaw) || etaRaw <= 0.0) {
            etaSeenAtMs.put(playerId, nowMs);
            return etaRaw;
        }
        // Clamp extreme values to keep UI readable and stable.
        etaRaw = Math.min(20.0, etaRaw);
        Double prev = etaSmoothedByPlayer.get(playerId);
        double smoothed;
        if (prev == null) {
            smoothed = etaRaw;
        } else {
            double delta = etaRaw - prev;
            // Anti-jitter: clamp abrupt per-tick jump.
            if (delta > ETA_MAX_STEP_PER_TICK) delta = ETA_MAX_STEP_PER_TICK;
            if (delta < -ETA_MAX_STEP_PER_TICK) delta = -ETA_MAX_STEP_PER_TICK;
            // Faster adaptation when target starts closing; avoids stale overestimated ETA.
            double alpha = delta < 0 ? Math.min(0.45, ETA_SMOOTH_ALPHA * 1.9) : ETA_SMOOTH_ALPHA;
            smoothed = prev + delta * alpha;
        }
        etaSmoothedByPlayer.put(playerId, smoothed);
        etaSeenAtMs.put(playerId, nowMs);
        return smoothed;
    }

    private static String formatEta(double etaSec) {
        // Quantize to 0.2s to eliminate fast micro-flicker in text.
        double q = Math.round(etaSec * 5.0) / 5.0;
        return String.format(Locale.ROOT, "%.1fs", q);
    }

    private static void purgeOldEta(long nowMs) {
        long ttlMs = 10_000L;
        Iterator<Map.Entry<UUID, Long>> it = etaSeenAtMs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (nowMs - e.getValue() > ttlMs) {
                etaSmoothedByPlayer.remove(e.getKey());
                it.remove();
            }
        }
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

    private static void drawTacticalRadar(GuiGraphics g, Minecraft mc, LocalPlayer self, List<AbstractClientPlayer> enemies) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int x = sw - RADAR_SIZE - 8;
        int y = 8;
        int size = RADAR_SIZE;
        int cx = x + size / 2;
        int cy = y + size / 2;
        int r = size / 2 - 6;

        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0x33000000);
        g.fill(x, y, x + size, y + size, 0x22000000);
        drawRadarRangeRings(g, mc, cx, cy, r);
        g.fill(cx - 1, y + 4, cx + 1, y + size - 4, 0x33FFFFFF);
        g.fill(x + 4, cy - 1, x + size - 4, cy + 1, 0x33FFFFFF);

        int builtMarks = drawRadarConstructions(g, mc, self, cx, cy, r);
        int enemyMarks = drawRadarEnemies(g, self, enemies, cx, cy, r);

        // Player marker in center.
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFF55FFFF);
        txt(g, mc, "RADAR", x + 5, y + 4, 0xFFFFFFFF);
        txt(g, mc, enemyMarks + "E", x + 6, y + size - 11, 0xFFFF6666);
        txt(g, mc, builtMarks + "B", x + size - 24, y + size - 11, 0xFFBBBBBB);
    }

    private static void drawRadarRangeRings(GuiGraphics g, Minecraft mc, int cx, int cy, int radius) {
        for (int mark : RADAR_RANGE_MARKS) {
            int rr = (int) Math.round(mark * (double) radius / radarRangeBlocks);
            if (rr <= 1) continue;
            int color = mark == 30 ? 0x66B8DCFF : 0x447AA5D6;
            drawRadarRing(g, cx, cy, rr, color);
        }
    }

    private static void drawRadarRing(GuiGraphics g, int cx, int cy, int rr, int color) {
        // Midpoint circle for a cleaner, continuous ring.
        int x = rr;
        int y = 0;
        int d = 1 - rr;
        while (x >= y) {
            plotRadarCircle8(g, cx, cy, x, y, color);
            y++;
            if (d < 0) {
                d += 2 * y + 1;
            } else {
                x--;
                d += 2 * (y - x) + 1;
            }
        }
    }

    private static void plotRadarCircle8(GuiGraphics g, int cx, int cy, int x, int y, int color) {
        drawRadarPixel(g, cx + x, cy + y, color);
        drawRadarPixel(g, cx + y, cy + x, color);
        drawRadarPixel(g, cx - y, cy + x, color);
        drawRadarPixel(g, cx - x, cy + y, color);
        drawRadarPixel(g, cx - x, cy - y, color);
        drawRadarPixel(g, cx - y, cy - x, color);
        drawRadarPixel(g, cx + y, cy - x, color);
        drawRadarPixel(g, cx + x, cy - y, color);
    }

    private static void drawRadarPixel(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 1, y + 1, color);
    }

    private static int drawRadarConstructions(GuiGraphics g, Minecraft mc, LocalPlayer self, int cx, int cy, int radius) {
        if (mc.level == null) return 0;
        ensureRadarConstructionsCache(mc, self);
        int marks = 0;
        for (RadarConstructionSample sample : cachedRadarConstructions) {
                int[] radar = toRadarCoords(self, sample.dx(), sample.dz(), radius);
                int rx = radar[0];
                int ry = radar[1];
                g.fill(cx + rx, cy + ry, cx + rx + 1, cy + ry + 1, sample.color());
                marks++;
        }
        return marks;
    }

    private static void ensureRadarConstructionsCache(Minecraft mc, LocalPlayer self) {
        long now = System.currentTimeMillis();
        BlockPos center = self.blockPosition();
        boolean rangeChanged = radarCacheRangeBlocks != radarRangeBlocks;
        boolean moved =
            Math.abs(center.getX() - radarCacheCenterX) >= RADAR_CACHE_MOVE_THRESHOLD
                || Math.abs(center.getZ() - radarCacheCenterZ) >= RADAR_CACHE_MOVE_THRESHOLD
                || Math.abs(center.getY() - radarCacheCenterY) >= 2;
        boolean stale = now - cachedRadarConstructionsAtMs >= RADAR_CONSTRUCTIONS_CACHE_MS;
        if (!rangeChanged && !moved && !stale && !cachedRadarConstructions.isEmpty()) {
            return;
        }
        rebuildRadarConstructionsCache(mc, center);
        cachedRadarConstructionsAtMs = now;
        radarCacheCenterX = center.getX();
        radarCacheCenterY = center.getY();
        radarCacheCenterZ = center.getZ();
        radarCacheRangeBlocks = radarRangeBlocks;
    }

    private static void rebuildRadarConstructionsCache(Minecraft mc, BlockPos center) {
        List<RadarConstructionSample> next = new ArrayList<>(RADAR_MAX_CONSTRUCTION_MARKS);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int y0 = center.getY();
        int step = Math.max(3, radarRangeBlocks / 16);

        for (int dx = -radarRangeBlocks; dx <= radarRangeBlocks; dx += step) {
            for (int dz = -radarRangeBlocks; dz <= radarRangeBlocks; dz += step) {
                double distSq = dx * dx + dz * dz;
                if (distSq > radarRangeBlocks * radarRangeBlocks) continue;
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int bestY = Integer.MIN_VALUE;
                int bestDy = Integer.MAX_VALUE;
                for (int dy = -RADAR_Y_RANGE; dy <= RADAR_Y_RANGE; dy++) {
                    p.set(x, y0 + dy, z);
                    if (!mc.level.isLoaded(p)) continue;
                    BlockState bs = mc.level.getBlockState(p);
                    if (bs.isAir() || bs.getBlock() instanceof BedBlock) continue;
                    int absDy = Math.abs(dy);
                    if (absDy < bestDy) {
                        bestDy = absDy;
                        bestY = y0 + dy;
                    }
                }
                if (bestY == Integer.MIN_VALUE) continue;
                int color = radarConstructionColor(mc, x, bestY, z, probe);
                next.add(new RadarConstructionSample(dx, dz, color));
                if (next.size() >= RADAR_MAX_CONSTRUCTION_MARKS) {
                    cachedRadarConstructions = next;
                    return;
                }
            }
        }
        cachedRadarConstructions = next;
    }

    private static int radarConstructionColor(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos probe) {
        boolean hasAbove = isSolidAt(mc, x, y + 1, z, probe);
        boolean hasAbove2 = isSolidAt(mc, x, y + 2, z, probe);
        boolean airBelow = !isSolidAt(mc, x, y - 1, z, probe);

        int sideSolid = 0;
        if (isSolidAt(mc, x + 1, y, z, probe)) sideSolid++;
        if (isSolidAt(mc, x - 1, y, z, probe)) sideSolid++;
        if (isSolidAt(mc, x, y, z + 1, probe)) sideSolid++;
        if (isSolidAt(mc, x, y, z - 1, probe)) sideSolid++;

        // High constructions (towers/stacks): 3+ vertical blocks.
        if (hasAbove && hasAbove2) return 0xCC66E6FF;
        // Walls/fortifications: dense side neighbors or 2-block height.
        if (hasAbove || sideSolid >= 3) return 0xCCFFB366;
        // Bridges / thin paths: exposed bottom or sparse neighbors.
        if (airBelow || sideSolid <= 1) return 0xCCCECECE;
        return 0xCC97D897;
    }

    private static boolean isSolidAt(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos probe) {
        probe.set(x, y, z);
        if (!mc.level.isLoaded(probe)) return false;
        return !mc.level.getBlockState(probe).isAir();
    }

    private static int drawRadarEnemies(GuiGraphics g, LocalPlayer self, List<AbstractClientPlayer> enemies, int cx, int cy, int radius) {
        int marks = 0;
        for (AbstractClientPlayer enemy : enemies) {
            double dx = enemy.getX() - self.getX();
            double dz = enemy.getZ() - self.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > radarRangeBlocks || dist < 0.01) continue;

            int[] radar = toRadarCoords(self, dx, dz, radius);
            int rx = radar[0];
            int ry = radar[1];
            int px = cx + rx;
            int py = cy + ry;

            int col = isAiming(enemy) ? 0xFFFFAA00 : 0xFFFF5555;
            g.fill(px - 1, py - 1, px + 2, py + 2, col);

            // Movement vector arrow.
            double vx = enemy.getX() - enemy.xo;
            double vz = enemy.getZ() - enemy.zo;
            double vm = Math.sqrt(vx * vx + vz * vz);
            if (vm > 0.002) {
                int mx = (int) Math.round(vx / vm * 4.0);
                int my = (int) Math.round(vz / vm * 4.0);
                g.fill(px + mx - 1, py + my - 1, px + mx + 1, py + my + 1, 0xFFFFCC88);
            }
            marks++;
        }
        return marks;
    }

    private static int[] toRadarCoords(LocalPlayer self, double dx, double dz, int radius) {
        // Rotate world offsets into player-local radar space:
        // +X on radar = player's right, -Y on radar = player's forward.
        Vec3 look = self.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0, look.z);
        if (forward.lengthSqr() < 1.0e-6) {
            forward = new Vec3(0.0, 0.0, 1.0);
        } else {
            forward = forward.normalize();
        }
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

        double side = dx * right.x + dz * right.z;
        double front = dx * forward.x + dz * forward.z;
        int rx = (int) Math.round(side * radius / radarRangeBlocks);
        int ry = (int) Math.round(-front * radius / radarRangeBlocks);
        return new int[] {rx, ry};
    }

    private static PlayerView pickPriorityTarget(List<PlayerView> enemies) {
        if (enemies.isEmpty()) return null;
        PlayerView best = null;
        for (PlayerView pv : enemies) {
            if (best == null || pv.threatScore() > best.threatScore()) best = pv;
        }
        return best;
    }

    private static boolean isProjectedPriorityThreat(PlayerView row, PlayerView priorityTarget) {
        if (priorityTarget == null) return false;
        if (row == priorityTarget) return true;
        // "Soon-priority" threat: close in score and already reasonably dangerous.
        return row.threatScore() >= priorityTarget.threatScore() * 0.86
            && (row.distM() <= 24 || row.aiming() || row.etaSec() <= 4.0);
    }

    private static void drawThreatRowCard(GuiGraphics g, int panelX, int y) {
        int top = y - 1;
        int bottom = y + ROW_H - 1;
        int left = panelX + 2;
        int right = panelX + PANEL_W + THREAT_ROW_EXTRA_BG;
        g.fill(left, top, right, bottom, 0x55300000);
        g.fill(left + 1, top + 1, right, bottom - 1, 0x33220000);
    }

    private static void refreshContextHelpers(Minecraft mc, LocalPlayer self, long nowMs) {
        if (ScopeConfig.FIREBALL_THREAT_ENABLED.get()) {
            evaluateFireballThreat(mc, self);
            if (fireballAlertDanger) {
                playWarningSound(mc, true, nowMs);
            }
        } else {
            fireballAlertText = "";
            fireballAlertDanger = false;
        }
        if (ScopeConfig.BRIDGE_HELPER_ENABLED.get()) {
            evaluateBridgeHelper(mc, self);
            if (bridgeAlertDanger) {
                playWarningSound(mc, false, nowMs);
            }
        } else {
            bridgeAlertText = "";
            bridgeAlertDanger = false;
        }
        evaluateSafeRetreat(mc, self);
    }

    private static void evaluateFireballThreat(Minecraft mc, LocalPlayer self) {
        fireballAlertDanger = false;
        fireballAlertText = "FIREBALL: clear";
        fireballAlertColor = 0xFF66CC66;
        AABB box = self.getBoundingBox().inflate(36.0, 18.0, 36.0);
        Entity best = null;
        double bestEta = Double.POSITIVE_INFINITY;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Entity e : mc.level.getEntities(self, box)) {
            Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            String path = entityId == null ? "" : entityId.getPath();
            if (!path.contains("fireball")) continue;
            Vec3 toPlayer = self.position().subtract(e.position());
            double dist = toPlayer.length();
            if (dist < 0.001) continue;
            Vec3 vel = e.getDeltaMovement();
            double closingPerTick = toPlayer.normalize().dot(vel);
            if (closingPerTick <= 0.015) continue;
            double etaSec = (dist / closingPerTick) / 20.0;
            if (etaSec < bestEta) {
                bestEta = etaSec;
                bestDist = dist;
                best = e;
            }
        }
        if (best != null) {
            fireballAlertDanger = bestEta <= 2.4 || bestDist <= 18.0;
            String dir = directionArrowFromVector(self, best.position().subtract(self.position()));
            fireballAlertText = String.format(Locale.ROOT, "FIREBALL %s %.0fm %.1fs", dir, bestDist, bestEta);
            fireballAlertColor = fireballAlertDanger ? 0xFFFF5555 : 0xFFFFAA55;
        }
    }

    private static void evaluateBridgeHelper(Minecraft mc, LocalPlayer self) {
        BlockPos base = self.blockPosition();
        boolean overVoid = isVoidColumn(mc, base, 14);
        int sideAir = 0;
        if (isAirAt(mc, base.getX() + 1, base.getY() - 1, base.getZ())) sideAir++;
        if (isAirAt(mc, base.getX() - 1, base.getY() - 1, base.getZ())) sideAir++;
        if (isAirAt(mc, base.getX(), base.getY() - 1, base.getZ() + 1)) sideAir++;
        if (isAirAt(mc, base.getX(), base.getY() - 1, base.getZ() - 1)) sideAir++;
        boolean edgeRisk = sideAir >= 2;
        double speed = self.getDeltaMovement().horizontalDistance();
        boolean moving = speed > 0.025;
        int predictSteps = speed > 0.11 ? 6 : speed > 0.06 ? 4 : 3;
        boolean predictedVoidDanger = false;
        Vec3 move = self.getDeltaMovement();
        if (moving) {
            double dirX = move.x;
            double dirZ = move.z;
            for (int i = 1; i <= predictSteps; i++) {
                int px = Mth.floor(self.getX() + dirX * i * 2.3);
                int pz = Mth.floor(self.getZ() + dirZ * i * 2.3);
                BlockPos probe = new BlockPos(px, base.getY(), pz);
                if (isVoidColumn(mc, probe, 12)) {
                    predictedVoidDanger = true;
                    break;
                }
            }
        }

        bridgeAlertDanger = moving && (overVoid || predictedVoidDanger)
            && (speed > 0.030 || self.fallDistance > 0.8f || edgeRisk || predictedVoidDanger);
        if (bridgeAlertDanger) {
            bridgeAlertText = predictedVoidDanger
                ? String.format(Locale.ROOT, "BRIDGE RISK AHEAD %.0fm", Math.max(2.0, speed * 38.0))
                : String.format(Locale.ROOT, "BRIDGE RISK speed %.2f", speed);
            bridgeAlertColor = predictedVoidDanger ? 0xFFFF7744 : 0xFFFFAA33;
        } else if (overVoid) {
            bridgeAlertText = "BRIDGE: over void";
            bridgeAlertColor = 0xFFFFFF55;
        } else {
            bridgeAlertText = "BRIDGE: stable";
            bridgeAlertColor = 0xFF66CC66;
        }
    }

    private static boolean isVoidColumn(Minecraft mc, BlockPos base, int depth) {
        for (int i = 1; i <= depth; i++) {
            BlockPos p = base.below(i);
            if (mc.level.isLoaded(p) && !mc.level.getBlockState(p).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static void drawContextHelpers(GuiGraphics g, Minecraft mc) {
        List<String> lines = new ArrayList<>(3);
        List<Integer> colors = new ArrayList<>(3);
        if (ScopeConfig.FIREBALL_THREAT_ENABLED.get() && !fireballAlertText.isEmpty()) {
            lines.add(fireballAlertText);
            colors.add(fireballAlertColor);
        }
        if (ScopeConfig.BRIDGE_HELPER_ENABLED.get() && !bridgeAlertText.isEmpty()) {
            lines.add(bridgeAlertText);
            colors.add(bridgeAlertColor);
        }
        if (retreatAlertDanger && !retreatAlertText.isEmpty()) {
            lines.add(retreatAlertText);
            colors.add(retreatAlertColor);
        }
        if (lines.isEmpty()) return;

        int maxW = 0;
        for (String line : lines) maxW = Math.max(maxW, mc.font.width(line));
        int x = mc.getWindow().getGuiScaledWidth() - maxW - 16;
        int y = 8 + RADAR_SIZE + 6;
        int h = lines.size() * 12 + 6;
        g.fill(x - 4, y - 2, x + maxW + 6, y + h, 0x44000000);
        for (int i = 0; i < lines.size(); i++) {
            txt(g, mc, lines.get(i), x, y + i * 12, colors.get(i));
        }
    }

    private static void drawFireballCrosshairWarning(GuiGraphics g, Minecraft mc) {
        if (!ScopeConfig.FIREBALL_THREAT_ENABLED.get() || !fireballAlertDanger || fireballAlertText.isEmpty()) return;
        long t = System.currentTimeMillis();
        if (((t / 220L) % 2L) == 0L) return; // blink

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        boolean scopeActive = ScopeOverlay.isScopeInputActive(mc);
        int y = sh / 2 + (scopeActive ? 52 : 30);
        String label = "FIREBALL INBOUND";
        int w = mc.font.width(label);
        int x = (sw - w) / 2;

        g.fill(x - 16, y - 3, x + w + 8, y + 10, 0xAA5A1200);
        // Small "fireball" icon at left
        g.fill(x - 12, y + 1, x - 6, y + 7, 0xFFFFAA33);
        g.fill(x - 11, y + 2, x - 7, y + 6, 0xFFFF5533);
        txt(g, mc, label, x, y, 0xFFFFE066);
    }

    private static void drawSafeRetreatVector(GuiGraphics g, Minecraft mc) {
        if (!retreatAlertDanger || retreatAlertText.isEmpty()) return;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        boolean scopeActive = ScopeOverlay.isScopeInputActive(mc);
        int y = sh / 2 + (scopeActive ? 70 : 46);
        if (ScopeConfig.FIREBALL_THREAT_ENABLED.get() && fireballAlertDanger) {
            y += 16;
        }
        y = Math.min(y, sh - 54);

        long t = System.currentTimeMillis();
        boolean pulse = ((t / 180L) % 2L) == 0L;
        String label = retreatAlertText;
        int w = mc.font.width(label);
        int x = (sw - w) / 2;
        int bg = pulse ? 0xAA2F0000 : 0xAA4A0000;
        int fg = pulse ? 0xFFFF8855 : retreatAlertColor;

        g.fill(x - 12, y - 4, x + w + 12, y + 11, bg);
        g.fill(x - 10, y - 2, x + w + 10, y + 10, 0x66200000);
        txt(g, mc, label, x, y, fg);
    }

    private static void playWarningSound(Minecraft mc, boolean fireball, long nowMs) {
        if (mc.player == null) return;
        if (!ScopeConfig.WARNING_SOUND_ENABLED.get()) return;
        if (fireball) {
            if (!ScopeConfig.FIREBALL_WARNING_SOUND.get()) return;
            if (nowMs - lastFireballWarnAtMs < FIREBALL_SOUND_COOLDOWN_MS) return;
            lastFireballWarnAtMs = nowMs;
            float vol = ScopeConfig.FIREBALL_WARNING_VOLUME.get() / 100.0f;
            if (vol > 0f) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), vol, 1.35f);
            }
        } else {
            if (!ScopeConfig.VOID_WARNING_SOUND.get()) return;
            if (nowMs - lastVoidWarnAtMs < VOID_SOUND_COOLDOWN_MS) return;
            lastVoidWarnAtMs = nowMs;
            float vol = ScopeConfig.VOID_WARNING_VOLUME.get() / 100.0f;
            if (vol > 0f) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), vol, 1.0f);
            }
        }
    }

    private static boolean isAirAt(Minecraft mc, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!mc.level.isLoaded(pos)) return true;
        return mc.level.getBlockState(pos).isAir();
    }

    private static void evaluateSafeRetreat(Minecraft mc, LocalPlayer self) {
        retreatAlertDanger = false;
        retreatAlertText = "";
        retreatAlertColor = 0xFFFF6666;

        double selfHp = self.getHealth() + self.getAbsorptionAmount();
        if (selfHp <= 0.1) return;

        AABB box = self.getBoundingBox().inflate(RETREAT_SCAN_RADIUS, 8.0, RETREAT_SCAN_RADIUS);
        PlayerTeam myTeam = mc.level.getScoreboard().getPlayersTeam(self.getScoreboardName());
        List<AbstractClientPlayer> threats = new ArrayList<>();
        int nearbyAllies = 0;

        for (Player raw : mc.level.players()) {
            if (!(raw instanceof AbstractClientPlayer p)) continue;
            if (p == self || !p.isAlive() || p.isSpectator()) continue;
            if (!box.intersects(p.getBoundingBox())) continue;
            PlayerTeam team = mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName());
            if (myTeam != null && myTeam.equals(team)) {
                if (self.distanceTo(p) <= 17.0f) nearbyAllies++;
                continue;
            }
            if (self.distanceTo(p) <= RETREAT_SCAN_RADIUS) threats.add(p);
        }
        if (threats.isEmpty()) return;

        int closeThreats = 0;
        double enemyHpSum = 0.0;
        double pressure = 0.0;
        Vec3 pressureVec = Vec3.ZERO;
        for (AbstractClientPlayer enemy : threats) {
            double dist = Math.max(0.8, self.distanceTo(enemy));
            if (dist <= 16.0) closeThreats++;
            double enemyHp = enemy.getHealth() + enemy.getAbsorptionAmount();
            enemyHpSum += enemyHp;
            double eta = etaToContact(self, enemy, dist);
            double etaFactor = (eta > 0.0 && eta < 8.0) ? (8.0 - eta) / 8.0 : 0.0;
            double weight = (1.0 / dist) * (1.0 + etaFactor + (isAiming(enemy) ? 0.35 : 0.0));
            pressure += weight;
            Vec3 toEnemy = enemy.position().subtract(self.position());
            Vec3 flat = new Vec3(toEnemy.x, 0.0, toEnemy.z);
            if (flat.lengthSqr() > 1.0e-6) {
                pressureVec = pressureVec.add(flat.normalize().scale(weight));
            }
        }

        int effectiveEnemies = Math.max(closeThreats, threats.size());
        int allySupport = nearbyAllies + 1;
        double enemyToSelfHpRatio = enemyHpSum / Math.max(1.0, selfHp);
        boolean outnumbered = effectiveEnemies > allySupport;
        boolean lowHp = selfHp <= 10.0 && effectiveEnemies >= 1;
        boolean hpDisadvantage = enemyToSelfHpRatio >= 1.55 && effectiveEnemies >= 2;
        boolean heavyPressure = pressure >= 0.42 && effectiveEnemies >= 2;
        boolean likelyLosing = outnumbered || lowHp || hpDisadvantage || heavyPressure;
        if (!likelyLosing) return;

        Vec3 retreatDir = chooseSafeRetreatDirection(mc, self, pressureVec, threats);
        if (retreatDir.lengthSqr() < 1.0e-6) return;
        String arrow = directionArrowFromVector(self, retreatDir);
        String reason;
        if (outnumbered) {
            reason = String.format(Locale.ROOT, "%dv%d", effectiveEnemies, allySupport);
        } else if (lowHp) {
            reason = String.format(Locale.ROOT, "HP %.0f", selfHp);
        } else if (hpDisadvantage) {
            reason = String.format(Locale.ROOT, "HP x%.1f", enemyToSelfHpRatio);
        } else {
            reason = "pressure";
        }

        retreatAlertDanger = true;
        retreatAlertColor = (effectiveEnemies >= 3 || selfHp <= 7.0) ? 0xFFFF4444 : 0xFFFFAA55;
        retreatAlertText = "RETREAT " + arrow + "  " + reason;
    }

    private static Vec3 chooseSafeRetreatDirection(Minecraft mc, LocalPlayer self, Vec3 pressureVec,
                                                   List<AbstractClientPlayer> threats) {
        Vec3 fallback;
        if (pressureVec.lengthSqr() < 1.0e-6) {
            Vec3 look = self.getLookAngle();
            fallback = new Vec3(-look.x, 0.0, -look.z);
        } else {
            fallback = new Vec3(-pressureVec.x, 0.0, -pressureVec.z);
        }
        if (fallback.lengthSqr() < 1.0e-6) {
            fallback = new Vec3(0.0, 0.0, 1.0);
        }
        fallback = fallback.normalize();

        Vec3 bestDir = fallback;
        double bestScore = scoreRetreatDirection(mc, self, fallback, threats);
        for (int i = 0; i < RETREAT_SAMPLE_DIRECTIONS; i++) {
            double angle = (Math.PI * 2.0 * i) / RETREAT_SAMPLE_DIRECTIONS;
            Vec3 dir = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
            double score = scoreRetreatDirection(mc, self, dir, threats);
            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }
        return bestDir;
    }

    private static double scoreRetreatDirection(Minecraft mc, LocalPlayer self, Vec3 dir,
                                                List<AbstractClientPlayer> threats) {
        dir = dir.normalize();
        BlockPos near = new BlockPos(
            Mth.floor(self.getX() + dir.x * RETREAT_SAMPLE_DISTANCE),
            self.blockPosition().getY(),
            Mth.floor(self.getZ() + dir.z * RETREAT_SAMPLE_DISTANCE)
        );
        BlockPos far = new BlockPos(
            Mth.floor(self.getX() + dir.x * RETREAT_SAMPLE_DISTANCE_FAR),
            self.blockPosition().getY(),
            Mth.floor(self.getZ() + dir.z * RETREAT_SAMPLE_DISTANCE_FAR)
        );

        double safety = 0.0;
        if (!isVoidColumn(mc, near, 12)) safety += 1.4;
        if (!isVoidColumn(mc, far, 12)) safety += 0.9;
        if (!isAirAt(mc, near.getX(), near.getY() - 1, near.getZ())) safety += 0.6;

        for (AbstractClientPlayer enemy : threats) {
            Vec3 fromEnemy = self.position().subtract(enemy.position());
            Vec3 away = new Vec3(fromEnemy.x, 0.0, fromEnemy.z);
            if (away.lengthSqr() < 1.0e-6) continue;
            away = away.normalize();
            double align = dir.dot(away); // >0 means direction moves away from enemy
            double dist = Math.max(1.0, self.distanceTo(enemy));
            safety += align * (10.0 / dist);
        }
        return safety;
    }

    private static String directionArrowFromVector(LocalPlayer self, Vec3 toTarget) {
        Vec3 forward = self.getLookAngle();
        forward = new Vec3(forward.x, 0.0, forward.z);
        if (forward.lengthSqr() < 1.0e-6) return "↑";
        forward = forward.normalize();
        Vec3 to = new Vec3(toTarget.x, 0.0, toTarget.z);
        if (to.lengthSqr() < 1.0e-6) return "↑";
        to = to.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        double front = forward.dot(to);
        double side = right.dot(to);
        if (front >= 0.9239) return "↑";
        if (front <= -0.9239) return "↓";
        if (Math.abs(side) >= 0.9239) return side > 0 ? "→" : "←";
        if (front > 0) return side > 0 ? "↗" : "↖";
        return side > 0 ? "↘" : "↙";
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
        double rx = self.getX() - p.getX();
        double rz = self.getZ() - p.getZ();
        double rlen = Math.sqrt(rx * rx + rz * rz);
        if (rlen < 1.0e-6) return 0.0;

        // Relative velocity (enemy against player), projected on line-of-sight.
        double evx = p.getX() - p.xo;
        double evz = p.getZ() - p.zo;
        double svx = self.getX() - self.xo;
        double svz = self.getZ() - self.zo;
        double rvx = evx - svx;
        double rvz = evz - svz;

        double nx = rx / rlen;
        double nz = rz / rlen;
        double closingPerTick = rvx * nx + rvz * nz;
        if (closingPerTick <= 0.018) return Double.POSITIVE_INFINITY;
        double etaSec = (rlen / closingPerTick) / 20.0;
        return Mth.clamp(etaSec, 0.0, 20.0);
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

    private static String computeFreshnessLabel(boolean usedCache) {
        if (scanState.active) return "SCAN";
        return usedCache ? "CACHE" : "LIVE";
    }

    private static String freshnessLabel() {
        return cachedFreshnessLabel;
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
        Map<PlayerTeam, List<AbstractClientPlayer>> aliveByTeam = new LinkedHashMap<>();
        for (AbstractClientPlayer p : enemies) {
            PlayerTeam t = mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName());
            if (t != null) aliveByTeam.computeIfAbsent(t, k -> new ArrayList<>()).add(p);
        }
        for (AbstractClientPlayer p : teammates) {
            PlayerTeam t = mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName());
            if (t != null) aliveByTeam.computeIfAbsent(t, k -> new ArrayList<>()).add(p);
        }
        if (self instanceof AbstractClientPlayer acp && myTeam != null) {
            aliveByTeam.computeIfAbsent(myTeam, k -> new ArrayList<>()).add(acp);
        }

        List<TeamStat> stats = new ArrayList<>();
        Set<PlayerTeam> addedEnemyTeams = new LinkedHashSet<>();

        // Use scoreboard teams as a source of truth so teams don't disappear when all members are dead/respawning.
        for (PlayerTeam t : mc.level.getScoreboard().getPlayerTeams()) {
            if (t == null) continue;
            if (myTeam != null && myTeam.equals(t)) continue;
            List<AbstractClientPlayer> pl = aliveByTeam.getOrDefault(t, List.of());
            BedInfo bed = bedForTeam(t, true, false);
            // Do not show "noise" scoreboard teams:
            // keep only currently active teams or teams with known bed state.
            if (pl.isEmpty() && bed == null) continue;
            int nc = teamDisplayColor(mc, t);
            stats.add(new TeamStat(t, t != null ? cap(t.getName(), 7) : "?",
                nc, pl.size(), computeScore(pl), bestArmorIn(pl), false, bed));
            addedEnemyTeams.add(t);
        }

        // Keep backward compatibility: include alive enemy teams that may be absent from scoreboard team list.
        for (Map.Entry<PlayerTeam, List<AbstractClientPlayer>> e : aliveByTeam.entrySet()) {
            PlayerTeam t = e.getKey();
            if (t == null) continue;
            if (myTeam != null && myTeam.equals(t)) continue;
            if (addedEnemyTeams.contains(t)) continue;
            List<AbstractClientPlayer> pl = e.getValue();
            int nc = teamDisplayColor(mc, t);
            stats.add(new TeamStat(t, cap(t.getName(), 7), nc, pl.size(), computeScore(pl), bestArmorIn(pl), false,
                bedForTeam(t, true, false)));
        }

        if (!teammates.isEmpty() || myTeam != null) {
            List<AbstractClientPlayer> mine = new ArrayList<>(teammates);
            if (self instanceof AbstractClientPlayer acp) mine.add(acp);
            int nc = teamDisplayColor(mc, myTeam);
            stats.add(new TeamStat(myTeam, myTeam != null ? cap(myTeam.getName(), 7) : "YOU",
                nc, mine.size(), computeScore(mine), bestArmorIn(mine), true,
                bedForTeam(myTeam, true, true)));
        }

        stats.sort(Comparator.comparingInt(TeamStat::score).reversed());
        return stats;
    }

    private static BedInfo bedForTeam(PlayerTeam team, boolean allowInferenceFallback, boolean strictInference) {
        Minecraft mc = Minecraft.getInstance();
        if (team == null || mc.level == null) return null;

        BedInfo inferred = allowInferenceFallback ? inferBedByTeamPlayers(mc, team, strictInference) : null;

        // Primary mapping: team color -> bed dye.
        DyeColor dye = teamColorToDye(team);
        if (dye != null) {
            BedInfo direct = bedData.get(dye);
            if (direct != null) {
                // If there are multiple beds with same dye, prefer position-based inference.
                if (inferred != null && countBedsByColor(dye) > 1) return inferred;
                return direct;
            }
        }

        // Additional mapping: some servers use nearby palette variants for team colors.
        for (DyeColor alt : teamColorAlternatives(team)) {
            BedInfo byAlt = bedData.get(alt);
            if (byAlt != null) {
                if (inferred != null && countBedsByColor(alt) > 1) return inferred;
                return byAlt;
            }
        }

        // Fallback for servers with custom scoreboard formatting:
        // pick the closest known bed to the members of this team.
        // For own team we avoid this heuristic to prevent false "alive" bed status.
        return inferred;
    }

    private static int countBedsByColor(DyeColor color) {
        int count = 0;
        for (BedInfo info : bedDataByPos.values()) {
            if (info != null && info.color() == color) count++;
        }
        return count;
    }

    private static BedInfo inferBedByTeamPlayers(Minecraft mc, PlayerTeam team, boolean strictInference) {
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
        double secondBestAvg = Double.MAX_VALUE;

        for (BedInfo info : bedDataByPos.values()) {
            if (info == null) continue;
            double sumSq = 0.0;
            for (AbstractClientPlayer p : teamPlayers) {
                sumSq += p.blockPosition().distSqr(info.pos());
            }
            double avgSq = sumSq / teamPlayers.size();
            if (avgSq < bestAvg) {
                secondBestAvg = bestAvg;
                bestAvg = avgSq;
                best = info;
            } else if (avgSq < secondBestAvg) {
                secondBestAvg = avgSq;
            }
        }

        // Ignore clearly unrelated beds; for own-team fallback require higher confidence.
        double maxDist = strictInference ? 140.0 : 220.0;
        if (bestAvg > (maxDist * maxDist)) return null;
        if (strictInference && secondBestAvg < Double.MAX_VALUE) {
            // Best match should be meaningfully better than next candidate.
            if (bestAvg > secondBestAvg * 0.80) return null;
        }
        return best;
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
