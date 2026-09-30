package com.vortex.client.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Wegfindung der Lauf-Bots (A* ueber Bloecke).
 *
 * Bis 2.32 liefen Crop und Tree Farmer gerade Linien und gaben auf, sobald
 * etwas im Weg stand -- ein Zaun, eine Wasserrinne, ein Baum. Jetzt suchen
 * sie einen Weg wie ein Spieler:
 *
 *   - gerade und schraeg laufen (schraeg nur, wenn keine Ecke im Weg ist)
 *   - eine Stufe hoch (springen), bis zu 3 Bloecke herunter
 *   - flaches Wasser (ein Block tief, fester Boden) nur, wenn es sein muss
 *   - NIE: Lava, Feuer, Kaktus, Beerenbusch, Magma, Spinnennetz, Pulverschnee
 *   - NIE auf Ackerboden herunterspringen (das zertritt ihn)
 *   - optional (Tree Farmer): durch natuerliches Laub, das unterwegs
 *     weggeschlagen wird -- sonst kommt man unter tief haengende Kronen und
 *     an Stamm und Holz darunter nie heran
 *
 * Diese Klasse kennt Minecraft nicht -- die Welt kommt ueber die Schnittstelle
 * {@link Welt}. So laesst sich die Suche ohne Spiel pruefen, und sie ist in
 * allen Minecraft-Versionen dieselbe.
 */
final class BotWeg {

    private BotWeg() {}

    /** Luft, Pflanzen, Leitern: man geht hindurch. */
    static final int FREI = 0;
    /** Teppich, Stufe, Schnee: niedrig genug, um hinaufzugehen, ohne zu springen. */
    static final int NIEDRIG = 1;
    /** Voller (oder fast voller) Block: man steht darauf. */
    static final int FEST = 2;
    /** Zaun, Mauer, Tor, ungeladen: weder hindurch noch darauf. */
    static final int HOCH = 3;

    /** Was die Suche ueber einen Block wissen muss. */
    interface Welt {
        int art(int x, int y, int z);
        /** Tut weh oder haelt fest (Lava, Feuer, Kaktus, Magma, Spinnennetz ...). */
        boolean gefahr(int x, int y, int z);
        boolean wasser(int x, int y, int z);
        boolean acker(int x, int y, int z);
        /** Darf weggeschlagen werden, um durchzukommen (natuerliches Laub)? Nur mit "freischneiden". */
        default boolean weich(int x, int y, int z) { return false; }
    }

    /** Wo der Weg hin soll. */
    interface Ziel {
        boolean erreicht(int x, int y, int z);
        /** Geschaetzte Reststrecke (darf nicht zu gross sein, sonst wird der Weg krumm). */
        double rest(int x, int y, int z);
    }

    /** Ergebnis: Felder (Fuss-Positionen) vom Start bis zum Ziel. */
    static final class Pfad {
        final List<int[]> felder;
        /** true = Suche abgebrochen (zu weit); der Pfad fuehrt nur naeher heran. */
        final boolean teil;
        Pfad(List<int[]> felder, boolean teil) { this.felder = felder; this.teil = teil; }
    }

    private static final class Knoten implements Comparable<Knoten> {
        final int x, y, z;
        double g, f;
        Knoten vor;
        boolean zu;
        Knoten(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public int compareTo(Knoten o) { return Double.compare(f, o.f); }
    }

