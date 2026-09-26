package com.vortex.client.cheat;

import com.mojang.blaze3d.platform.InputConstants;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.ElytraFlyModule;
import com.vortex.client.module.modules.InventoryMoveModule;
import com.vortex.client.module.modules.JesusModule;
import com.vortex.client.module.modules.NoSlowModule;
import com.vortex.client.module.modules.SpeedModule;
import com.vortex.client.module.modules.StepModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Bewegungs-Cheats: Step, Jesus, Speed, No Slow, Inventory Move, Elytra Fly.
 *
 * Zwei Wege, je nachdem, was das Modul braucht:
 *  - im Tick (Stufenhoehe, Tasten, Aufsteigen im Wasser, Raketen)
 *  - direkt vor der Bewegung (CheatMoveMixin -> bewegung()): Speed und
 *    Elytra Fly geben hier die Geschwindigkeit vor, nachdem Minecraft
 *    Schwerkraft und Gleitflug schon ausgerechnet hat. Vorher gesetzt, wuerde
 *    diese Rechnung sie wieder verfaelschen.
 */
public final class MoveCheats {

    private MoveCheats() {}

    /** Stufenhoehe von Minecraft, wenn nichts sie aendert. */
    private static final double VANILLA_STUFE = 0.6;
    private static boolean stufeGesetzt = false;

