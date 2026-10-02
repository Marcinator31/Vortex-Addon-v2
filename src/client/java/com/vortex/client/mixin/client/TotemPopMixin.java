package com.vortex.client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto Totem "Instant Refill": Ereignis 35 an dich = dein Totem ist geplatzt.
 * Am Ende der Verarbeitung (Haupt-Thread) sofort das naechste nachlegen.
 */
@Mixin(ClientPacketListener.class)
public abstract class TotemPopMixin {

    @Inject(method = "handleEntityEvent", at = @At("TAIL"), require = 0)
    private void vortex$totemPop(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        try {
            if (packet.getEventId() != 35) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            if (packet.getEntity(mc.level) != mc.player) return;
            com.vortex.client.hud.AutoTotem.nachPop();
        } catch (Throwable t) {
            com.vortex.client.core.Errors.report("TotemPop", t);
        }
    }
}
