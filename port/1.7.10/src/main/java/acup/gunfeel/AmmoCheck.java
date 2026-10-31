package acup.gunfeel;

// port: new for 1.7.10, a dev check without a window (26.2 checks this in ClientSmoke's client scenario). On a dev server
// started with a file "ammocheck" in its folder (run/server), once it is up: a fake player with an NTM gun, ammo in the
// inventory and in an ammo bag, then the reload order, the menu's choice, the lock and the unload into the bag through
// NTM's own magazine methods (so through MixinAmmoLock/MixinAmmoLockBelt) and AmmoLock.select/toggle. Writes
// "ammocheck: ..." lines and a summary to the log.

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.hbm.items.ModItems;
import com.hbm.items.tool.ItemAmmoBag.InventoryAmmoBag;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineBelt;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

final class AmmoCheck {
    private static int ok, failed;

    private AmmoCheck() {
    }

    // GunFeelMod.serverStarted
    static void maybeRun() {
        if (!new File("ammocheck").exists()) return;
        try {
            run();
        } catch (RuntimeException | LinkageError e) {
            failed++;
            GunFeel.LOGGER.error("ammocheck: crashed", e);
        }
        GunFeel.LOGGER.info("ammocheck: {} ok, {} failed", ok, failed);
    }

    private static void check(String what, boolean pass, Object got) {
        if (pass) ok++;
        else failed++;
        GunFeel.LOGGER.info("ammocheck: {} {} (got {})", pass ? "ok  " : "FAIL", what, got);
    }

    private static void run() {
        FakePlayer p = FakePlayerFactory.getMinecraft(MinecraftServer.getServer().worldServers[0]);
        Item single = null, belt = null;
        for (Object o : Item.itemRegistry) {
            if (!(o instanceof ItemGunBaseNT) || ((ItemGunBaseNT) o).getConfigCount() != 1) continue;
            IMagazine<?> mag = AmmoLock.primary(new ItemStack((Item) o));
            if (single == null && mag instanceof MagazineSingleTypeBase && usable(((MagazineSingleTypeBase) mag).acceptedBullets) != null
                    && ((MagazineSingleTypeBase) mag).capacity >= 2) single = (Item) o;
            if (belt == null && mag instanceof MagazineBelt && usable(((MagazineBelt) mag).acceptedBullets) != null) belt = (Item) o;
        }
        check("a gun with a magazine of two ammo types", single != null, single == null ? null : name(single));
        if (single != null) magazine(p, single);
        check("a gun fed by a belt of two ammo types", belt != null, belt == null ? null : name(belt));
        if (belt != null) belt(p, belt);
    }

    // two types of the list with their own items, one round per item
    private static BulletConfig[] usable(List<BulletConfig> list) {
        List<BulletConfig> two = new ArrayList<BulletConfig>();
        for (BulletConfig c : list) {
            if (c.ammo == null || c.ammo.item == null || c.ammoReloadCount != 1) continue;
            if (two.isEmpty() || two.get(0).ammo.item != c.ammo.item || two.get(0).ammo.meta != c.ammo.meta) two.add(c);
            if (two.size() == 2) return two.toArray(new BulletConfig[2]);
        }
        return null;
    }

    private static ItemStack ammo(BulletConfig c, int n) {
        ItemStack s = c.ammo.toStack();
        s.stackSize = n;
        return s;
    }

    private static ItemStack bag(BulletConfig c, int n) {
        ItemStack bag = new ItemStack(ModItems.ammo_bag);
        InventoryAmmoBag in = new InventoryAmmoBag(bag);
        if (n > 0) in.setInventorySlotContents(0, ammo(c, n));
        return bag;
    }

    private static int inBag(ItemStack bag, BulletConfig c) {
        return AmmoLock.count(c, new InventoryAmmoBag(bag));
    }

    private static void idle(ItemStack gun) {
        for (int i = 0; i < ((ItemGunBaseNT) gun.getItem()).getConfigCount(); i++) ItemGunBaseNT.setState(gun, i, ItemGunBaseNT.GunState.IDLE);
    }

    // NTM's reload starts an animation for the player (a packet a fake player cannot take): what AmmoLock did before
    // that counts
    private static void select(FakePlayer p, BulletConfig c) {
        try {
            AmmoLock.select(p, c.id);
        } catch (RuntimeException e) {
            GunFeel.LOGGER.info("ammocheck: (NTM's reload after the choice: {})", e.toString());
        }
    }

    private static void toggle(FakePlayer p) {
        try {
            AmmoLock.toggle(p);
        } catch (RuntimeException e) {
            GunFeel.LOGGER.info("ammocheck: (the message to a fake player: {})", e.toString());
        }
    }