    private static boolean invMoveAktiv = false;
    private static long tick = 0;
    private static long letzteRakete = -1000;
    private static long letzterStart = -1000;

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            try {
                inventoryMove(mc);
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("MoveCheats.invMove", pvpErr);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) {
                stufeGesetzt = false;
                return;
            }
            try {
                bootTick(p);
                step(p);
                jesusTick(mc, p);
                speedTick(mc, p);
                elytraTick(mc, p);
            } catch (Throwable pvpErr) {
                com.vortex.client.core.Errors.report("MoveCheats.tick", pvpErr);
            }
        });
    }

    private static <T extends com.vortex.client.module.Module> T an(Class<T> typ) {
        T m = ModuleManager.INSTANCE.get(typ);
        return (m != null && m.isEnabled()) ? m : null;
    }

    // ------------------------------------------------------------------
    // Step
    // ------------------------------------------------------------------

    private static void step(LocalPlayer p) {
        StepModule m = an(StepModule.class);
        AttributeInstance at = p.getAttribute(Attributes.STEP_HEIGHT);
        if (at == null) return;
        if (m != null) {
            // Beim Schleichen wie normal -- sonst stuerzt man Kanten hinunter,
            // an denen man sich eigentlich festhalten wollte.
            double ziel = p.isShiftKeyDown() ? VANILLA_STUFE : m.height.get();
            if (Math.abs(at.getBaseValue() - ziel) > 1e-4) at.setBaseValue(ziel);
            stufeGesetzt = true;
        } else if (stufeGesetzt) {
            at.setBaseValue(VANILLA_STUFE);
            stufeGesetzt = false;
        }
    }

    /** Vom Modul beim Ausschalten: Stufenhoehe sofort zurueck. */
    public static void stepAus() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        AttributeInstance at = p.getAttribute(Attributes.STEP_HEIGHT);
        if (at != null) at.setBaseValue(VANILLA_STUFE);
        stufeGesetzt = false;
    }

    // ------------------------------------------------------------------
    // Jesus
    // ------------------------------------------------------------------

    /**
     * Vom Kollisions-Mixin: soll dieser Fluessigkeitsblock fest sein?
     *
     * Nur unter den Fuessen (sonst liefe man gegen Wasserwaende), nicht beim
     * Schleichen (so taucht man absichtlich ein), nicht nach einem tiefen Fall
     * (dann ins Wasser, das bremst den Fall -- auf "festem" Wasser waere es
     * ein Sturz auf Stein).
     */
    public static boolean jesusFest(BlockState state, BlockPos pos) {
        JesusModule m = an(JesusModule.class);
        if (m == null || m.mode.getIndex() != 0) return false;
        if (state.getFluidState().isEmpty()) return false;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return false;
        boolean wasser = state.getFluidState().is(FluidTags.WATER);
        boolean lava = state.getFluidState().is(FluidTags.LAVA);
        if (!wasser && !(lava && m.lava.get())) return false;
        if (p.isInWater() || p.isInLava()) return false;
        if (Minecraft.getInstance().options.keyShift.isDown()) return false;
        if (p.fallDistance > m.dipHeight.get()) return false;
        return pos.getY() <= p.getY() - 1;
    }

    private static void jesusTick(Minecraft mc, LocalPlayer p) {
        JesusModule m = an(JesusModule.class);
        if (m == null || mc.options.keyShift.isDown()) return;
        boolean imWasser = p.isInWater();
        boolean inLava = p.isInLava() && m.lava.get();
        if (!imWasser && !inLava) return;
        // Im Wasser (hineingefallen, oder Modus "Bob"): zur Oberflaeche
        // treiben. Im Modus "Solid" steht man danach auf dem Wasser, im Modus
        // "Bob" schwimmt man knapp an der Oberflaeche.
        Vec3 v = p.getDeltaMovement();
        if (v.y < 0.11) p.setDeltaMovement(v.x, 0.11, v.z);
    }

    // ------------------------------------------------------------------
    // No Slow
    // ------------------------------------------------------------------

    public static boolean noSlow() {
        NoSlowModule m = an(NoSlowModule.class);
        return m != null && m.items.get();
    }

    // ------------------------------------------------------------------
    // Speed
    // ------------------------------------------------------------------

    private static boolean speedErlaubt(LocalPlayer p) {
        return !p.isInWater() && !p.isInLava() && !p.isFallFlying()
                && !p.getAbilities().flying && !p.isShiftKeyDown() && !p.isPassenger();
    }

    private static void speedTick(Minecraft mc, LocalPlayer p) {
        SpeedModule m = an(SpeedModule.class);
        if (m == null || !m.autoJump.get()) return;
        if (com.vortex.client.freecam.Freecam.isActive()) return;
        if (!speedErlaubt(p) || !p.onGround() || !bewegtSich(mc)) return;
        if (mc.options.keyJump.isDown()) return;   // springt ohnehin
        p.jumpFromGround();
    }

    private static boolean bewegtSich(Minecraft mc) {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    /**
     * Richtung aus den Tasten, relativ zum Blick, als Einheitsvektor
     * {x, z}, oder null ohne Eingabe.
     */
    private static double[] tastenRichtung(Minecraft mc, float yaw) {
        double vor = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double seit = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        if (vor == 0 && seit == 0) return null;
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r), cos = Math.cos(r);
        double x = vor * -sin + seit * cos;
        double z = vor * cos + seit * sin;
        double l = Math.sqrt(x * x + z * z);
        return new double[]{x / l, z / l};
    }

    // ------------------------------------------------------------------
    // Bewegung direkt vor dem Ausfuehren (CheatMoveMixin)
    // ------------------------------------------------------------------

    public static Vec3 bewegung(LocalPlayer p, Vec3 v) {
        Minecraft mc = Minecraft.getInstance();
        // Freecam: die Tasten steuern dann die Kamera, nicht den Spieler.
        if (com.vortex.client.freecam.Freecam.isActive()) return v;

        ElytraFlyModule ely = an(ElytraFlyModule.class);
        if (ely != null && p.isFallFlying() && !(ely.stopInWater.get() && p.isInWater())) {
            Vec3 neu = elytra(mc, p, v, ely);
            if (neu != v) {
                p.setDeltaMovement(neu);
                return neu;
            }
            return v;
        }

        SpeedModule sp = an(SpeedModule.class);
        if (sp != null && speedErlaubt(p)) {
            double[] dir = tastenRichtung(mc, p.getYRot());
            if (dir == null) return v;
            double tempo;
            if (sp.mode.getIndex() == 0) {
                // Strafe: feste Geschwindigkeit in Tastenrichtung, am Boden und
                // in der Luft. 0,2873 ist Sprinten ohne Effekte.
                tempo = 0.2873 * sp.speed.get();
            } else {
                // Boost: nur am Boden, die jetzige Geschwindigkeit verstaerkt
                if (!p.onGround()) return v;
                double jetzt = Math.sqrt(v.x * v.x + v.z * v.z);
                tempo = Math.min(1.0, Math.max(jetzt, 0.2) * sp.speed.get());
            }
            Vec3 neu = new Vec3(dir[0] * tempo, v.y, dir[1] * tempo);
            p.setDeltaMovement(neu);
            return neu;
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Boat Fly
    // ------------------------------------------------------------------

    /** Das Boot, dem wir die Kollision genommen haben -- um es zurueckzugeben. */
    private static net.minecraft.world.entity.vehicle.boat.AbstractBoat geistBoot = null;

    /**
     * Gilt No Clip fuer dieses Wesen? Fuer dich (auf deiner Seite der
     * LocalPlayer, im Einzelspieler-Server dein ServerPlayer) und dein Boot,
     * solange du im Boot sitzt und Boat Fly mit No Clip an ist.
     */
    public static boolean noClipFuer(net.minecraft.world.entity.Entity e) {
        com.vortex.client.module.modules.BoatFlyModule m =
                ModuleManager.INSTANCE.get(com.vortex.client.module.modules.BoatFlyModule.class);
        if (m == null || !m.isEnabled() || !m.noClip.get() || e == null) return false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer ich = mc.player;
        if (ich == null) return false;
        java.util.UUID meine = ich.getUUID();
        // Spieler: ich selbst (Client oder Einzelspieler-Server), im Boot
        if (e instanceof net.minecraft.world.entity.player.Player pl) {
            return pl.getUUID().equals(meine)
                    && pl.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat;
        }
        // Boot: von mir gesteuert
        if (e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat b) {
            var fahrer = b.getControllingPassenger();
            return fahrer != null && fahrer.getUUID().equals(meine);
        }
        return false;
    }

    private static void bootTick(LocalPlayer p) {
        net.minecraft.world.entity.vehicle.boat.AbstractBoat boot =
                p.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat b ? b : null;
        if (boot != null && noClipFuer(boot)) {
            // Ohne Kollision bewegt Minecraft das Boot einfach weiter.
            if (geistBoot != null && geistBoot != boot) geistBoot.noPhysics = false;
            boot.noPhysics = true;
            geistBoot = boot;
        } else {
            bootAus();
        }
    }

    /** No Clip sofort zuruecknehmen (Ausschalten, Aussteigen). */
    public static void bootAus() {
        if (geistBoot != null) {
            geistBoot.noPhysics = false;
            geistBoot = null;
        }
    }

    /** Bewegung des gesteuerten Boots, direkt vor dem Ausfuehren. */
    public static Vec3 boot(net.minecraft.world.entity.vehicle.boat.AbstractBoat boot, LocalPlayer p, Vec3 v) {
        com.vortex.client.module.modules.BoatFlyModule m = an(com.vortex.client.module.modules.BoatFlyModule.class);
        if (m == null) return v;
        // Freecam: die Tasten steuern dann die Kamera, nicht das Boot.
        if (com.vortex.client.freecam.Freecam.isActive()) return v;
        Minecraft mc = Minecraft.getInstance();

        if (m.faceCamera.get()) boot.setYRot(p.getYRot());

        // Waagerecht: Tasten relativ zur Blickrichtung, Bloecke/s -> pro Tick
        double[] dir = tastenRichtung(mc, p.getYRot());
        double h = m.speed.get() / 20.0;
        double x = dir == null ? 0 : dir[0] * h;
        double z = dir == null ? 0 : dir[1] * h;

        // Senkrecht: Springen hoch, Sprinttaste runter, sonst schweben.
        // (Schleichen steigt aus dem Boot aus -- deshalb nicht dafuer.)
        double vs = m.verticalSpeed.get() / 20.0;
        double y = 0;
        if (mc.options.keyJump.isDown()) y = vs;
        else if (mc.options.keySprint.isDown()) y = -vs;
        else if (m.antiKick.get() && tick % 40 == 0) y = -0.04;

        Vec3 neu = new Vec3(x, y, z);
        boot.setDeltaMovement(neu);
        return neu;
    }

    // ------------------------------------------------------------------
    // Elytra Fly
    // ------------------------------------------------------------------

    private static Vec3 elytra(Minecraft mc, LocalPlayer p, Vec3 v, ElytraFlyModule m) {
        int modus = m.mode.getIndex();
        if (modus == 0) {
            // CONTROL: fliegen wie im Kreativmodus. Ohne Tasten steht man in
            // der Luft; Springen/Schleichen fuer hoch/runter.
            double[] dir = tastenRichtung(mc, p.getYRot());
            double h = m.speed.get();
            double x = dir == null ? 0 : dir[0] * h;
            double z = dir == null ? 0 : dir[1] * h;
            double y;
            if (mc.options.keyJump.isDown()) y = m.verticalSpeed.get();
            else if (mc.options.keyShift.isDown()) y = -m.verticalSpeed.get();
            // Ein Hauch nach unten: manche Server werfen Spieler, die lange
            // exakt auf einer Hoehe schweben.
            else y = m.antiKick.get() ? -0.01 : 0.0;
            return new Vec3(x, y, z);
        }
        if (modus == 1) {
            // BOOST: normaler Gleitflug, aber Vorwaerts beschleunigt in
            // Blickrichtung bis zur Hoechstgeschwindigkeit.
            if (!mc.options.keyUp.isDown()) return v;
            double r = Math.toRadians(p.getYRot());
            double ax = -Math.sin(r) * m.acceleration.get();
            double az = Math.cos(r) * m.acceleration.get();
            double nx = v.x + ax, nz = v.z + az;
            double flach = Math.sqrt(nx * nx + nz * nz);
            double max = m.maxSpeed.get();
            if (flach > max) {
                nx = nx / flach * max;
                nz = nz / flach * max;
            }
            return new Vec3(nx, v.y, nz);
        }
        return v;   // FIREWORK: Bewegung bleibt vanilla, Raketen im Tick
    }

    private static void elytraTick(Minecraft mc, LocalPlayer p) {
        ElytraFlyModule m = an(ElytraFlyModule.class);
        if (m == null || mc.gui.screen() != null) return;
        boolean elytra = p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        if (!elytra) return;

        // Automatisch losfliegen: faellt man mit Elytra und haelt Springen,
        // startet der Gleitflug ohne das zweite Druecken.
        if (m.autoTakeoff.get() && !p.isFallFlying() && !p.onGround() && !p.isInWater()
                && p.getDeltaMovement().y < -0.08 && mc.options.keyJump.isDown()
                && tick - letzterStart > 10 && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p,
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            letzterStart = tick;
        }

        if (m.mode.getIndex() == 2 && p.isFallFlying()) {
            // FIREWORK: unter der eingestellten Geschwindigkeit eine Rakete
            // zuenden, hoechstens alle "Firework Delay" Sekunden.
            Vec3 v = p.getDeltaMovement();
            double tempo = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
            long abstand = (long) (m.fireworkDelay.get() * 20);
            if (tempo < m.fireworkBelow.get() && tick - letzteRakete >= abstand) {
                int platz = Inv.hotbar(p, st -> st.is(Items.FIREWORK_ROCKET));
                if (platz >= 0) {
                    Inv.benutzeMitBlick(mc, platz, null);
                    letzteRakete = tick;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Inventory Move
    // ------------------------------------------------------------------

    private static void inventoryMove(Minecraft mc) {
        InventoryMoveModule m = an(InventoryMoveModule.class);
        Screen s = mc.gui.screen();
        boolean darf = m != null && mc.player != null && s != null && erlaubterBildschirm(s);
        if (!darf) {
            // Beim Schliessen liest Minecraft selbst neu ein, welche Tasten
            // gerade gehalten werden. Hier NICHTS loslassen -- sonst bliebe man
            // stehen, obwohl W noch gedrueckt ist.
            invMoveAktiv = false;
            zuletzt.clear();
            return;
        }
        invMoveAktiv = true;
        uebernimm(mc, mc.options.keyUp);
        uebernimm(mc, mc.options.keyDown);
        uebernimm(mc, mc.options.keyLeft);
        uebernimm(mc, mc.options.keyRight);
        if (m.jump.get()) uebernimm(mc, mc.options.keyJump);
        if (m.sneak.get()) uebernimm(mc, mc.options.keyShift);
        uebernimm(mc, mc.options.keySprint);

        if (m.arrows.get()) {
            float dy = 0, dp = 0;
            if (gedrueckt(mc, InputConstants.KEY_LEFT)) dy -= 4f;
            if (gedrueckt(mc, InputConstants.KEY_RIGHT)) dy += 4f;
            if (gedrueckt(mc, InputConstants.KEY_UP)) dp -= 4f;
            if (gedrueckt(mc, InputConstants.KEY_DOWN)) dp += 4f;
            if (dy != 0 || dp != 0) {
                LocalPlayer p = mc.player;
                p.setYRot(p.getYRot() + dy);
                p.setXRot(Math.max(-90f, Math.min(90f, p.getXRot() + dp)));
            }
        }
    }

    /**
     * Nur Inventare, Kisten und die eigenen Menues -- und nie, solange ein
     * Textfeld den Fokus hat. Sonst liefe man beim Tippen von "w" los.
     */
    private static boolean erlaubterBildschirm(Screen s) {
        if (s.getFocused() instanceof EditBox) return false;
        // Amboss (Namensfeld) und Kreativinventar (Suchfeld): dort wird getippt
        if (s instanceof net.minecraft.client.gui.screens.inventory.AnvilScreen) return false;
        if (s instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) return false;
        if (s instanceof AbstractContainerScreen<?>) return true;
        return s.getClass().getName().startsWith("com.vortex.client.gui.");
    }

    /** Letzter gelesener Zustand je Taste -- nur Wechsel werden weitergegeben. */
    private static final java.util.Map<KeyMapping, Boolean> zuletzt = new java.util.HashMap<>();

    private static void uebernimm(Minecraft mc, KeyMapping k) {
        int code = ((com.vortex.client.mixin.client.KeyMappingKeyAccessor) k).vortex$getKey().getValue();
        // Maustasten (0..7) und nicht belegte Tasten ueberspringen -- nur
        // echte Tastaturtasten lassen sich so abfragen.
        if (code < 32) return;
        boolean jetzt = InputConstants.isKeyDown(mc.getWindow(), code);
        // NUR BEI WECHSELN. Schleichen und Sprinten koennen auf "Umschalten"
        // stehen -- dann schaltet jedes setDown(true) um. Jeden Tick gesetzt,
        // wuerde es jeden Tick an- und ausgehen. Wie eine echte Taste: ein
        // Druck beim Herunterdruecken, ein Loslassen beim Loslassen.
        Boolean vorher = zuletzt.put(k, jetzt);
        if (vorher == null || vorher != jetzt) k.setDown(jetzt);
    }

    private static boolean gedrueckt(Minecraft mc, int code) {
        return InputConstants.isKeyDown(mc.getWindow(), code);
    }
}
