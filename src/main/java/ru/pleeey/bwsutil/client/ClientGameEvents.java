package ru.pleeey.bwsutil.client;

import ru.pleeey.bwsutil.BwsUtilMod;
import ru.pleeey.bwsutil.client.keybind.ScopeKeys;
import ru.pleeey.bwsutil.client.overlay.BedWarsOverlay;
import ru.pleeey.bwsutil.client.overlay.ScopeOverlay;
import ru.pleeey.bwsutil.client.screen.ScopeConfigScreen;
import ru.pleeey.bwsutil.config.ScopeConfig;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BwsUtilMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientGameEvents {

    private ClientGameEvents() {}

    @SubscribeEvent
    public static void onClientTickPost(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (ScopeKeys.OPEN_CONFIG.consumeClick()) {
            mc.setScreen(new ScopeConfigScreen());
        }
        if (ScopeKeys.SCOPE_TOGGLE.consumeClick()) {
            ScopeOverlay.toggleEnabled();
        }
        if (ScopeKeys.MODE_TOGGLE.consumeClick()) {
            ScopeOverlay.toggleMode();
        }
        while (ScopeKeys.ZERO_INCREASE.consumeClick()) {
            ScopeConfig.ZERO_DISTANCE.set(Math.min(200, ScopeConfig.ZERO_DISTANCE.get() + 5));
        }
        while (ScopeKeys.ZERO_DECREASE.consumeClick()) {
            ScopeConfig.ZERO_DISTANCE.set(Math.max(10, ScopeConfig.ZERO_DISTANCE.get() - 5));
        }
        if (ScopeKeys.BEDWARS_TOGGLE.consumeClick()) {
            BedWarsOverlay.toggleEnabled();
        }

        // Захват цели — ровно один раз за тик, независимо от числа render-вызовов за кадр
        ScopeOverlay.tick(mc, mc.player);
        // Сканирование кроватей BedWars раз в 3 секунды
        BedWarsOverlay.tick(mc);
    }
}
