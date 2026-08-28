package ru.pleeey.bwsutil.client.overlay;

import ru.pleeey.bwsutil.client.aim.AimSolver;
import ru.pleeey.bwsutil.client.aim.TargetTracker;
import ru.pleeey.bwsutil.config.ScopeConfig;
import ru.pleeey.bwsutil.physics.ArrowPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.Optional;

public final class ScopeOverlay {

    // ── Визуальные константы ────────────────────────────────────────────────

    private static final int GAP         = 5;
    private static final int ARM_LEN     = 18;
    private static final int STADIA_HALF = 12;
    private static final int STADIA_TICK = 6;

    private static final double MAX_RANGE       = 512.0;
    /**
     * Entity lookup range for the rangefinder. Deliberately much shorter than {@link #MAX_RANGE}:
     * the block raycast is cheap at any distance, but an entity AABB half a map long is not.
     */
    private static final double ENTITY_SCAN_RANGE = 128.0;
    private static final double AUTO_CONE_RAD   = Math.toRadians(15.0); // 15° поиск цели

    // ── Авто-доводка (AUTO) ─────────────────────────────────────────────────

    /**
     * Максимальный доворот за тик при 100% силе. Ограничение работает поверх
     * пропорционального шага: без него дальняя цель на первом тике дёргала бы камеру рывком.
     */
    private static final double AUTO_AIM_MAX_STEP_DEG = 12.0;
    /**
     * Доводка не работает, если цель ушла по горизонтали дальше этого угла: захват держится,
     * пока натянут лук, и без этого предела разворот цели за спину раскрутил бы камеру.
     * Порог только по yaw — вертикаль законно уходит далеко из-за угла возвышения.
     */
    private static final double AUTO_AIM_MAX_YAW_DEG = 60.0;
    /** Ниже этого рассогласования доводка не трогает камеру — иначе она мелко дрожит. */
    private static final double AUTO_AIM_DEADZONE_DEG = 0.05;
    /**
     * Доля, на которую сглаженное решение подтягивается к сырому за тик.
     *
     * <p>Фильтр стоит между решателем и контроллером камеры: даже с усреднённой скоростью цели
     * решение слегка дышит, и без фильтра это дыхание уходит прямо в поворот камеры.</p>
     */
    private static final double SOLUTION_SMOOTHING = 0.35;
    /** За столько тиков сила доводки нарастает от нуля — чтобы не дёргать камеру при захвате. */
    private static final int AUTO_AIM_EASE_TICKS = 4;
    /**
     * Сколько тиков держаться за последнее решение, по которому попадание было возможно.
     *
     * <p>Цель нырнула за укрытие — прицел остаётся там, где по ней ещё можно было попасть, и
     * подхватывает её без рывка, когда она снова высунется. Наводиться в стену смысла нет.</p>
     */
    private static final int SOLUTION_HOLD_TICKS = 10;

    /**
     * Захват сбрасывается, если цель ушла дальше этого угла от взгляда. Шире конуса захвата:
     * цель имеет право двигаться, пока игрок держит натяжение, но не имеет права оказаться
     * за спиной.
     */
    private static final double RETARGET_CONE_DEG = 45.0;

    // ── Состояние прицела ────────────────────────────────────────────────────

    public enum ScopeMode { MANUAL, AUTO }

    private static boolean     enabled     = true;
    private static ScopeMode   currentMode = ScopeMode.MANUAL;

    /** Захваченная цель в AUTO-режиме. Управляется через tick(), не через render(). */
    private static WeakReference<LivingEntity> lockedTarget = null;

    /** Дальномер считается раз в тик; рендер только читает результат. */
    private static double cachedMeasuredDistance = -1;

    /** Доводка реально управляла камерой в последнем тике — для индикатора режима. */
    private static boolean autoAimEngaged = false;

    /** Решение по цели за последний тик; рендер только рисует его. */
    private static AimSolver.Solution cachedSolution = null;

    /** Усреднение скорости захваченной цели. */
    private static final TargetTracker targetTracker = new TargetTracker();

    /** Последнее решение, по которому попадание было возможно — по нему и ведётся камера. */
    private static AimSolver.Solution steerSolution = null;
    private static int steerHoldTicks = 0;

