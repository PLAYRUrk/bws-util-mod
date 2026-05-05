package ru.pleeey.bwsutil.client.overlay;

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
    private static final double AUTO_CONE_RAD   = Math.toRadians(15.0); // 15° поиск цели

    // ── Состояние прицела ────────────────────────────────────────────────────

    public enum ScopeMode { MANUAL, AUTO }

    private static boolean     enabled     = true;
    private static ScopeMode   currentMode = ScopeMode.MANUAL;

    /** Захваченная цель в AUTO-режиме. Управляется через tick(), не через render(). */
    private static WeakReference<LivingEntity> lockedTarget = null;

    public static void toggleEnabled() { enabled = !enabled; }
    public static boolean isEnabled()  { return enabled; }

    public static void toggleMode() {
        currentMode = (currentMode == ScopeMode.MANUAL) ? ScopeMode.AUTO : ScopeMode.MANUAL;
    }

    public static ScopeMode getMode() { return currentMode; }

    /**
     * Вызывается ровно один раз за игровой тик из ClientGameEvents.
     * Управляет захватом/сбросом цели в AUTO-режиме.
     */
    public static void tick(Minecraft mc, LocalPlayer player) {
        if (!enabled || mc.level == null) return;

        boolean isDrawing = player.isUsingItem()
            && (player.getUseItem().getItem() instanceof BowItem);

        if (currentMode == ScopeMode.AUTO) {
            if (isDrawing) {
                // Если нет валидного захвата — захватываем (первый тик ИЛИ после переключения режима)
                LivingEntity current = lockedTarget != null ? lockedTarget.get() : null;
                if (current == null || !current.isAlive()) {
                    LivingEntity found = findLivingTarget(mc, player);
                    lockedTarget = found != null ? new WeakReference<>(found) : null;
                }
            } else {
                // Выстрел произведён или натяжение отменено — сбрасываем
                lockedTarget = null;
            }
        } else {
            lockedTarget = null;
        }
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
            double measuredDist = ScopeConfig.SHOW_RANGEFINDER.get()
                ? measureDistance(mc, player) : -1;

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
        String  modeText = isAuto ? "[AUTO]" : "[MAN]";
        int     c        = isAuto ? brighten(color) : dimColor;
        int     x        = sw - mc.font.width(modeText) - 6;
        int     y        = cy - 30;
        g.drawString(mc.font, modeText, x + 1, y + 1, 0xFF000000, false);
        g.drawString(mc.font, modeText, x, y, c, false);
    }

    // ── Авто-режим: упреждение ───────────────────────────────────────────────

    private static void drawAutoLead(GuiGraphics g, Minecraft mc, LocalPlayer player,
                                     int cx, int cy, double pixPerRad,
                                     int zeroD, double zeroAngleDeg, float charge,
                                     float partialTick,
                                     int color, int outlineColor) {
        if (lockedTarget == null) return;
        LivingEntity target = lockedTarget.get();
        if (target == null || !target.isAlive()) return;

        // Интерполированные значения для плавного движения ромба между тиками
        Vec3 eyePos = player.getEyePosition(partialTick);

        double tx = Mth.lerp(partialTick, target.xo, target.getX());
        double ty = Mth.lerp(partialTick, target.yo, target.getY()) + target.getBbHeight() / 2.0;
        double tz = Mth.lerp(partialTick, target.zo, target.getZ());
        Vec3 targetCenter = new Vec3(tx, ty, tz);

        float playerYaw   = Mth.lerp(partialTick, player.yRotO, player.getYRot());
        float playerPitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());

        Vec3 toTarget = targetCenter.subtract(eyePos);

        // Скорость вычисляется из реальной разности позиций за тик (xo/zo — позиция
        // предыдущего тика). getDeltaMovement() на клиенте для серверных мобов почти
        // всегда ноль: сервер шлёт только обновления позиции, не velocity-пакеты.
        // При hurtTime > 0 кнокбэк делает дельту ненадёжной — обнуляем.
        // Вертикаль игнорируем: наземная цель остаётся на земле.
        Vec3 vel;
        if (target.hurtTime > 0) {
            vel = Vec3.ZERO;
        } else {
            double dvx = target.getX() - target.xo;
            double dvz = target.getZ() - target.zo;
            vel = new Vec3(dvx, 0.0, dvz);
        }

        double horizDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
        if (horizDist < 0.5) return;

        // Итерация 1: грубая оценка времени полёта по текущей дистанции
        int ticks0 = ArrowPhysics.flightTicks(horizDist,
            ArrowPhysics.zeroAngle(horizDist, charge), charge);
        if (ticks0 < 0) return;

        // Первое предсказание позиции → уточнённая горизонтальная дистанция
        Vec3   pred0  = targetCenter.add(vel.scale(ticks0));
        Vec3   top0   = pred0.subtract(eyePos);
        double hDist0 = Math.max(1.0, Math.sqrt(top0.x * top0.x + top0.z * top0.z));

        // Итерация 2: время полёта по предсказанной дистанции (для быстрых целей)
        int ticks = ArrowPhysics.flightTicks(hDist0,
            ArrowPhysics.zeroAngle(hDist0, charge), charge);
        if (ticks < 0) ticks = ticks0; // fallback

        // Финальное предсказание позиции цели
        Vec3   predictedPos       = targetCenter.add(vel.scale(ticks));
        Vec3   toPredict          = predictedPos.subtract(eyePos);
        double predictedLen       = toPredict.length();
        if (predictedLen < 0.01) return;
        double predictedHorizDist = Math.max(1.0,
            Math.sqrt(toPredict.x * toPredict.x + toPredict.z * toPredict.z));

        // Угловое направление на предсказанную позицию
        double predYaw   = Math.toDegrees(Math.atan2(-toPredict.x, toPredict.z));
        double predPitch = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0,
            -toPredict.y / predictedLen))));

        double dyaw = predYaw - playerYaw;
        while (dyaw >  180) dyaw -= 360;
        while (dyaw < -180) dyaw += 360;
        double dpitch = predPitch - playerPitch;

        // Баллистическая коррекция вертикали.
        //
        // Прицел (ay) находится на zeroAngleDeg НИЖЕ центра экрана.
        // Когда игрок совмещает прицел с ромбом, его взгляд уходит на zeroAngleDeg
        // ВЫШЕ ромба, и стрела летит под углом:
        //   (launch_elevation) = zeroAngleDeg − dpitch_diamond
        // Нам нужно: launch_elevation = requiredAngle(predictedHorizDist, charge).
        // Отсюда: dpitch_diamond = zeroAngleDeg − requiredAngle(predictedHorizDist, charge).
        // В экранных координатах: correctionDeg = zeroAngleDeg − requiredAngle(...)
        // (положительный → ромб ВЫШЕ направления на цель, отрицательный → ниже).
        double correctionDeg = zeroAngleDeg
            - ArrowPhysics.zeroAngle(predictedHorizDist, charge);

        int leadX = cx + (int) Math.round(Math.toRadians(dyaw) * pixPerRad);
        int leadY = cy + (int) Math.round(
            (Math.toRadians(dpitch) + Math.toRadians(correctionDeg)) * pixPerRad);

        if (leadX < 4 || leadX > cx * 2 - 4 || leadY < 4 || leadY > cy * 2 - 4) return;

        drawLeadDiamond(g, leadX, leadY, color, outlineColor);

        String label = target.getName().getString() + " " + (int) horizDist + "m";
        g.drawString(mc.font, label, leadX + 10 + 1, leadY - 4 + 1, 0xFF000000, false);
        g.drawString(mc.font, label, leadX + 10, leadY - 4, color, false);
    }

    private static LivingEntity findLivingTarget(Minecraft mc, LocalPlayer player) {
        Vec3 eye  = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end  = eye.add(look.scale(MAX_RANGE));

        AABB   searchBox = new AABB(eye, end).inflate(4.0);
        double bestAngle = AUTO_CONE_RAD;
        LivingEntity closest = null;

        for (Entity entity : mc.level.getEntities(player, searchBox)) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (entity.isSpectator() || !entity.isAlive()) continue;

            Vec3   center = entity.getBoundingBox().getCenter();
            Vec3   dir    = center.subtract(eye).normalize();
            double angle  = Math.acos(Math.max(-1.0, Math.min(1.0, dir.dot(look))));
            if (angle < bestAngle) {
                bestAngle = angle;
                closest   = living;
            }
        }
        return closest;
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
        AABB searchBox = new AABB(eye, end).inflate(2.0);
        for (Entity entity : mc.level.getEntities(player, searchBox)) {
            if (entity.isSpectator() || !entity.isAlive()) continue;
            AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceTo(hit.get());
                if (d < entityDist) entityDist = d;
            }
        }

        double dist = Math.min(blockDist, entityDist);
        return dist >= MAX_RANGE ? -1 : dist;
    }

    // ── Таблица дистанций ────────────────────────────────────────────────────

    private static int[] buildDistanceTable(int z) {
        return new int[]{
            Math.max(10, z / 4),
            Math.max(10, z / 2),
            z * 3 / 4,
            z,
            z + z / 4,
            z + z / 2,
            z * 2,
            z * 3
        };
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
