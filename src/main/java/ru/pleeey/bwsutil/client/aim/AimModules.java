package ru.pleeey.bwsutil.client.aim;

/**
 * Выбор модуля наведения при старте клиента.
 *
 * <p>Расширенный модуль лежит в отдельном каталоге исходников и в сборку попадает только если
 * этот каталог есть. Публичный код на него не ссылается — иначе он не компилировался бы без
 * него, — поэтому связь идёт по имени класса, а при его отсутствии подставляется
 * {@link SimpleAimModule}.</p>
 */
public final class AimModules {

    private static final String EXTENDED = "ru.pleeey.bwsutil.client.aim.full.FullAimModule";

    private static AimModule instance;

    private AimModules() {}

    public static AimModule get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static AimModule load() {
        try {
            return Class.forName(EXTENDED)
                .asSubclass(AimModule.class)
                .getDeclaredConstructor()
                .newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return new SimpleAimModule();
        }
    }
}
