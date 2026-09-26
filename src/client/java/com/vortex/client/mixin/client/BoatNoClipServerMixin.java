package com.vortex.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Boat Fly "No Clip" -- NUR im Einzelspieler (und als Gastgeber eines
 * LAN-Spiels, nur fuer dich selbst).
 *
 * WARUM ES OHNE DAS NICHT GEHT: Beim Boot rechnet der Server die Bewegung
 * selbst mit Kollision nach (handleMoveVehicle) und setzt das Boot zurueck,
 * wenn es
 *   - "moved wrongly" ist und vorher frei stand (level.noCollision), oder
 *   - neu mit einem Block kollidiert (isEntityCollidingWithAnythingNew).
 * Im Einzelspieler laeuft dieser Server in deinem eigenen Minecraft -- hier
 * werden genau diese zwei Abfragen fuer DICH auf "kein Hindernis" gestellt,
 * solange Boat Fly mit No Clip an ist. Auf fremden Servern laeuft deren Code,
 * dort bleibt es unmoeglich.
 *
 * Stellen laut Vanilla-Code (sichtbar in Paper, Mojang-Namen). Beide
 * Beschreibungen fuer den Level-Parameter sind angegeben; was nicht passt,
 * findet Mixin nicht (require = 0) -- dann bleibt nur No Clip wirkungslos.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class BoatNoClipServerMixin {

    private boolean vortex$fuerMich() {
        try {
            var sp = ((ServerGamePacketListenerImpl) (Object) this).getPlayer();
            return sp != null && com.vortex.client.cheat.MoveCheats.noClipFuer(sp);
        } catch (Throwable t) {
            return false;
        }
    }

    @ModifyExpressionValue(method = "handleMoveVehicle",
            at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isEntityCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;DDD)Z"),
                    @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isEntityCollidingWithAnythingNew(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;DDD)Z"),
                    @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isEntityCollidingWithAnythingNew(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;DDD)Z")
            },
            require = 0)
    private boolean vortex$keineNeueKollision(boolean original) {
        return original && !vortex$fuerMich();
    }

    @ModifyExpressionValue(method = "handleMoveVehicle",
            at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/CollisionGetter;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z")
            },
            require = 0)
    private boolean vortex$nichtFrei(boolean original) {
        // "Stand vorher frei" -> false: dann wird "moved wrongly" nicht mehr
        // zurueckgesetzt.
        return original && !vortex$fuerMich();
    }
}
