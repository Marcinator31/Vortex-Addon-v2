package com.vortex.client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Die Bewegung des eigenen Spielers, direkt bevor sie ausgefuehrt wird.
 *
 * Elytra Fly und Speed brauchen genau diese Stelle: Minecraft rechnet erst
 * Schwerkraft, Reibung und Gleitflug aus und bewegt dann. Wer vorher im Tick
 * die Geschwindigkeit setzt, wird von dieser Rechnung wieder verfaelscht --
 * hier dagegen gilt, was wir vorgeben.
 *
 * Methode "move(MoverType, Vec3)" wie in Meteor fuer 26.2 (EntityMixin).
 * ModifyVariable mit argsOnly aendert nur den Vec3-Parameter.
 */
@Mixin(Entity.class)
public abstract class CheatMoveMixin {

    @ModifyVariable(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Vec3 vortex$bewegung(Vec3 bewegung) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return bewegung;
            if ((Object) this == mc.player) {
                LocalPlayer ich = (LocalPlayer) (Object) this;
                Vec3 v = com.vortex.client.cheat.MoveCheats.bewegung(ich, bewegung);
                // 2.24.0: Spider, Parkour (Safe Walk laeuft ueber SafeWalkMixin)
                return com.vortex.client.cheat.ExtraCheats.bewegung(ich, v);
            }
            // Boat Fly: das Boot, das DU steuerst (Meteor steuert Fahrzeuge
            // an derselben Stelle, EntityControl).
            if ((Object) this instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat boot
                    && boot.getControllingPassenger() == mc.player) {
                return com.vortex.client.cheat.MoveCheats.boot(boot, mc.player, bewegung);
            }
            return bewegung;
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("CheatMoveMixin", pvpErr);
            return bewegung;
        }
    }
}