    private static long schluessel(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static final int[][] RICHTUNGEN = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * Sucht einen Weg.
     *
     * @param maxKnoten Rechenbudget (einige Tausend reichen fuer 30-40 Bloecke)
     * @param radius    wie weit (waagerecht) sich die Suche vom Start entfernen darf
     * @return Pfad, oder null, wenn es sicher keinen gibt
     */
    static Pfad suche(Welt w, int sx, int sy, int sz, Ziel ziel, int maxKnoten, int radius) {
        return suche(w, sx, sy, sz, ziel, maxKnoten, radius, false);
    }

    /** Wie viel ein weggeschlagener Block "kostet" (etwa so viel wie 3 Schritte Umweg). */
    static final double LAUB_KOSTEN = 3.0;

    /**
     * @param freischneiden Laub ({@link Welt#weich}) an Fuss/Kopf darf auf geraden
     *                      Schritten weggeschlagen werden (BotMotor erledigt das beim Laufen)
     */
    static Pfad suche(Welt w, int sx, int sy, int sz, Ziel ziel, int maxKnoten, int radius, boolean freischneiden) {
        Gecacht welt = new Gecacht(w);
        HashMap<Long, Knoten> alle = new HashMap<>();
        PriorityQueue<Knoten> offen = new PriorityQueue<>();
        Knoten start = new Knoten(sx, sy, sz);
        start.f = ziel.rest(sx, sy, sz);
        alle.put(schluessel(sx, sy, sz), start);
        offen.add(start);
        Knoten naechster = start;
        double naechsterRest = start.f;
        int n = 0;
        while (!offen.isEmpty()) {
            Knoten k = offen.poll();
            if (k.zu) continue;
            k.zu = true;
            if (ziel.erreicht(k.x, k.y, k.z)) return pfad(k, false);
            double r = ziel.rest(k.x, k.y, k.z);
            if (r < naechsterRest) { naechsterRest = r; naechster = k; }
            if (++n > maxKnoten) break;
            for (int[] d : RICHTUNGEN) {
                boolean schraeg = d[0] != 0 && d[1] != 0;
                int nx = k.x + d[0], nz = k.z + d[1];
                if (Math.abs(nx - sx) > radius || Math.abs(nz - sz) > radius) continue;
                // gleiche Hoehe
                if (stehen(welt, nx, k.y, nz)) {
                    if (!schraeg || (durch(welt, k.x + d[0], k.y, k.z) && durch(welt, k.x, k.y, k.z + d[1]))) {
                        pruefe(welt, alle, offen, k, nx, k.y, nz, schraeg ? 1.414 : 1.0, ziel);
                    }
                    continue;
                }
                if (schraeg) continue;           // hoch/runter nur gerade
                // gleiche Hoehe, aber Laub an Fuss oder Kopf: wegschlagen
                if (freischneiden && stehenFrei(welt, nx, k.y, nz)) {
                    pruefe(welt, alle, offen, k, nx, k.y, nz, 1.0 + LAUB_KOSTEN * weiche(welt, nx, k.y, nz), ziel);
                    continue;
                }
                // eine Stufe hoch: ueber dem Kopf muss Platz zum Springen sein.
                // Aus dem Wasser NICHT auf Ackerboden: beim Herausschwimmen faellt man
                // hoeher als bei einer normalen Stufe und zertritt den Acker.
                if (welt.art(k.x, k.y + 2, k.z) == FREI && !welt.gefahr(k.x, k.y + 2, k.z)
                        && stehen(welt, nx, k.y + 1, nz)
                        && !(welt.wasser(k.x, k.y, k.z) && welt.acker(nx, k.y, nz))) {
                    pruefe(welt, alle, offen, k, nx, k.y + 1, nz, 2.0, ziel);
                    continue;
                }
                // Stufe hoch mit Laub ueber dem eigenen Kopf oder am Ziel
                if (freischneiden && !welt.wasser(k.x, k.y, k.z)
                        && (welt.art(k.x, k.y + 2, k.z) == FREI && !welt.gefahr(k.x, k.y + 2, k.z) || welt.weich(k.x, k.y + 2, k.z))
                        && (stehen(welt, nx, k.y + 1, nz) || stehenFrei(welt, nx, k.y + 1, nz))) {
                    int laub = (welt.weich(k.x, k.y + 2, k.z) ? 1 : 0) + weiche(welt, nx, k.y + 1, nz);
                    if (laub > 0) {
                        pruefe(welt, alle, offen, k, nx, k.y + 1, nz, 2.0 + LAUB_KOSTEN * laub, ziel);
                        continue;
                    }
                }
                // herunter: Spalte frei, dann sicher landen
                if (!durch(welt, nx, k.y, nz)) continue;
                for (int fall = 1; fall <= 3; fall++) {
                    int ty = k.y - fall;
                    if (stehen(welt, nx, ty, nz)) {
                        if (welt.acker(nx, ty - 1, nz)) break;      // wuerde den Acker zertreten
                        pruefe(welt, alle, offen, k, nx, ty, nz, 1.0 + 0.5 * fall, ziel);
                        break;
                    }
                    int a = welt.art(nx, ty, nz);
                    if (a != FREI || welt.gefahr(nx, ty, nz) || welt.wasser(nx, ty, nz)) break;
                }
            }
        }
        // Kein Ziel gefunden. Budget erschoepft -> Stueck in die richtige Richtung;
        // alles abgesucht -> es gibt keinen Weg.
        if (n > maxKnoten && naechster != start) return pfad(naechster, true);
        return null;
    }

    private static void pruefe(Gecacht welt, HashMap<Long, Knoten> alle, PriorityQueue<Knoten> offen,
                               Knoten von, int x, int y, int z, double kosten, Ziel ziel) {
        if (welt.wasser(x, y, z)) kosten += 4.0;
        if (nebenGefahr(welt, x, y, z)) kosten += 3.0;
        double g = von.g + kosten;
        long s = schluessel(x, y, z);
        Knoten k = alle.get(s);
        if (k == null) {
            k = new Knoten(x, y, z);
            alle.put(s, k);
        } else if (k.zu || g >= k.g) {
            return;
        }
        k.g = g;
        k.f = g + ziel.rest(x, y, z);
        k.vor = von;
        offen.add(k);
    }

    /** Kann ein Spieler mit den Fuessen in diesem Block stehen? */
    static boolean stehen(Welt w, int x, int y, int z) {
        int fuss = w.art(x, y, z);
        if (fuss != FREI && fuss != NIEDRIG) return false;
        if (w.gefahr(x, y, z) || w.art(x, y + 1, z) != FREI || w.gefahr(x, y + 1, z)) return false;
        if (fuss == NIEDRIG) {
            // steht auf dem niedrigen Block -> der Kopf ragt in den uebernaechsten
            return w.art(x, y + 2, z) == FREI && !w.gefahr(x, y + 2, z);
        }
        return w.art(x, y - 1, z) == FEST && !w.gefahr(x, y - 1, z);
    }

    /**
     * Stehen koennen, NACHDEM Laub an Fuss und/oder Kopf weggeschlagen ist
     * (mindestens einer der beiden ist Laub; der Boden darunter bleibt).
     */
    static boolean stehenFrei(Welt w, int x, int y, int z) {
        boolean fussW = w.weich(x, y, z), kopfW = w.weich(x, y + 1, z);
        if (!fussW && !kopfW) return false;
        if (!fussW && (w.art(x, y, z) != FREI || w.gefahr(x, y, z))) return false;
        if (!kopfW && (w.art(x, y + 1, z) != FREI || w.gefahr(x, y + 1, z))) return false;
        return w.art(x, y - 1, z) == FEST && !w.gefahr(x, y - 1, z);        // auf Laub stehen ist in Ordnung
    }

    private static int weiche(Welt w, int x, int y, int z) {
        return (w.weich(x, y, z) ? 1 : 0) + (w.weich(x, y + 1, z) ? 1 : 0);
    }

    /** Kann ein Spieler durch diesen Block (Fuss- und Kopfhoehe) hindurch? */
    static boolean durch(Welt w, int x, int y, int z) {
        int fuss = w.art(x, y, z);
        return (fuss == FREI || fuss == NIEDRIG) && !w.gefahr(x, y, z)
                && w.art(x, y + 1, z) == FREI && !w.gefahr(x, y + 1, z);
    }

    private static boolean nebenGefahr(Welt w, int x, int y, int z) {
        return w.gefahr(x + 1, y, z) || w.gefahr(x - 1, y, z) || w.gefahr(x, y, z + 1) || w.gefahr(x, y, z - 1);
    }

    private static Pfad pfad(Knoten ende, boolean teil) {
        ArrayList<int[]> l = new ArrayList<>();
        for (Knoten k = ende; k != null; k = k.vor) l.add(new int[]{k.x, k.y, k.z});
        java.util.Collections.reverse(l);
        return new Pfad(l, teil);
    }

    /** Merkt sich Abfragen waehrend einer Suche (jeder Block wird oft gefragt). */
    private static final class Gecacht implements Welt {
        private final Welt w;
        private final HashMap<Long, Integer> daten = new HashMap<>();
        Gecacht(Welt w) { this.w = w; }

        private int info(int x, int y, int z) {
            long s = schluessel(x, y, z);
            Integer v = daten.get(s);
            if (v == null) {
                v = w.art(x, y, z) | (w.gefahr(x, y, z) ? 4 : 0) | (w.wasser(x, y, z) ? 8 : 0) | (w.acker(x, y, z) ? 16 : 0)
                        | (w.weich(x, y, z) ? 32 : 0);
                daten.put(s, v);
            }
            return v;
        }
        @Override public int art(int x, int y, int z) { return info(x, y, z) & 3; }
        @Override public boolean gefahr(int x, int y, int z) { return (info(x, y, z) & 4) != 0; }
        @Override public boolean wasser(int x, int y, int z) { return (info(x, y, z) & 8) != 0; }
        @Override public boolean acker(int x, int y, int z) { return (info(x, y, z) & 16) != 0; }
        @Override public boolean weich(int x, int y, int z) { return (info(x, y, z) & 32) != 0; }
    }

    // ------------------------------------------------------------------
    // Fertige Ziele

    /** Stehen, wo man einen Punkt (Blockmitte) mit der Hand erreicht. */
    static Ziel inReichweite(double px, double py, double pz, double reichweite, double augenHoehe) {
        double r2 = reichweite * reichweite;
        return new Ziel() {
            @Override public boolean erreicht(int x, int y, int z) {
                double dx = x + 0.5 - px, dy = y + augenHoehe - py, dz = z + 0.5 - pz;
                return dx * dx + dy * dy + dz * dz <= r2;
            }
            @Override public double rest(int x, int y, int z) {
                double dx = x + 0.5 - px, dy = y + augenHoehe - py, dz = z + 0.5 - pz;
                return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reichweite);
            }
        };
    }

