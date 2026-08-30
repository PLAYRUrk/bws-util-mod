package ru.pleeey.bwsutil.client.aim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Модуль наведения AUTO-режима.
 *
 * <p>Оверлей прицела не знает, как считается упреждение: он отдаёт модулю тик и кадр, а тот сам
 * решает, что показать. Благодаря этому расчётную часть можно заменить целиком, не трогая
 * прицел, — {@link AimModules} подставляет ту реализацию, которая есть в сборке.</p>
 */
public interface AimModule {

    /**
     * Всё, что нужно модулю, чтобы поставить метку на экран.
     *
     * @param cx           центр экрана по горизонтали
     * @param cy           центр экрана по вертикали
     * @param pixPerRad    пикселей на радиан при текущем FOV
     * @param zeroAngleDeg угол пристрелки: основной крест смещён на него вниз от центра
     * @param charge       натяжение лука, 0..1
     */
    record LeadContext(int cx, int cy, double pixPerRad, double zeroAngleDeg,
                       float charge, float partialTick, int color, int outlineColor) {}

    /** Ровно один раз за игровой тик, пока в AUTO-режиме натянут лук. */
    void tick(Minecraft mc, LocalPlayer player);

    /** Режим сменился, натяжение отпущено или мир выгружен. */
    void reset();

    /** Рисует метку упреждения поверх прицела. */
    void renderLead(GuiGraphics g, Minecraft mc, LocalPlayer player, LeadContext ctx);

    /** Модуль сейчас сам управляет камерой — для индикатора режима. */
    default boolean isSteering() { return false; }

    /**
     * Виджеты модуля для экрана настроек.
     *
     * <p>Пусто, если настраивать нечего: тогда экран настроек просто не покажет ничего лишнего.</p>
     */
    default List<AbstractWidget> settingsWidgets(Screen parent, int x, int y, int width, int row) {
        return List.of();
    }
}
