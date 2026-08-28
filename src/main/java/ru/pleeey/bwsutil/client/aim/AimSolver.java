package ru.pleeey.bwsutil.client.aim;

import ru.pleeey.bwsutil.physics.ArrowPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Выбор цели и построение стрелкового решения с учётом рельефа.
 *
 * <p>Решение — это направление взгляда, при котором стрела попадёт в цель: сюда входят
 * упреждение по движению, точный угол бросания под перепад высот и проверка, что траектория
 * не упирается в блок. Последнее — главное отличие от простой формулы: угол, верный
 * баллистически, регулярно упирается в край моста, парапет базы или в блок под ногами цели,
 * и стрела гарантированно уходит в стену.</p>
 *
 * <p>Класс не хранит состояния и ничего не рисует: он считается один раз за тик и отдаёт
 * готовый снимок вызывающему коду.</p>
 */
public final class AimSolver {

    /**
     * Точки прицеливания по высоте хитбокса, в порядке предпочтения.
     *
     * <p>Грудь идёт первой как самая широкая часть; голова — запасной вариант, который часто
     * единственный виден из-за низкого укрытия; ноги помогают, когда цель стоит за выступом
     * сверху. Перебор по этим точкам и есть учёт рельефа «рядом с целью».</p>
     */
    private static final double[] AIM_FRACTIONS = { 0.62, 0.88, 0.28 };

    /** Доля высоты, по которой считается упреждение (центр масс). */
    private static final double LEAD_ANCHOR = 0.62;

    /** Потолок симуляции полёта. Навесная траектория на 100+ блоков укладывается с запасом. */
    private static final int TRACE_MAX_TICKS = 140;
    /** Насколько дальше цели продолжать симуляцию, прежде чем признать промах. */
    private static final double TRACE_OVERSHOOT = 6.0;
    /** Запас тиков сверх расчётного времени полёта — на квантование дистанции в решателе. */
    private static final int TRACE_TICK_MARGIN = 6;

    private static final double SEARCH_RANGE   = 160.0;
    private static final double SEARCH_INFLATE = 4.0;

    // Веса отбора цели. Угол доминирует (игрок смотрит на того, кого хочет убить), дистанция
    // разводит одинаково отцентрованных, а штрафы отсекают заведомо неудачный выбор.
    private static final double ANGLE_WEIGHT        = 2.0;
    private static final double DISTANCE_WEIGHT     = 0.10;
    private static final double NON_PLAYER_PENALTY  = 60.0;
    private static final double OCCLUDED_PENALTY    = 45.0;

    private AimSolver() {}

    /**
     * Готовое стрелковое решение.
     *
     * @param yaw                абсолютный yaw взгляда (град.)
     * @param pitch              абсолютный pitch взгляда (град., отрицательный — вверх)
     * @param horizDist          текущая горизонтальная дистанция до цели
     * @param predictedHorizDist горизонтальная дистанция до точки упреждения
     * @param lofted             решение навесное (стрела перекидывается через препятствие)
     * @param clear              траектория проверена и доходит до цели, не задев блок
     * @param aimPoint           точка, в которую целимся
     */
    public record Solution(LivingEntity target, double yaw, double pitch,
                           double horizDist, double predictedHorizDist,
                           boolean lofted, boolean clear, Vec3 aimPoint) {}

    // ── Решение ─────────────────────────────────────────────────────────────