    /**
     * Nahe genug an einem Punkt, um ihn aufzusammeln (waagerecht hoechstens
     * "r" von der Feldmitte, hoechstens einen Block hoeher/tiefer). Anders als
     * {@link #feld} muss man nicht AUF dem Block stehen -- wichtig, wenn der
     * Gegenstand in einem Busch oder an einer Kante liegt.
     */
    static Ziel nahBei(double px, double py, double pz, double r) {
        int ty = (int) Math.floor(py);
        return new Ziel() {
            @Override public boolean erreicht(int x, int y, int z) {
                double dx = x + 0.5 - px, dz = z + 0.5 - pz;
                return dx * dx + dz * dz <= r * r && Math.abs(y - ty) <= 1;
            }
            @Override public double rest(int x, int y, int z) {
                double dx = x + 0.5 - px, dz = z + 0.5 - pz;
                return Math.max(0, Math.sqrt(dx * dx + dz * dz) - r) + Math.max(0, Math.abs(y - ty) - 1);
            }
        };
    }

    /** In einem Ring um einen Punkt stehen (waagerecht zwischen min und max, etwa auf gleicher Hoehe). */
    static Ziel ring(double px, double pz, int ty, double min, double max) {
        return new Ziel() {
            @Override public boolean erreicht(int x, int y, int z) {
                double dx = x + 0.5 - px, dz = z + 0.5 - pz;
                double h = Math.sqrt(dx * dx + dz * dz);
                return h >= min && h <= max && Math.abs(y - ty) <= 1;
            }
            @Override public double rest(int x, int y, int z) {
                double dx = x + 0.5 - px, dz = z + 0.5 - pz;
                double h = Math.sqrt(dx * dx + dz * dz);
                return (h < min ? min - h : h > max ? h - max : 0) + Math.max(0, Math.abs(y - ty) - 1);
            }
        };
    }

    /** Auf einem bestimmten Feld stehen (z. B. wo ein Gegenstand liegt). */
    static Ziel feld(int tx, int ty, int tz) {
        return new Ziel() {
            @Override public boolean erreicht(int x, int y, int z) {
                return x == tx && z == tz && Math.abs(y - ty) <= 1;
            }
            @Override public double rest(int x, int y, int z) {
                double dx = x - tx, dy = y - ty, dz = z - tz;
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        };
    }
}
