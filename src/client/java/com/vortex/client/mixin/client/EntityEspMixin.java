package com.vortex.client.mixin.client;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.EspModule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ESP: laesst ausgewaehlte Mobs leuchten.
 *
 *  - isGlowing (method_5851): true fuer aktivierte Mob-Typen -> Outline an.
 *  - getTeamColorValue (method_22861): faerbt die Outline in der ESP-Farbe.
 *
 * Wir bestimmen den Entity-Typ ueber die Registry-ID und gleichen sie mit der
 * Auswahl im EspModule ab.
 */
@Mixin(Entity.class)
public abstract class EntityEspMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void pvpclient$espGlow(CallbackInfoReturnable<Boolean> cir) {
        EspModule esp = pvpclient$esp();
        if (esp == null || !esp.isEnabled()) return;
        if (pvpclient$isEspMob(esp)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void pvpclient$espColor(CallbackInfoReturnable<Integer> cir) {
        EspModule esp = pvpclient$esp();
        if (esp == null || !esp.isEnabled()) return;
        if (pvpclient$isEspMob(esp)) {
            cir.setReturnValue(esp.getGlowColor() & 0xFFFFFF);
        }
    }

    private boolean pvpclient$isEspMob(EspModule esp) {
        try {
            Entity self = (Entity) (Object) this;
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(self.getType());
            return esp.isMobEnabled(id);
        } catch (Throwable t) {
            return false;
        }
    }

    private static EspModule pvpclient$esp() {
        // Konstante Laufzeit statt Liste durchlaufen -- laeuft in Render-Pfaden.
        return ModuleManager.INSTANCE.get(EspModule.class);
    }
}