    /**
     * Считает решение по цели.
     *
     * @param charge       натяжение лука (0..1), оно же множитель начальной скорости
     * @param partialTick  {@code 1.0f} для тик-логики, интерполяция кадра — для рендера
     * @param checkTerrain перебирать точки прицеливания и дуги, проверяя траекторию по блокам
     * @return решение или {@code null}, если цель недостижима на текущем натяжении
     */
    public static Solution solve(LocalPlayer shooter, LivingEntity target,
                                 float charge, float partialTick, boolean checkTerrain) {
        if (charge <= 0.01f || shooter.level() == null) return null;
        double power = charge;

        Vec3 eye = shooter.getEyePosition(partialTick);
        double bx = Mth.lerp(partialTick, target.xo, target.getX());
        double by = Mth.lerp(partialTick, target.yo, target.getY());
        double bz = Mth.lerp(partialTick, target.zo, target.getZ());
        Vec3 base = new Vec3(bx, by, bz);

        double currentHoriz = Math.hypot(bx - eye.x, bz - eye.z);
        if (currentHoriz < 0.5) return null;

        double height = Math.max(0.5, target.getBbHeight());
        Vec3 lead = predictLead(eye, base, trackedVelocity(target), height, power);

        // Хитбокс в предсказанной позиции: именно с ним сверяется симуляция полёта.
        AABB predictedBox = target.getBoundingBox().move(
            lead.x - target.getX(), lead.y - target.getY(), lead.z - target.getZ());

        // Порядок перебора: сначала все точки прицеливания настильной дугой, и только потом
        // навесная. Навес на 50 блоках летит ~5 секунд против одной — по живой цели он почти
        // всегда промах, поэтому смена точки на теле важнее смены дуги.
        Solution fallback = null;
        for (int arc = 0; arc < 2; arc++) {
            for (double fraction : AIM_FRACTIONS) {
                Vec3 aimPoint = new Vec3(lead.x, lead.y + height * fraction, lead.z);
                double horiz = Math.max(1.0, Math.hypot(aimPoint.x - eye.x, aimPoint.z - eye.z));
                double dy = aimPoint.y - eye.y;
                double yaw = Math.toDegrees(Math.atan2(-(aimPoint.x - eye.x), aimPoint.z - eye.z));

                double elevation = ArrowPhysics.solveElevations(horiz, dy, power)[arc];
                if (Double.isNaN(elevation)) continue;

                boolean lofted = arc == 1;
                if (!checkTerrain) {
                    return new Solution(target, yaw, -elevation, currentHoriz, horiz,
                        lofted, true, aimPoint);
                }
                if (fallback == null) {
                    fallback = new Solution(target, yaw, -elevation, currentHoriz, horiz,
                        lofted, false, aimPoint);
                }

                // Симуляция ограничена ожидаемым временем полёта: дальше цели проверять нечего,
                // а перекрытая цель иначе каждый тик прогоняла бы полный потолок по всем дугам.
                int flightTicks = ArrowPhysics.flightTicks(horiz, elevation, power);
                if (flightTicks < 0) continue;
                int cap = Math.min(TRACE_MAX_TICKS, flightTicks + TRACE_TICK_MARGIN);

                if (trajectoryReaches(shooter, eye, yaw, -elevation, power, predictedBox, horiz, cap)) {
                    return new Solution(target, yaw, -elevation, currentHoriz, horiz,
                        lofted, true, aimPoint);
                }
            }
        }
        // Чистой траектории нет — отдаём прямое решение с пометкой: игрок увидит, что выстрел
        // упрётся в блок, а доводка всё равно наведётся (стрелять или нет — его решение).
        return fallback;
    }

    /**
     * Позиция цели на момент прилёта стрелы.
     *
     * <p>Две итерации: время полёта зависит от дистанции, а дистанция — от предсказанной
     * позиции. Одной итерации не хватает по быстрой цели на большой дистанции.</p>
     */
    private static Vec3 predictLead(Vec3 eye, Vec3 base, Vec3 velocity, double height, double power) {
        Vec3 lead = base;
        for (int i = 0; i < 2; i++) {
            double horiz = Math.max(1.0, Math.hypot(lead.x - eye.x, lead.z - eye.z));
            double dy = lead.y + height * LEAD_ANCHOR - eye.y;

            double[] arcs = ArrowPhysics.solveElevations(horiz, dy, power);
            double elevation = Double.isNaN(arcs[0]) ? ArrowPhysics.zeroAngle(horiz, power) : arcs[0];

            int ticks = ArrowPhysics.flightTicks(horiz, elevation, power);
            if (ticks < 0) return lead;
            lead = base.add(velocity.scale(ticks));
        }
        return lead;
    }

    /**
     * Скорость цели по разнице позиций за тик.
     *
     * <p>{@code getDeltaMovement()} на клиенте для чужих сущностей почти всегда ноль: сервер
     * шлёт обновления позиции, а не векторы скорости. При {@code hurtTime > 0} дельту портит
     * кнокбэк, поэтому упреждение на это время обнуляется. Вертикаль игнорируется: наземная
     * цель остаётся на земле, а прыжок предсказывать вреднее, чем не предсказывать.</p>
     */
    private static Vec3 trackedVelocity(LivingEntity target) {
        if (target.hurtTime > 0) return Vec3.ZERO;
        return new Vec3(target.getX() - target.xo, 0.0, target.getZ() - target.zo);
    }

    // ── Симуляция полёта ────────────────────────────────────────────────────

