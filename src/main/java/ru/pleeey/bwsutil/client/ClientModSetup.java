package ru.pleeey.bwsutil.client;

import ru.pleeey.bwsutil.BwsUtilMod;
import ru.pleeey.bwsutil.client.autoclicker.AutoclickerBridgeClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = BwsUtilMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModSetup {

    private ClientModSetup() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        AutoclickerBridgeClient.start();
    }
}
