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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import ru.pleeey.bwsutil.client.match.MatchTracker;
import ru.pleeey.bwsutil.config.ScopeConfig;
import ru.pleeey.bwsutil.util.TeamColors;

import java.util.*;
import java.util.function.Predicate;

/**
 * BedWars tactical HUD: threat panel, team power table, match log and a compass radar.
 *
 * <p><b>Where work happens:</b> every piece of game logic — player scanning, threat scoring,
 * bed detection, alert evaluation and sounds — runs in {@link #tick(Minecraft)}. {@link #render}
 * only draws the snapshot that tick produced. That split matters for more than performance:
 * rendering is skipped while a screen is open or the HUD is hidden, so logic living there used
 * to go silent exactly when the player opened chat.</p>
 */
public final class BedWarsOverlay {

    private static boolean enabled = false;

    public static void toggleEnabled() {
        enabled = !enabled;
        resetState();
    }

    public static boolean isEnabled() { return enabled; }

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

    /** Snapshot rebuild cadence, in client ticks. Two ticks (~100 ms) reads as instant. */
    private static final int SNAPSHOT_INTERVAL_TICKS = 2;

    private static final double ETA_SMOOTH_ALPHA = 0.22;
    private static final double ETA_MAX_STEP_PER_TICK = 0.45;

    // ── Константы радара ─────────────────────────────────────────────────────

    private static final int RADAR_SIZE   = 96;
    private static final int RADAR_MARGIN = 8;
    /** Gap between the plotted area and the outer rim, reserved for the compass letters. */
    private static final int RADAR_RIM_PAD = 8;

    private static final int RADAR_RANGE_BLOCKS_DEFAULT = 48;
    private static final int RADAR_RANGE_BLOCKS_MIN = 24;
    private static final int RADAR_RANGE_BLOCKS_MAX = 96;
    private static final int RADAR_RANGE_BLOCKS_STEP = 4;
    private static final int RADAR_Y_RANGE = 10;
    private static final long RADAR_CONSTRUCTIONS_CACHE_MS = 420L;
    private static final int RADAR_CACHE_MOVE_THRESHOLD = 4;
    private static final int RADAR_MAX_CONSTRUCTION_MARKS = 220;

    private static final int RADAR_BG        = 0xBF0C0F14;
    private static final int RADAR_BG_INNER  = 0x59131A22;
    private static final int RADAR_RIM       = 0x8C8FB4CE;
    private static final int RADAR_RING      = 0x3D7FA6C4;
    private static final int RADAR_SELF      = 0xFF6FE9F5;
    private static final int RADAR_ENEMY     = 0xFFFF5555;
    private static final int RADAR_ENEMY_AIM = 0xFFFFB03A;
    private static final int RADAR_ENEMY_FAR = 0x8CFF5555;
    private static final int RADAR_COMPASS   = 0x99C7D6E0;
    private static final int RADAR_COMPASS_N = 0xFFE8F2F8;

    private static int radarRangeBlocks = RADAR_RANGE_BLOCKS_DEFAULT;

    // ── Константы помощников ─────────────────────────────────────────────────

    private static final long FIREBALL_SOUND_COOLDOWN_MS = 900L;
    private static final long VOID_SOUND_COOLDOWN_MS = 1_200L;
    private static final double RETREAT_SCAN_RADIUS = 26.0;
    private static final int RETREAT_SAMPLE_DIRECTIONS = 12;
    private static final double RETREAT_SAMPLE_DISTANCE = 7.0;
    private static final double RETREAT_SAMPLE_DISTANCE_FAR = 12.0;

    // ── Кровати: сканирование ────────────────────────────────────────────────

    /** Информация о найденной кровати. */
    private record BedInfo(BlockPos pos, DyeColor color, boolean alive, int defScore) {}

    private record DefenseSample(int score, boolean complete) {}

    /** DyeColor → информация о кровати (null = ни разу не видели в радиусе). */
    private static final Map<DyeColor, BedInfo> bedData = new LinkedHashMap<>();
    /** Состояние кроватей по позиции (нужно, чтобы разные кровати не "слипались"). */
    private static final Map<BlockPos, BedInfo> bedDataByPos = new LinkedHashMap<>();

    private static int bedScanTick = 0;
    private static final int SCAN_EVERY = 60;          // каждые 3 секунды
    private static final int SCAN_CHUNK_RADIUS = 8;    // 8 чанков = 128 блоков по XZ
    private static final int SCAN_Y = 48;              // блоков по Y
    private static final int SCAN_CHUNKS_PER_TICK = 32;

    /**
     * The scan walks loaded chunks and asks each section's palette whether a bed could be present
     * at all, so sections without beds cost nothing. Walking raw block positions instead meant
     * roughly 6.4 million {@code getBlockState} calls per pass, spread over ~23 seconds.
     */
    private static final Predicate<BlockState> IS_BED = state -> state.getBlock() instanceof BedBlock;

    private static final class BedScanState {
        int minCX, maxCX, minCZ, maxCZ;
        int cx, cz;
        int minY, maxY;
        boolean active;
        BlockPos center = BlockPos.ZERO;
    }

    private static final BedScanState scanState = new BedScanState();

    // ── Снимок состояния боя ─────────────────────────────────────────────────

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
    private static List<TeamStat>   cachedTeamStats  = new ArrayList<>();

    /**
     * Live enemy entities kept for the radar. Positions are read at draw time so markers move
     * smoothly between snapshots; the list itself is only rebuilt on tick.
     */
    private static List<AbstractClientPlayer> cachedRadarEnemies = new ArrayList<>();

    private static int snapshotTick = 0;

    private static final Map<UUID, Double> etaSmoothedByPlayer = new HashMap<>();
    private static final Map<UUID, Long> etaSeenAtMs = new HashMap<>();

    private static List<RadarConstructionSample> cachedRadarConstructions = new ArrayList<>();
    private static long cachedRadarConstructionsAtMs = 0L;
    private static int radarCacheCenterX;
    private static int radarCacheCenterY;
    private static int radarCacheCenterZ;
    private static int radarCacheRangeBlocks = RADAR_RANGE_BLOCKS_DEFAULT;

    private static boolean fireballAlertDanger;
    private static boolean bridgeAlertDanger;
    private static String retreatAlertText = "";
    private static int retreatAlertColor = 0xFFFF6666;
    private static boolean retreatAlertDanger;
    private static long lastFireballWarnAtMs;
    private static long lastVoidWarnAtMs;