    /**
     * Проверяет, доходит ли стрела до хитбокса цели, не встретив блок.
     *
     * <p>Интегрирование в точности повторяет {@link ArrowPhysics#heightAt} (сначала сопротивление
     * и гравитация, потом шаг): иначе метка на экране и проверка рельефа расходились бы, и
     * «чистая» траектория оказывалась бы заблокированной.</p>
     */
    private static boolean trajectoryReaches(LocalPlayer shooter, Vec3 eye,
                                             double yawDeg, double pitchDeg, double power,
                                             AABB targetBox, double targetHoriz, int maxTicks) {
        Level level = shooter.level();
        if (level == null) return false;

        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitch);
        Vec3 direction = new Vec3(
            -Math.sin(yaw) * cosPitch,
            -Math.sin(pitch),
            Math.cos(yaw) * cosPitch);

        Vec3 pos = eye;
        double vx = direction.x * power * ArrowPhysics.FULL_SPEED;
        double vy = direction.y * power * ArrowPhysics.FULL_SPEED;
        double vz = direction.z * power * ArrowPhysics.FULL_SPEED;

        for (int tick = 0; tick < maxTicks; tick++) {
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            vz *= DRAG;
            Vec3 next = pos.add(vx, vy, vz);

            Optional<Vec3> hit = targetBox.clip(pos, next);
            if (hit.isPresent()) return true;

            if (level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, shooter)).getType() != HitResult.Type.MISS) {
                return false;
            }

            pos = next;
            if (Math.hypot(pos.x - eye.x, pos.z - eye.z) > targetHoriz + TRACE_OVERSHOOT) return false;
            if (pos.y - eye.y < -TRACE_DROP_LIMIT) return false;
        }
        return false;
    }

    private static final double DRAG    = 0.99;
    private static final double GRAVITY = 0.05;
    /** Стрела ушла настолько ниже глаз, что до цели уже не дойдёт — обрываем симуляцию. */
    private static final double TRACE_DROP_LIMIT = 256.0;

    // ── Отбор цели ──────────────────────────────────────────────────────────

    /**
     * Ищет цель в конусе вокруг взгляда.
     *
     * <p>Выбирается не просто ближайшая к центру экрана сущность: приоритет у игроков, у целей
     * на прямой видимости и у близких. Иначе захват уходил на мобов и на противника за стеной,
     * стоящего чуть ровнее того, в кого игрок реально целится.</p>
     */
    public static LivingEntity pickTarget(Minecraft mc, LocalPlayer player, double coneRad) {
        if (mc.level == null) return null;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        AABB search = new AABB(eye, eye.add(look.scale(SEARCH_RANGE))).inflate(SEARCH_INFLATE);

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : mc.level.getEntities(player, search)) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isValidTarget(player, living)) continue;

            Vec3 center = entity.getBoundingBox().getCenter();
            Vec3 toTarget = center.subtract(eye);
            double dist = toTarget.length();
            if (dist < 0.5 || dist > SEARCH_RANGE) continue;

            double angle = Math.acos(Mth.clamp(toTarget.scale(1.0 / dist).dot(look), -1.0, 1.0));
            if (angle > coneRad) continue;

            double score = Math.toDegrees(angle) * ANGLE_WEIGHT + dist * DISTANCE_WEIGHT;
            if (!(living instanceof Player)) score += NON_PLAYER_PENALTY;
            if (!hasLineOfSight(player, eye, center)) score += OCCLUDED_PENALTY;

            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    /**
     * Годится ли сущность в качестве цели.
     *
     * <p>Отсекаются союзники по скорборду, невидимые, зрители и креативщики, стойки для брони
     * и мёртвые: захват на любую из них стоит игроку выстрела.</p>
     */
    public static boolean isValidTarget(LocalPlayer player, LivingEntity entity) {
        if (entity == player || !entity.isAlive() || entity.isDeadOrDying()) return false;
        if (entity.isSpectator() || entity instanceof ArmorStand) return false;
        if (entity.isInvisibleTo(player)) return false;
        if (player.isAlliedTo(entity)) return false;
        return !(entity instanceof Player other) || (!other.isCreative() && !other.isSpectator());
    }

    /** Угол между взглядом игрока и направлением на цель, в градусах. */
    public static double angleToTarget(LocalPlayer player, LivingEntity target) {
        Vec3 eye = player.getEyePosition();
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(eye);
        double len = toTarget.length();
        if (len < 1.0e-6) return 0.0;
        double dot = toTarget.scale(1.0 / len).dot(player.getLookAngle());
        return Math.toDegrees(Math.acos(Mth.clamp(dot, -1.0, 1.0)));
    }

    private static boolean hasLineOfSight(LocalPlayer player, Vec3 eye, Vec3 point) {
        Level level = player.level();
        if (level == null) return false;
        return level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }
}
