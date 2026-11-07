package acup.gunfeel.gl54;

import java.util.List;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

// A kind of 20 mm grenade as NTM's ammunition: the "bullet" the radial menu and the lock know, one per GrenadeKind and one
// for mixed magazines. The ammunition item is the magazine (matchesAmmo is true for a magazine of this kind), and the
// picture the wheel draws is a full magazine of it. A mixed config never flies: the gun shoots the real grenade's own kind.
// Every kind flies as the HE grenade so far (stage 3 gives the others their flight).
public final class GrenadeType extends BulletConfig {
    public final String kind;

    GrenadeType(String kind) {
        this.kind = kind;
        this.ammoItem = () -> Gl54Items.MAG.get();
        // stage 3: the other kinds' own flight and effect; until then they fly and blast as HE
        Gl54Grenade.apply(this);
    }

    public GrenadeKind grenade() {
        return GrenadeKind.of(kind);
    }

    public boolean mixed() {
        return GrenadeKind.MIXED.equals(kind);
    }

    @Override
    public boolean matchesAmmo(ItemStack slot) {
        if (slot.isEmpty() || slot.getItem() != Gl54Items.MAG.get()) return false;
        MagContents c = slot.get(Gl54Items.MAG_CONTENTS.get());
        return c != null && !c.rounds().isEmpty() && c.kind().equals(kind);
    }

    // a full magazine of this kind (the mixed one: the sample order)
    @Override
    public ItemStack ammoStack() {
        ItemStack s = new ItemStack(Gl54Items.MAG.get());
        s.set(Gl54Items.MAG_CONTENTS.get(), mixed() ? new MagContents(GrenadeKind.SAMPLE_MIX) : MagContents.filled(kind, MagContents.CAPACITY));
        return s;
    }

    // the grenades of this kind in the magazines of an inventory
    public int count(Container inv) {
        return MagLogic.stock(magazines(inv), kind).rounds();
    }

    public MagLogic.Stock stock(Container inv) {
        return MagLogic.stock(magazines(inv), kind);
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

}
