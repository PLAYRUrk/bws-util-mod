package ru.pleeey.bwsutil;

import ru.pleeey.bwsutil.config.ScopeConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BwsUtilMod.MOD_ID)
public class BwsUtilMod {

    public static final String MOD_ID = "bws_util";

    public BwsUtilMod(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, ScopeConfig.SPEC, "bws_util-client.toml");
    }
}
