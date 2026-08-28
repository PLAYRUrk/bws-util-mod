package ru.pleeey.bwsutil.client.screen;

import ru.pleeey.bwsutil.config.ScopeConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ScopeConfigScreen extends Screen {

    private static final Component TITLE = Component.literal("BWS Util — General Settings");

    private static final int[] COLOR_PRESETS = {
        0xFF44FF44, 0xFFFFFFFF, 0xFFFF4444, 0xFF44FFFF, 0xFFFFFF44, 0xFFFF44FF,
    };
    private static final String[] COLOR_NAMES = {
        "Green", "White", "Red", "Cyan", "Yellow", "Magenta"
    };

    public ScopeConfigScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        int midX = width / 2;
        int leftX = midX - 155;
        int rightX = midX + 5;
        // Шаг и старт подобраны так, чтобы обе колонки помещались по высоте после добавления
        // авто-доводки: в левой теперь 6 тумблеров + слайдер, в правой 7 тумблеров + 2 слайдера.
        int startY = 38;
        int row = 21;
        int colW = 150;
        int btnW = 48;

        addRenderableWidget(new ZeroDistanceSlider(leftX, startY + 16, colW, 20));

        int colorY = startY + 42;
        for (int i = 0; i < COLOR_PRESETS.length; i++) {
            final int presetColor = COLOR_PRESETS[i];
            final String name     = COLOR_NAMES[i];
            int bx = leftX + (i % 3) * (btnW + 2);
            int by = colorY + (i / 3) * 20;
            addRenderableWidget(Button.builder(Component.literal(name), btn ->
                ScopeConfig.RETICLE_COLOR.set(presetColor))
                .bounds(bx, by, btnW, 18).build());
        }

        int tY = startY + 92;
        addRenderableWidget(toggleButton(leftX, tY, colW, "Show only while drawing",
            ScopeConfig.SHOW_ONLY_WHILE_DRAWING::get, ScopeConfig.SHOW_ONLY_WHILE_DRAWING::set));
        addRenderableWidget(toggleButton(leftX, tY + row, colW, "Show range markers",
            ScopeConfig.SHOW_STADIA_MARKS::get, ScopeConfig.SHOW_STADIA_MARKS::set));
        addRenderableWidget(toggleButton(leftX, tY + row * 2, colW, "Show rangefinder",
            ScopeConfig.SHOW_RANGEFINDER::get, ScopeConfig.SHOW_RANGEFINDER::set));
        addRenderableWidget(toggleButton(leftX, tY + row * 3, colW, "Show charge bar",
            ScopeConfig.SHOW_CHARGE_BAR::get, ScopeConfig.SHOW_CHARGE_BAR::set));
        addRenderableWidget(toggleButton(leftX, tY + row * 4, colW, "Auto-aim (AUTO mode)",
            ScopeConfig.AUTO_AIM_ENABLED::get, ScopeConfig.AUTO_AIM_ENABLED::set));
        addRenderableWidget(new RangeSlider(leftX, tY + row * 5, colW, 20,
            "Auto-aim strength", ScopeConfig.AUTO_AIM_STRENGTH, 5, 100));

        int rY = startY + 16;
        addRenderableWidget(toggleButton(rightX, rY, colW, "Fireball Threat",
            ScopeConfig.FIREBALL_THREAT_ENABLED::get, ScopeConfig.FIREBALL_THREAT_ENABLED::set));
        addRenderableWidget(toggleButton(rightX, rY + row, colW, "Bridge Fight Helper",
            ScopeConfig.BRIDGE_HELPER_ENABLED::get, ScopeConfig.BRIDGE_HELPER_ENABLED::set));
        addRenderableWidget(toggleButton(rightX, rY + row * 2, colW, "Warning sounds (master)",
            ScopeConfig.WARNING_SOUND_ENABLED::get, ScopeConfig.WARNING_SOUND_ENABLED::set));
        addRenderableWidget(toggleButton(rightX, rY + row * 3, colW, "Fireball warning sound",
            ScopeConfig.FIREBALL_WARNING_SOUND::get, ScopeConfig.FIREBALL_WARNING_SOUND::set));
        addRenderableWidget(toggleButton(rightX, rY + row * 4, colW, "Void warning sound",
            ScopeConfig.VOID_WARNING_SOUND::get, ScopeConfig.VOID_WARNING_SOUND::set));
        addRenderableWidget(toggleButton(rightX, rY + row * 5, colW, "Match log (chat events)",
            ScopeConfig.MATCH_LOG_ENABLED::get, ScopeConfig.MATCH_LOG_ENABLED::set));
        addRenderableWidget(toggleButton(rightX, rY + row * 6, colW, "Emergency block swap",
            ScopeConfig.EMERGENCY_BLOCK_SWAP_ENABLED::get, ScopeConfig.EMERGENCY_BLOCK_SWAP_ENABLED::set));

        addRenderableWidget(new RangeSlider(rightX, rY + row * 7, colW, 20,
            "Fireball volume", ScopeConfig.FIREBALL_WARNING_VOLUME, 0, 100));
        addRenderableWidget(new RangeSlider(rightX, rY + row * 8 + 2, colW, 20,
            "Void volume", ScopeConfig.VOID_WARNING_VOLUME, 0, 100));

        addRenderableWidget(Button.builder(Component.literal("Close"), btn -> onClose())
            .bounds(midX - 50, height - 26, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Avoid vanilla blur call here (Forge 1.21.11 can invoke blur earlier this frame).
        g.fill(0, 0, width, height, 0xCC101016);
        int midX = width / 2;
        int leftX = midX - 155;
        int rightX = midX + 5;
        int startY = 38;
        g.drawCenteredString(font, TITLE, width / 2, 16, 0xFFFFFFFF);
        g.drawString(font, "Scope", leftX, startY, 0xFFCCCCCC);
        g.drawString(font, "Zeroing Distance:", leftX, startY + 8, 0xFF9F9F9F);
        g.drawString(font, "Reticle Color:", leftX, startY + 34, 0xFF9F9F9F);
        g.drawString(font, "BedWars helper + sound", rightX, startY, 0xFFCCCCCC);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        // ConfigValue.set only updates the in-memory config; without this the settings changed
        // here are lost when the game exits.
        ScopeConfig.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private interface BoolSetter { void set(boolean value); }
    private interface BoolGetter { boolean get(); }

    private Button toggleButton(int x, int y, int w, String label, BoolGetter getter, BoolSetter setter) {
        return Button.builder(toggleLabel(label, getter.get()), btn -> {
            boolean next = !getter.get();
            setter.set(next);
            btn.setMessage(toggleLabel(label, next));
        }).bounds(x, y, w, 20).build();
    }

    private static Component toggleLabel(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "ON" : "OFF"));
    }

    private static final class ZeroDistanceSlider extends AbstractSliderButton {

        ZeroDistanceSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), toSliderValue(ScopeConfig.ZERO_DISTANCE.get()));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Zero: " + toBlocks(value) + " blocks"));
        }

        @Override
        protected void applyValue() {
            ScopeConfig.ZERO_DISTANCE.set(toBlocks(value));
        }

        private static int toBlocks(double v) {
            return (int) Math.round(v * 190 + 10);
        }

        private static double toSliderValue(int blocks) {
            return (blocks - 10.0) / 190.0;
        }
    }

    /**
     * Слайдер для целочисленной настройки в процентах.
     *
     * <p>Границы задаются явно: {@code defineInRange} не проверяет значение в {@code set()},
     * поэтому слайдер обязан сам держаться в допустимом диапазоне ключа.</p>
     */
    private static final class RangeSlider extends AbstractSliderButton {
        private final String label;
        private final net.minecraftforge.common.ForgeConfigSpec.IntValue configValue;
        private final int min;
        private final int max;

        RangeSlider(int x, int y, int w, int h, String label,
                    net.minecraftforge.common.ForgeConfigSpec.IntValue configValue,
                    int min, int max) {
            super(x, y, w, h, Component.empty(), (configValue.get() - min) / (double) (max - min));
            this.label = label;
            this.configValue = configValue;
            this.min = min;
            this.max = max;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + toValue(value) + "%"));
        }

        @Override
        protected void applyValue() {
            configValue.set(toValue(value));
        }

        private int toValue(double v) {
            return Math.max(min, Math.min(max, (int) Math.round(min + v * (max - min))));
        }
    }
}
