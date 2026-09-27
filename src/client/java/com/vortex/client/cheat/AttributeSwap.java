package com.vortex.client.cheat;

import com.vortex.client.module.ModuleManager;
import com.vortex.client.module.modules.AttributeSwapModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Logik fuer Attribute Swap (siehe AttributeSwapModule).
 *
 * Ablauf eines Schlags (MaceKillMixin, MultiPlayerGameMode.attack):
 *   HEAD: Slot auf die Waffe stellen. Vanilla schickt danach selbst den
 *         Slot-Wechsel (ensureHasSentCarriedItem) und das Angriffspaket.
 *   TAIL: Slot zurueck und SOFORT melden -- im selben Paket-Stapel, damit
 *         dazwischen kein Server-Tick die Waffenwerte uebernimmt.
 */
public final class AttributeSwap {

    private AttributeSwap() {}

    private static final int MACE = 0, AXE = 1, SWORD = 2;

    private static int zurueck = -1;

    public static boolean aktiv() {
        AttributeSwapModule m = ModuleManager.INSTANCE.get(AttributeSwapModule.class);
        return m != null && m.isEnabled();
    }

    public static void vorDemSchlag(Player spieler, Entity ziel) {
        zurueck = -1;
        AttributeSwapModule m = ModuleManager.INSTANCE.get(AttributeSwapModule.class);
        if (m == null || !m.isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(spieler instanceof LocalPlayer p) || p != mc.player) return;
        if (!(ziel instanceof LivingEntity lz)) return;
        var inv = p.getInventory();
        int jetzt = inv.getSelectedSlot();

        int platz = -1;
        // Blockt das Ziel mit dem Schild: Axt nehmen (Schild wird ausgeschaltet)
        if (m.axeVsShield.get() && lz.isBlocking()) platz = waffenPlatz(p, AXE);
        if (platz < 0) platz = waffenPlatz(p, m.mode.getIndex());
        if (platz < 0 || platz == jetzt) return;

        // Wohin danach? Schwert (falls gewuenscht und vorhanden), sonst alter Slot.
        int danach = jetzt;
        if (m.returnToSword.get() && !inv.getItem(jetzt).is(ItemTags.SWORDS)) {
            int schwert = waffenPlatz(p, SWORD);
            if (schwert >= 0 && schwert != platz) danach = schwert;
        }
        zurueck = danach;
        inv.setSelectedSlot(platz);
    }

    /** true = der Slot wurde zurueckgestellt und muss sofort gemeldet werden. */
    public static boolean nachDemSchlag(Player spieler) {
        if (zurueck < 0) return false;
        int alt = zurueck;
        zurueck = -1;
        if (!(spieler instanceof LocalPlayer p)) return false;
        p.getInventory().setSelectedSlot(alt);
        return true;
    }

    private static int waffenPlatz(LocalPlayer p, int art) {
        var inv = p.getInventory();
        int best = -1;
        int bestWert = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            boolean passt = switch (art) {
                case MACE -> st.is(Items.MACE);
                case AXE -> st.is(ItemTags.AXES);
                default -> st.is(ItemTags.SWORDS);
            };
            if (!passt) continue;
            // Mehr Verzauberungen = besser (die zaehlen beim Swap sofort)
            int wert = st.getEnchantments().size() * 10 + (st.isDamaged() ? 0 : 1);
            if (wert > bestWert) { bestWert = wert; best = i; }
        }
        return best;
    }
}
