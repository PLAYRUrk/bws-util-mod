package ru.pleeey.bwsutil.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ScopeConfig {

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue     ZERO_DISTANCE;
    public static final ForgeConfigSpec.IntValue     RETICLE_COLOR;
    public static final ForgeConfigSpec.BooleanValue SHOW_ONLY_WHILE_DRAWING;
    public static final ForgeConfigSpec.BooleanValue SHOW_STADIA_MARKS;
    public static final ForgeConfigSpec.BooleanValue SHOW_RANGEFINDER;
    public static final ForgeConfigSpec.BooleanValue SHOW_CHARGE_BAR;

    static {
        BUILDER.comment("BWS Util client settings");

        ZERO_DISTANCE = BUILDER
            .comment("Zeroing distance in blocks (arrow will cross line-of-sight at this distance)")
            .defineInRange("zero_distance", 50, 10, 200);

        RETICLE_COLOR = BUILDER
            .comment("Reticle color in ARGB hex (default 0xFFD4A843 = warm amber)")
            .defineInRange("reticle_color", 0xFFD4A843, Integer.MIN_VALUE, Integer.MAX_VALUE);

        SHOW_ONLY_WHILE_DRAWING = BUILDER
            .comment("If true, scope only appears while the bow is being drawn")
            .define("show_only_while_drawing", false);

        SHOW_STADIA_MARKS = BUILDER
            .comment("Show range stadia marks below the main crosshair")
            .define("show_stadia_marks", true);

        SHOW_RANGEFINDER = BUILDER
            .comment("Show real-time distance to the aimed target")
            .define("show_rangefinder", true);

        SHOW_CHARGE_BAR = BUILDER
            .comment("Show bow charge progress bar")
            .define("show_charge_bar", false);

        SPEC = BUILDER.build();
    }

    private ScopeConfig() {}
}
