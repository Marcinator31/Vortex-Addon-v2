package com.vortex.client.mixin.client;

import com.vortex.client.hud.StashFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Mixin(Minecraft.class)
public class ContainerOpenMixin {

    @Inject(method = "setScreen", at = @At("TAIL"))
    private void onSetScreen(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null) return;

            Container container = containerScreen.getScreenHandler();
            if (container == null) return;

            // Wir prüfen, ob wir uns an einem bekannten Stash befinden
            int x = (int) Math.floor(player.getX());
            int z = (int) Math.floor(player.getZ());
            
            // Wir rufen eine neue Methode in StashFinder auf, um den Inhalt zu speichern
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < container.slots.size(); i++) {
                ItemStack stack = container.slots.get(i).getItem();
                if (!stack.isEmpty()) {
                    items.add(stack);
                }
            }
            
            StashFinder.updateContent(player.level().dimension().toString(), x, z, items);
        }
    }
}
