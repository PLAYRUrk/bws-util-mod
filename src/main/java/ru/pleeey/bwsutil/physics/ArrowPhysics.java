package ru.pleeey.bwsutil.physics;

public final class ArrowPhysics {

    private static final double GRAVITY   = 0.05;
    private static final double DRAG      = 0.99;
    public  static final double FULL_SPEED = 3.0;

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
