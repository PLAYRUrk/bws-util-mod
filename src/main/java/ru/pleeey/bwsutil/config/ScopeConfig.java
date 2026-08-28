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
    public static final ForgeConfigSpec.BooleanValue FIREBALL_THREAT_ENABLED;
    public static final ForgeConfigSpec.BooleanValue BRIDGE_HELPER_ENABLED;
    public static final ForgeConfigSpec.BooleanValue WARNING_SOUND_ENABLED;
    public static final ForgeConfigSpec.BooleanValue FIREBALL_WARNING_SOUND;
    public static final ForgeConfigSpec.BooleanValue VOID_WARNING_SOUND;
    public static final ForgeConfigSpec.IntValue FIREBALL_WARNING_VOLUME;
    public static final ForgeConfigSpec.IntValue VOID_WARNING_VOLUME;
    public static final ForgeConfigSpec.BooleanValue EMERGENCY_BLOCK_SWAP_ENABLED;
    public static final ForgeConfigSpec.BooleanValue MATCH_LOG_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_AIM_ENABLED;
    public static final ForgeConfigSpec.IntValue     AUTO_AIM_STRENGTH;

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

        FIREBALL_THREAT_ENABLED = BUILDER
            .comment("Show fireball threat alert in BedWars overlay")
            .define("fireball_threat_enabled", true);

        BRIDGE_HELPER_ENABLED = BUILDER
            .comment("Show bridge/void risk helper in BedWars overlay")
            .define("bridge_helper_enabled", true);

        WARNING_SOUND_ENABLED = BUILDER
            .comment("Enable warning sounds for BedWars helper alerts")
            .define("warning_sound_enabled", true);

        FIREBALL_WARNING_SOUND = BUILDER
            .comment("Play warning sound for fireball threat")
            .define("fireball_warning_sound", true);

        VOID_WARNING_SOUND = BUILDER
            .comment("Play warning sound for bridge/void danger")
            .define("void_warning_sound", true);

        FIREBALL_WARNING_VOLUME = BUILDER
            .comment("Fireball warning sound volume in percent")
            .defineInRange("fireball_warning_volume", 75, 0, 100);

        VOID_WARNING_VOLUME = BUILDER
            .comment("Bridge/void warning sound volume in percent")
            .defineInRange("void_warning_volume", 70, 0, 100);

        EMERGENCY_BLOCK_SWAP_ENABLED = BUILDER
            .comment("Auto-select a hotbar block slot when a fatal fall or void drop is detected")
            .define("emergency_block_swap_enabled", true);

        MATCH_LOG_ENABLED = BUILDER
            .comment("Track BedWars chat events (bed destruction, team elimination) and show a match log")
            .define("match_log_enabled", true);

        AUTO_AIM_ENABLED = BUILDER
            .comment("AUTO scope mode: steer the view toward the ballistic lead point while drawing the bow")
            .define("auto_aim_enabled", false);

        AUTO_AIM_STRENGTH = BUILDER
            .comment("Auto-aim pull per tick in percent of the remaining error (100 = instant snap)")
            .defineInRange("auto_aim_strength", 35, 5, 100);

        SPEC = BUILDER.build();
    }

    /**
     * Forces a write of the in-memory config to disk.
     *
     * <p>{@code ConfigValue.set} only mutates the loaded config; without this call the changes
     * made through the settings screen or the {@code +}/{@code -} keys are lost on exit.</p>
     */
    public static void save() {
        if (SPEC.isLoaded()) SPEC.save();
    }

    private ScopeConfig() {}
}