    /** Точка прицеливания прошлого тика, чтобы выбор не прыгал по телу цели. */
    private static int aimIndex = AimSolver.NO_CANDIDATE;

    // Фильтр решения и плавный вход.
    private static double smoothedYaw = 0.0;
    private static double smoothedPitch = 0.0;
    private static boolean smoothingPrimed = false;
    private static int engageTicks = 0;

    /** Сбрасывает состояние при выходе с сервера / выгрузке мира. */
    public static void resetState() {
        lockedTarget = null;
        cachedMeasuredDistance = -1;
        autoAimEngaged = false;
        cachedSolution = null;
        steerSolution = null;
        targetTracker.reset();
        resetAimFilter();
    }

    /** Сбрасывает фильтр, разгон и выбранную точку прицеливания. */
    private static void resetAimFilter() {
        aimIndex = AimSolver.NO_CANDIDATE;
        smoothingPrimed = false;
        engageTicks = 0;
        steerHoldTicks = 0;
    }

    public static void toggleEnabled() { enabled = !enabled; }
    public static boolean isEnabled()  { return enabled; }

    public static void toggleMode() {
        currentMode = (currentMode == ScopeMode.MANUAL) ? ScopeMode.AUTO : ScopeMode.MANUAL;
    }

    public static ScopeMode getMode() { return currentMode; }

    public static boolean isScopeInputActive(Minecraft mc) {
        if (!enabled || mc == null || mc.player == null) return false;
        LocalPlayer player = mc.player;
        ItemStack heldMain = player.getMainHandItem();
        ItemStack heldOff  = player.getOffhandItem();
        boolean isDrawing = player.isUsingItem() && (player.getUseItem().getItem() instanceof BowItem);
        boolean isHolding = (heldMain.getItem() instanceof BowItem) || (heldOff.getItem() instanceof BowItem);
        if (ScopeConfig.SHOW_ONLY_WHILE_DRAWING.get()) {
            return isDrawing;
        }
        return isDrawing || isHolding;
    }

    /**
     * Вызывается ровно один раз за игровой тик из ClientGameEvents.
     *
     * <p>Здесь же строится решение по цели: симуляция полёта стрелы по блокам слишком дорога
     * для кадра, поэтому рендер рисует готовый снимок {@link #cachedSolution}.</p>
     */
    public static void tick(Minecraft mc, LocalPlayer player) {
        if (!enabled || mc.level == null) return;

        // Rangefinder is a raycast plus an entity sweep — once per tick is plenty, and it keeps
        // the cost off the render thread's per-frame path.
        cachedMeasuredDistance =
            (mc.screen == null && currentMode == ScopeMode.MANUAL
                && ScopeConfig.SHOW_RANGEFINDER.get() && isScopeInputActive(mc))
                ? measureDistance(mc, player)
                : -1;

        boolean isDrawing = player.isUsingItem()
            && (player.getUseItem().getItem() instanceof BowItem);

        autoAimEngaged = false;

        if (currentMode != ScopeMode.AUTO || !isDrawing) {
            // Выстрел произведён, натяжение отменено или режим сменился — сбрасываем захват.
            lockedTarget = null;
            cachedSolution = null;
            steerSolution = null;
            resetAimFilter();
            return;
        }

        LivingEntity target = acquireTarget(mc, player);
        if (target == null) {
            cachedSolution = null;
            steerSolution = null;
            resetAimFilter();
            return;
        }

        targetTracker.observe(target);

        float charge = BowItem.getPowerForTime(player.getTicksUsingItem());
        cachedSolution = AimSolver.solve(player, target, charge, targetTracker, aimIndex);
        if (cachedSolution == null) {
            resetAimFilter();
            return;
        }
        aimIndex = cachedSolution.aimIndex();
        updateSteerSolution(target);

        // Доводка включается только на полном натяжении: до этого момента скорость стрелы
        // растёт каждый тик, решение вместе с ней уползает, и камера ехала бы за целью,
        // которая на самом деле стоит на месте.
        if (mc.screen == null && ScopeConfig.AUTO_AIM_ENABLED.get() && isFullyDrawn(player)) {
            applyAutoAim(player, steerSolution);
        } else {
            engageTicks = 0;
        }
    }

