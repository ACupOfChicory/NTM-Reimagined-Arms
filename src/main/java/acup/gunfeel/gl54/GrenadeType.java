package acup.gunfeel.gl54;

import java.util.List;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

// NTM's "ammunition" of the GL-54 in two shapes, both a BulletConfig. A kind of grenade (one per GrenadeKind, made once): what
// the gun fires, its flight; every kind flies as the HE grenade so far (stage 3 gives the others their own). And a wheel entry
// (made for the radial menu, never registered): the magazines with exactly some contents, in that order, and how many there are.
// An entry is equal to another with the same contents; it takes itself out of NTM's list of configs at once, as no number of
// contents may fill it. The picture an entry draws is a magazine with those grenades.
public final class GrenadeType extends BulletConfig {
    // a kind's key, or "entry"
    public final String kind;
    // an entry's contents (null for a kind) and the magazines that have them
    public final List<String> contents;
    public final int magazines;

    GrenadeType(String kind) {
        this.kind = kind;
        this.contents = null;
        this.magazines = 0;
        this.ammoItem = () -> Gl54Items.MAG.get();
        // stage 3: the other kinds' own flight and effect; until then they fly and blast as HE
        Gl54Grenade.apply(this);
    }

    private GrenadeType(List<String> contents, int magazines) {
        this.kind = "entry";
        this.contents = List.copyOf(contents);
        this.magazines = magazines;
        this.ammoItem = () -> Gl54Items.MAG.get();
        // not a config of the game's: out of the list again (the id it was given is not used, the wheel sends the contents)
        BulletConfig.configs.remove(this);
    }

    public static GrenadeType entry(List<String> contents, int magazines) {
        return new GrenadeType(contents, magazines);
    }

    public GrenadeKind grenade() {
        return contents != null ? GrenadeKind.of(MagLogic.kind(contents)) : GrenadeKind.of(kind);
    }

    // the kind of the entry's magazine: a key, "mixed" or "empty"
    public String contentsKind() {
        return contents == null ? kind : MagLogic.kind(contents);
    }

    @Override
    public boolean matchesAmmo(ItemStack slot) {
        if (contents == null || slot.isEmpty() || slot.getItem() != Gl54Items.MAG.get()) return false;
        return contents.equals(MagItem.contents(slot).rounds());
    }

    // a magazine with the entry's grenades (a kind: eight of it)
    @Override
    public ItemStack ammoStack() {
        ItemStack s = new ItemStack(Gl54Items.MAG.get());
        s.set(Gl54Items.MAG_CONTENTS.get(), new MagContents(contents != null ? contents : MagContents.filled(kind, MagContents.CAPACITY).rounds()));
        return s;
    }

    // the grenades in the inventory's magazines with exactly an entry's contents (stacks counted)
    public int count(Container inv) {
        return contents == null ? 0 : MagLogic.reserve(magazines(inv), counts(inv), contents);
    }

    @Override
    public boolean equals(Object o) {
        if (contents == null || !(o instanceof GrenadeType t)) return this == o;
        return contents.equals(t.contents);
    }

    @Override
    public int hashCode() {
        return contents == null ? System.identityHashCode(this) : contents.hashCode();
    }

    // the contents of every slot of the container (a list of the same size; null where there is no magazine with grenades)
    public static List<List<String>> magazines(Container inv) {
        List<List<String>> out = new java.util.ArrayList<>();
        if (inv == null) return out;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            MagContents c = s.getItem() == Gl54Items.MAG.get() ? s.get(Gl54Items.MAG_CONTENTS.get()) : null;
            out.add(c == null ? null : c.rounds());
        }
        return out;
    }

    // how many magazines lie in each slot (a stack of identical ones)
    public static int[] counts(Container inv) {
        int[] out = new int[inv == null ? 0 : inv.getContainerSize()];
        for (int i = 0; i < out.length; i++) out[i] = inv.getItem(i).getCount();
        return out;
    }
}
