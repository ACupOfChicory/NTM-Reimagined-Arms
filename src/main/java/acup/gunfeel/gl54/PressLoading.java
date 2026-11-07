package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

// Loading a 20 mm magazine in NTM's ammo press (MixinAmmoPress), with grenades in any order. It is not a recipe of the press
// (there would be no end of them): while NO recipe is selected the press takes our grenades in slots 0..7 and one empty
// magazine in slot 8, and when slot 9 (the output) is empty it makes a magazine with those grenades, read left to right, top
// to bottom, empty slots skipped: the top left fires first. 1 to 8 grenades, a part-filled magazine is fine. One grenade from
// each used slot and the empty magazine are taken. With a recipe selected the press is NTM's, untouched. A layout the press
// has made that is not a preset is remembered in it (PressLayouts) and listed in the recipe selector; selected, it works
// like a preset: each slot takes only its grenade.
public final class PressLoading {
    public static final int SLOT_MAG = 8, SLOT_OUT = 9, GRENADES = 8;
    // a slot with something that is not our grenade
    public static final String FOREIGN = "?";

    private PressLoading() {
    }

    // is the press ours: no recipe chosen
    public static boolean ours(int selectedRecipe) {
        return selectedRecipe < 0;
    }

    // what the press lets be put in a slot while it is ours (NTM refuses everything with no recipe)
    public static boolean accepts(int selectedRecipe, int slot, boolean isGrenade, boolean isEmptyMagazine) {
        if (!ours(selectedRecipe)) return false;
        return slot >= 0 && slot < GRENADES ? isGrenade : slot == SLOT_MAG && isEmptyMagazine;
    }

    // the grenades of the new magazine in firing order: slots[i] is the kind of slot i ("" empty, FOREIGN another item);
    // null when nothing is to be made: a recipe is chosen, no empty magazine, the output taken, another item in, no grenade
    public static List<String> contents(int selectedRecipe, List<String> slots, boolean emptyMagazine, boolean outputFree) {
        if (!ours(selectedRecipe) || !emptyMagazine || !outputFree) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < GRENADES && i < slots.size(); i++) {
            String s = slots.get(i);
            if (s.isEmpty()) continue;
            if (s.equals(FOREIGN)) return null;
            out.add(s);
        }
        return out.isEmpty() ? null : out;
    }

    // what the press lets be put in a slot while a stored layout is selected: slot i takes the grenade layout[i] (the first
    // n slots), slot 8 the empty magazine, nothing else
    public static boolean acceptsLayout(List<String> layout, int slot, String kind, boolean isEmptyMagazine) {
        if (slot >= 0 && slot < GRENADES) return slot < layout.size() && layout.get(slot).equals(kind);
        return slot == SLOT_MAG && isEmptyMagazine;
    }

    // the grenades of the new magazine while a stored layout is selected: the layout itself, once its first n slots hold
    // exactly its grenades (the rest empty), the empty magazine is in and the output is free; null otherwise
    public static List<String> contentsLayout(List<String> layout, List<String> slots, boolean emptyMagazine, boolean outputFree) {
        if (!emptyMagazine || !outputFree || layout.isEmpty()) return null;
        for (int i = 0; i < GRENADES; i++) {
            String want = i < layout.size() ? layout.get(i) : "";
            if (!(i < slots.size() ? slots.get(i) : "").equals(want)) return null;
        }
        return layout;
    }

    public static boolean isGrenade(ItemStack s) {
        return s.getItem() instanceof GrenadeItem;
    }

    // one pass of the press (the block entity's performRecipe while no recipe is chosen, or a stored layout is: layout);
    // the grenades of the magazine made, null when none was
    public static List<String> perform(int selectedRecipe, List<String> layout, Container c) {
        if (layout == null && !ours(selectedRecipe)) return null;
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < GRENADES; i++) {
            ItemStack s = c.getItem(i);
            slots.add(s.isEmpty() ? "" : s.getItem() instanceof GrenadeItem g ? g.kind : FOREIGN);
        }
        ItemStack mag = c.getItem(SLOT_MAG);
        boolean empty = !mag.isEmpty() && mag.getItem() == Gl54Items.MAG_EMPTY.get();
        boolean free = c.getItem(SLOT_OUT).isEmpty();
        List<String> made = layout == null ? contents(selectedRecipe, slots, empty, free) : contentsLayout(layout, slots, empty, free);
        if (made == null) return null;
        for (int i = 0; i < GRENADES; i++) if (!slots.get(i).isEmpty()) c.removeItem(i, 1);
        c.removeItem(SLOT_MAG, 1);
        c.setItem(SLOT_OUT, Gl54Items.magazine(new MagContents(made)));
        return made;
    }
}
