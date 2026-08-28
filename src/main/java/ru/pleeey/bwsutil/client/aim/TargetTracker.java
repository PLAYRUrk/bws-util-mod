package ru.pleeey.bwsutil.client.aim;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Скользящее окно скорости захваченной цели.
 *
 * <p>Позиции чужих сущностей на клиенте не подставляются напрямую из пакетов, а разгоняются
 * интерполяцией: {@code InterpolationHandler.DEFAULT_INTERPOLATION_STEPS = 3}. Из-за этого
 * дельта за один тик ({@code x - xo}) — алиасинг настоящей скорости по трёхтиковому окну: тик
 * может показать почти ноль, следующий — двойной шаг. Упреждение, построенное на такой дельте,
 * дёргается, и вместе с ним дёргается угол доводки.</p>
 *
 * <p>Поэтому скорость усредняется по окну не меньше интерполяционного. Тики под кнокбэком
 * ({@code hurtTime > 0}) в окно не попадают: там цель летит не туда, куда бежит.</p>
 */
public final class TargetTracker {

    /** Длина окна усреднения в тиках. Вдвое больше интерполяционного — три шага плюс запас. */
    private static final int WINDOW = 6;

    private int targetId = Integer.MIN_VALUE;

    private final double[] dx = new double[WINDOW];
    private final double[] dy = new double[WINDOW];
    private final double[] dz = new double[WINDOW];
    /** Тик пригоден для усреднения (не кнокбэк). */
    private final boolean[] valid = new boolean[WINDOW];

    private int cursor;
    private int filled;

    /** Была ли цель на земле в последнем измерении. */
    private boolean grounded = true;

    public void reset() {
        targetId = Integer.MIN_VALUE;
        cursor = 0;
        filled = 0;
        grounded = true;
    }

    /**
     * Добавляет измерение за текущий тик. Вызывается ровно один раз за тик по захваченной цели.
     *
     * <p>Смена цели обнуляет окно: усреднять перемещения двух разных игроков бессмысленно.</p>
     */
    public void observe(LivingEntity target) {
        if (target.getId() != targetId) {
            reset();
            targetId = target.getId();
        }

        dx[cursor] = target.getX() - target.xo;
        dy[cursor] = target.getY() - target.yo;
        dz[cursor] = target.getZ() - target.zo;
        valid[cursor] = target.hurtTime <= 0;

        cursor = (cursor + 1) % WINDOW;
        if (filled < WINDOW) filled++;
        grounded = target.onGround();
    }

    /** Цель стояла на земле в последнем измерении. */
    public boolean isGrounded() { return grounded; }

    /** Есть ли данные по этой цели. */
    public boolean tracks(LivingEntity target) {
        return target.getId() == targetId && filled > 0;
    }

    /**
     * Усреднённая скорость цели в блоках за тик.
     *
     * <p>Если все накопленные тики пришлись на кнокбэк, возвращается нулевой вектор: лучше не
     * упреждать вовсе, чем упреждать по отбросу от удара.</p>
     */
    public Vec3 velocity() {
        double sx = 0, sy = 0, sz = 0;
        int count = 0;
        for (int i = 0; i < filled; i++) {
            if (!valid[i]) continue;
            sx += dx[i];
            sy += dy[i];
            sz += dz[i];
            count++;
        }
        if (count == 0) return Vec3.ZERO;
        return new Vec3(sx / count, sy / count, sz / count);
    }

    /**
     * Та же арифметика, что и в {@link #velocity()}, но без типов Minecraft — чтобы усреднение
     * можно было проверить отдельной программой, не поднимая клиент.
     *
     * @return {@code [x, y, z]} среднего перемещения за тик по пригодным измерениям
     */
    public static double[] averageDelta(double[] dx, double[] dy, double[] dz,
                                        boolean[] valid, int count) {
        double sx = 0, sy = 0, sz = 0;
        int used = 0;
        for (int i = 0; i < count; i++) {
            if (!valid[i]) continue;
            sx += dx[i];
            sy += dy[i];
            sz += dz[i];
            used++;
        }
        if (used == 0) return new double[] { 0.0, 0.0, 0.0 };
        return new double[] { sx / used, sy / used, sz / used };
    }
}