    /**
     * Обновляет решение, по которому ведётся камера.
     *
     * <p>Перекрытая цель не отбрасывает прицел в стену: удерживается последнее решение с
     * попаданием, и только когда оно устареет, доводка отпускает камеру.</p>
     */
    private static void updateSteerSolution(LivingEntity target) {
        if (cachedSolution.clear()) {
            steerSolution = cachedSolution;
            steerHoldTicks = 0;
            return;
        }
        if (steerSolution != null && steerSolution.target() == target
            && steerHoldTicks < SOLUTION_HOLD_TICKS) {
            steerHoldTicks++;
            return;
        }
        steerSolution = null;
    }

    /** Натянут ли лук полностью (дальше сила выстрела не растёт). */
    private static boolean isFullyDrawn(LocalPlayer player) {
        return player.getTicksUsingItem() >= BowItem.MAX_DRAW_DURATION;
    }

    /**
     * Возвращает захваченную цель, при необходимости захватывая заново.
     *
     * <p>Захват держится всё натяжение, но перепроверяется каждый тик: цель могла умереть,
     * стать союзником по скорборду, уйти в невидимость или просто уехать далеко в сторону.
     * Раньше проверялось только {@code isAlive()}, и захват мог остаться на цели, в которую
     * игрок уже не целится.</p>
     */
    private static LivingEntity acquireTarget(Minecraft mc, LocalPlayer player) {
        LivingEntity current = lockedTarget != null ? lockedTarget.get() : null;
        boolean keep = current != null
            && AimSolver.isValidTarget(player, current)
            && AimSolver.angleToTarget(player, current) <= RETARGET_CONE_DEG;

        if (!keep) {
            LivingEntity previous = current;
            current = AimSolver.pickTarget(mc, player, AUTO_CONE_RAD);
            lockedTarget = current != null ? new WeakReference<>(current) : null;
            if (current != previous) {
                // Фильтр и выбранная точка привязаны к конкретной цели: перенос их на новую
                // заставил бы камеру ползти от старого решения к новому через полэкрана.
                steerSolution = null;
                resetAimFilter();
            }
        }
        return current;
    }

    /** {@code true}, если авто-доводка сейчас ведёт камеру к захваченной цели. */
    public static boolean isAutoAimEngaged() { return autoAimEngaged; }

    /**
     * Доворачивает взгляд игрока к готовому стрелковому решению.
     *
     * <p>Шаг пропорционален оставшемуся рассогласованию и ограничен сверху, поэтому камера
     * подходит к решению по экспоненте, а не телепортируется в него. Мышь игрока при этом
     * продолжает работать: доводка добавляется к текущему повороту, а не заменяет его.</p>
     */
    private static void applyAutoAim(LocalPlayer player, AimSolver.Solution aim) {
        if (aim == null) {
            // Попадания нет и удержание истекло — камера остаётся за игроком.
            engageTicks = 0;
            return;
        }

        double desiredYaw   = aim.yaw();
        double desiredPitch = Mth.clamp(aim.pitch(), -90.0, 90.0);

        if (!smoothingPrimed) {
            smoothedYaw = desiredYaw;
            smoothedPitch = desiredPitch;
            smoothingPrimed = true;
        } else {
            smoothedYaw += Mth.wrapDegrees(desiredYaw - smoothedYaw) * SOLUTION_SMOOTHING;
            smoothedPitch += (desiredPitch - smoothedPitch) * SOLUTION_SMOOTHING;
        }

        double dYaw   = Mth.wrapDegrees(smoothedYaw - player.getYRot());
        double dPitch = smoothedPitch - player.getXRot();
        if (Math.abs(dYaw) > AUTO_AIM_MAX_YAW_DEG) {
            engageTicks = 0;
            return;
        }

        autoAimEngaged = true;
        if (engageTicks < AUTO_AIM_EASE_TICKS) engageTicks++;

        if (Math.abs(dYaw) < AUTO_AIM_DEADZONE_DEG && Math.abs(dPitch) < AUTO_AIM_DEADZONE_DEG) {
            return;
        }

        double ease = engageTicks / (double) AUTO_AIM_EASE_TICKS;
        double strength = Mth.clamp(ScopeConfig.AUTO_AIM_STRENGTH.get() / 100.0, 0.05, 1.0) * ease;
        double maxStep  = AUTO_AIM_MAX_STEP_DEG * strength;
        double stepYaw   = Mth.clamp(dYaw   * strength, -maxStep, maxStep);
        double stepPitch = Mth.clamp(dPitch * strength, -maxStep, maxStep);

        player.setYRot((float) (player.getYRot() + stepYaw));
        player.setXRot((float) Mth.clamp(player.getXRot() + stepPitch, -90.0, 90.0));
        // Голова у локального игрока не следует за yRot сама — без этого модель смотрит в сторону.
        player.setYHeadRot(player.getYRot());
    }

