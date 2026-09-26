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
            if (mc == null || (Object) this != mc.player) return bewegung;
            return com.vortex.client.cheat.MoveCheats.bewegung((LocalPlayer) (Object) this, bewegung);
        } catch (Throwable pvpErr) {
            com.vortex.client.core.Errors.report("CheatMoveMixin", pvpErr);
            return bewegung;
        }
    }
}
