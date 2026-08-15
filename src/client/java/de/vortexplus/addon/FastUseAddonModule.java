package de.vortexplus.addon;

import com.vortex.client.core.setting.BooleanSetting;
import com.vortex.client.module.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;

/**
 * Removes the pause between uses.
 *
 * WHY THIS WAS REWRITTEN COMPLETELY: the previous version looked up two fields
 * by name -- "itemUseCooldown" and "blockBreakingCooldown". Those are the Yarn
 * names, which exist only while developing. In the finished game the fields are
 * called field_1752 and field_3716, so the lookup found nothing, returned null,
 * and every check silently did nothing. The module has never once worked, and
 * because the failure was caught and ignored, nothing ever said so.
 *
 * This version does not go looking for private fields at all. It simply uses
 * the item again itself, which needs no internals and cannot break when the
 * next version renames something.
 */
public final class FastUseAddonModule extends Module {

    /**
     * Uses per tick. One is the normal rate.
     *
     * Vanilla allows one use every four ticks, which for experience bottles
     * means five a second. Throwing a stack that way takes over a minute.
     */
    public final com.vortex.client.core.setting.NumberSetting usesPerTick =
            new com.vortex.client.core.setting.NumberSetting("Uses Per Tick", 4, 1, 20, 1);

    /** Only while the use key is actually held. */
    public final BooleanSetting onlyWhileHeld = new BooleanSetting("Only While Held", true);

    /**
     * Only speed up things that are thrown.
     *
     * Bottles, pearls, snowballs and eggs. Left on, eating and drawing a bow
     * keep their normal timing -- those are held down rather than tapped, and
     * hurrying them does nothing useful while making everything you do look
     * wrong at once.
     */
    public final BooleanSetting throwablesOnly = new BooleanSetting("Throwables Only", true);

    public FastUseAddonModule() {
        super("Fast Use", Category.CHEATS);
        addSetting(usesPerTick);
        addSetting(onlyWhileHeld);
        addSetting(throwablesOnly);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        try {
            if (!isEnabled()) return;
            if (client.player == null || client.interactionManager == null) return;
            if (client.currentScreen != null) return;

            if (onlyWhileHeld.get()
                    && (client.options.useKey == null || !client.options.useKey.isPressed())) {
                return;
            }

            var held = client.player.getMainHandStack();
            if (held == null || held.isEmpty()) return;

            if (throwablesOnly.get() && !isThrowable(held)) return;

            // One extra use per tick beyond what the game does itself.
            int extra = Math.max(0, usesPerTick.getInt() - 1);
            for (int i = 0; i < extra; i++) {
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            }
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("FastUse", pvpErr);
        }
    }

    /**
     * Is this something you throw?
     *
     * Matched by item id rather than by class: the item classes were merged in
     * this version, so there is no single type left that covers them. The name
     * is stable and cannot fail to compile.
     */
    private static boolean isThrowable(net.minecraft.item.ItemStack stack) {
        try {
            var id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
            if (id == null) return false;
            String path = id.getPath();
            return path.equals("experience_bottle")
                    || path.equals("ender_pearl")
                    || path.equals("snowball")
                    || path.equals("egg")
                    || path.equals("splash_potion")
                    || path.equals("lingering_potion");
        } catch (Throwable pvpErr) {
            return false;
        }
    }
}
