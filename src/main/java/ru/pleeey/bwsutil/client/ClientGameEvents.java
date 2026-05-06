package ru.pleeey.bwsutil.client;

import ru.pleeey.bwsutil.BwsUtilMod;
import ru.pleeey.bwsutil.client.autoclicker.AutoclickerBridgeClient;
import ru.pleeey.bwsutil.client.keybind.ScopeKeys;
import ru.pleeey.bwsutil.client.overlay.BedWarsOverlay;
import ru.pleeey.bwsutil.client.overlay.ScopeOverlay;
import ru.pleeey.bwsutil.client.screen.ScopeConfigScreen;
import ru.pleeey.bwsutil.config.ScopeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BwsUtilMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientGameEvents {

    private ClientGameEvents() {}

    @SubscribeEvent
    public static void onClientTickPre(TickEvent.ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            // Important: if we stop ticking (main menu / disconnect), release any previous suppression
            // so AUT-CLK doesn't stay forced OFF when launched after Minecraft.
            AutoclickerBridgeClient.setInputSuppression(false, false);
            return;
        }
        syncAutoclickerState(mc);
    }

    @SubscribeEvent
    public static void onClientTickPost(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen != null) return;

        if (ScopeKeys.OPEN_CONFIG.consumeClick()) {
            mc.setScreen(new ScopeConfigScreen());
        }
        if (ScopeKeys.SCOPE_TOGGLE.consumeClick()) {
            ScopeOverlay.toggleEnabled();
        }
        if (ScopeKeys.MODE_TOGGLE.consumeClick()) {
            ScopeOverlay.toggleMode();
        }
        if (ScopeKeys.BEDWARS_TOGGLE.consumeClick()) {
            BedWarsOverlay.toggleEnabled();
        }
        handlePlusMinusActions(mc);

        // Захват цели — ровно один раз за тик, независимо от числа render-вызовов за кадр
        ScopeOverlay.tick(mc, mc.player);
        // Сканирование кроватей BedWars раз в 3 секунды
        BedWarsOverlay.tick(mc);

    }

    private static void syncAutoclickerState(Minecraft mc) {
        boolean isGuiOpen = mc.screen != null;
        boolean isBowInMainHand = mc.player.getMainHandItem().getItem() instanceof BowItem;
        boolean isConsumableInHand = isConsumable(mc.player.getMainHandItem())
                || isConsumable(mc.player.getOffhandItem());

        boolean suppressRmb = isGuiOpen || isBowInMainHand || isConsumableInHand;
        boolean suppressLmb = isGuiOpen || isBowInMainHand;
        AutoclickerBridgeClient.setInputSuppression(suppressLmb, suppressRmb);
        AutoclickerBridgeClient.tickSuppressionPulse();
    }

    private static boolean isConsumable(ItemStack stack) {
        ItemUseAnimation anim = stack.getUseAnimation();
        return anim == ItemUseAnimation.EAT || anim == ItemUseAnimation.DRINK;
    }

    private static void handlePlusMinusActions(Minecraft mc) {
        boolean scopeActive = ScopeOverlay.isScopeInputActive(mc);
        ScopeOverlay.ScopeMode mode = ScopeOverlay.getMode();
        boolean bedWarsActive = BedWarsOverlay.isEnabled();

        // Radar scale control: AUTO scope mode OR no active scope context (e.g. no bow in hands).
        if (bedWarsActive && (mode == ScopeOverlay.ScopeMode.AUTO || !scopeActive)) {
            while (ScopeKeys.ZERO_INCREASE.consumeClick()) {
                BedWarsOverlay.increaseRadarScale();
            }
            while (ScopeKeys.ZERO_DECREASE.consumeClick()) {
                BedWarsOverlay.decreaseRadarScale();
            }
            return;
        }

        if (scopeActive && mode == ScopeOverlay.ScopeMode.MANUAL) {
            while (ScopeKeys.ZERO_INCREASE.consumeClick()) {
                ScopeConfig.ZERO_DISTANCE.set(Math.min(200, ScopeConfig.ZERO_DISTANCE.get() + 5));
            }
            while (ScopeKeys.ZERO_DECREASE.consumeClick()) {
                ScopeConfig.ZERO_DISTANCE.set(Math.max(10, ScopeConfig.ZERO_DISTANCE.get() - 5));
            }
            return;
        }

        // Consume clicks in other contexts so keypresses don't spill into later state changes.
        while (ScopeKeys.ZERO_INCREASE.consumeClick()) { }
        while (ScopeKeys.ZERO_DECREASE.consumeClick()) { }
    }

}