    /**
     * Clears every per-match cache. Called when the overlay is toggled and when the player leaves
     * a server, so bed data from one map never leaks into the next.
     *
     * <p>The radar zoom is intentionally preserved: it is a user preference, not match state.</p>
     */
    public static void resetState() {
        cachedEnemyViews = new ArrayList<>();
        cachedTeamViews = new ArrayList<>();
        cachedTeamStats = new ArrayList<>();
        cachedRadarEnemies = new ArrayList<>();
        snapshotTick = 0;

        bedData.clear();
        bedDataByPos.clear();
        bedScanTick = 0;
        scanState.active = false;
        scanState.center = BlockPos.ZERO;

        etaSmoothedByPlayer.clear();
        etaSeenAtMs.clear();

        cachedRadarConstructions = new ArrayList<>();
        cachedRadarConstructionsAtMs = 0L;

        fireballAlertDanger = false;
        bridgeAlertDanger = false;
        retreatAlertText = "";
        retreatAlertDanger = false;
        lastFireballWarnAtMs = 0L;
        lastVoidWarnAtMs = 0L;
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

    // ── Тик: единственный источник состояния ─────────────────────────────────

    /**
     * Advances all overlay state by one client tick. Must be called even while a screen is open,
     * otherwise fireball and void warnings stop firing the moment the player opens chat.
     */
    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;

        if (scanState.active) {
            continueScanBeds(mc);
        } else if (++bedScanTick >= SCAN_EVERY) {
            bedScanTick = 0;
            startScanBeds(mc.player);
            continueScanBeds(mc);
        }

        if (++snapshotTick >= SNAPSHOT_INTERVAL_TICKS) {
            snapshotTick = 0;
            refreshSnapshot(mc, mc.player);
            refreshContextHelpers(mc, mc.player, System.currentTimeMillis());
        }
    }

    // ── Периодическое сканирование кроватей ──────────────────────────────────

    private static void startScanBeds(LocalPlayer player) {
        BlockPos center = player.blockPosition();
        scanState.center = center;
        scanState.minCX = (center.getX() >> 4) - SCAN_CHUNK_RADIUS;
        scanState.maxCX = (center.getX() >> 4) + SCAN_CHUNK_RADIUS;
        scanState.minCZ = (center.getZ() >> 4) - SCAN_CHUNK_RADIUS;
        scanState.maxCZ = (center.getZ() >> 4) + SCAN_CHUNK_RADIUS;
        scanState.minY = center.getY() - SCAN_Y;
        scanState.maxY = center.getY() + SCAN_Y;
        scanState.cx = scanState.minCX;
        scanState.cz = scanState.minCZ;
        scanState.active = true;
    }

    private static void continueScanBeds(Minecraft mc) {
        if (!scanState.active) return;

        int processed = 0;
        while (scanState.active && processed < SCAN_CHUNKS_PER_TICK) {
            scanChunkForBeds(mc, scanState.cx, scanState.cz);
            processed++;

            scanState.cz++;
            if (scanState.cz > scanState.maxCZ) {
                scanState.cz = scanState.minCZ;
                scanState.cx++;
                if (scanState.cx > scanState.maxCX) {
                    scanState.active = false;
                }
            }
        }

        if (!scanState.active) {
            finalizeScan(mc, scanState.center);
        }
    }

