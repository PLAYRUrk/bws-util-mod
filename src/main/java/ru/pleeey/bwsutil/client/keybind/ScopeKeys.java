package ru.pleeey.bwsutil.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class ScopeKeys {

    public static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bws_util", "key_category"));

    /** Открыть экран настройки (Z). */
    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
        "key.bws_util.open_config",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_Z,
        CATEGORY
    );

    /** Увеличить дистанцию пристрелки (=). */
    public static final KeyMapping ZERO_INCREASE = new KeyMapping(
        "key.bws_util.zero_increase",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_EQUAL,
        CATEGORY
    );

    /** Уменьшить дистанцию пристрелки (-). */
    public static final KeyMapping ZERO_DECREASE = new KeyMapping(
        "key.bws_util.zero_decrease",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_MINUS,
        CATEGORY
    );

    /** Переключить режим прицела MANUAL / AUTO (M). */
    public static final KeyMapping MODE_TOGGLE = new KeyMapping(
        "key.bws_util.mode_toggle",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_M,
        CATEGORY
    );

    /** Включить / выключить прицел полностью (O). */
    public static final KeyMapping SCOPE_TOGGLE = new KeyMapping(
        "key.bws_util.scope_toggle",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_O,
        CATEGORY
    );

    /** Включить / выключить тактический оверлей BedWars (B). */
    public static final KeyMapping BEDWARS_TOGGLE = new KeyMapping(
        "key.bws_util.bedwars_toggle",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_B,
        CATEGORY
    );

    private ScopeKeys() {}
}
