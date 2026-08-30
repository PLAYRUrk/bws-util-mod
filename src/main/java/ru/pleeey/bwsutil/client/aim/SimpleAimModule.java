package ru.pleeey.bwsutil.client.aim;

import ru.pleeey.bwsutil.client.overlay.ScopeGfx;
import ru.pleeey.bwsutil.physics.ArrowPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;

/**
 * Базовое наведение AUTO-режима: метка на корпусе ближайшей к центру экрана цели.
 *
 * <p>Считается ровно один угол — тот, под которым стрела проходит через грудь цели с учётом
 * перепада высот. Ни упреждения по движению, ни проверки рельефа здесь нет: метка показывает,
 * куда стрелять по неподвижной цели на открытом месте, а остальное остаётся на игроке.</p>
 */
public final class SimpleAimModule implements AimModule {

    /** Конус поиска цели вокруг направления взгляда. */
    private static final double CONE_RAD = Math.toRadians(15.0);
    /** Дальше стрела не долетает даже на полном натяжении. */
    private static final double SEARCH_RANGE = 96.0;
    /** Доля высоты хитбокса, по которой ставится метка. */
    private static final double CHEST = 0.62;

    private WeakReference<LivingEntity> target = null;
    private double yaw, pitch, distance;
    private boolean solved = false;

    @Override
    public void reset() {
        target = null;
        solved = false;
    }

    @Override
    public void tick(Minecraft mc, LocalPlayer player) {
        solved = false;
        if (mc.level == null) return;

        LivingEntity found = pickTarget(mc, player);
        target = found != null ? new WeakReference<>(found) : null;
        if (found == null) return;

        float charge = chargeOf(player);
        if (charge <= 0.01f) return;

        Vec3 eye = player.getEyePosition();
        Vec3 aimPoint = new Vec3(found.getX(), found.getY() + found.getBbHeight() * CHEST,
            found.getZ());

        double horiz = Math.max(1.0, Math.hypot(aimPoint.x - eye.x, aimPoint.z - eye.z));
        double elevation = ArrowPhysics.solveElevations(horiz, aimPoint.y - eye.y, charge)[0];
        if (Double.isNaN(elevation)) return;

        yaw = Math.toDegrees(Math.atan2(-(aimPoint.x - eye.x), aimPoint.z - eye.z));
        pitch = -elevation;
        distance = horiz;
        solved = true;
    }

    @Override
    public void renderLead(GuiGraphics g, Minecraft mc, LocalPlayer player, LeadContext ctx) {
        if (!solved) return;
        LivingEntity found = target != null ? target.get() : null;
        if (found == null || !found.isAlive()) return;

        float playerYaw   = Mth.lerp(ctx.partialTick(), player.yRotO, player.getYRot());
        float playerPitch = Mth.lerp(ctx.partialTick(), player.xRotO, player.getXRot());

        // Метка стоит там, куда нужно подвести основной крест: тот смещён вниз на угол
        // пристрелки, поэтому к разнице по pitch он и добавляется.
        double dyaw   = Mth.wrapDegrees(yaw - playerYaw);
        double dpitch = pitch - playerPitch + ctx.zeroAngleDeg();

        int x = ctx.cx() + (int) Math.round(Math.toRadians(dyaw) * ctx.pixPerRad());
        int y = ctx.cy() + (int) Math.round(Math.toRadians(dpitch) * ctx.pixPerRad());
        if (x < 4 || x > ctx.cx() * 2 - 4 || y < 4 || y > ctx.cy() * 2 - 4) return;

        ScopeGfx.drawLeadDiamond(g, x, y, ctx.color(), ctx.outlineColor());
        ScopeGfx.drawShadowed(g, mc.font,
            found.getName().getString() + " " + (int) distance + "m",
            x + 10, y - 4, ctx.color());
    }

    private static float chargeOf(LocalPlayer player) {
        return net.minecraft.world.item.BowItem.getPowerForTime(player.getTicksUsingItem());
    }

    /** Ближайшая к центру экрана живая цель в конусе. */
    private static LivingEntity pickTarget(Minecraft mc, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double radius = SEARCH_RANGE * Math.tan(CONE_RAD) + 4.0;
        AABB search = new AABB(eye, eye.add(look.scale(SEARCH_RANGE))).inflate(radius);

        LivingEntity best = null;
        double bestAngle = Double.MAX_VALUE;

        for (Entity entity : mc.level.getEntities(player, search)) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living == player || !living.isAlive() || living.isDeadOrDying()) continue;
            if (living.isSpectator() || living instanceof ArmorStand) continue;
            if (living.isInvisibleTo(player)) continue;

            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(eye);
            double dist = toTarget.length();
            if (dist < 0.5 || dist > SEARCH_RANGE) continue;

            double angle = Math.acos(Mth.clamp(toTarget.scale(1.0 / dist).dot(look), -1.0, 1.0));
            if (angle > CONE_RAD || angle >= bestAngle) continue;

            bestAngle = angle;
            best = living;
        }
        return best;
    }
}
