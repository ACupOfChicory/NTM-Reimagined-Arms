package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;
import com.hbm.particle.SpentCasing;

import acup.gunfeel.AmmoLock;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// The GL-54's magazine: the magazine is the ammunition. The gun holds the grenades of the magazine in it and the one in the
// barrel (GunMag); a reload swaps the magazine for the fullest one of the wanted kind from the inventory (at the end of the
// begin phase of NTM's reload, so a reload cut short loses nothing); the barrel's grenade stays: 8 + 1 = 9, and an empty gun
// is cocked and the first fed in: 8. The old magazine goes to the inventory; one not fired out pours into the fullest
// unfinished one of its kind first (MagLogic). Semi-automatic: after a shot the next grenade is fed into the barrel.
// getType is the barrel's grenade (the one NTM fires, Lego.doStandardFire); the kind the gun is "of" is `inGunKind`.
public final class GrenadeMagazine extends MagazineSingleTypeBase {
    public GrenadeMagazine() {
        super(0, Gl54Ammo.CAPACITY);
    }

    public static GunMag gun(ItemStack stack) {
        GunMag g = stack == null ? null : stack.get(Gl54Items.GUN_MAG.get());
        return g == null ? GunMag.NONE : g;
    }

    static void set(ItemStack stack, GunMag g) {
        stack.set(Gl54Items.GUN_MAG.get(), g);
    }

    // ---- what the gun holds ----

    @Override
    public int getAmount(ItemStack stack, Container inventory) {
        return gun(stack).count();
    }

    @Override
    public void setAmount(ItemStack stack, int amount) {
        GunMag g = gun(stack);
        if (amount <= 0) {
            set(stack, new GunMag(List.of(), "", g.sel(), g.inserted(), false));
            return;
        }
        // filled up or cut down (the dev stand, a mob): missing grenades are HE
        List<String> all = new ArrayList<>();
        if (g.hasChamber()) all.add(g.chamber());
        all.addAll(g.mag());
        while (all.size() < amount) all.add(GrenadeKind.HE.key);
        while (all.size() > amount) all.remove(all.size() - 1);
        set(stack, new GunMag(all.subList(1, all.size()), all.get(0), g.sel(), true, false));
    }

    // the grenade that fires: the barrel's
    @Override
    public BulletConfig getType(ItemStack stack, Container inventory) {
        String next = gun(stack).next();
        return next.isEmpty() ? null : Gl54Ammo.type(next);
    }

    @Override
    public void setType(ItemStack stack, BulletConfig type) {
        // the kind is in the grenades themselves
    }

    @Override
    public void initNewType(ItemStack stack, Container inventory) {
    }

    @Override
    public void useUpAmmo(ItemStack stack, Container inventory, int amount) {
        if (!(IMagazine.shouldUseUpTrenchie(inventory))) return;
        GunMag g = gun(stack);
        for (int i = 0; i < amount; i++) g = g.afterShot();
        set(stack, g);
    }

    @Override
    public SpentCasing getCasing(ItemStack stack, Container inventory) {
        BulletConfig t = getType(stack, inventory);
        return (t != null ? t : Gl54Ammo.he()).casing;
    }

    // the magazine in the gun as an item (the HUD's icon, the lock's message); the one in the gun with nothing left: the empty one
    @Override
    public ItemStack getIconForHUD(ItemStack stack, Player player) {
        return Gl54Items.magazine(new MagContents(gun(stack).mag()));
    }

    @Override
    public String reportAmmoStateForHUD(ItemStack stack, Player player) {
        GunMag g = gun(stack);
        return g.mag().size() + (g.hasChamber() ? "+1" : "") + " / " + getCapacity(stack);
    }

    // ---- what a reload does ----

    // the kind R would load (see MagLogic.wanted): the selected one, as the gun last loaded it or the wheel chose
    public String wanted(ItemStack stack, Container inv) {
        GunMag g = gun(stack);
        String sel = g.sel();
        if (sel.isEmpty()) sel = !g.mag().isEmpty() ? g.kind() : g.hasChamber() ? g.chamber() : "";
        List<List<String>> mags = GrenadeType.magazines(inv);
        return MagLogic.wanted(sel, AmmoLock.locked(stack), k -> MagLogic.fullest(mags, k) >= 0);
    }

    // the kind of the magazine in the gun; with it empty the kind the wheel marks (the one R loads)
    public String inGunKind(ItemStack stack, Container inv) {
        GunMag g = gun(stack);
        return g.mag().isEmpty() ? wanted(stack, inv) : g.kind();
    }

    @Override
    public BulletConfig getFirstConfig(ItemStack stack, Container inventory) {
        if (inventory == null) return null;
        String w = wanted(stack, inventory);
        return Gl54Ammo.typeOrNull(w);
    }

    // would a swap change anything; right after one the decider asks once more and must hear no (fresh)
    @Override
    public boolean canReload(ItemStack stack, Container inventory) {
        GunMag g = gun(stack);
        if (g.fresh()) return false;
        if (inventory == null) return g.count() < getCapacity(stack) + 1;
        List<List<String>> mags = GrenadeType.magazines(inventory);
        String w = wanted(stack, inventory);
        int best = MagLogic.fullest(mags, w);
        return best >= 0 && MagLogic.improves(g.kind(), g.mag().size(), w, mags.get(best).size());
    }

    @Override
    public void reloadAction(ItemStack stack, Container inventory) {
        GunMag g = gun(stack);
        if (inventory == null) {
            // a mob: a full magazine of the first kind
            set(stack, g.withMagazine(MagContents.filled(GrenadeKind.HE.key, getCapacity(stack)).rounds(), GrenadeKind.HE.key));
            return;
        }
        List<List<String>> mags = GrenadeType.magazines(inventory);
        MagSwap swap = MagSwap.of(g, mags, wanted(stack, inventory), AmmoLock.locked(stack));
        if (swap == null) return;
        inventory.setItem(swap.taken(), ItemStack.EMPTY);
        set(stack, swap.gun());
        if (swap.pourSlot() >= 0) inventory.getItem(swap.pourSlot()).set(Gl54Items.MAG_CONTENTS.get(), new MagContents(swap.pourContents()));
        // the old magazine back: an item of its own (an empty one, a mixed one, what did not fit a pour)
        if (swap.back() != null) {
            ItemStack back = Gl54Items.magazine(new MagContents(swap.back()));
            if (inventory instanceof Inventory pinv) {
                pinv.add(back);
                if (!back.isEmpty()) pinv.player.drop(back, false);
            } else if (inventory instanceof net.minecraft.world.SimpleContainer sc) {
                sc.addItem(back);
            }
        }
    }

    @Override
    public void standardReload(ItemStack stack, Container inventory, int loadLimit) {
        reloadAction(stack, inventory);
    }

    @Override
    public void setAmountAfterReload(ItemStack stack, int amount) {
        GunMag g = gun(stack);
        if (g.fresh()) set(stack, g.withFresh(false));
        super.setAmountAfterReload(stack, amount);
    }

    // the wheel picked a kind: R loads it; the lock keeps what it is. True when that is another kind than the gun has now
    public boolean choose(ItemStack stack, String kind) {
        GunMag g = gun(stack);
        boolean other = !g.kind().equals(kind) || g.mag().isEmpty();
        set(stack, g.withSel(kind));
        return other;
    }

}