    private ScopeOverlay() {}

    // ── Точка входа ─────────────────────────────────────────────────────────

    public static void render(GuiGraphics g, float partialTick) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.options.hideGui) return;

        LocalPlayer player = mc.player;
        ItemStack heldMain = player.getMainHandItem();
        ItemStack heldOff  = player.getOffhandItem();

        boolean isDrawing = player.isUsingItem()
            && (player.getUseItem().getItem() instanceof BowItem);
        boolean isHolding = (heldMain.getItem() instanceof BowItem)
            || (heldOff.getItem() instanceof BowItem);

        if (ScopeConfig.SHOW_ONLY_WHILE_DRAWING.get() && !isDrawing) return;
        if (!isDrawing && !isHolding) return;

        float charge = isDrawing ? BowItem.getPowerForTime(player.getTicksUsingItem()) : 0f;

        int zeroD        = ScopeConfig.ZERO_DISTANCE.get();
        int color        = ScopeConfig.RETICLE_COLOR.get();
        int dimColor     = applyAlpha(color, 0.5f);
        int outlineColor = 0xCC000000;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int cx = sw / 2;
        int cy = sh / 2;

        double fovRad    = Math.toRadians(mc.options.fov().get());
        double pixPerRad = sh / fovRad;

        double zeroAngleDeg = ArrowPhysics.zeroAngle(zeroD, 1.0);
        int    ay           = cy + (int) Math.round(Math.toRadians(zeroAngleDeg) * pixPerRad);

        // Индикатор режима — виден всегда
        drawModeIndicator(g, mc, sw, cy, color, dimColor);

        if (currentMode == ScopeMode.AUTO) {
            // ── AUTO: прицел (куда летит стрела) + метка упреждения ─────────
            drawCenterMark(g, cx, cy, dimColor);
            drawMainCrosshair(g, cx, ay, color, outlineColor);

            if (isDrawing && charge > 0.01f) {
                drawAutoLead(g, mc, player, cx, cy, pixPerRad,
                    zeroD, zeroAngleDeg, charge, partialTick, color, outlineColor);
            }
        } else {
            // ── MANUAL: полный прицел ────────────────────────────────────────
            double measuredDist = ScopeConfig.SHOW_RANGEFINDER.get() ? cachedMeasuredDistance : -1;

            drawCenterMark(g, cx, cy, dimColor);
            drawMainCrosshair(g, cx, ay, color, outlineColor);

            if (isDrawing && charge > 0.01f && charge < 0.999f) {
                double chargeAngleDeg = ArrowPhysics.zeroAngle(zeroD, charge);
                int    chargeY        = cy + (int) Math.round(Math.toRadians(chargeAngleDeg) * pixPerRad);
                if (chargeY != ay) {
                    drawChargeMark(g, cx, chargeY, color, outlineColor, charge);
                }
            }

            if (ScopeConfig.SHOW_STADIA_MARKS.get()) {
                drawStadiaMarks(g, mc, cx, cy, zeroD, pixPerRad,
                    color, dimColor, outlineColor, measuredDist, sh);
            }

            if (ScopeConfig.SHOW_RANGEFINDER.get() && measuredDist > 0) {
                drawRangefinder(g, mc, cx, cy, measuredDist, zeroD, color);
            }

            drawZeroLabel(g, mc, cx, ay, zeroD, color);

            if (ScopeConfig.SHOW_CHARGE_BAR.get()) {
                drawChargeBar(g, cx, cy, sh, charge, color, outlineColor);
            }
        }
    }

    // ── Элементы прицела ────────────────────────────────────────────────────

    private static void drawCenterMark(GuiGraphics g, int cx, int cy, int color) {
        hLine(g, cx - 2, cx + 2, cy, color);
        vLine(g, cx, cy - 2, cy + 2, color);
    }

    private static void drawMainCrosshair(GuiGraphics g, int cx, int ay,
                                          int color, int outlineColor) {
        shadowHLine(g, cx - GAP - ARM_LEN, cx - GAP, ay, outlineColor, color);
        shadowHLine(g, cx + GAP, cx + GAP + ARM_LEN, ay, outlineColor, color);
        shadowVLine(g, cx, ay - GAP - ARM_LEN, ay - GAP, outlineColor, color);
        shadowVLine(g, cx, ay + GAP, ay + GAP + ARM_LEN / 3, outlineColor, color);

        g.fill(cx - 1, ay - 1, cx + 2, ay + 2, outlineColor);
        g.fill(cx, ay, cx + 1, ay + 1, color);
    }

    /**
     * Вторичная метка натяжения — маленькая горизонтальная черта с центральной точкой.
     * При полном заряде совпадает с основным прицелом; при нулевом — выше.
     * {@code charge} используется для регулировки яркости/прозрачности.
     */
    private static void drawChargeMark(GuiGraphics g, int cx, int chargeY,
                                       int color, int outlineColor, float charge) {
        int half = 8;
        int c    = applyAlpha(color, 0.4f + 0.6f * charge);

        g.fill(cx - half - 1, chargeY - 1, cx + half + 2, chargeY + 2, outlineColor);
        g.fill(cx - half, chargeY, cx + half + 1, chargeY + 1, c);

        g.fill(cx - 1, chargeY - 1, cx + 2, chargeY + 2, outlineColor);
        g.fill(cx, chargeY, cx + 1, chargeY + 1, c);
    }

    private static void drawStadiaMarks(GuiGraphics g, Minecraft mc,
                                        int cx, int cy, int zeroD,
                                        double pixPerRad,
                                        int color, int dimColor, int outlineColor,
                                        double measuredDist, int sh) {
        for (int d : buildDistanceTable(zeroD)) {
            if (d == zeroD || d <= 0 || d > 200) continue;

            double angleRad = Math.toRadians(ArrowPhysics.requiredAngle(d));
            int    markY    = cy + (int) Math.round(angleRad * pixPerRad);

            if (markY < cy - sh / 4 || markY > cy + sh / 2) continue;

            boolean closest   = measuredDist > 0 && Math.abs(d - measuredDist) < 7.5;
            int     markColor = closest ? brighten(color) : dimColor;
            int     halfLen   = (d % 25 == 0) ? STADIA_HALF : STADIA_TICK;

            g.fill(cx - halfLen - 1, markY - 1, cx + halfLen + 1, markY + 2, outlineColor);
            g.fill(cx - halfLen, markY, cx + halfLen, markY + 1, markColor);
            g.fill(cx - 1, markY - 1, cx + 2, markY + 2, outlineColor);
            g.fill(cx, markY, cx + 1, markY + 1, markColor);

            int labelX = cx + halfLen + 4;
            int labelY = markY - 4;
            g.drawString(mc.font, d + "m", labelX + 1, labelY + 1, 0xFF000000, false);
            g.drawString(mc.font, d + "m", labelX, labelY, markColor, false);
        }
    }

    private static void drawRangefinder(GuiGraphics g, Minecraft mc,
                                        int cx, int cy,
                                        double dist, int zeroD, int color) {
        String text      = String.format("◎ %.1fm", dist);
        int    textColor = (dist < zeroD * 0.9 || dist > zeroD * 1.1) ? 0xFFFFAA00 : color;
        int    x         = cx + 60;
        int    y         = cy - 40;
        g.drawString(mc.font, text, x + 1, y + 1, 0xFF000000, false);
        g.drawString(mc.font, text, x, y, textColor, false);
    }

    private static void drawZeroLabel(GuiGraphics g, Minecraft mc,
                                      int cx, int ay, int zeroD, int color) {
        String label = "⊕ " + zeroD + "m";
        int    x     = cx - ARM_LEN - GAP - mc.font.width(label) - 6;
        int    y     = ay - 4;
        g.drawString(mc.font, label, x + 1, y + 1, 0xFF000000, false);
        g.drawString(mc.font, label, x, y, color, false);
    }

    private static void drawChargeBar(GuiGraphics g, int cx, int cy, int sh,
                                      float charge, int color, int outlineColor) {
        int barH   = sh / 5;
        int barW   = 4;
        int barX   = cx - ARM_LEN - GAP - 16;
        int barTop = cy - barH / 2;
        int barBot = cy + barH / 2;
        int fillH  = (int) (charge * barH);

        g.fill(barX - 1, barTop - 1, barX + barW + 1, barBot + 1, outlineColor);
        g.fill(barX, barTop, barX + barW, barBot, 0x44000000);

        if (fillH > 0) {
            int fillColor = charge >= 1.0f ? brighten(color) : color;
            g.fill(barX, barBot - fillH, barX + barW, barBot, fillColor);
        }
        g.fill(barX - 2, barTop - 1, barX + barW + 2, barTop, color);
    }

    // ── Индикатор режима ─────────────────────────────────────────────────────

    private static void drawModeIndicator(GuiGraphics g, Minecraft mc,
                                          int sw, int cy, int color, int dimColor) {
        boolean isAuto   = (currentMode == ScopeMode.AUTO);
        boolean isSteering = isAuto && autoAimEngaged;
        String  modeText = isSteering ? "[AUTO+]" : isAuto ? "[AUTO]" : "[MAN]";
        int     c        = isAuto ? brighten(color) : dimColor;
        int     x        = sw - mc.font.width(modeText) - 6;
        int     y        = cy - 30;
        g.drawString(mc.font, modeText, x + 1, y + 1, 0xFF000000, false);
        g.drawString(mc.font, modeText, x, y, c, false);
    }

    // ── Авто-режим: упреждение ───────────────────────────────────────────────

    /**
     * Рисует метку упреждения по решению, посчитанному в тике.
     *
     * <p>Метка стоит там, куда игрок должен подвести основной крест: тот смещён на угол
     * пристрелки вниз от центра, поэтому к разнице по pitch добавляется {@code zeroAngleDeg}.
     * При совмещении креста с ромбом взгляд совпадает с решением — тем самым, к которому
     * тянет доводка.</p>
     */
    private static void drawAutoLead(GuiGraphics g, Minecraft mc, LocalPlayer player,
                                     int cx, int cy, double pixPerRad,
                                     int zeroD, double zeroAngleDeg, float charge,
                                     float partialTick,
                                     int color, int outlineColor) {
        AimSolver.Solution aim = cachedSolution;
        if (aim == null) return;
        LivingEntity target = aim.target();
        if (target == null || !target.isAlive()) return;

        float playerYaw   = Mth.lerp(partialTick, player.yRotO, player.getYRot());
        float playerPitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());

        double dyaw   = Mth.wrapDegrees(aim.yaw() - playerYaw);
        double dpitch = aim.pitch() - playerPitch + zeroAngleDeg;

        int leadX = cx + (int) Math.round(Math.toRadians(dyaw) * pixPerRad);
        int leadY = cy + (int) Math.round(Math.toRadians(dpitch) * pixPerRad);

        if (leadX < 4 || leadX > cx * 2 - 4 || leadY < 4 || leadY > cy * 2 - 4) return;

        // Перекрытая траектория гасит метку: решение показано, но стрела упрётся в блок.
        int markColor = aim.clear() ? color : applyAlpha(color, 0.45f);
        drawLeadDiamond(g, leadX, leadY, markColor, outlineColor);

        StringBuilder label = new StringBuilder(target.getName().getString())
            .append(' ').append((int) aim.horizDist()).append('m');
        if (aim.lofted()) label.append(" ARC");
        if (!aim.clear()) label.append(" BLOCKED");

        String text = label.toString();
        g.drawString(mc.font, text, leadX + 10 + 1, leadY - 4 + 1, 0xFF000000, false);
        g.drawString(mc.font, text, leadX + 10, leadY - 4, markColor, false);
    }

    /** Ромбовидная метка упреждения (◇). */
    private static void drawLeadDiamond(GuiGraphics g, int x, int y, int color, int outlineColor) {
        int r = 6;
        // Тень (каждая диагональная грань смещена на 1px)
        for (int i = 0; i <= r; i++) {
            int hw = r - i;
            g.fill(x - hw - 1, y - i - 1, x - hw + 1, y - i + 1, outlineColor);
            g.fill(x + hw,     y - i - 1, x + hw + 2, y - i + 1, outlineColor);
            g.fill(x - hw - 1, y + i - 1, x - hw + 1, y + i + 1, outlineColor);
            g.fill(x + hw,     y + i - 1, x + hw + 2, y + i + 1, outlineColor);
        }
        // Цветные пиксели граней ромба
        for (int i = 0; i <= r; i++) {
            int hw = r - i;
            g.fill(x - hw, y - i, x - hw + 1, y - i + 1, color);
            g.fill(x + hw, y - i, x + hw + 1, y - i + 1, color);
            g.fill(x - hw, y + i, x - hw + 1, y + i + 1, color);
            g.fill(x + hw, y + i, x + hw + 1, y + i + 1, color);
        }
        // Яркий центр
        g.fill(x - 1, y - 1, x + 2, y + 2, outlineColor);
        g.fill(x, y, x + 1, y + 1, brighten(color));
    }

    // ── Дальномер ────────────────────────────────────────────────────────────

    private static double measureDistance(Minecraft mc, LocalPlayer player) {
        Vec3 eye  = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end  = eye.add(look.scale(MAX_RANGE));

        BlockHitResult blockHit = mc.level.clip(new ClipContext(
            eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double blockDist = MAX_RANGE;
        if (blockHit.getType() != HitResult.Type.MISS) {
            blockDist = eye.distanceTo(blockHit.getLocation());
        }

        double entityDist = MAX_RANGE;
        Vec3 entityEnd = eye.add(look.scale(ENTITY_SCAN_RANGE));
        AABB searchBox = new AABB(eye, entityEnd).inflate(2.0);
        for (Entity entity : mc.level.getEntities(player, searchBox)) {
            if (entity.isSpectator() || !entity.isAlive()) continue;
            AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
            Optional<Vec3> hit = box.clip(eye, entityEnd);
            if (hit.isPresent()) {
                double d = eye.distanceTo(hit.get());
                if (d < entityDist) entityDist = d;
            }
        }

        double dist = Math.min(blockDist, entityDist);
        return dist >= MAX_RANGE ? -1 : dist;
    }

    // ── Таблица дистанций ────────────────────────────────────────────────────

    /**
     * Distance ladder for the stadia marks. Values are deduplicated: at small zeroing
     * distances several formulas collapse onto the same number (e.g. {@code z/4} and
     * {@code z/2} both give 10), which used to draw the same mark twice.
     */
    private static int[] buildDistanceTable(int z) {
        int[] raw = {
            Math.max(10, z / 4),
            Math.max(10, z / 2),
            z * 3 / 4,
            z,
            z + z / 4,
            z + z / 2,
            z * 2,
            z * 3
        };
        java.util.TreeSet<Integer> unique = new java.util.TreeSet<>();
        for (int d : raw) unique.add(d);
        int[] out = new int[unique.size()];
        int i = 0;
        for (int d : unique) out[i++] = d;
        return out;
    }

    // ── Примитивы ────────────────────────────────────────────────────────────

    private static void hLine(GuiGraphics g, int x1, int x2, int y, int color) {
        g.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 1, color);
    }

    private static void vLine(GuiGraphics g, int x, int y1, int y2, int color) {
        g.fill(x, Math.min(y1, y2), x + 1, Math.max(y1, y2) + 1, color);
    }

    private static void shadowHLine(GuiGraphics g, int x1, int x2, int y,
                                    int shadow, int color) {
        hLine(g, x1, x2, y + 1, shadow);
        hLine(g, x1, x2, y, color);
    }

    private static void shadowVLine(GuiGraphics g, int x, int y1, int y2,
                                    int shadow, int color) {
        vLine(g, x + 1, y1, y2, shadow);
        vLine(g, x, y1, y2, color);
    }

    // ── Цвет ─────────────────────────────────────────────────────────────────

    private static int applyAlpha(int argb, float factor) {
        int a = (int) (((argb >> 24) & 0xFF) * factor);
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    private static int brighten(int argb) {
        int r  = Math.min(255, ((argb >> 16) & 0xFF) + 80);
        int gv = Math.min(255, ((argb >>  8) & 0xFF) + 80);
        int b  = Math.min(255, ( argb        & 0xFF) + 80);
        return (argb & 0xFF000000) | (r << 16) | (gv << 8) | b;
    }
}
