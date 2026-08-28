package ru.pleeey.bwsutil.physics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ArrowPhysics {

    private static final double GRAVITY   = 0.05;
    private static final double DRAG      = 0.99;
    public  static final double FULL_SPEED = 3.0;

    /**
     * Memoization for {@link #zeroAngle(double, double)}.
     *
     * <p>A single {@code zeroAngle} call runs a 64-step binary search, and every step simulates
     * the arrow tick by tick. The scope calls it a dozen times per frame with arguments that
     * barely change, so results are cached on a quantized (distance, power) grid. Quantization
     * is far below what a pixel on screen can resolve, so it costs no visible accuracy.</p>
     */
    private static final Map<Long, Double> ZERO_ANGLE_CACHE = new ConcurrentHashMap<>();

    private static final double DIST_QUANTUM  = 0.5;
    private static final double POWER_QUANTUM = 0.02;
    /** Safety valve: the grid is small in practice, but never let the cache grow without bound. */
    private static final int CACHE_MAX_ENTRIES = 8_192;

    private ArrowPhysics() {}

    public static double heightAt(double launchAngleDeg, double power, double targetX) {
        if (targetX <= 0) return 0;
        double speed = power * FULL_SPEED;
        double rad   = Math.toRadians(launchAngleDeg);
        double vx    = speed * Math.cos(rad);
        double vy    = speed * Math.sin(rad);
        double x = 0, y = 0;
        for (int tick = 0; tick < 2000; tick++) {
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (x + vx >= targetX) {
                double frac = (targetX - x) / vx;
                return y + vy * frac;
            }
            x += vx;
            y += vy;
            if (y < -256 || x > 1000) break;
        }
        return y;
    }

    public static double zeroAngle(double zeroDistance, double power) {
        long distKey  = Math.round(zeroDistance / DIST_QUANTUM);
        long powerKey = Math.round(power / POWER_QUANTUM);
        long key = distKey * 8_192L + powerKey;

        Double cached = ZERO_ANGLE_CACHE.get(key);
        if (cached != null) return cached;

        double angle = computeZeroAngle(distKey * DIST_QUANTUM, powerKey * POWER_QUANTUM);
        if (ZERO_ANGLE_CACHE.size() >= CACHE_MAX_ENTRIES) ZERO_ANGLE_CACHE.clear();
        ZERO_ANGLE_CACHE.put(key, angle);
        return angle;
    }

    private static double computeZeroAngle(double zeroDistance, double power) {
        double lo = 0.0, hi = 45.0;
        for (int i = 0; i < 64; i++) {
            double mid = (lo + hi) * 0.5;
            if (heightAt(mid, power, zeroDistance) < 0) lo = mid;
            else                                        hi = mid;
        }
        return (lo + hi) * 0.5;
    }

    public static double requiredAngle(double distance) {
        return zeroAngle(distance, 1.0);
    }

    public static double dropRelativeToZero(double zeroDistance, double targetDistance, double power) {
        return heightAt(zeroAngle(zeroDistance, power), power, targetDistance);
    }

    /**
     * Returns the number of ticks for an arrow to travel {@code targetX} blocks horizontally.
     * Returns {@code -1} if unreachable.
     */
    public static int flightTicks(double targetX, double launchAngleDeg, double power) {
        if (targetX <= 0) return 0;
        double speed = power * FULL_SPEED;
        double rad   = Math.toRadians(launchAngleDeg);
        double vx    = speed * Math.cos(rad);
        double vy    = speed * Math.sin(rad);
        double x = 0, y = 0;
        for (int tick = 0; tick < 2000; tick++) {
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (x + vx >= targetX) return tick + 1;
            x += vx;
            y += vy;
            if (y < -256 || x > 1000) return -1;
        }
        return -1;
    }
}
