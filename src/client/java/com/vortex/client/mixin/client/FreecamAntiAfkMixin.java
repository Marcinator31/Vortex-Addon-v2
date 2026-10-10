package com.vortex.client.mixin.client;

import com.vortex.client.freecam.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.protocol.game.ClientPlayerRotationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anti-AFK Heartbeat fuer die Freecam.
 * Sendet minimale Rotations-Pakete an den Server, um ein Einkicken durch
 * AFK-Timer zu verhindern, waehrend man in der Freecam fliegt.
 */
@Mixin(Minecraft.class)
public class FreecamAntiAfkMixin {

    private long lastHeartbeat = 0;

    @Inject(method = "runTick", at = @At("HEAD"), require = 0)
    private void onRunTick(CallbackInfo ci) {
        if (!Freecam.isActive()) return;

        long now = System.currentTimeMillis();
        if (now - lastHeartbeat > 5000) { // Alle 5 Sekunden ein Paket
            Minecraft mc = (Minecraft) (Object) this;
            if (mc.player != null) {
                // Sende eine minimale Rotation (fast identisch mit der aktuellen)
                // um Aktivitaet vorzutäuschen, ohne dass es im Spiel auffällt.
                float yaw = mc.player.getYRot();
                float pitch = mc.player.getXRot();
                
                // Wir senden eine minimale Änderung von 0.01 Grad
                mc.getConnection().send(new ClientPlayerRotationPacket(yaw + 0.01f, pitch + 0.01f));
                lastHeartbeat = now;
            }
        }
    }
}