    private static void magazine(FakePlayer p, Item item) {
        ItemStack gun = new ItemStack(item);
        MagazineSingleTypeBase mag = (MagazineSingleTypeBase) AmmoLock.primary(gun);
        BulletConfig[] t = usable(mag.acceptedBullets);
        BulletConfig a = t[0], b = t[1];
        int cap = mag.getCapacity(gun);
        p.inventory.clearInventory(null, -1);
        p.inventory.currentItem = 0;
        p.inventory.setInventorySlotContents(0, gun);
        ItemStack bag = bag(a, cap + 10);
        p.inventory.setInventorySlotContents(1, bag); // the bag before the loose rounds: NTM would take from it first
        p.inventory.setInventorySlotContents(5, ammo(b, cap + 1));
        gun = p.inventory.getStackInSlot(0);
        GunFeel.LOGGER.info("ammocheck: {} capacity {}, ammo {} and {}", name(item), cap, a.id, b.id);

        check("empty: loose rounds before the bag's", mag.getFirstConfig(gun, p.inventory) == b, id(mag.getFirstConfig(gun, p.inventory)));
        mag.standardReload(gun, p.inventory, 999);
        check("reload: full of the loose type", mag.getType(gun, null) == b && mag.getAmount(gun, null) == cap, mag.getAmount(gun, null));
        check("reload: those rounds left the inventory", AmmoLock.count(b, p.inventory) == 1, AmmoLock.count(b, p.inventory));
        check("reload: none from a bag", ItemGunBaseNT.getValueInt(gun, AmmoLock.FROM_BAG + mag.index) == 0, null);

        idle(gun);
        select(p, a);
        check("choice: noted", AmmoLock.chosen(gun) == a, id(AmmoLock.chosen(gun)));
        check("choice: unloaded back into the inventory", AmmoLock.count(b, p.inventory) == cap + 1, AmmoLock.count(b, p.inventory));
        mag.setAmount(gun, 0); // in case NTM's reload ran
        mag.standardReload(gun, p.inventory, 999);
        check("choice: reloads the chosen from the bag", mag.getType(gun, null) == a && mag.getAmount(gun, null) == cap,
                id(mag.getType(gun, null)) + " x" + mag.getAmount(gun, null));
        check("choice: noted as from a bag", ItemGunBaseNT.getValueInt(gun, AmmoLock.FROM_BAG + mag.index) == 1, null);
        bag = p.inventory.getStackInSlot(1);
        int left = inBag(bag, a);
        check("choice: taken out of the bag", left == 10, left);

        // the other type again: the bag's rounds go back into the bag
        idle(gun);
        select(p, b);
        bag = p.inventory.getStackInSlot(1);
        check("unload: back into the bag", inBag(bag, a) == cap + 10, inBag(bag, a));

        // lock on the chosen type: an empty gun waits for it
        mag.setType(gun, a);
        mag.setAmount(gun, 1);
        ItemGunBaseNT.setValueInt(gun, AmmoLock.NEXT, 0);
        toggle(p);
        check("lock: on", AmmoLock.locked(gun), null);
        p.inventory.setInventorySlotContents(1, null);
        mag.setAmount(gun, 0);
        check("lock: without its ammo none loads", mag.getFirstConfig(gun, p.inventory) == null && !mag.canReload(gun, p.inventory),
                id(mag.getFirstConfig(gun, p.inventory)));
        p.inventory.setInventorySlotContents(1, bag(a, 3));
        check("lock: with it, it", mag.getFirstConfig(gun, p.inventory) == a, id(mag.getFirstConfig(gun, p.inventory)));
        toggle(p);
        check("lock: off", !AmmoLock.locked(gun), null);
        check("unlocked: NTM's order again", mag.getFirstConfig(gun, p.inventory) == b, id(mag.getFirstConfig(gun, p.inventory)));
    }

    private static void belt(FakePlayer p, Item item) {
        ItemStack gun = new ItemStack(item);
        MagazineBelt mag = (MagazineBelt) AmmoLock.primary(gun);
        BulletConfig[] t = usable(mag.acceptedBullets);
        BulletConfig a = t[0], b = t[1];
        p.inventory.clearInventory(null, -1);
        p.inventory.currentItem = 0;
        p.inventory.setInventorySlotContents(0, gun);
        p.inventory.setInventorySlotContents(2, ammo(b, 5));
        p.inventory.setInventorySlotContents(3, ammo(a, 5));
        gun = p.inventory.getStackInSlot(0);
        GunFeel.LOGGER.info("ammocheck: {} (belt), ammo {} and {}", name(item), a.id, b.id);
        check("belt: feeds the first found", mag.getFirstConfig(gun, p.inventory) == b, id(mag.getFirstConfig(gun, p.inventory)));
        idle(gun);
        select(p, a);
        check("belt: feeds the chosen", mag.getFirstConfig(gun, p.inventory) == a, id(mag.getFirstConfig(gun, p.inventory)));
        toggle(p);
        check("belt: locked", AmmoLock.locked(gun) && AmmoLock.belt(mag, gun).size() == 1 && AmmoLock.belt(mag, gun).get(0) == a, null);
        p.inventory.setInventorySlotContents(3, null);
        check("belt: locked, the other is not fed", mag.getFirstConfig(gun, p.inventory) != b, id(mag.getFirstConfig(gun, p.inventory)));
        toggle(p);
        check("belt: unlocked, the first found again", mag.getFirstConfig(gun, p.inventory) == b, id(mag.getFirstConfig(gun, p.inventory)));
    }

    // the item's registry name (GunFeelClient.gunId is the client's)
    private static Object name(Item item) {
        return Item.itemRegistry.getNameForObject(item);
    }

    private static Object id(BulletConfig c) {
        return c == null ? null : c.id;
    }
}
