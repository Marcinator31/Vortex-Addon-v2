package com.vortex.client.bot;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.vortex.client.cheat.Inv;
import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.ElytraAutopilotModule;
import com.vortex.client.waypoint.WaypointManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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
 *
 * Seit 2.33:
 *   - Ziel auch als Waypoint: /autopilot <name>
 *   - Elytra fast kaputt: Ersatz-Elytra aus dem Inventar wird im Flug angelegt,
 *     gelandet wird erst, wenn keine mehr da ist
 *   - Landung: sicherer Landeplatz (keine Lava, kein Kaktus/Magma/Feuer),
 *     langsames Sinken (sonst Fallschaden), Kreisen ueber dem Platz, wenn der
 *     Bot zu hoch ankommt, Abfangen kurz ueber dem Boden
 *   - Statuszeile: Ziel, Entfernung, Restzeit, Raketen, Elytra
 *
 * Seit 2.37 -- WAENDE: Eine Rakete zieht in Blickrichtung. Bis 2.36 zuendete
 * der Pilot bei Wandkontakt ("hochziehen") und beim Feststecken ("zu
 * langsam") eine Rakete nach der anderen -- in die Wand, bis der Spieler tot
 * war. Jetzt:
 *   - vor JEDER Rakete: in Blickrichtung muessen 14 Bloecke frei sein
 *   - nach Wandkontakt (oder langsam vor einer Wand): 2 s keine Rakete,
 *     abdrehen in die freieste Richtung nahe am Ziel, notfalls steil nach oben
 *   - 3 Raketen ohne Vorankommen in 5 s: 5 s Raketenpause
 */
public final class ElytraPilot {

    private ElytraPilot() {}

    private static Double zielX = null, zielZ = null;
    private static String zielName = null;
    private static long tick = 0;
    private static boolean warAn = false;
    private static long raketeZuletzt = -1000;
    private static int startSchritt = 0;
    private static boolean landen = false;
    private static boolean ohneRaketenGemeldet = false;
    private static long meldungZuletzt = -1000;

    /** Sicherer Landeplatz (null = noch nicht bestimmt). */
    private static BlockPos landePlatz = null;
    private static double tempoGlatt = 0;
    private static boolean imWasserGemeldet = false;
    private static String phase = "Idle";
    /** Letzter Wandkontakt (Tick): danach 2 s abdrehen, Raketen nur mit freiem Weg. */
    private static long wandZuletzt = -1000;
    /** Wann zuletzt Raketen gezuendet wurden, obwohl der Spieler kaum vorankam. */
    private static final java.util.ArrayDeque<Long> LANGSAME_RAKETEN = new java.util.ArrayDeque<>();
    private static long raketenPauseBis = -1;

    private static final SuggestionProvider<FabricClientCommandSource> WAYPOINTS = (ctx, b) -> {
        try {
            String rest = b.getRemaining().toLowerCase();
            for (WaypointManager.Waypoint w : WaypointManager.visibleIn(welt())) {
                String n = w.name.replace(' ', '_');
                if (n.toLowerCase().startsWith(rest)) b.suggest(n);
            }
        } catch (Throwable ignored) { }
        return b.buildFuture();
    };

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tick++;
            try { tick(mc); } catch (Throwable e) { com.vortex.client.core.Errors.report("ElytraPilot", e); }
        });
        ClientCommandRegistrationCallback.EVENT.register((d, access) -> d.register(literal("autopilot")
                .requires(s -> !cleanModules())   // Clean Modules: unsichtbar
                .then(literal("stop").executes(c -> {
                    zielX = zielZ = null;
                    zielName = null;
                    var m = ModuleManager.INSTANCE.get(ElytraAutopilotModule.class);
                    if (m != null && m.isEnabled()) m.setEnabled(false);
                    c.getSource().sendFeedback(Component.literal("§d[Autopilot]§r stopped."));
                    return 1;
                }))
                .then(argument("x", StringArgumentType.word()).suggests(WAYPOINTS)
                        // Ein Wort: Waypoint-Name
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "x");
                            WaypointManager.Waypoint w = finde(name);
                            if (w == null) {
                                boolean woanders = WaypointManager.find(name) != null || WaypointManager.find(name.replace('_', ' ')) != null;
                                c.getSource().sendError(Component.literal(woanders
                                        ? "Waypoint \"" + name + "\" is in another world or dimension."
                                        : "Usage: /autopilot <x> <z>  or  /autopilot <waypoint>"));
                                return 0;
                            }
                            starte(w.x + 0.5, w.z + 0.5, w.name);
                            c.getSource().sendFeedback(Component.literal("§d[Autopilot]§r flying to " + w.name
                                    + " (" + w.x + " / " + w.z + ")."));
                            return 1;
                        })
                        .then(argument("z", StringArgumentType.word()).executes(c -> {
                            double x, z;
                            try {
                                x = Double.parseDouble(StringArgumentType.getString(c, "x"));
                                z = Double.parseDouble(StringArgumentType.getString(c, "z"));
                            } catch (NumberFormatException e) {
                                c.getSource().sendError(Component.literal("Usage: /autopilot <x> <z>  or  /autopilot <waypoint>"));
                                return 0;
                            }
                            starte(x, z, null);
                            c.getSource().sendFeedback(Component.literal("§d[Autopilot]§r flying to "
                                    + Math.round(x) + " / " + Math.round(z) + "."));
                            return 1;
                        })))));
    }

    private static String welt() {
        return com.vortex.client.hud.WaypointRenderer.currentWorldKey(Minecraft.getInstance());
    }

    private static WaypointManager.Waypoint finde(String name) {
        String a = name.toLowerCase(), b = name.replace('_', ' ').toLowerCase();
        for (WaypointManager.Waypoint w : WaypointManager.visibleIn(welt())) {
            String n = w.name.toLowerCase();
            if (n.equals(a) || n.equals(b)) return w;
        }
        return null;
    }

    private static void starte(double x, double z, String name) {
        zielX = x;
        zielZ = z;
        zielName = name;
        landen = false;
        landePlatz = null;
        startSchritt = 0;
        var m = ModuleManager.INSTANCE.get(ElytraAutopilotModule.class);
        if (m != null && !m.isEnabled()) m.setEnabled(true);
    }

    /** Statuszeile fuer die Bot-Seite. */
    public static String status() {
        var m = ModuleManager.INSTANCE.get(ElytraAutopilotModule.class);
        LocalPlayer p = Minecraft.getInstance().player;
        if (m == null || !m.isEnabled() || p == null) return "Idle";
        if (zielX == null) return "No target -- /autopilot <x> <z> or /autopilot <waypoint>";
        double dx = zielX - p.getX(), dz = zielZ - p.getZ();
        long abstand = Math.round(Math.sqrt(dx * dx + dz * dz));
        StringBuilder s = new StringBuilder(phase).append("  |  ")
                .append(zielName != null ? zielName + ": " : "").append(abstand).append(" blocks");
        if (tempoGlatt > 3 && p.isFallFlying()) {
            long sek = Math.round(abstand / tempoGlatt);
            s.append("  |  ~").append(sek >= 60 ? (sek / 60) + " min" : sek + " s");
        }
        s.append("  |  ").append(raketen(p)).append(" rockets");
        ItemStack brust = p.getItemBySlot(EquipmentSlot.CHEST);
        if (brust.is(Items.ELYTRA)) s.append("  |  elytra ").append(brust.getMaxDamage() - brust.getDamageValue());
        return s.toString();
    }

    private static void melde(LocalPlayer p, String text) {
        p.sendSystemMessage(Component.literal("§d[Autopilot]§r " + text));
    }

    private static void ende(Minecraft mc, ElytraAutopilotModule m, LocalPlayer p, String text) {
        melde(p, text);
        zielX = zielZ = null;
        zielName = null;
        landePlatz = null;
        m.setEnabled(false);
        mc.options.keyJump.setDown(false);
    }

    private static void tick(Minecraft mc) {
        ElytraAutopilotModule m = ModuleManager.INSTANCE.get(ElytraAutopilotModule.class);
        LocalPlayer p = mc.player;
        boolean an = m != null && m.isEnabled() && p != null && mc.level != null && mc.gameMode != null;
        if (!an) {
            if (warAn) { mc.options.keyJump.setDown(false); startSchritt = 0; landen = false; landePlatz = null; phase = "Idle"; }
            warAn = false;
            return;
        }
        warAn = true;
        if (!p.isAlive()) return;
        if (zielX == null) {
            phase = "No target";
            if (tick - meldungZuletzt > 200) {
                meldungZuletzt = tick;
                p.sendOverlayMessage(Component.literal("§dAutopilot: set a target with /autopilot <x> <z> or /autopilot <waypoint>"));
            }
            return;
        }
        ItemStack brust = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!brust.is(Items.ELYTRA)) {
            if (p.onGround()) ende(mc, m, p, "No elytra on -- stopped.");
            return;
        }
        int rest = brust.getMaxDamage() - brust.getDamageValue();
        // Die Elytra verliert im Flug etwa 1 Haltbarkeit pro Sekunde. Landen,
        // solange die Rest-Flugzeit noch fuer einen ruhigen Abstieg reicht.
        double ueberBoden = Math.max(0, p.getY() - bodenUnter(mc, p));
        int noetig = (int) Math.ceil(ueberBoden / 0.45 / 20.0) + 15;
        if (rest <= Math.max(m.minDurability.getInt(), noetig) && !landen) {
            if (ersatzElytra(mc, p, m.minDurability.getInt())) {
                melde(p, "Elytra almost broken (" + rest + ") -- switched to a spare one.");
            } else {
                landen = true;
                landePlatz = null;
                melde(p, "Elytra almost broken (" + rest + ") and no spare -- landing.");
            }
        }

        Vec3 v = p.getDeltaMovement();
        double tempo = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;     // Bloecke pro Sekunde
        tempoGlatt = tempoGlatt * 0.95 + tempo * 0.05;

        double dx = zielX - p.getX(), dz = zielZ - p.getZ();
        double abstand = Math.sqrt(dx * dx + dz * dz);
        boolean imLanden = (m.land.get() && abstand < 80) || landen;

        // Landeplatz bestimmen (am Ziel bzw. bei einer Notlandung unter uns)
        if (imLanden && landePlatz == null) {
            landePlatz = landeplatz(mc, landen ? p.getX() + v.x * 30 : zielX, landen ? p.getZ() + v.z * 30 : zielZ);
        }

        // Steht der Spieler, ist er gelandet -- auch wenn der Client noch "gleitet"
        // meldet (im echten Spiel bleibt das nach der Landung manchmal haengen;
        // gefunden im Bot-Test).
        boolean gleitet = p.isFallFlying() && !(p.onGround() && tempo < 6);

        // --- im Wasser gelandet: oben bleiben (sonst ertrinkt ein AFK-Spieler) --
        if (!gleitet && p.isInWater()) {
            mc.options.keyJump.setDown(true);
            phase = "In water -- swim to land";
            if (!imWasserGemeldet) { imWasserGemeldet = true; melde(p, "Landed in water -- holding you at the surface. Swim to land or stop with /autopilot stop."); }
            return;                                      // aus dem Wasser kann man nicht abheben
        }
        imWasserGemeldet = false;

        // --- am Boden -------------------------------------------------------
        if (!gleitet) {
            if (p.onGround()) {
                double platzAbstand = landePlatz == null ? abstand
                        : Math.sqrt(Math.pow(landePlatz.getX() + 0.5 - p.getX(), 2) + Math.pow(landePlatz.getZ() + 0.5 - p.getZ(), 2));
                if (landen || abstand < (m.land.get() ? 60 : 12) || (landePlatz != null && platzAbstand < 20)) {
                    ende(mc, m, p, abstand < 80 ? "Arrived (" + Math.round(abstand) + " blocks from the target)." : "Landed.");
                    return;
                }
                // Ohne Raketen kommt man vom Boden nicht weg -- nicht endlos hopsen.
                if (!m.rockets.get() || raketen(p) == 0) {
                    ende(mc, m, p, "No firework rockets to take off -- stopped.");
                    return;
                }
                // Abheben: springen, im Fallen die Elytra oeffnen
                phase = "Taking off";
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
        double hx = zielX, hz = zielZ;
        if (imLanden && landePlatz != null) { hx = landePlatz.getX() + 0.5; hz = landePlatz.getZ() + 0.5; }
        double ldx = hx - p.getX(), ldz = hz - p.getZ();
        double landeAbstand = Math.sqrt(ldx * ldx + ldz * ldz);
        float zielYaw = (float) (Math.toDegrees(Math.atan2(ldz, ldx)) - 90.0);

        float pitch;
        boolean steigen = false;
        if (imLanden) {
            double boden = landePlatz != null ? landePlatz.getY() : bodenUnter(mc, p);
            double hoeheUeber = p.getY() - boden;
            // Reicht die Elytra nicht mehr fuer ein ruhiges Sinken? Dann steil
            // hinunter und kurz ueber dem Boden abfangen (Fallhoehe wird beim
            // Gleiten zurueckgesetzt, sobald man langsamer als 0.5 Bloecke/Tick sinkt).
            int restFlug = brust.getMaxDamage() - brust.getDamageValue();
            boolean eilig = landen && restFlug * 20 < hoeheUeber / 0.45 + 100;
            // Zu hoch fuer die Strecke? Dann ueber dem Platz kreisen, bis die Hoehe passt.
            if (landeAbstand < 25 && hoeheUeber > landeAbstand * 0.8 + 6) {
                zielYaw += 75f;
                phase = "Landing (circling down)";
            } else {
                phase = "Landing";
            }
            if (hoeheUeber < 3) pitch = -8f;                 // abfangen
            else if (hoeheUeber < 10) pitch = 12f;
            else pitch = 22f;
            if (eilig && hoeheUeber > 50) {              // ab 50 Bloecken abfangen: das Abbremsen braucht Platz
                pitch = 55f;                                  // Sturzflug
                phase = "Emergency landing";
            } else if (v.y < -0.45) {
                // Nie schneller sinken als 0.45 Bloecke/Tick -- sonst gibt es Fallschaden.
                pitch = Math.min(pitch, -5f);
            }
            if (landeAbstand < 3 && hoeheUeber < 6) pitch = Math.min(pitch, 10f);
            // Berg oder Baeume zwischen uns und dem Landeplatz? Hochziehen.
            if (hoeheUeber > 12 && landeAbstand > 20 && gelaendeVoraus(mc, p, zielYaw)) {
                pitch = -30f;
                steigen = true;
            }
        } else {
            phase = "Flying";
            double soll = m.cruiseY.get();
            double hoehe = p.getY();
            if (gelaendeVoraus(mc, p, zielYaw)) { pitch = -35f; steigen = true; }
            else if (hoehe < soll - 8) { pitch = -30f; steigen = true; }
            else if (hoehe > soll + 8) pitch = 15f;
            else pitch = 3f;
        }
        // --- Wand: abdrehen statt Raketen hinein ------------------------------
        // Gegen die Wand gestossen, oder langsam und direkt vor einer (feststecken)?
        boolean steckt = tempo < 3 && freiLaenge(mc, p, p.getYRot(), 0f, 3) < 2.5;
        if (p.horizontalCollision || steckt) wandZuletzt = tick;
        if (tick - wandZuletzt < 40) {
            float[] weg = ausweg(mc, p, zielYaw);
            if (weg != null) {
                zielYaw = weg[0];
                pitch = weg[1];
                steigen = weg[1] < -20f;
            } else {
                pitch = p.getY() - bodenUnter(mc, p) > 6 ? 15f : 0f;   // gleiten, Tempo aufbauen
                steigen = false;
            }
            phase = "Avoiding a wall";
        }
        float drehung = Mth.clamp(Mth.wrapDegrees(zielYaw - p.getYRot()), -m.turnSpeed.getFloat(), m.turnSpeed.getFloat());
        p.setYRot(p.getYRot() + drehung);
        p.setXRot(Mth.lerp(0.35f, p.getXRot(), pitch));

        // Kein Raketen-Einsatz erlaubt und zu tief zum Weitergleiten: landen.
        if (!m.rockets.get() && !landen && !imLanden && p.getY() - bodenUnter(mc, p) < 25) {
            melde(p, "Too low to keep gliding without rockets -- landing.");
            landen = true;
            landePlatz = null;
        }
        // Ziel erreicht, aber "Land At Target" aus: Steuerung zurueckgeben statt zu kreisen.
        if (!m.land.get() && !landen && abstand < 12) {
            ende(mc, m, p, "Arrived above the target -- you have control.");
            return;
        }

        // --- Raketen --------------------------------------------------------
        if ((!imLanden || steigen) && m.rockets.get() && tick - raketeZuletzt >= Math.round(m.rocketDelay.get() * 20)) {
            boolean brauchtSchub = tempo < m.minSpeed.get() || steigen;
            boolean vorausGeladen = mc.level.hasChunk((int) Math.floor(p.getX() + v.x * 40) >> 4, (int) Math.floor(p.getZ() + v.z * 40) >> 4);
            // Die Rakete zieht dahin, wohin man SCHAUT: dort muss frei sein.
            boolean wegFrei = freiLaenge(mc, p, p.getYRot(), p.getXRot(), 14) >= 14;
            boolean nachStoss = tick - wandZuletzt < 40 && p.hurtTime > 0;
            if (brauchtSchub && vorausGeladen && wegFrei && !nachStoss && tick >= raketenPauseBis) {
                if (rakete(mc, p)) {
                    raketeZuletzt = tick;
                    ohneRaketenGemeldet = false;
                    // Feststecken: Raketen, die nichts bringen, nicht weiter zuenden
                    if (tempo < 4) {
                        LANGSAME_RAKETEN.addLast(tick);
                        while (!LANGSAME_RAKETEN.isEmpty() && tick - LANGSAME_RAKETEN.peekFirst() > 100) LANGSAME_RAKETEN.pollFirst();
                        if (LANGSAME_RAKETEN.size() >= 3) {
                            LANGSAME_RAKETEN.clear();
                            raketenPauseBis = tick + 100;
                            melde(p, "Not getting anywhere with rockets (stuck?) -- pausing rockets for 5 s.");
                        }
                    }
                } else if (!landen) {
                    // Ohne Schub haelt man die Hoehe nicht -- geordnet landen statt abzustuerzen.
                    if (!ohneRaketenGemeldet) { ohneRaketenGemeldet = true; melde(p, "Out of firework rockets -- landing."); }
                    landen = true;
                    landePlatz = null;
                }
            }
        }
    }

    /**
     * Wie weit ist in Richtung (yaw, pitch) frei -- hoechstens "max"? Geprueft
     * von Augen und Koerpermitte aus (beim Gleiten ist man nur 0.6 hoch).
     */
    private static double freiLaenge(Minecraft mc, LocalPlayer p, float yaw, float pitch, double max) {
        double y = Math.toRadians(yaw), x = Math.toRadians(pitch);
        Vec3 richtung = new Vec3(-Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x));
        double frei = max;
        for (Vec3 von : new Vec3[]{p.getEyePosition(), p.position().add(0, 0.3, 0)}) {
            Vec3 bis = von.add(richtung.scale(max));
            var hr = mc.level.clip(new net.minecraft.world.level.ClipContext(von, bis,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
            if (hr.getType() != net.minecraft.world.phys.HitResult.Type.MISS) frei = Math.min(frei, hr.getLocation().distanceTo(von));
        }
        return frei;
    }

    /**
     * Ausweg nach einem Wandkontakt: die Richtung, die dem Ziel am naechsten
     * liegt und 24 Bloecke frei ist (leicht steigend); sonst steil nach oben,
     * wenn dort frei ist. {yaw, pitch} oder null (nirgends frei -- nur gleiten).
     */
    private static float[] ausweg(Minecraft mc, LocalPlayer p, float zielYaw) {
        int[] versatz = {0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};
        for (int o : versatz) {
            float yaw = zielYaw + o;
            if (freiLaenge(mc, p, yaw, -15f, 24) >= 24) return new float[]{yaw, -15f};
        }
        if (freiLaenge(mc, p, p.getYRot(), -80f, 16) >= 16) return new float[]{p.getYRot(), -80f};
        return null;
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

    /**
     * Sicherer Landeplatz nahe (x, z): die oberste Flaeche, auf der man stehen
     * kann, ohne Lava/Feuer/Kaktus/Magma/Pulverschnee. Wasser ist in Ordnung
     * (bremst den Fall). Sucht bis 16 Bloecke um den Punkt; null = Chunk noch
     * nicht geladen (dann spaeter noch einmal).
     */
    private static BlockPos landeplatz(Minecraft mc, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!mc.level.hasChunk(bx >> 4, bz >> 4)) return null;
        // Erst festen Boden suchen, erst danach auch Wasser (weich, aber nass).
        for (int durchgang = 0; durchgang < 2; durchgang++) {
            for (int r = 0; r <= 16; r++) {
                for (int ox = -r; ox <= r; ox++) for (int oz = -r; oz <= r; oz++) {
                    if (Math.max(Math.abs(ox), Math.abs(oz)) != r) continue;       // nur der Ring
                    int cx = bx + ox, cz = bz + oz;
                    if (!mc.level.hasChunk(cx >> 4, cz >> 4)) continue;
                    BlockPos oben = oberflaeche(mc, cx, cz);
                    if (oben == null) continue;
                    BlockState unter = mc.level.getBlockState(oben.below());
                    if (unsicher(unter) || unsicher(mc.level.getBlockState(oben))) continue;
                    if (!mc.level.getBlockState(oben).getCollisionShape(mc.level, oben).isEmpty()) continue;
                    boolean nass = unter.getFluidState().is(FluidTags.WATER) || mc.level.getBlockState(oben).getFluidState().is(FluidTags.WATER);
                    if (nass && durchgang == 0) continue;
                    return oben;
                }
            }
        }
        BlockPos o = oberflaeche(mc, bx, bz);
        return o != null ? o : new BlockPos(bx, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz), bz);
    }

    /**
     * Erster freier Block ueber dem Boden. In Dimensionen mit Decke (Nether)
     * liefert die Hoehenkarte das Bedrock-Dach -- dort von der Spielerhoehe
     * abwaerts nach Boden mit zwei freien Bloecken darueber suchen.
     */
    private static BlockPos oberflaeche(Minecraft mc, int x, int z) {
        if (!mc.level.dimensionType().hasCeiling()) {
            return new BlockPos(x, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        }
        LocalPlayer p = mc.player;
        int start = Math.min(p != null ? (int) Math.floor(p.getY()) : 120, mc.level.getMaxY() - 2);
        BlockPos.MutableBlockPos b = new BlockPos.MutableBlockPos();
        for (int y = start; y > mc.level.getMinY(); y--) {
            b.set(x, y, z);
            BlockState st = mc.level.getBlockState(b);
            boolean boden = !st.getCollisionShape(mc.level, b).isEmpty() || st.getFluidState().is(FluidTags.LAVA);
            if (!boden) continue;
            BlockPos oben = new BlockPos(x, y + 1, z);
            if (mc.level.getBlockState(oben).isAir() && mc.level.getBlockState(oben.above()).isAir()) return oben;
        }
        return null;
    }

    private static boolean unsicher(BlockState s) {
        return s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS)
                || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POINTED_DRIPSTONE);
    }

    /** Hoehe des Bodens unter dem Spieler (fuer Notlandungen ohne Platz). */
    private static double bodenUnter(Minecraft mc, LocalPlayer p) {
        int x = Mth.floor(p.getX()), z = Mth.floor(p.getZ());
        if (!mc.level.hasChunk(x >> 4, z >> 4)) return p.getY() - 40;
        BlockPos o = oberflaeche(mc, x, z);
        return o != null ? o.getY() : p.getY() - 40;
    }

    /**
     * Ersatz-Elytra anlegen: die mit der meisten Haltbarkeit aus dem Inventar
     * gegen die getragene tauschen (drei Klicks im Inventarfenster -- die
     * Brust ist dabei nie leer, der Flug geht weiter).
     */
    private static boolean ersatzElytra(Minecraft mc, LocalPlayer p, int minimum) {
        int best = -1, bestRest = minimum + 20;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.is(Items.ELYTRA)) continue;
            int r = st.getMaxDamage() - st.getDamageValue();
            if (r > bestRest) { bestRest = r; best = i; }
        }
        if (best < 0) return false;
        Inv.tausche(mc, Inv.fensterPlatz(best), 6);                 // 6 = Brust im Inventarfenster
        return p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private static int raketen(LocalPlayer p) {
        int n = p.getOffhandItem().is(Items.FIREWORK_ROCKET) ? p.getOffhandItem().getCount() : 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(Items.FIREWORK_ROCKET)) n += st.getCount();
        }
        return n;
    }

    /** Rakete aus Off-Hand oder Hotbar zuenden (Platz danach zurueck); notfalls aus dem Rucksack holen. */
    private static boolean rakete(Minecraft mc, LocalPlayer p) {
        if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
            return true;
        }
        int slot = Inv.hotbar(p, st -> st.is(Items.FIREWORK_ROCKET));
        if (slot < 0) slot = BotMotor.holeInHotbar(mc, p, st -> st.is(Items.FIREWORK_ROCKET));
        if (slot < 0) return false;
        Inv.benutzeMitBlick(mc, slot, null);
        return true;
    }

    /** "Clean Modules" im Client an? (Aelterer Client ohne die Einstellung: nein.) */
    private static boolean cleanModules() {
        try {
            return com.vortex.client.core.CleanModules.aktiv();
        } catch (Throwable alterClient) {
            return false;
        }
    }
}