    private static void scanChunkForBeds(Minecraft mc, int chunkX, int chunkZ) {
        LevelChunk chunk = mc.level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) return;

        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < sections.length; i++) {
            int sectionMinY = chunk.getMinY() + (i << 4);
            if (sectionMinY + 15 < scanState.minY || sectionMinY > scanState.maxY) continue;

            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) continue;
            // Palette-level rejection: skips the 4096-block walk for every section without a bed.
            if (!section.maybeHas(IS_BED)) continue;

            for (int ly = 0; ly < 16; ly++) {
                int worldY = sectionMinY + ly;
                if (worldY < scanState.minY || worldY > scanState.maxY) continue;

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState bs = section.getBlockState(lx, ly, lz);
                        if (!(bs.getBlock() instanceof BedBlock bed)) continue;
                        if (bs.getValue(BedBlock.PART) != BedPart.HEAD) continue;

                        mpos.set((chunkX << 4) + lx, worldY, (chunkZ << 4) + lz);
                        recordBed(mc, mpos, bed);
                    }
                }
            }
        }
    }

    private static void recordBed(Minecraft mc, BlockPos.MutableBlockPos mpos, BedBlock bed) {
        BlockPos pos = mpos.immutable();
        BedInfo prev = bedDataByPos.get(pos);
        DefenseSample sample = calcDefense(mc, mpos);
        int def = sample.score();

        if (prev != null && prev.alive()) {
            if (!sample.complete()) {
                def = prev.defScore(); // keep last stable value on incomplete chunk sample
            } else {
                def = stabilizeDefense(prev.defScore(), def);
            }
        }
        // Only the by-position map is written during the pass. The colour index is rebuilt at the
        // end of the scan, where a conflict between same-coloured beds can be resolved properly.
        bedDataByPos.put(pos, new BedInfo(pos, bed.getColor(), true, def));
    }

    private static void finalizeScan(Minecraft mc, BlockPos center) {
        // Для ранее найденных кроватей, которые теперь в радиусе — проверяем, не снесли ли.
        for (Map.Entry<BlockPos, BedInfo> entry : bedDataByPos.entrySet()) {
            BedInfo info = entry.getValue();
            if (!info.alive()) continue;
            if (!inScanRange(center, info.pos())) continue;
            if (!mc.level.isLoaded(info.pos())) continue;

            BlockState bs = mc.level.getBlockState(info.pos());
            if (!(bs.getBlock() instanceof BedBlock)) {
                entry.setValue(new BedInfo(info.pos(), info.color(), false, 0));
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
            if (info.alive() == prev.alive()
                    && info.pos().distSqr(center) < prev.pos().distSqr(center)) {
                bedData.put(info.color(), info);
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
                // Forge's state-aware variant; the plain Block accessor is deprecated because it
                // ignores per-state and per-position modifiers.
                tierCounts[defenseTierByResistance(bs.getExplosionResistance(mc.level, pos, null))]++;
            }
        }

        // Not enough direct shell coverage => treat as no defense, avoid stale "WOL".
        if (shellSlots == 0) return new DefenseSample(0, true);
        if (occupiedSlots / (double) shellSlots < 0.60) return new DefenseSample(0, true);

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
        return Math.max(current, prev - 14);
    }

    private static boolean inScanRange(BlockPos center, BlockPos pos) {
        return Math.abs(pos.getX() - center.getX()) <= SCAN_CHUNK_RADIUS * 16
            && Math.abs(pos.getZ() - center.getZ()) <= SCAN_CHUNK_RADIUS * 16
            && Math.abs(pos.getY() - center.getY()) <= SCAN_Y;
    }

    // ── Построение снимка ────────────────────────────────────────────────────

    private static void refreshSnapshot(Minecraft mc, LocalPlayer self) {
        PlayerTeam myTeam = mc.level.getScoreboard().getPlayersTeam(self.getScoreboardName());

        List<AbstractClientPlayer> enemies   = new ArrayList<>();
        List<AbstractClientPlayer> teammates = new ArrayList<>();

        for (Player raw : mc.level.players()) {
            if (!(raw instanceof AbstractClientPlayer p)) continue;
            if (p == self || p.isSpectator() || !p.isAlive()) continue;
            PlayerTeam t = mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName());
            ((myTeam != null && myTeam.equals(t)) ? teammates : enemies).add(p);
        }

        // Solo modes: the scoreboard is the authority on whether the player has allies at all.
        // The previous head-count heuristic hid real teammates in four-team modes.
        if (myTeam == null || myTeam.getPlayers().size() <= 1) {
            teammates.clear();
        }

        // Stable order prevents row jumping/flicker when enemies start/stop drawing bow.
        enemies.sort(Comparator
            .comparingDouble(self::distanceTo)
            .thenComparing(p -> p.getUUID().toString()));
        teammates.sort(Comparator.comparingDouble(self::distanceTo));

        cachedEnemyViews = buildPlayerViews(mc, self, enemies);
        cachedTeamViews  = buildPlayerViews(mc, self, teammates);
        cachedTeamStats  = buildTeamStats(mc, self, enemies, teammates, myTeam);
        cachedRadarEnemies = enemies;
    }

    private static List<PlayerView> buildPlayerViews(Minecraft mc, LocalPlayer self,
                                                     List<AbstractClientPlayer> players) {
        List<PlayerView> rows = new ArrayList<>(players.size());
        long now = System.currentTimeMillis();
        for (AbstractClientPlayer p : players) {
            double dist  = self.distanceTo(p);
            float hp     = p.getHealth();
            float hpPct  = hp / Math.max(1f, p.getMaxHealth());
            double dy    = p.getY() - self.getY();
            double eta   = smoothEta(p.getUUID(), etaToContact(self, p, dist), now);
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
            double delta = Mth.clamp(etaRaw - prev, -ETA_MAX_STEP_PER_TICK, ETA_MAX_STEP_PER_TICK);
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
        return String.format(Locale.ROOT, "%.1fs", Math.round(etaSec * 5.0) / 5.0);
    }

    private static void purgeOldEta(long nowMs) {
        Iterator<Map.Entry<UUID, Long>> it = etaSeenAtMs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (nowMs - e.getValue() > 10_000L) {
                etaSmoothedByPlayer.remove(e.getKey());
                it.remove();
            }
        }
    }

    // ── Основной рендер ──────────────────────────────────────────────────────

    /** Draws the cached snapshot. Performs no game logic — see {@link #tick(Minecraft)}. */
    public static void render(GuiGraphics g, float partialTick) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (mc.options.hideGui) return;

        List<PlayerView> enemyViews = cachedEnemyViews;
        List<PlayerView> teamViews  = cachedTeamViews;
        List<TeamStat>   teamStats  = cachedTeamStats;

        PlayerView priorityTarget = pickPriorityTarget(enemyViews);
        boolean danger = priorityTarget != null
            && (priorityTarget.distM() < DANGER_M || priorityTarget.aiming() || priorityTarget.etaSec() <= 2.5);

        boolean showTable = !teamStats.isEmpty();
        List<MatchTracker.LogLine> matchLog = ScopeConfig.MATCH_LOG_ENABLED.get()
            ? MatchTracker.recentLines() : List.of();

        int rows = 1   // заголовок
            + (enemyViews.isEmpty() ? 0 : 1 + Math.min(enemyViews.size(), MAX_ENEMIES))
            + (teamViews.isEmpty()  ? 0 : 1 + Math.min(teamViews.size(),  MAX_TEAM))
            + (showTable ? 1 + teamStats.size() : 0)
            + (matchLog.isEmpty() ? 0 : 1 + matchLog.size());

        int panelX = 6;
        int panelY = 6;
        int panelH = rows * ROW_H + PAD * 2;

        // Keep panel readable without blocking too much screen.
        g.fill(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY + panelH + 1, 0x22000000);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0x11000000);

        int y = drawHeader(g, mc, enemyViews, panelX, panelY + PAD);

        // Враги
        if (!enemyViews.isEmpty()) {
            txt(g, mc, "ENEMIES (" + enemyViews.size() + ")", panelX + PAD, y, 0xFFFF5555);
            y += ROW_H;
            for (int i = 0; i < Math.min(enemyViews.size(), MAX_ENEMIES); i++) {
                PlayerView row = enemyViews.get(i);
                boolean emphasize = isProjectedPriorityThreat(row, priorityTarget);
                int rowShift = emphasize ? THREAT_ROW_SHIFT_X : 0;
                if (emphasize) drawThreatRowCard(g, panelX, y);
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
            y = drawTeamTable(g, mc, teamStats, panelX + PAD, y, PANEL_W - PAD * 2);
        }

        // Лента событий матча
        if (!matchLog.isEmpty()) {
            drawMatchLog(g, mc, matchLog, panelX + PAD, y);
        }

        drawRadar(g, mc, mc.player);

        // Центральные предупреждения — последними, поверх всего остального.
        if (danger) drawCenteredDanger(g, mc, priorityTarget);
        drawFireballCrosshairWarning(g, mc);
        drawSafeRetreatVector(g, mc);
    }

    private static int drawHeader(GuiGraphics g, Minecraft mc, List<PlayerView> enemyViews, int panelX, int y) {
        String title = "◉ BEDWARS";
        int titleX = panelX + PAD;
        txt(g, mc, title, titleX, y, 0xFFFFFFFF);

        String freshness = scanState.active ? "SCAN" : "LIVE";
        int freshnessColor = scanState.active ? 0xFF55FFFF : 0xFF55FF55;
        int rightEdgeX = panelX + PANEL_W - PAD;
        int gap = 4;
        int charW = Math.max(1, mc.font.width("m"));
        int freshnessX = titleX + mc.font.width(title) + 8;

        String hint = enemyViews.isEmpty() ? "" : enemyViews.size() + " hostile";

        // The right-hand hint may collide with the freshness label; shorten to keep one clean line.
        if (!hint.isEmpty()) {
            int hintX = rightEdgeX - mc.font.width(hint);
            int availFreshW = (hintX - gap) - freshnessX;
            if (availFreshW > 0 && mc.font.width(freshness) > availFreshW) {
                freshness = cap(freshness, Math.max(3, availFreshW / charW));
            }
            int availHintW = rightEdgeX - (freshnessX + mc.font.width(freshness) + gap);
            if (availHintW <= 0) {
                hint = "";
            } else if (mc.font.width(hint) > availHintW) {
                hint = cap(hint, Math.max(3, availHintW / charW));
            }
        }

        txt(g, mc, freshness, freshnessX, y, freshnessColor);
        if (!hint.isEmpty()) {
            txt(g, mc, hint, rightEdgeX - mc.font.width(hint), y, 0xFFFF5555);
        }
        return y + ROW_H;
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

    private static PlayerView pickPriorityTarget(List<PlayerView> enemies) {
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

    // ── Радар ────────────────────────────────────────────────────────────────

    /**
     * Circular compass radar in the top-right corner.
     *
     * <p>The disc rotates with the player's view, so "up" is always forward — that is what makes
     * a radar readable mid-fight. Compass letters ride the rim so absolute direction stays
     * available, and enemies beyond the current range are pinned to the rim rather than hidden:
     * knowing someone is closing from the north matters before they are in range.</p>
     */
    private static void drawRadar(GuiGraphics g, Minecraft mc, LocalPlayer self) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int r = RADAR_SIZE / 2;
        int cx = sw - RADAR_MARGIN - r;
        int cy = RADAR_MARGIN + r;
        int plotR = r - RADAR_RIM_PAD;

        drawDisc(g, cx, cy, r, RADAR_BG);
        drawDisc(g, cx, cy, plotR + 2, RADAR_BG_INNER);

        // Rings are derived from the current zoom, so they stay meaningful at any range.
        drawRing(g, cx, cy, plotR / 3, RADAR_RING);
        drawRing(g, cx, cy, plotR * 2 / 3, RADAR_RING);
        drawRing(g, cx, cy, plotR, RADAR_RIM);

        double[] basis = viewBasis(self);
        drawRadarConstructions(g, mc, self, basis, cx, cy, plotR);
        drawCompass(g, mc, basis, cx, cy, r - 6);
        int enemyMarks = drawRadarEnemies(g, self, basis, cx, cy, plotR);
        drawSelfMarker(g, cx, cy);

        String label = enemyMarks + "E · " + radarRangeBlocks + "m";
        txt(g, mc, label, cx - mc.font.width(label) / 2, cy + r + 3, 0xFFB9C7D2);
    }

    /** Filled circle, drawn as horizontal spans so it stays crisp at any GUI scale. */
    private static void drawDisc(GuiGraphics g, int cx, int cy, int r, int color) {
        if (r <= 0) return;
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            if (half <= 0) continue;
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** Midpoint circle — a continuous single-pixel ring. */
    private static void drawRing(GuiGraphics g, int cx, int cy, int rr, int color) {
        if (rr <= 1) return;
        int x = rr;
        int y = 0;
        int d = 1 - rr;
        while (x >= y) {
            plotCircle8(g, cx, cy, x, y, color);
            y++;
            if (d < 0) {
                d += 2 * y + 1;
            } else {
                x--;
                d += 2 * (y - x) + 1;
            }
        }
    }

    private static void plotCircle8(GuiGraphics g, int cx, int cy, int x, int y, int color) {
        pixel(g, cx + x, cy + y, color);
        pixel(g, cx + y, cy + x, color);
        pixel(g, cx - y, cy + x, color);
        pixel(g, cx - x, cy + y, color);
        pixel(g, cx - x, cy - y, color);
        pixel(g, cx - y, cy - x, color);
        pixel(g, cx + y, cy - x, color);
        pixel(g, cx + x, cy - y, color);
    }

    private static void pixel(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 1, y + 1, color);
    }

    private static void drawSelfMarker(GuiGraphics g, int cx, int cy) {
        g.fill(cx - 1, cy - 3, cx + 2, cy + 4, RADAR_SELF);
        g.fill(cx - 3, cy - 1, cx + 4, cy + 2, RADAR_SELF);
        g.fill(cx, cy, cx + 1, cy + 1, 0xFF0C0F14);
    }

    private static void drawCompass(GuiGraphics g, Minecraft mc, double[] basis, int cx, int cy, int radius) {
        // North is -Z in world space.
        drawCompassLetter(g, mc, basis, cx, cy, radius, 0.0, -1.0, "N", RADAR_COMPASS_N);
        drawCompassLetter(g, mc, basis, cx, cy, radius, 1.0, 0.0, "E", RADAR_COMPASS);
        drawCompassLetter(g, mc, basis, cx, cy, radius, 0.0, 1.0, "S", RADAR_COMPASS);
        drawCompassLetter(g, mc, basis, cx, cy, radius, -1.0, 0.0, "W", RADAR_COMPASS);
    }

    private static void drawCompassLetter(GuiGraphics g, Minecraft mc, double[] basis,
                                          int cx, int cy, int radius,
                                          double worldDx, double worldDz, String letter, int color) {
        int px = cx + (int) Math.round(localSide(basis, worldDx, worldDz) * radius);
        int py = cy - (int) Math.round(localFront(basis, worldDx, worldDz) * radius);
        txt(g, mc, letter, px - mc.font.width(letter) / 2, py - 4, color);
    }

    /**
     * View basis for radar rotation, as {@code [forwardX, forwardZ]}. The right vector is
     * {@code (-forwardZ, forwardX)}, so one pair of numbers describes the whole rotation.
     *
     * <p>Computed once per radar frame: a per-marker version allocated three {@link Vec3} for
     * each of up to 220 geometry dots plus every enemy.</p>
     */
    private static double[] viewBasis(LocalPlayer self) {
        Vec3 look = self.getLookAngle();
        double len = Math.hypot(look.x, look.z);
        if (len < 1.0e-6) return new double[] { 0.0, 1.0 };
        return new double[] { look.x / len, look.z / len };
    }

    /** Side (right-positive) component of a world XZ offset in view-local axes. */
    private static double localSide(double[] basis, double dx, double dz) {
        return dx * -basis[1] + dz * basis[0];
    }

    /** Front (forward-positive) component of a world XZ offset in view-local axes. */
    private static double localFront(double[] basis, double dx, double dz) {
        return dx * basis[0] + dz * basis[1];
    }

    private static int drawRadarEnemies(GuiGraphics g, LocalPlayer self, double[] basis,
                                        int cx, int cy, int plotR) {
        double scale = plotR / (double) radarRangeBlocks;
        int inRange = 0;

        for (AbstractClientPlayer enemy : cachedRadarEnemies) {
            if (!enemy.isAlive() || enemy.isSpectator()) continue;

            double dx = enemy.getX() - self.getX();
            double dz = enemy.getZ() - self.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.01) continue;

            double side = localSide(basis, dx, dz);
            double front = localFront(basis, dx, dz);
            double len = Math.hypot(side, front);
            if (len < 1.0e-6) continue;

            boolean outside = dist > radarRangeBlocks;
            // Out-of-range contacts are pinned to the rim instead of vanishing.
            double drawDist = outside ? plotR - 2 : Math.min(plotR - 3, dist * scale);
            double px = cx + side / len * drawDist;
            double py = cy - front / len * drawDist;

            int color = outside ? RADAR_ENEMY_FAR : (isAiming(enemy) ? RADAR_ENEMY_AIM : RADAR_ENEMY);
            if (!outside) inRange++;

            double vx = enemy.getX() - enemy.xo;
            double vz = enemy.getZ() - enemy.zo;
            double vSide = localSide(basis, vx, vz);
            double vFront = localFront(basis, vx, vz);
            double vLen = Math.hypot(vSide, vFront);
            if (!outside && vLen > 0.012) {
                drawEnemyArrow(g, px, py, vSide / vLen, -vFront / vLen, color);
            } else {
                drawEnemyDot(g, px, py, color);
            }
        }
        return inRange;
    }

    /** Movement-oriented arrowhead: heading is readable without a second marker next to the dot. */
    private static void drawEnemyArrow(GuiGraphics g, double x, double y, double ux, double uy, int color) {
        double tipLen = 4.2;
        double backLen = 2.6;
        double halfWidth = 2.6;
        // Perpendicular to the heading.
        double nx = -uy;
        double ny = ux;

        fillTriangle(g,
            x + ux * tipLen,                   y + uy * tipLen,
            x - ux * backLen + nx * halfWidth, y - uy * backLen + ny * halfWidth,
            x - ux * backLen - nx * halfWidth, y - uy * backLen - ny * halfWidth,
            color);
    }

    private static void drawEnemyDot(GuiGraphics g, double x, double y, int color) {
        int px = (int) Math.round(x);
        int py = (int) Math.round(y);
        g.fill(px - 1, py - 2, px + 2, py + 3, color);
        g.fill(px - 2, py - 1, px + 3, py + 2, color);
    }

    /** Scanline triangle fill; {@link GuiGraphics} only exposes axis-aligned rectangles. */
    private static void fillTriangle(GuiGraphics g,
                                     double x0, double y0, double x1, double y1, double x2, double y2,
                                     int color) {
        int minY = (int) Math.floor(Math.min(y0, Math.min(y1, y2)));
        int maxY = (int) Math.ceil(Math.max(y0, Math.max(y1, y2)));
        double[][] edges = { { x0, y0, x1, y1 }, { x1, y1, x2, y2 }, { x2, y2, x0, y0 } };

        for (int py = minY; py <= maxY; py++) {
            double rowY = py + 0.5;
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;

            for (double[] e : edges) {
                double ay = e[1];
                double by = e[3];
                if (rowY < Math.min(ay, by) || rowY >= Math.max(ay, by)) continue;
                double xAt = e[0] + (e[2] - e[0]) * ((rowY - ay) / (by - ay));
                lo = Math.min(lo, xAt);
                hi = Math.max(hi, xAt);
            }
            if (lo > hi) continue;

            int xStart = (int) Math.floor(lo);
            int xEnd = (int) Math.ceil(hi);
            if (xEnd > xStart) g.fill(xStart, py, xEnd, py + 1, color);
        }
    }

    private static void drawRadarConstructions(GuiGraphics g, Minecraft mc, LocalPlayer self,
                                               double[] basis, int cx, int cy, int plotR) {
        if (mc.level == null) return;
        ensureRadarConstructionsCache(mc, self);

        double scale = plotR / (double) radarRangeBlocks;
        int limitSq = (plotR - 1) * (plotR - 1);

        for (RadarConstructionSample sample : cachedRadarConstructions) {
            int rx = (int) Math.round(localSide(basis, sample.dx(), sample.dz()) * scale);
            int ry = (int) Math.round(-localFront(basis, sample.dx(), sample.dz()) * scale);
            // Hard clip: nothing may spill outside the disc.
            if (rx * rx + ry * ry > limitSq) continue;
            pixel(g, cx + rx, cy + ry, sample.color());
        }
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
        if (!rangeChanged && !moved && !stale && !cachedRadarConstructions.isEmpty()) return;

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
                if (dx * dx + dz * dz > radarRangeBlocks * radarRangeBlocks) continue;
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

                next.add(new RadarConstructionSample(dx, dz, radarConstructionColor(mc, x, bestY, z, probe)));
                if (next.size() >= RADAR_MAX_CONSTRUCTION_MARKS) {
                    cachedRadarConstructions = next;
                    return;
                }
            }
        }
        cachedRadarConstructions = next;
    }

    /**
     * Categorises terrain so the radar reads as a map rather than noise. Alphas stay low on
     * purpose: geometry is context, enemies are the subject.
     */
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
        if (hasAbove && hasAbove2) return 0x9959C8E0;
        // Walls/fortifications: dense side neighbors or 2-block height.
        if (hasAbove || sideSolid >= 3) return 0x99E09A55;
        // Bridges / thin paths: exposed bottom or sparse neighbors.
        if (airBelow || sideSolid <= 1) return 0x8CBFBFBF;
        return 0x7A85B885;
    }

    private static boolean isSolidAt(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos probe) {
        probe.set(x, y, z);
        if (!mc.level.isLoaded(probe)) return false;
        return !mc.level.getBlockState(probe).isAir();
    }

    // ── Контекстные помощники ────────────────────────────────────────────────

    private static void refreshContextHelpers(Minecraft mc, LocalPlayer self, long nowMs) {
        if (ScopeConfig.FIREBALL_THREAT_ENABLED.get()) {
            evaluateFireballThreat(mc, self);
            if (fireballAlertDanger) playWarningSound(mc, true, nowMs);
        } else {
            fireballAlertDanger = false;
        }

        if (ScopeConfig.BRIDGE_HELPER_ENABLED.get()) {
            evaluateBridgeHelper(mc, self);
            if (bridgeAlertDanger) playWarningSound(mc, false, nowMs);
        } else {
            bridgeAlertDanger = false;
        }

        evaluateSafeRetreat(mc, self);
    }

    private static void evaluateFireballThreat(Minecraft mc, LocalPlayer self) {
        fireballAlertDanger = false;
        AABB box = self.getBoundingBox().inflate(36.0, 18.0, 36.0);
        double bestEta = Double.POSITIVE_INFINITY;
        double bestDist = Double.POSITIVE_INFINITY;

        for (Entity e : mc.level.getEntities(self, box)) {
            Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (entityId == null || !entityId.getPath().contains("fireball")) continue;

            Vec3 toPlayer = self.position().subtract(e.position());
            double dist = toPlayer.length();
            if (dist < 0.001) continue;

            double closingPerTick = toPlayer.normalize().dot(e.getDeltaMovement());
            if (closingPerTick <= 0.015) continue;

            double etaSec = (dist / closingPerTick) / 20.0;
            if (etaSec < bestEta) {
                bestEta = etaSec;
                bestDist = dist;
            }
        }
        if (Double.isFinite(bestEta)) {
            fireballAlertDanger = bestEta <= 2.4 || bestDist <= 18.0;
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
        if (moving) {
            Vec3 move = self.getDeltaMovement();
            for (int i = 1; i <= predictSteps; i++) {
                int px = Mth.floor(self.getX() + move.x * i * 2.3);
                int pz = Mth.floor(self.getZ() + move.z * i * 2.3);
                if (isVoidColumn(mc, new BlockPos(px, base.getY(), pz), 12)) {
                    predictedVoidDanger = true;
                    break;
                }
            }
        }

        bridgeAlertDanger = moving && (overVoid || predictedVoidDanger)
            && (speed > 0.030 || self.fallDistance > 0.8f || edgeRisk || predictedVoidDanger);
    }

    private static boolean isVoidColumn(Minecraft mc, BlockPos base, int depth) {
        for (int i = 1; i <= depth; i++) {
            BlockPos p = base.below(i);
            if (mc.level.isLoaded(p) && !mc.level.getBlockState(p).isAir()) return false;
        }
        return true;
    }

    private static boolean isAirAt(Minecraft mc, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!mc.level.isLoaded(pos)) return true;
        return mc.level.getBlockState(pos).isAir();
    }

    private static void drawFireballCrosshairWarning(GuiGraphics g, Minecraft mc) {
        if (!ScopeConfig.FIREBALL_THREAT_ENABLED.get() || !fireballAlertDanger) return;
        if (((System.currentTimeMillis() / 220L) % 2L) == 0L) return; // blink

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
        if (ScopeConfig.FIREBALL_THREAT_ENABLED.get() && fireballAlertDanger) y += 16;
        y = Math.min(y, sh - 54);

        boolean pulse = ((System.currentTimeMillis() / 180L) % 2L) == 0L;
        int w = mc.font.width(retreatAlertText);
        int x = (sw - w) / 2;

        g.fill(x - 12, y - 4, x + w + 12, y + 11, pulse ? 0xAA2F0000 : 0xAA4A0000);
        g.fill(x - 10, y - 2, x + w + 10, y + 10, 0x66200000);
        txt(g, mc, retreatAlertText, x, y, pulse ? 0xFFFF8855 : retreatAlertColor);
    }

    private static void playWarningSound(Minecraft mc, boolean fireball, long nowMs) {
        if (mc.player == null) return;
        if (!ScopeConfig.WARNING_SOUND_ENABLED.get()) return;

        if (fireball) {
            if (!ScopeConfig.FIREBALL_WARNING_SOUND.get()) return;
            if (nowMs - lastFireballWarnAtMs < FIREBALL_SOUND_COOLDOWN_MS) return;
            lastFireballWarnAtMs = nowMs;
            float vol = ScopeConfig.FIREBALL_WARNING_VOLUME.get() / 100.0f;
            if (vol > 0f) mc.player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), vol, 1.35f);
        } else {
            if (!ScopeConfig.VOID_WARNING_SOUND.get()) return;
            if (nowMs - lastVoidWarnAtMs < VOID_SOUND_COOLDOWN_MS) return;
            lastVoidWarnAtMs = nowMs;
            float vol = ScopeConfig.VOID_WARNING_VOLUME.get() / 100.0f;
            if (vol > 0f) mc.player.playSound(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), vol, 1.0f);
        }
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
            enemyHpSum += enemy.getHealth() + enemy.getAbsorptionAmount();

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
        boolean lowHp = selfHp <= 10.0;
        boolean hpDisadvantage = enemyToSelfHpRatio >= 1.55 && effectiveEnemies >= 2;
        boolean heavyPressure = pressure >= 0.42 && effectiveEnemies >= 2;
        if (!(outnumbered || lowHp || hpDisadvantage || heavyPressure)) return;

        Vec3 retreatDir = chooseSafeRetreatDirection(mc, self, pressureVec, threats);
        if (retreatDir.lengthSqr() < 1.0e-6) return;

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
        retreatAlertText = "RETREAT " + directionArrowFromVector(self, retreatDir) + "  " + reason;
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
        if (fallback.lengthSqr() < 1.0e-6) fallback = new Vec3(0.0, 0.0, 1.0);
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
        int baseY = self.blockPosition().getY();
        BlockPos near = new BlockPos(
            Mth.floor(self.getX() + dir.x * RETREAT_SAMPLE_DISTANCE), baseY,
            Mth.floor(self.getZ() + dir.z * RETREAT_SAMPLE_DISTANCE));
        BlockPos far = new BlockPos(
            Mth.floor(self.getX() + dir.x * RETREAT_SAMPLE_DISTANCE_FAR), baseY,
            Mth.floor(self.getZ() + dir.z * RETREAT_SAMPLE_DISTANCE_FAR));

        double safety = 0.0;
        if (!isVoidColumn(mc, near, 12)) safety += 1.4;
        if (!isVoidColumn(mc, far, 12)) safety += 0.9;
        if (!isAirAt(mc, near.getX(), near.getY() - 1, near.getZ())) safety += 0.6;

        for (AbstractClientPlayer enemy : threats) {
            Vec3 fromEnemy = self.position().subtract(enemy.position());
            Vec3 away = new Vec3(fromEnemy.x, 0.0, fromEnemy.z);
            if (away.lengthSqr() < 1.0e-6) continue;
            away = away.normalize();
            // dot > 0 means the direction moves away from this enemy; nearer enemies weigh more.
            safety += dir.dot(away) * (10.0 / Math.max(1.0, self.distanceTo(enemy)));
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
        return arrowFor(forward.dot(to), right.dot(to));
    }

    /** Maps a (front, side) unit projection onto one of eight arrow glyphs. */
    private static String arrowFor(double front, double side) {
        if (front >= 0.9239) return "↑";   // <= 22.5°
        if (front <= -0.9239) return "↓";  // >= 157.5°
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
        double rvx = (p.getX() - p.xo) - (self.getX() - self.xo);
        double rvz = (p.getZ() - p.zo) - (self.getZ() - self.zo);
        double closingPerTick = rvx * (rx / rlen) + rvz * (rz / rlen);
        if (closingPerTick <= 0.018) return Double.POSITIVE_INFINITY;

        return Mth.clamp((rlen / closingPerTick) / 20.0, 0.0, 20.0);
    }

    private static String moveDir(AbstractClientPlayer p) {
        double vx = p.getX() - p.xo;
        double vz = p.getZ() - p.zo;
        if (vx * vx + vz * vz < 0.0009) return "•";

        double yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-vx, vz)));
        double abs = Math.abs(yaw);
        if (abs <= 22.5) return "↑";
        if (abs >= 157.5) return "↓";
        return yaw < 0
            ? (abs <= 67.5 ? "↗" : abs <= 112.5 ? "→" : "↘")
            : (abs <= 67.5 ? "↖" : abs <= 112.5 ? "←" : "↙");
    }

    // ── Таблица командных сил ─────────────────────────────────────────────────

    private static int drawTeamTable(GuiGraphics g, Minecraft mc, List<TeamStat> stats, int x, int y, int w) {
        txt(g, mc, "TEAM POWER", x, y, 0xFFFFAA00);
        y += ROW_H;

        int maxScore = stats.stream().mapToInt(TeamStat::score).max().orElse(1);
        TeamStat weakest = stats.stream().filter(s -> !s.isMyTeam())
            .min(Comparator.comparingInt(TeamStat::score)).orElse(null);
        TeamStat strongest = stats.stream().filter(s -> !s.isMyTeam())
            .max(Comparator.comparingInt(TeamStat::score)).orElse(null);

        for (TeamStat ts : stats) {
            // Цветной квадрат команды
            g.fill(x, y + 2, x + 6, y + 7, 0xFF000000);
            g.fill(x + 1, y + 3, x + 5, y + 7, 0xFF000000 | (ts.nameColor() & 0x00FFFFFF));

            txt(g, mc, ts.name(), x + 8, y, ts.nameColor());
            txt(g, mc, ts.playerCount() + "p", x + 52, y, 0xFFAAAAAA);

            // Полоска силы
            int bx = x + 68;
            int by = y + 2;
            int bw = 30;
            int bh = 5;
            int bfill = maxScore > 0 ? (int) ((long) ts.score() * bw / maxScore) : 0;
            int barCol = ts.isMyTeam() ? 0xFF55AA55
                : (ts == strongest ? 0xFFFF5555
                : (ts == weakest ? 0xFF55FF55 : 0xFFFFAA00));
            g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, 0xFF000000);
            g.fill(bx, by, bx + bw, by + bh, 0x33FFFFFF);
            if (bfill > 0) g.fill(bx, by, bx + bfill, by + bh, barCol);

            drawBedStatus(g, mc, ts, x + 100, y);

            String tag;
            int tagColor;
            if (ts.isMyTeam()) {
                tag = "(you)"; tagColor = 0xFF55FF55;
            } else if (ts == weakest && ts != strongest) {
                tag = "→HUNT"; tagColor = 0xFF55FF55;
            } else if (ts == strongest) {
                tag = "THRT"; tagColor = 0xFFFF5555;
            } else {
                tag = ""; tagColor = 0;
            }
            if (!tag.isEmpty()) txt(g, mc, tag, x + w - mc.font.width(tag), y, tagColor);

            y += ROW_H;
        }
        return y;
    }

    /** Символ (✔/✗/?) + код защиты (OBS/END/TER/WOL/---) + оценка сложности пробития. */
    private static void drawBedStatus(GuiGraphics g, Minecraft mc, TeamStat ts, int x, int y) {
        BedInfo bed = ts.bed();
        String sym;
        int symCol;
        String defTxt;
        int defCol;

        if (bed == null) {
            sym = "?";      symCol = 0xFF777777;
            defTxt = "UNK"; defCol = 0xFF777777;
        } else if (bed.alive()) {
            sym = "✔"; symCol = 0xFF55FF55;
            defTxt = defCode(bed.defScore());
            defCol = defColor(bed.defScore());
        } else {
            sym = "✗"; symCol = 0xFFFF5555;
            defTxt = "";    defCol = 0;
        }

        txt(g, mc, sym, x, y, symCol);
        if (!defTxt.isEmpty()) txt(g, mc, defTxt, x + 10, y, defCol);
        if (bed != null && bed.alive()) {
            String chance = breachChanceCode(bed.defScore(), ts.playerCount());
            txt(g, mc, chance, x + 40, y, breachChanceColor(chance));
        }
    }

    // ── Лента событий матча ───────────────────────────────────────────────────

    private static void drawMatchLog(GuiGraphics g, Minecraft mc, List<MatchTracker.LogLine> lines, int x, int y) {
        txt(g, mc, "MATCH LOG", x, y, 0xFF88AACC);
        y += ROW_H;
        for (MatchTracker.LogLine line : lines) {
            txt(g, mc, line.time(), x, y, 0xFF7A8894);
            txt(g, mc, line.text(), x + 32, y, line.argb());
            y += ROW_H;
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
        if (myTeam != null) {
            aliveByTeam.computeIfAbsent(myTeam, k -> new ArrayList<>()).add(self);
        }

        List<TeamStat> stats = new ArrayList<>();
        Set<PlayerTeam> addedEnemyTeams = new LinkedHashSet<>();

        // Scoreboard teams are the source of truth, so a team doesn't vanish from the table while
        // all of its members are dead or respawning.
        for (PlayerTeam t : mc.level.getScoreboard().getPlayerTeams()) {
            if (t == null) continue;
            if (myTeam != null && myTeam.equals(t)) continue;

            List<AbstractClientPlayer> pl = aliveByTeam.getOrDefault(t, List.of());
            BedInfo bed = resolveBed(t, false);
            if (isLikelyInternalTeamName(t) && bed == null) continue;
            // Skip "noise" scoreboard teams: keep only active teams or ones with known bed state.
            if (pl.isEmpty() && bed == null) continue;

            stats.add(new TeamStat(t, cap(t.getName(), 7), TeamColors.displayArgb(t),
                pl.size(), computeScore(pl), bestArmorIn(pl), false, bed));
            addedEnemyTeams.add(t);
        }

        // Keep alive enemy teams that may be absent from the scoreboard team list.
        for (Map.Entry<PlayerTeam, List<AbstractClientPlayer>> e : aliveByTeam.entrySet()) {
            PlayerTeam t = e.getKey();
            if (t == null || addedEnemyTeams.contains(t)) continue;
            if (myTeam != null && myTeam.equals(t)) continue;

            BedInfo bed = resolveBed(t, false);
            if (isLikelyInternalTeamName(t) && bed == null) continue;
            stats.add(new TeamStat(t, cap(t.getName(), 7), TeamColors.displayArgb(t),
                e.getValue().size(), computeScore(e.getValue()), bestArmorIn(e.getValue()), false, bed));
        }

        if (!teammates.isEmpty() || myTeam != null) {
            List<AbstractClientPlayer> mine = new ArrayList<>(teammates);
            mine.add(self);
            stats.add(new TeamStat(myTeam, myTeam != null ? cap(myTeam.getName(), 7) : "YOU",
                TeamColors.displayArgb(myTeam), mine.size(), computeScore(mine), bestArmorIn(mine),
                true, resolveBed(myTeam, true)));
        }

        stats.sort(Comparator.comparingInt(TeamStat::score).reversed());
        return stats;
    }

    /**
     * Bed state for a team, combining the block scanner with chat announcements.
     *
     * <p>Chat wins when it reports a destroyed bed: it is authoritative at any distance, while
     * the scanner only ever sees the area around the player. Chat can never mark a bed as alive,
     * so this can only downgrade a status — it cannot invent one.</p>
     */
    private static BedInfo resolveBed(PlayerTeam team, boolean strictInference) {
        BedInfo scanned = bedForTeam(team, strictInference);
        if (!ScopeConfig.MATCH_LOG_ENABLED.get()) return scanned;

        DyeColor dye = TeamColors.ofTeam(team);
        if (dye == null || MatchTracker.bedState(dye) != MatchTracker.BedState.DESTROYED) return scanned;

        return scanned != null
            ? new BedInfo(scanned.pos(), scanned.color(), false, 0)
            : new BedInfo(BlockPos.ZERO, dye, false, 0);
    }

    private static boolean isLikelyInternalTeamName(PlayerTeam team) {
        String name = team.getName();
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        // Common server-side technical team names that should not be shown in BedWars table.
        return n.matches("sb-\\d+") || n.matches("team\\d+") || n.matches("line-\\d+");
    }

    private static BedInfo bedForTeam(PlayerTeam team, boolean strictInference) {
        Minecraft mc = Minecraft.getInstance();
        if (team == null || mc.level == null) return null;

        BedInfo inferred = inferBedByTeamPlayers(mc, team, strictInference);

        // Primary mapping: team color -> bed dye.
        DyeColor dye = TeamColors.ofTeam(team);
        if (dye != null) {
            BedInfo direct = bedData.get(dye);
            if (direct != null) {
                // If several beds share this dye, position-based inference is more trustworthy.
                return (inferred != null && countBedsByColor(dye) > 1) ? inferred : direct;
            }
        }

        // Some servers use nearby palette variants for team colors.
        for (DyeColor alt : TeamColors.alternatives(team)) {
            BedInfo byAlt = bedData.get(alt);
            if (byAlt != null) {
                return (inferred != null && countBedsByColor(alt) > 1) ? inferred : byAlt;
            }
        }

        // Fallback for servers with custom scoreboard formatting:
        // pick the closest known bed to the members of this team.
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
            if (team.equals(mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName()))) {
                teamPlayers.add(p);
            }
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
        // Best match should be meaningfully better than the next candidate.
        if (strictInference && secondBestAvg < Double.MAX_VALUE && bestAvg > secondBestAvg * 0.80) return null;
        return best;
    }

    private static int computeScore(List<AbstractClientPlayer> players) {
        int total = 0;
        for (AbstractClientPlayer p : players) {
            float hpPct = p.getHealth() / Math.max(1f, p.getMaxHealth());
            total += (int) (hpPct * 100) + armorScoreOf(armorChar(p)) * 15;
        }
        return total;
    }

    private static String bestArmorIn(List<AbstractClientPlayer> players) {
        int best = 0;
        String bestTier = "";
        for (AbstractClientPlayer p : players) {
            String a = armorChar(p);
            int s = armorScoreOf(a);
            if (s > best) {
                best = s;
                bestTier = a;
            }
        }
        return bestTier;
    }

    // ── Защита кровати ────────────────────────────────────────────────────────

    private static String defCode(int score) {
        if (score == 0)  return "---";
        if (score < 25)  return "WOL";   // вул/дерево (слабая)
        if (score < 70)  return "TER";   // терракота/бетон (средняя)
        if (score < 140) return "END";   // энд-камень (сильная)
        return "OBS";                    // обсидиан (максимальная)
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

        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        return arrowFor(forward.dot(to), right.dot(to));
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
            if (armorScoreOf(tier) > armorScoreOf(best)) best = tier;
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

    private static int nameColor(Minecraft mc, AbstractClientPlayer p) {
        PlayerTeam team = mc.level.getScoreboard().getPlayersTeam(p.getScoreboardName());
        if (team != null) {
            ChatFormatting fmt = team.getColor();
            if (fmt != null && fmt.getColor() != null) return 0xFF000000 | fmt.getColor();
        }
        Style style = p.getDisplayName().getStyle();
        TextColor tc = style.getColor();
        return tc != null ? 0xFF000000 | tc.getValue() : 0xFFFFFFFF;
    }

    private static String cap(String s, int maxChars) {
        if (maxChars <= 1) return s.isEmpty() ? s : "…";
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "…";
    }
}
