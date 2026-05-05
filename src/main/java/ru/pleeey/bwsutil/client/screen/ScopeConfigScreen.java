package ru.pleeey.bwsutil.client.screen;

import ru.pleeey.bwsutil.config.ScopeConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ScopeConfigScreen extends Screen {

    private static final Component TITLE = Component.literal("BWS Util — Scope Settings");

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
        int midX   = width / 2;
        int startY = height / 4;
        int row    = 26;

        addRenderableWidget(new ZeroDistanceSlider(midX - 100, startY, 200, 20));

        int btnW   = 56;
        int colorY = startY + row;
        for (int i = 0; i < COLOR_PRESETS.length; i++) {
            final int presetColor = COLOR_PRESETS[i];
            final String name     = COLOR_NAMES[i];
            int bx = midX - (COLOR_PRESETS.length * btnW / 2) + i * btnW;
            addRenderableWidget(Button.builder(Component.literal(name), btn ->
                ScopeConfig.RETICLE_COLOR.set(presetColor))
                .bounds(bx, colorY, btnW - 2, 18).build());
        }

        int chkY = startY + row * 2 + 4;

        addRenderableWidget(Checkbox.builder(
            Component.literal("Show only while drawing"), this.font)
            .pos(midX - 120, chkY)
            .selected(ScopeConfig.SHOW_ONLY_WHILE_DRAWING.get())
            .onValueChange((box, val) -> ScopeConfig.SHOW_ONLY_WHILE_DRAWING.set(val))
            .build());

        addRenderableWidget(Checkbox.builder(
            Component.literal("Show range markers"), this.font)
            .pos(midX - 120, chkY + row)
            .selected(ScopeConfig.SHOW_STADIA_MARKS.get())
            .onValueChange((box, val) -> ScopeConfig.SHOW_STADIA_MARKS.set(val))
            .build());

        addRenderableWidget(Checkbox.builder(
            Component.literal("Show rangefinder"), this.font)
            .pos(midX - 120, chkY + row * 2)
            .selected(ScopeConfig.SHOW_RANGEFINDER.get())
            .onValueChange((box, val) -> ScopeConfig.SHOW_RANGEFINDER.set(val))
            .build());

        addRenderableWidget(Checkbox.builder(
            Component.literal("Show charge bar"), this.font)
            .pos(midX - 120, chkY + row * 3)
            .selected(ScopeConfig.SHOW_CHARGE_BAR.get())
            .onValueChange((box, val) -> ScopeConfig.SHOW_CHARGE_BAR.set(val))
            .build());

        addRenderableWidget(Button.builder(Component.literal("Close"), btn -> onClose())
            .bounds(midX - 50, height - 30, 100, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, TITLE, width / 2, height / 4 - 20, 0xFFFFFFFF);
        g.drawString(font, "Zeroing Distance:", width / 2 - 100, height / 4 - 8, 0xFFCCCCCC);
        g.drawString(font, "Reticle Color:", width / 2 - 100, height / 4 + 22, 0xFFCCCCCC);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }

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
}
