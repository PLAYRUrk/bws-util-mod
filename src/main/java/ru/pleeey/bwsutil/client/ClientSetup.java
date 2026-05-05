package ru.pleeey.bwsutil.client;

import ru.pleeey.bwsutil.BwsUtilMod;
import ru.pleeey.bwsutil.client.keybind.ScopeKeys;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BwsUtilMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientSetup {

    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ScopeKeys.OPEN_CONFIG);
        event.register(ScopeKeys.ZERO_INCREASE);
        event.register(ScopeKeys.ZERO_DECREASE);
        event.register(ScopeKeys.MODE_TOGGLE);
        event.register(ScopeKeys.SCOPE_TOGGLE);
        event.register(ScopeKeys.BEDWARS_TOGGLE);
    }
}
