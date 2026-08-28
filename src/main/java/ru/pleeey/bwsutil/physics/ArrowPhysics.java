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

    /** Same idea for {@link #solveElevations}, which is far more expensive than a binary search. */
    private static final Map<Long, double[]> ELEVATION_CACHE = new ConcurrentHashMap<>();

    private static final double DIST_QUANTUM  = 0.5;
    private static final double POWER_QUANTUM = 0.02;
    private static final double DY_QUANTUM    = 0.25;
    /** Height differences past this are clamped: no bow shot is meaningful that far above or below. */
    private static final double DY_LIMIT      = 128.0;

    /**
     * Elevation sweep bounds. Straight up is excluded on purpose: horizontal progress there is
     * dominated by drag, and the simulation would burn its whole tick budget for a shot no player
     * would take.
     */
    private static final double ELEVATION_MIN_DEG  = -85.0;
    private static final double ELEVATION_MAX_DEG  = 85.0;
    private static final double ELEVATION_STEP_DEG = 1.5;
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
     * Exact launch elevations that put the arrow through a point {@code dy} blocks above the eye
     * at {@code horizDist} blocks horizontally.
     *
     * <p>{@link #zeroAngle} only answers the flat case (target at eye level), which is fine for a
     * reticle but not for aiming: a target on a tower or in a pit needs a different angle, and the
     * error grows with the height difference.</p>
     *
     * <p>A ballistic arc reaches a given point from two directions — a flat, fast trajectory and a
     * lobbed one. Both are returned: the flat one is preferable (shorter flight, less lead error),
     * the lobbed one is what clears a wall between shooter and target.</p>
     *
     * @return {@code [flatDeg, loftedDeg]}; a component is {@link Double#NaN} when that arc does
     *         not exist (target out of reach for this draw strength)
     */
    public static double[] solveElevations(double horizDist, double dy, double power) {
        if (horizDist <= 0.0 || power <= 0.0) return new double[] { Double.NaN, Double.NaN };

        // dy is offset before packing: it is signed, and a raw negative component would collide
        // with the power field in the composite key.
        long distKey  = Math.round(horizDist / DIST_QUANTUM);
        long dyKey    = Math.round(Math.max(-DY_LIMIT, Math.min(DY_LIMIT, dy)) / DY_QUANTUM);
        long powerKey = Math.round(power / POWER_QUANTUM);
        long key = distKey * 4_194_304L + (dyKey + 512L) * 1_024L + powerKey;

        double[] cached = ELEVATION_CACHE.get(key);
        if (cached != null) return cached;

        double[] result = computeElevations(
            distKey * DIST_QUANTUM, dyKey * DY_QUANTUM, powerKey * POWER_QUANTUM);

        if (ELEVATION_CACHE.size() >= CACHE_MAX_ENTRIES) ELEVATION_CACHE.clear();
        ELEVATION_CACHE.put(key, result);
        return result;
    }

    /**
     * Sweeps the whole usable elevation range and bisects every sign change of
     * {@code heightAt(angle) - dy}.
     *
     * <p>A plain binary search cannot be used here: the height at a fixed distance is not monotone
     * in the launch angle — it rises to the apex and falls again, which is exactly why two
     * solutions exist. The sweep finds both brackets; bisection then refines them.</p>
     */
    private static double[] computeElevations(double horizDist, double dy, double power) {
        double lowRoot  = Double.NaN;
        double highRoot = Double.NaN;

        double prevAngle = ELEVATION_MIN_DEG;
        double prevDelta = heightAt(prevAngle, power, horizDist) - dy;

        for (double angle = ELEVATION_MIN_DEG + ELEVATION_STEP_DEG;
             angle <= ELEVATION_MAX_DEG; angle += ELEVATION_STEP_DEG) {
            double delta = heightAt(angle, power, horizDist) - dy;
            if (prevDelta == 0.0) {
                lowRoot = Double.isNaN(lowRoot) ? prevAngle : lowRoot;
                highRoot = prevAngle;
            } else if ((prevDelta < 0) != (delta < 0)) {
                double root = bisectElevation(prevAngle, angle, power, horizDist, dy);
                if (Double.isNaN(lowRoot)) lowRoot = root;
                else                       highRoot = root;
            }
            prevAngle = angle;
            prevDelta = delta;
        }
        return new double[] { lowRoot, highRoot };
    }

    private static double bisectElevation(double lo, double hi, double power,
                                          double horizDist, double dy) {
        double loDelta = heightAt(lo, power, horizDist) - dy;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) * 0.5;
            double midDelta = heightAt(mid, power, horizDist) - dy;
            if ((loDelta < 0) == (midDelta < 0)) {
                lo = mid;
                loDelta = midDelta;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5;
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
