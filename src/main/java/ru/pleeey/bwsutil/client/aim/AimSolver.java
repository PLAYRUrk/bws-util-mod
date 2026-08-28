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

    /** Точки прицеливания по высоте хитбокса: грудь, голова, ноги. */
    private static final double[] VERTICAL_FRACTIONS = { 0.62, 0.88, 0.35 };

    /**
     * Боковые смещения точек прицеливания в долях полуширины хитбокса.
     *
     * <p>Без них все кандидаты лежат на одной вертикальной оси, и цель, высунувшаяся из-за
     * укрытия боком, считается полностью закрытой: попасть по открытому краю корпуса физически
     * можно, а решение возвращалось «в стену». Крайние доли меньше единицы — это отступ от
     * грани, чтобы стрела не чиркала по ней.</p>
     */
    private static final double[] LATERAL_FRACTIONS = { 0.0, 0.55, -0.55, 0.85, -0.85 };

    /** Штраф за выбор точки не по центру корпуса — чем ближе к краю, тем меньше запас. */
    private static final double LATERAL_PENALTY = 2.0;
    /** Штраф за высоту: грудь, голова, ноги. */
    private static final double[] VERTICAL_PENALTY = { 0.0, 0.5, 0.8 };

    /** Порядок обхода сетки: от самой надёжной точки к краевым. */
    private static final int[] CANDIDATE_ORDER;

    static {
        int count = VERTICAL_FRACTIONS.length * LATERAL_FRACTIONS.length;
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(AimSolver::candidateScore));

        CANDIDATE_ORDER = new int[count];
        for (int i = 0; i < count; i++) CANDIDATE_ORDER[i] = order[i];
    }

    private static double candidateScore(int index) {
        int vertical = index / LATERAL_FRACTIONS.length;
        int lateral  = index % LATERAL_FRACTIONS.length;
        return Math.abs(LATERAL_FRACTIONS[lateral]) * LATERAL_PENALTY + VERTICAL_PENALTY[vertical];
    }

    /** «Предыдущего выбора нет» для гистерезиса точки прицеливания. */
    public static final int NO_CANDIDATE = -1;

    /** Доля высоты, по которой считается упреждение (центр масс). */
    private static final double LEAD_ANCHOR = 0.62;
    /** Итерации схождения упреждения: время полёта зависит от дистанции, дистанция — от него. */
    private static final int LEAD_ITERATIONS = 3;

    /** Гравитация и затухание вертикальной скорости игрока — для предсказания падающей цели. */
    private static final double PLAYER_GRAVITY = 0.08;
    private static final double PLAYER_DRAG    = 0.98;
    /** Насколько глубоко искать землю под предсказанной позицией. */
    private static final double GROUND_PROBE_DEPTH = 12.0;

    /** Сколько траекторий проверять настильным проходом, прежде чем перейти к навесу. */
    private static final int FLAT_TRACE_BUDGET = 4;

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
     * @param aimIndex           индекс точки в сетке — возвращается в следующий тик, чтобы
     *                           выбор не прыгал по телу цели
     */
    public record Solution(LivingEntity target, double yaw, double pitch,
                           double horizDist, double predictedHorizDist,
                           boolean lofted, boolean clear, Vec3 aimPoint, int aimIndex) {}

    // ── Решение ─────────────────────────────────────────────────────────────

    /**
     * Считает решение по цели.
     *
     * <p>Порядок работы: упреждение по усреднённой скорости → сетка точек прицеливания на
     * предсказанном хитбоксе → отсев по прямой видимости → симуляция полёта. Настильный проход
     * идёт первым и по всей сетке, навесной — только по центральной колонке и только если
     * настильно не вышло.</p>
     *
     * @param charge         натяжение лука (0..1), оно же множитель начальной скорости
     * @param tracker        накопитель скорости цели, обновлённый в этом тике
     * @param preferredIndex точка, выбранная в прошлом тике, или {@link #NO_CANDIDATE}
     * @return решение или {@code null}, если цель недостижима на текущем натяжении
     */
    public static Solution solve(LocalPlayer shooter, LivingEntity target, float charge,
                                 TargetTracker tracker, int preferredIndex) {
        Level level = shooter.level();
        if (charge <= 0.01f || level == null) return null;
        double power = charge;

        Vec3 eye = shooter.getEyePosition();
        Vec3 base = target.position();

        double currentHoriz = Math.hypot(base.x - eye.x, base.z - eye.z);
        if (currentHoriz < 0.5) return null;

        double height = Math.max(0.5, target.getBbHeight());
        double halfWidth = Math.max(0.15, target.getBbWidth() / 2.0);

        Vec3 lead = predictLead(level, shooter, eye, base, tracker, height, power);

        // Хитбокс в предсказанной позиции: именно с ним сверяется симуляция полёта.
        AABB predictedBox = target.getBoundingBox().move(
            lead.x - target.getX(), lead.y - target.getY(), lead.z - target.getZ());

        // Горизонтальная нормаль к направлению выстрела — вдоль неё разносятся боковые точки.
        double toX = lead.x - eye.x;
        double toZ = lead.z - eye.z;
        double toLen = Math.max(1.0e-6, Math.hypot(toX, toZ));
        Vec3 right = new Vec3(-toZ / toLen, 0.0, toX / toLen);

        Solution fallback = null;
        int traced = 0;

        // ── Настильный проход ───────────────────────────────────────────────────────────
        // Точка прошлого тика проверяется первой: пока по ней можно попасть, она и остаётся.
        // Иначе выбор прыгает по телу цели каждый тик, а вместе с ним скачет и угол доводки —
        // ширина хитбокса игрока это ~7° на дистанции пяти блоков.
        if (preferredIndex >= 0 && preferredIndex < CANDIDATE_ORDER.length) {
            Solution kept = evaluate(shooter, level, eye, target, lead, right, height, halfWidth,
                power, predictedBox, currentHoriz, preferredIndex, false, true);
            if (kept != null) {
                if (kept.clear()) return kept;
                fallback = kept;
                traced++;
            }
        }

        for (int index : CANDIDATE_ORDER) {
            if (index == preferredIndex) continue;
            if (traced >= FLAT_TRACE_BUDGET) break;

            Solution candidate = evaluate(shooter, level, eye, target, lead, right, height,
                halfWidth, power, predictedBox, currentHoriz, index, false, true);
            if (candidate == null) continue;
            if (candidate.clear()) return candidate;
            if (fallback == null) fallback = candidate;
            traced++;
        }

        // ── Навесной проход: центральная колонка, без отсева по видимости ────────────────
        // Навес существует ровно для случая, когда прямой видимости нет ни у одной точки:
        // прогонять его через тот же фильтр — значит выключить его полностью.
        for (int vertical = 0; vertical < VERTICAL_FRACTIONS.length; vertical++) {
            int index = vertical * LATERAL_FRACTIONS.length; // боковое смещение 0
            Solution candidate = evaluate(shooter, level, eye, target, lead, right, height,
                halfWidth, power, predictedBox, currentHoriz, index, true, false);
            if (candidate == null) continue;
            if (candidate.clear()) return candidate;
            if (fallback == null) fallback = candidate;
        }

        // Чистой траектории нет. Решение отдаётся с пометкой clear = false; наводиться по нему
        // в стену смысла нет, и что делать дальше — решает вызывающий код.
        return fallback;
    }

    /**
     * Строит и проверяет одного кандидата сетки.
     *
     * @param lofted     брать навесную дугу вместо настильной
     * @param requireLos отсеивать точку, если до неё нет прямой видимости
     * @return кандидат (поле {@code clear} — результат симуляции) или {@code null}, если точка
     *         отсеяна либо такой дуги не существует
     */
    private static Solution evaluate(LocalPlayer shooter, Level level, Vec3 eye,
                                     LivingEntity target, Vec3 lead, Vec3 right,
                                     double height, double halfWidth, double power,
                                     AABB predictedBox, double currentHoriz,
                                     int index, boolean lofted, boolean requireLos) {
        int verticalIndex = index / LATERAL_FRACTIONS.length;
        int lateralIndex  = index % LATERAL_FRACTIONS.length;
        double side = LATERAL_FRACTIONS[lateralIndex] * halfWidth;

        Vec3 aimPoint = new Vec3(
            lead.x + right.x * side,
            lead.y + height * VERTICAL_FRACTIONS[verticalIndex],
            lead.z + right.z * side);

        // Дешёвый отсев: один луч против полной симуляции полёта.
        if (requireLos && !hasLineOfSight(shooter, eye, aimPoint)) return null;

        double horiz = Math.max(1.0, Math.hypot(aimPoint.x - eye.x, aimPoint.z - eye.z));
        double dy = aimPoint.y - eye.y;
        double yaw = Math.toDegrees(Math.atan2(-(aimPoint.x - eye.x), aimPoint.z - eye.z));

        double elevation = ArrowPhysics.solveElevations(horiz, dy, power)[lofted ? 1 : 0];
        if (Double.isNaN(elevation)) return null;

        // Симуляция ограничена ожидаемым временем полёта: дальше цели проверять нечего.
        int flightTicks = ArrowPhysics.flightTicks(horiz, elevation, power);
        if (flightTicks < 0) return null;
        int cap = Math.min(TRACE_MAX_TICKS, flightTicks + TRACE_TICK_MARGIN);

        boolean clear = trajectoryReaches(shooter, eye, yaw, -elevation, power,
            predictedBox, horiz, cap);
        return new Solution(target, yaw, -elevation, currentHoriz, horiz,
            lofted, clear, aimPoint, index);
    }

    // ── Упреждение ──────────────────────────────────────────────────────────

    /**
     * Позиция цели на момент прилёта стрелы.
     *
     * <p>Итерации нужны потому, что время полёта зависит от дистанции, а дистанция — от
     * предсказанной позиции. Кроме упреждения по горизонтали здесь два ограничителя: падение
     * цели и упор в стену. Оба убирают систематический промах, а не шум.</p>
     */
    private static Vec3 predictLead(Level level, LocalPlayer shooter, Vec3 eye, Vec3 base,
                                    TargetTracker tracker, double height, double power) {
        Vec3 velocity = tracker.velocity();
        double vy0 = tracker.isGrounded() ? 0.0 : velocity.y;

        Vec3 lead = base;
        for (int i = 0; i < LEAD_ITERATIONS; i++) {
            double horiz = Math.max(1.0, Math.hypot(lead.x - eye.x, lead.z - eye.z));
            double dy = lead.y + height * LEAD_ANCHOR - eye.y;

            double[] arcs = ArrowPhysics.solveElevations(horiz, dy, power);
            double elevation = Double.isNaN(arcs[0]) ? ArrowPhysics.zeroAngle(horiz, power) : arcs[0];

            int ticks = ArrowPhysics.flightTicks(horiz, elevation, power);
            if (ticks < 0) return lead;

            lead = new Vec3(
                base.x + velocity.x * ticks,
                base.y + verticalOffset(vy0, ticks),
                base.z + velocity.z * ticks);
        }

        lead = clampToWall(level, shooter, base, lead, height);
        return clampToGround(level, shooter, base, lead);
    }

    /**
     * Насколько цель опустится за {@code ticks} тиков свободного падения.
     *
     * <p>Считается по тиковой модели игрока, а не формулой: с затуханием скорости замкнутого
     * выражения нет. Для цели на земле вызывается с нулевой скоростью и возвращает ноль —
     * прыжок предсказывать вреднее, чем не предсказывать.</p>
     */
    private static double verticalOffset(double vy0, int ticks) {
        if (vy0 == 0.0) return 0.0;
        double vy = vy0;
        double offset = 0.0;
        for (int i = 0; i < ticks; i++) {
            vy = (vy - PLAYER_GRAVITY) * PLAYER_DRAG;
            offset += vy;
            if (offset < -TRACE_DROP_LIMIT) break;
        }
        return offset;
    }

    /**
     * Обрезает упреждение по первому блоку на пути цели.
     *
     * <p>Иначе противник, бегущий вдоль стены, «уводит» точку прицеливания сквозь неё, хотя сам
     * там остановится.</p>
     */
    private static Vec3 clampToWall(Level level, LocalPlayer shooter, Vec3 base, Vec3 lead,
                                    double height) {
        double midY = base.y + height * 0.5;
        Vec3 from = new Vec3(base.x, midY, base.z);
        Vec3 to   = new Vec3(lead.x, midY, lead.z);
        if (from.distanceToSqr(to) < 1.0e-6) return lead;

        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, shooter));
        if (hit.getType() == HitResult.Type.MISS) return lead;

        Vec3 stop = hit.getLocation();
        return new Vec3(stop.x, lead.y, stop.z);
    }

    /** Не даёт предсказанию падения уехать под пол. */
    private static Vec3 clampToGround(Level level, LocalPlayer shooter, Vec3 base, Vec3 lead) {
        if (lead.y >= base.y) return lead;

        Vec3 from = new Vec3(lead.x, base.y + 0.5, lead.z);
        Vec3 to   = new Vec3(lead.x, base.y - GROUND_PROBE_DEPTH, lead.z);
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, shooter));
        if (hit.getType() == HitResult.Type.MISS) return lead;

        double groundY = hit.getLocation().y;
        return lead.y < groundY ? new Vec3(lead.x, groundY, lead.z) : lead;
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
