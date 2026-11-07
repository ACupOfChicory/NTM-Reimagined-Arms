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
// getType is the barrel's grenade (the one NTM fires, Lego.doStandardFire).
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
            set(stack, new GunMag(List.of(), "", g.want(), g.inserted(), false));
            return;
        }
        // filled up or cut down (the dev stand, a mob): missing grenades are HE
        List<String> all = new ArrayList<>();
        if (g.hasChamber()) all.add(g.chamber());
        all.addAll(g.mag());
        while (all.size() < amount) all.add(GrenadeKind.HE.key);
        while (all.size() > amount) all.remove(all.size() - 1);
        set(stack, new GunMag(all.subList(1, all.size()), all.get(0), g.want(), true, false));
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

    // what R would load: exactly the contents the wheel chose or the gun last took, as a list of grenades in fire order; with none
    // of those about, the fullest magazine there is (MagLogic.wantedContents)
    public List<String> wanted(ItemStack stack, Container inv) {
        GunMag g = gun(stack);
        return MagLogic.wantedContents(g.want(), AmmoLock.locked(stack), GrenadeType.magazines(inv));
    }

    // the kind of the first grenade wanted (NTM asks for a config; the HUD and the wheel use `wanted`)
    @Override
    public BulletConfig getFirstConfig(ItemStack stack, Container inventory) {
        if (inventory == null) return null;
        List<String> w = wanted(stack, inventory);
        return w.isEmpty() ? null : Gl54Ammo.typeOrNull(w.get(0));
    }

    // would a swap change anything; right after one the decider asks once more and must hear no (fresh)
    @Override
    public boolean canReload(ItemStack stack, Container inventory) {
        GunMag g = gun(stack);
        if (g.fresh()) return false;
        if (inventory == null) return g.count() < getCapacity(stack) + 1;
        return MagSwap.of(g, GrenadeType.magazines(inventory), wanted(stack, inventory), AmmoLock.locked(stack)) != null;
    }

    @Override
    public void reloadAction(ItemStack stack, Container inventory) {
        GunMag g = gun(stack);
        if (inventory == null) {
            // a mob: a full magazine of the first kind
            List<String> full = MagContents.filled(GrenadeKind.HE.key, getCapacity(stack)).rounds();
            set(stack, g.withMagazine(full, full));
            return;
        }
        List<List<String>> mags = GrenadeType.magazines(inventory);
        MagSwap swap = MagSwap.of(g, mags, wanted(stack, inventory), AmmoLock.locked(stack));
        if (swap == null) return;
        // one magazine off the stack it lay in (magazines stack to 4)
        inventory.removeItem(swap.taken(), 1);
        set(stack, swap.gun());
        if (swap.pourSlot() >= 0) {
            ItemStack target = inventory.getItem(swap.pourSlot());
            if (target.getCount() > 1) {
                // one of an identical stack is filled up: it leaves the stack, and merges back if another is like it
                target.shrink(1);
                giveBack(inventory, swap.pourContents());
            } else {
                target.set(Gl54Items.MAG_CONTENTS.get(), new MagContents(swap.pourContents()));
            }
        }
        // the old magazine back: an item of its own (an empty one, a mixed one, what did not fit a pour)
        if (swap.back() != null) giveBack(inventory, swap.back());
    }

    // a magazine with those grenades into the inventory (an identical stack takes it), the floor if there is no room
    private static void giveBack(Container inventory, List<String> contents) {
        ItemStack back = Gl54Items.magazine(new MagContents(contents));
        if (inventory instanceof Inventory pinv) {
            pinv.add(back);
            if (!back.isEmpty()) pinv.player.drop(back, false);
        } else if (inventory instanceof net.minecraft.world.SimpleContainer sc) {
            sc.addItem(back);
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

    // the wheel picked magazines with these contents: R loads them. True when the gun's magazine is another, so a reload follows
    public boolean choose(ItemStack stack, List<String> contents) {
        GunMag g = gun(stack);
        set(stack, g.withWant(contents));
        return !g.mag().equals(contents);
    }

    // the server: the wheel chose these contents for the held gun (a magazine with exactly them has to be in the inventory)
    public static void select(net.minecraft.server.level.ServerPlayer player, List<String> contents) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun) || contents.isEmpty() || contents.size() > MagContents.CAPACITY) return;
        var mag = AmmoLock.primary(stack);
        if (!(mag instanceof GrenadeMagazine grenades)) return;
        for (int c = 0; c < gun.getConfigCount(); c++) if (com.hbm.items.weapon.sedna.ItemGunBaseNT.getState(stack, c) != com.hbm.items.weapon.sedna.ItemGunBaseNT.GunState.IDLE) return;
        if (MagLogic.exact(GrenadeType.magazines(player.getInventory()), contents) < 0) return;
        if (grenades.choose(stack, contents)) {
            gun.handleKeybind(player, stack, com.hbm.handler.HbmKeybinds.EnumKeybind.RELOAD, true);
            gun.handleKeybind(player, stack, com.hbm.handler.HbmKeybinds.EnumKeybind.RELOAD, false);
        }
    }
}
