package ru.pleeey.bwsutil.mixin;

import ru.pleeey.bwsutil.client.overlay.BedWarsOverlay;
import ru.pleeey.bwsutil.client.overlay.ScopeOverlay;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    @Shadow @Final
    GuiRenderState renderState;

    @Inject(method = "render", at = @At("HEAD"))
    private void bowScope$addOverlay(GpuBufferSlice slice, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (mc.options.hideGui) return;

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        GuiGraphics g = new GuiGraphics(mc, this.renderState, w, h);
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        ScopeOverlay.render(g, pt);
        BedWarsOverlay.render(g, pt);
    }
}
