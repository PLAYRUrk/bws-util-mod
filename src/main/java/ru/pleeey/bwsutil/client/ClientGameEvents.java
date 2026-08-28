package ru.pleeey.bwsutil.client;

import ru.pleeey.bwsutil.BwsUtilMod;
import ru.pleeey.bwsutil.client.autoclicker.AutoclickerBridgeClient;
import ru.pleeey.bwsutil.client.match.MatchTracker;
import ru.pleeey.bwsutil.client.keybind.ScopeKeys;
import ru.pleeey.bwsutil.client.overlay.BedWarsOverlay;
import ru.pleeey.bwsutil.client.overlay.ScopeOverlay;
import ru.pleeey.bwsutil.client.screen.ScopeConfigScreen;
import ru.pleeey.bwsutil.config.ScopeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BwsUtilMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientGameEvents {

    private static boolean emergencyBlockSwapActive;
    private static int emergencyPreviousHotbarSlot = -1;
    private static long emergencySwapStartedAtMs;

    private ClientGameEvents() {}

    @SubscribeEvent
    public static void onClientTickPre(TickEvent.ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            // Important: if we stop ticking (main menu / disconnect), release any previous suppression
            // so AUT-CLK doesn't stay forced OFF when launched after Minecraft.
            AutoclickerBridgeClient.setInputSuppression(false, false);
            resetEmergencyBlockSwapState();
            return;
        }
        syncAutoclickerState(mc);
        handleEmergencyBlockSwap(mc);
    }

    @SubscribeEvent
    public static void onClientTickPost(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Overlay state advances regardless of open screens: fireball and void warnings must keep
        // firing while the player is typing in chat, which is exactly when they cannot react.
        ScopeOverlay.tick(mc, mc.player);
        BedWarsOverlay.tick(mc);

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
    }

    /** Feeds server chat to the match tracker (bed destruction, team elimination). */
    @SubscribeEvent
    public static void onChatReceived(ClientChatReceivedEvent event) {
        if (!ScopeConfig.MATCH_LOG_ENABLED.get()) return;
        MatchTracker.onChatMessage(event.getMessage());
    }

    /**
     * Drops all per-match state around a server change, so beds, threat data and the match log
     * from one map never carry over into the next one.
     *
     * <p>Login is reset too, not just logout: the match clock in the log is measured from map
     * entry, and a stale start time would date every event wrongly.</p>
     */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        resetAllState();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAllState();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            resetAllState();
        }
    }

    private static void resetAllState() {
        BedWarsOverlay.resetState();
        ScopeOverlay.resetState();
        MatchTracker.reset();
        resetEmergencyBlockSwapState();
    }

    private static void syncAutoclickerState(Minecraft mc) {
        boolean isGuiOpen = mc.screen != null;
        boolean isBowInHands = mc.player.getMainHandItem().getItem() instanceof BowItem
                || mc.player.getOffhandItem().getItem() instanceof BowItem;
        boolean isConsumableInMainHand = isConsumable(mc.player.getMainHandItem());
        boolean isFireballInHands = isFireball(mc.player.getMainHandItem())
                || isFireball(mc.player.getOffhandItem());
        boolean isUsingOffhandConsumable = mc.player.isUsingItem()
                && isConsumable(mc.player.getUseItem())
                && mc.player.getOffhandItem() == mc.player.getUseItem();

        // Legacy behavior requested: if bow is in hand, suppress both channels.
        boolean suppressRmb = isGuiOpen || isBowInHands || isConsumableInMainHand || isUsingOffhandConsumable || isFireballInHands;
        boolean suppressLmb = isGuiOpen || isBowInHands;
        AutoclickerBridgeClient.setInputSuppression(suppressLmb, suppressRmb);
        AutoclickerBridgeClient.tickSuppressionPulse();
    }

    private static boolean isConsumable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemUseAnimation anim = stack.getUseAnimation();
        return anim == ItemUseAnimation.EAT || anim == ItemUseAnimation.DRINK;
    }

    private static boolean isFireball(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;
        String path = id.getPath();
        return path.contains("fire_charge") || path.contains("fireball");
    }

    private static void handleEmergencyBlockSwap(Minecraft mc) {
        if (!ScopeConfig.EMERGENCY_BLOCK_SWAP_ENABLED.get()) {
            // Restore the player's own slot choice before going idle, never leave it hijacked.
            if (emergencyBlockSwapActive && mc.player != null) {
                restorePreviousHotbarSlot(mc.player);
            }
            resetEmergencyBlockSwapState();
            return;
        }
        if (mc.player == null || mc.level == null) {
            resetEmergencyBlockSwapState();
            return;
        }
        var player = mc.player;
        if (!player.isAlive()) {
            resetEmergencyBlockSwapState();
            return;
        }

        if (emergencyBlockSwapActive && shouldRestoreAfterEmergency(mc, player)) {
            restorePreviousHotbarSlot(player);
            resetEmergencyBlockSwapState();
            return;
        }

        if (!isEmergencyFallRisk(mc, player)) return;

        int selected = player.getInventory().getSelectedSlot();
        if (isHotbarBlockSlot(player, selected)) return;

        int blockSlot = findBestHotbarBlockSlot(player);
        if (blockSlot < 0 || blockSlot == selected) return;

        emergencyPreviousHotbarSlot = selected;
        emergencySwapStartedAtMs = System.currentTimeMillis();
        emergencyBlockSwapActive = true;
        player.getInventory().setSelectedSlot(blockSlot);
    }

    private static boolean shouldRestoreAfterEmergency(Minecraft mc, LocalPlayer player) {
        if (!emergencyBlockSwapActive) return false;
        long activeMs = System.currentTimeMillis() - emergencySwapStartedAtMs;
        if (activeMs > 10_000L) return true;
        if (player.isCreative()) return true;
        if (player.onGround()) {
            return !isVoidBelow(mc, player.blockPosition(), 10);
        }
        boolean stabilized = player.fallDistance <= 0.35f && player.getDeltaMovement().y >= -0.04;
        return stabilized && !isEmergencyFallRisk(mc, player);
    }

    private static boolean isEmergencyFallRisk(Minecraft mc, LocalPlayer player) {
        if (player.onGround()) return false;
        double vy = player.getDeltaMovement().y;
        if (vy > -0.07) return false;

        boolean voidRisk = isVoidBelow(mc, player.blockPosition(), 18);
        double hp = player.getHealth() + player.getAbsorptionAmount();
        double projectedFall = player.fallDistance + Math.max(0.0, Math.abs(vy) * 9.0);
        double projectedDamage = Math.max(0.0, projectedFall - 3.0);
        boolean fatalRisk = projectedDamage >= Math.max(4.0, hp - 0.5);
        return (voidRisk && player.fallDistance > 1.25f) || fatalRisk;
    }

    private static boolean isVoidBelow(Minecraft mc, BlockPos base, int depth) {
        for (int i = 1; i <= depth; i++) {
            BlockPos p = base.below(i);
            if (mc.level.isLoaded(p) && !mc.level.getBlockState(p).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static int findBestHotbarBlockSlot(LocalPlayer player) {
        int bestSlot = -1;
        int bestCount = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack s = player.getInventory().getItem(slot);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) continue;
            int count = s.getCount();
            if (count > bestCount) {
                bestCount = count;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static boolean isHotbarBlockSlot(LocalPlayer player, int slot) {
        if (slot < 0 || slot >= 9) return false;
        ItemStack s = player.getInventory().getItem(slot);
        return !s.isEmpty() && s.getItem() instanceof BlockItem;
    }

    private static void restorePreviousHotbarSlot(LocalPlayer player) {
        if (emergencyPreviousHotbarSlot >= 0 && emergencyPreviousHotbarSlot < 9) {
            player.getInventory().setSelectedSlot(emergencyPreviousHotbarSlot);
        }
    }

    private static void resetEmergencyBlockSwapState() {
        emergencyBlockSwapActive = false;
        emergencyPreviousHotbarSlot = -1;
        emergencySwapStartedAtMs = 0L;
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
            boolean changed = false;
            while (ScopeKeys.ZERO_INCREASE.consumeClick()) {
                ScopeConfig.ZERO_DISTANCE.set(Math.min(200, ScopeConfig.ZERO_DISTANCE.get() + 5));
                changed = true;
            }
            while (ScopeKeys.ZERO_DECREASE.consumeClick()) {
                ScopeConfig.ZERO_DISTANCE.set(Math.max(10, ScopeConfig.ZERO_DISTANCE.get() - 5));
                changed = true;
            }
            // Debounced by the key press itself: one write per adjustment burst, not per tick.
            if (changed) ScopeConfig.save();
            return;
        }

        // Consume clicks in other contexts so keypresses don't spill into later state changes.
        while (ScopeKeys.ZERO_INCREASE.consumeClick()) { }
        while (ScopeKeys.ZERO_DECREASE.consumeClick()) { }
    }

}
