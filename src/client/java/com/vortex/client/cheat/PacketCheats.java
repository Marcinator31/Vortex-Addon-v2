package com.vortex.client.cheat;

import com.vortex.client.core.PacketHooks;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AntiHungerModule;
import com.vortex.client.module.modules.AntiKnockbackModule;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.phys.Vec3;

/**
 * Anti Knockback und Anti Hunger -- beide greifen an Netzwerkpaketen an und
 * benutzen dafuer die eine gemeinsame Stelle des Clients (PacketHooks).
 *
 * THREAD: Ankommende Pakete laufen auf dem Netzwerk-Thread. Hier wird nur
 * das Paket selbst geaendert, bevor Minecraft es verarbeitet -- die Welt
 * wird nicht angefasst. (Das Lesen der eigenen Geschwindigkeit ist ein
 * harmloser Lesezugriff; Meteor macht es an derselben Stelle genauso.)
 */
public final class PacketCheats {

    private PacketCheats() {}

    /** Letzter Stand "am Boden" -- fuer die Landung bei Anti Hunger. */
    private static boolean warAmBoden = true;
    /** Das naechste Bewegungspaket unveraendert lassen (Landung). */
    private static volatile boolean landungDurchlassen = false;

    public static void register() {
        PacketHooks.onReceive(PacketCheats::empfangen);
        PacketHooks.onSend(PacketCheats::senden);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            LocalPlayer p = mc.player;
            if (p == null) return;
            // Nach einer Landung EIN echtes Paket durchlassen. Sonst meint der
            // Server, man sei nie gelandet -- und berechnet den Fallschaden
            // spaeter aus einer viel zu grossen Hoehe.
            boolean amBoden = p.onGround();
            if (amBoden && !warAmBoden) landungDurchlassen = true;
            warAmBoden = amBoden;
        });
    }

    // ------------------------------------------------------------------
    // Anti Knockback
    // ------------------------------------------------------------------

    private static boolean empfangen(net.minecraft.network.protocol.Packet<?> paket) {
        AntiKnockbackModule m = ModuleManager.INSTANCE.get(AntiKnockbackModule.class);
        if (m == null || !m.isEnabled()) return false;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return false;

        double h = m.horizontal.get() / 100.0;
        double v = m.vertical.get() / 100.0;

        if (paket instanceof ClientboundSetEntityMotionPacket bew) {
            if (bew.id() != p.getId()) return false;
            // 0 % in beide Richtungen: Paket einfach verwerfen.
            if (h <= 0 && v <= 0) return true;
            // Sonst nur den ZUSATZ abschwaechen, den der Treffer bringt --
            // die eigene Bewegung bleibt erhalten.
            Vec3 jetzt = p.getDeltaMovement();
            Vec3 ziel = bew.movement();
            Vec3 neu = new Vec3(
                    jetzt.x + (ziel.x - jetzt.x) * h,
                    jetzt.y + (ziel.y - jetzt.y) * v,
                    jetzt.z + (ziel.z - jetzt.z) * h);
            ((com.vortex.client.mixin.client.MotionPacketAccessor) (Object) bew).vortex$setMovement(neu);
            return false;
        }

        if (paket instanceof ClientboundExplodePacket exp && m.explosions.get()) {
            Optional<Vec3> kb = exp.playerKnockback();
            if (kb.isPresent()) {
                Vec3 k = kb.get();
                ((com.vortex.client.mixin.client.ExplodePacketAccessor) (Object) exp).vortex$setKnockback(
                        Optional.of(new Vec3(k.x * h, k.y * v, k.z * h)));
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Anti Hunger
    // ------------------------------------------------------------------

    private static boolean senden(net.minecraft.network.protocol.Packet<?> paket) {
        AntiHungerModule m = ModuleManager.INSTANCE.get(AntiHungerModule.class);
        if (m == null || !m.isEnabled()) return false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return false;

        // Sprinten kostet Hunger -- der Server erfaehrt nicht, dass man sprintet.
        if (m.sprint.get() && paket instanceof ServerboundPlayerCommandPacket cmd
                && cmd.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
            return true;
        }

        // Springen kostet Hunger -- der Server bekommt "nicht am Boden" gemeldet
        // und sieht deshalb keinen Absprung. Nicht beim Abbauen: in der Luft
        // baut man auf dem Server fuenfmal langsamer ab.
        if (m.jump.get() && paket instanceof ServerboundMovePlayerPacket bew) {
            if (landungDurchlassen) {
                landungDurchlassen = false;
                return false;
            }
            if (p.onGround() && p.fallDistance <= 0.0
                    && (mc.gameMode == null || !mc.gameMode.isDestroying())) {
                ((com.vortex.client.mixin.client.MovePacketAccessor) bew).vortex$setOnGround(false);
            }
        }
        return false;
    }
}
