package com.vortex.client.bot;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.vortex.client.cheat.Inv;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.ElytraAutopilotModule;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Elytra Autopilot (siehe ElytraAutopilotModule).
 *
 * Steuerung nur ueber den Blick (wie ein Spieler mit Elytra):
 *   Blick nach oben + Rakete = steigen, Blick leicht nach unten = gleiten,
 *   staerker nach unten = sinken. Die Richtung dreht weich aufs Ziel.
 * Vorausschau: liegt in ~40 Bloecken Flugrichtung Gelaende auf Flughoehe,
 * wird gestiegen.
 */
public final class ElytraPilot {

    private ElytraPilot() {}

    private static Double zielX = null, zielZ = null;
    private static long tick = 0;
    private static boolean warAn = false;
    private static long raketeZuletzt = -1000;
    private static int startSchritt = 0;
    private static boolean landen = false;
    private static boolean ohneRaketenGemeldet = false;
    private static long meldungZuletzt = -1000;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try { tick(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("ElytraPilot", e); }
        });
        ClientCommandRegistrationCallback.EVENT.register((d, access) -> d.register(literal("autopilot")
                .then(literal("stop").executes(c -> {
                    zielX = zielZ = null;
                    var m = ModuleManager.INSTANCE.get(ElytraAutopilotModule.class);
                    if (m != null && m.isEnabled()) m.setEnabled(false);
                    c.getSource().sendFeedback(Component.literal("§d[Autopilot]§r stopped."));
                    return 1;
                }))
                .then(argument("x", StringArgumentType.word()).then(argument("z", StringArgumentType.word()).executes(c -> {
                    try {
                        zielX = Double.parseDouble(StringArgumentType.getString(c, "x"));
                        zielZ = Double.parseDouble(StringArgumentType.getString(c, "z"));
                    } catch (NumberFormatException e) {
                        c.getSource().sendError(Component.literal("Usage: /autopilot <x> <z>"));
                        return 0;
                    }
                    landen = false;
                    startSchritt = 0;
                    var m = ModuleManager.INSTANCE.get(ElytraAutopilotModule.class);
                    if (m != null && !m.isEnabled()) m.setEnabled(true);
                    c.getSource().sendFeedback(Component.literal("§d[Autopilot]§r flying to "
                            + Math.round(zielX) + " / " + Math.round(zielZ) + "."));
                    return 1;
                })))));
    }

    private static void melde(LocalPlayer p, String text) {
        p.sendSystemMessage(Component.literal("§d[Autopilot]§r " + text));
    }

    private static void ende(Minecraft mc, ElytraAutopilotModule m, LocalPlayer p, String text) {
        melde(p, text);
        zielX = zielZ = null;
        m.setEnabled(false);
        mc.options.keyJump.setDown(false);
    }

    private static void tick(Minecraft mc) {
        ElytraAutopilotModule m = ModuleManager.INSTANCE.get(ElytraAutopilotModule.class);
        LocalPlayer p = mc.player;
        boolean an = m != null && m.isEnabled() && p != null && mc.level != null && mc.gameMode != null;
        if (!an) {
            if (warAn) { mc.options.keyJump.setDown(false); startSchritt = 0; landen = false; }
            warAn = false;
            return;
        }
        warAn = true;
        if (!p.isAlive()) return;
        if (zielX == null) {
            if (tick - meldungZuletzt > 200) {
                meldungZuletzt = tick;
                p.sendOverlayMessage(Component.literal("§dAutopilot: set a target with /autopilot <x> <z>"));
            }
            return;
        }
        ItemStack brust = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!brust.is(Items.ELYTRA)) {
            if (p.onGround()) ende(mc, m, p, "No elytra on -- stopped.");
            return;
        }
        int rest = brust.getMaxDamage() - brust.getDamageValue();
        if (rest <= m.minDurability.getInt() && !landen) {
            landen = true;
            melde(p, "Elytra almost broken (" + rest + ") -- landing.");
        }

        double dx = zielX - p.getX(), dz = zielZ - p.getZ();
        double abstand = Math.sqrt(dx * dx + dz * dz);

        // --- am Boden -------------------------------------------------------
        if (!p.isFallFlying()) {
            if (p.onGround()) {
                if (landen || abstand < (m.land.get() ? 60 : 12)) {
                    ende(mc, m, p, abstand < 60 ? "Arrived (" + Math.round(abstand) + " blocks from the target)." : "Landed.");
                    return;
                }
                // Abheben: springen, im Fallen die Elytra oeffnen
                mc.options.keyJump.setDown(startSchritt % 10 == 0);
                startSchritt++;
                return;
            }
            mc.options.keyJump.setDown(false);
            if (p.getDeltaMovement().y < 0 && !p.isInWater()) {
                var net = mc.getConnection();
                if (net != null) {
                    net.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    p.startFallFlying();
                    raketeZuletzt = -1000;       // gleich eine Rakete zum Steigen
                }
            }
            return;
        }
        startSchritt = 0;

        // --- im Flug --------------------------------------------------------
        float zielYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float drehung = Mth.clamp(Mth.wrapDegrees(zielYaw - p.getYRot()), -m.turnSpeed.getFloat(), m.turnSpeed.getFloat());
        p.setYRot(p.getYRot() + drehung);

        Vec3 v = p.getDeltaMovement();
        double tempo = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;     // Bloecke pro Sekunde
        double hoehe = p.getY();
        boolean imLanden = (m.land.get() && abstand < 60) || landen;
        float pitch;
        boolean steigen = false;
        if (imLanden) {
            pitch = abstand < 8 ? 50f : 25f;
            if (abstand < 3 && !landen) pitch = 70f;
        } else {
            double soll = m.cruiseY.get();
            if (gelaendeVoraus(mc, p, zielYaw)) { pitch = -35f; steigen = true; }
            else if (hoehe < soll - 8) { pitch = -30f; steigen = true; }
            else if (hoehe > soll + 8) pitch = 15f;
            else pitch = 3f;
        }
        p.setXRot(Mth.lerp(0.35f, p.getXRot(), pitch));

        // --- Raketen --------------------------------------------------------
        if (!imLanden && m.rockets.get() && tick - raketeZuletzt >= Math.round(m.rocketDelay.get() * 20)) {
            boolean brauchtSchub = tempo < m.minSpeed.get() || steigen;
            boolean vorausGeladen = mc.level.hasChunk((int) Math.floor(p.getX() + v.x * 40) >> 4, (int) Math.floor(p.getZ() + v.z * 40) >> 4);
            if (brauchtSchub && vorausGeladen) {
                if (rakete(mc, p)) {
                    raketeZuletzt = tick;
                    ohneRaketenGemeldet = false;
                } else if (!ohneRaketenGemeldet) {
                    ohneRaketenGemeldet = true;
                    melde(p, "No firework rockets -- gliding only.");
                    if (hoehe < m.cruiseY.get() - 40) landen = true;
                }
            }
        }
    }

    /** Liegt voraus (bis ~40 Bloecke) Gelaende auf oder knapp unter Flughoehe? */
    private static boolean gelaendeVoraus(Minecraft mc, LocalPlayer p, float yaw) {
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        for (int d = 8; d <= 40; d += 4) {
            int x = (int) Math.floor(p.getX() + fx * d), z = (int) Math.floor(p.getZ() + fz * d);
            if (!mc.level.hasChunk(x >> 4, z >> 4)) return false;
            for (int dy = -6; dy <= 2; dy += 2) {
                BlockPos b = new BlockPos(x, (int) Math.floor(p.getY()) + dy, z);
                if (!mc.level.getBlockState(b).getCollisionShape(mc.level, b).isEmpty()) return true;
            }
        }
        return false;
    }

    /** Rakete aus Off-Hand oder Hotbar zuenden (Platz danach zurueck). */
    private static boolean rakete(Minecraft mc, LocalPlayer p) {
        if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
            return true;
        }
        int slot = Inv.hotbar(p, st -> st.is(Items.FIREWORK_ROCKET));
        if (slot < 0) return false;
        Inv.benutzeMitBlick(mc, slot, null);
        return true;
    }
}
