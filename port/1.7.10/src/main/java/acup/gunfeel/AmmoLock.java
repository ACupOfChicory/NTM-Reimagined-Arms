package acup.gunfeel;

// port: copy of 26.2's AmmoLock.java (0762086) for NTM 1.7.10. The rules are the same; the searches (count, pick,
// reload's taking, unload's putting back) moved into AmmoLogic, run here on IInventory and NTM's ammo bags. 1.7.10 has
// no IMagazine.ammoBag/isInfiniteBag: a bag is ModItems.ammo_bag or ammo_bag_infinite, its slots
// ItemAmmoBag.InventoryAmmoBag (as MagazineSingleTypeBase.standardReload reads them). An empty stack is null.
// BulletConfig.ammo is a ComparableStack: matchesAmmo -> ammo.matchesRecipe(stack, true), ammoStack -> ammo.toStack().
// The lock's message goes to NTM's line above the hotbar (PlayerInformPacket; 26.2: the overlay message).

import java.util.Collections;
import java.util.List;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.ModItems;
import com.hbm.items.tool.ItemAmmoBag.InventoryAmmoBag;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineBelt;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;
import com.hbm.packet.PacketDispatcher;
import com.hbm.packet.toclient.PlayerInformPacket;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

// Ammo on a gun, kept on the gun itself:
// - lock (key tap, AmmoLockPayload): it keeps the ammo type it has, also once empty, and loads no other
// - choice (key held: the radial menu, AmmoSelectPayload): the type it loads when empty / a belt feeds while there is any;
//   choosing unloads the magazine and reloads with the new type
// - reload (MixinAmmoLock): rounds are taken from the inventory, then ammo bags, then infinite bags; the gun notes
//   whether any came from a bag, and an unload puts them back there first
public final class AmmoLock {
    public static final String KEY = "gunfeel_ammolock";
    public static final String NEXT = "gunfeel_ammonext"; // chosen type id + 1, 0 = none
    public static final String FROM_BAG = "gunfeel_frombag"; // + magazine index: some of its rounds came from a bag
    public static final int INFINITE = AmmoLogic.INFINITE;
    // the gun a magazine is looking for ammo for (MixinAmmoLock: set where the search starts)
    public static final ThreadLocal<ItemStack> STACK = new ThreadLocal<ItemStack>();
    // port: the id of our line in NTM's messages above the hotbar (PlayerInformPacket replaces a line of the same id)
    static final int INFORM_ID = 7710;

    private AmmoLock() {
    }

    public static boolean locked(ItemStack stack) {
        // only a gun with a magazine: NTM's power armour melee controller is a "gun" with none
        return stack != null && stack.getItem() instanceof ItemGunBaseNT && ItemGunBaseNT.getValueInt(stack, KEY) != 0 && primary(stack) != null;
    }

    public static BulletConfig chosen(ItemStack stack) {
        int id = ItemGunBaseNT.getValueInt(stack, NEXT) - 1;
        return id >= 0 && id < BulletConfig.configs.size() ? BulletConfig.configs.get(id) : null;
    }

    // the magazine the menu and the choice are about: the first one of the gun
    public static IMagazine<?> primary(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemGunBaseNT)) return null;
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        if (gun.getConfigCount() == 0) return null;
        Receiver[] receivers = gun.getConfig(stack, 0).getReceivers(stack);
        return receivers == null || receivers.length == 0 ? null : receivers[0].getMagazine(stack);
    }

    public static List<BulletConfig> accepted(IMagazine<?> mag) {
        if (mag instanceof MagazineSingleTypeBase) return ((MagazineSingleTypeBase) mag).acceptedBullets;
        if (mag instanceof MagazineBelt) return ((MagazineBelt) mag).acceptedBullets;
        return Collections.emptyList();
    }

    // port: 26.2's BulletConfig.ammoStack(); null when the config names no item
    public static ItemStack ammoStack(BulletConfig type) {
        return type == null || type.ammo == null ? null : type.ammo.toStack();
    }

    // ---- port: the inventory and NTM's ammo bags as AmmoLogic sees them ----

    static final AmmoLogic.Kit<BulletConfig, ItemStack> KIT = new AmmoLogic.Kit<BulletConfig, ItemStack>() {
        @Override
        public boolean matches(BulletConfig type, ItemStack s) {
            return type.ammo != null && type.ammo.matchesRecipe(s, true);
        }

        @Override
        public int count(ItemStack s) {
            return s.stackSize;
        }

        @Override
        public AmmoLogic.Slots<ItemStack> bag(ItemStack s) {
            return s.getItem() == ModItems.ammo_bag || s.getItem() == ModItems.ammo_bag_infinite ? slots(new InventoryAmmoBag(s)) : null;
        }

        @Override
        public boolean infinite(ItemStack s) {
            return s.getItem() == ModItems.ammo_bag_infinite;
        }

        @Override
        public boolean same(ItemStack a, ItemStack b) {
            return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage() && ItemStack.areItemStackTagsEqual(a, b);
        }

        @Override
        public ItemStack copy(ItemStack s, int n) {
            ItemStack c = s.copy();
            c.stackSize = n;
            return c;
        }
    };

    static AmmoLogic.Slots<ItemStack> slots(final IInventory inv) {
        return inv == null ? null : new AmmoLogic.Slots<ItemStack>() {
            @Override
            public int size() {
                return inv.getSizeInventory();
            }

            @Override
            public ItemStack get(int i) {
                ItemStack s = inv.getStackInSlot(i);
                return s == null || s.stackSize <= 0 ? null : s;
            }

            @Override
            public void set(int i, ItemStack s) {
                inv.setInventorySlotContents(i, s);
            }

            @Override
            public void remove(int i, int n) {
                inv.decrStackSize(i, n);
            }

            @Override
            public int max(ItemStack s) {
                return Math.min(inv.getInventoryStackLimit(), s.getMaxStackSize());
            }
        };
    }

    // rounds of that ammo (items) in the inventory and its ammo bags; INFINITE when an infinite bag holds it
    public static int count(BulletConfig type, IInventory inv) {
        return AmmoLogic.count(type, slots(inv), KIT);
    }

    // the type an empty magazine loads: a locked gun its own (none while there is none: it waits), else the type chosen
    // in the menu while there is any, else the first found as NTM does
    // found: in the inventory, then in ammo bags, then in infinite bags; null = none there
    public static BulletConfig pick(List<BulletConfig> accepted, BulletConfig current, ItemStack gun, IInventory inv) {
        return AmmoLogic.pick(accepted, current, locked(gun), chosen(gun), slots(inv), KIT);
    }

    // what a belt may feed: on a locked gun only the type it fed
    public static List<BulletConfig> belt(MagazineBelt mag, ItemStack stack) {
        if (stack == null || !locked(stack)) return mag.acceptedBullets;
        int id = MagazineBelt.getMagType(stack);
        BulletConfig c = id >= 0 && id < BulletConfig.configs.size() ? BulletConfig.configs.get(id) : null;
        return c != null && mag.acceptedBullets.contains(c) ? Collections.singletonList(c) : mag.acceptedBullets;
    }

    // NTM's standardReload in our order (inventory, bags, infinite bags); false = left to NTM (no inventory)
    public static boolean reload(MagazineSingleTypeBase mag, ItemStack gun, IInventory inv, int loadLimit) {
        if (inv == null) return false;
        int loaded = mag.getAmount(gun, null);
        BulletConfig type;
        if (loaded > 0) {
            type = mag.getType(gun, null);
            if (type == null) type = mag.acceptedBullets.get(0);
        } else {
            // the menu's choice holds while there is any of it; once it has run out the gun takes ammo as NTM does
            if (!locked(gun) && chosen(gun) != null && count(chosen(gun), inv) == 0) ItemGunBaseNT.setValueInt(gun, NEXT, 0);
            type = pick(mag.acceptedBullets, mag.getType(gun, null), gun, inv);
            if (type == null) return true;
        }
        mag.setType(gun, type);
        int per = Math.max(1, type.ammoReloadCount);
        int[] took = AmmoLogic.take(type, AmmoLogic.want(mag.getCapacity(gun), loaded, per, loadLimit), slots(inv), KIT);
        if (took[0] == 0) return true;
        mag.setAmount(gun, Math.min(loaded + took[0] * per, mag.capacity));
        String key = FROM_BAG + mag.index;
        ItemGunBaseNT.setValueInt(gun, key, took[1] != 0 || (loaded > 0 && ItemGunBaseNT.getValueInt(gun, key) != 0) ? 1 : 0);
        return true;
    }

    // the server: the held gun's lock on or off
    public static void toggle(EntityPlayerMP player) {
        ItemStack stack = player.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemGunBaseNT) || primary(stack) == null) return;
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        boolean on = !locked(stack);
        ItemStack icon = null;
        if (on) {
            for (int c = 0; c < gun.getConfigCount(); c++) {
                GunConfig config = gun.getConfig(stack, c);
                Receiver[] receivers = config.getReceivers(stack);
                if (receivers == null) continue;
                for (Receiver r : receivers) {
                    IMagazine<?> mag = r.getMagazine(stack);
                    if (mag == null) continue;
                    // a belt keeps what it feeds now
                    if (mag instanceof MagazineBelt) ((MagazineBelt) mag).getType(stack, player.inventory);
                    if (icon == null) icon = mag.getIconForHUD(stack, player);
                }
            }
        }
        ItemGunBaseNT.setValueInt(stack, KEY, on ? 1 : 0);
        ItemGunBaseNT.setValueInt(stack, NEXT, 0); // locked: keeps its type; unlocked: back to NTM's choice
        // port: the item's name is worked out here (on a dedicated server in its language), as NTM names items in its messages
        IChatComponent msg = on
                ? new ChatComponentTranslation("message.ntmgunfeel.ammo_locked", icon == null ? "?" : icon.getDisplayName())
                : new ChatComponentTranslation("message.ntmgunfeel.ammo_unlocked");
        inform(player, msg);
    }

    private static void inform(EntityPlayerMP player, IChatComponent msg) {
        try {
            PacketDispatcher.wrapper.sendTo(new PlayerInformPacket(msg, INFORM_ID, 1500), player);
        } catch (RuntimeException | LinkageError e) {
            player.addChatComponentMessage(msg); // NTM's channel not there: the chat
        }
    }

    // the server: the radial menu chose `id` for the held gun: unload the magazine, reload with it (a locked gun stays
    // locked, now on that type)
    public static void select(EntityPlayerMP player, int id) {
        ItemStack stack = player.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemGunBaseNT) || id < 0 || id >= BulletConfig.configs.size()) return;
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        for (int c = 0; c < gun.getConfigCount(); c++) if (ItemGunBaseNT.getState(stack, c) != ItemGunBaseNT.GunState.IDLE) return;
        IMagazine<?> mag = primary(stack);
        BulletConfig target = BulletConfig.configs.get(id);
        if (mag == null || !accepted(mag).contains(target) || count(target, player.inventory) == 0) return;
        ItemGunBaseNT.setValueInt(stack, NEXT, id + 1);
        if (mag instanceof MagazineBelt) {
            MagazineBelt.setMagType(stack, id);
            return;
        }
        if (!(mag instanceof MagazineSingleTypeBase)) return;
        MagazineSingleTypeBase single = (MagazineSingleTypeBase) mag;
        BulletConfig current = single.getType(stack, null);
        int rounds = single.getAmount(stack, null);
        if (current == target && rounds >= single.getCapacity(stack)) return;
        if (current != target && rounds > 0) {
            unload(player, current, rounds, ItemGunBaseNT.getValueInt(stack, FROM_BAG + single.index) != 0);
            single.setAmount(stack, 0);
        }
        single.setType(stack, target);
        gun.handleKeybind(player, stack, EnumKeybind.RELOAD, true);
        gun.handleKeybind(player, stack, EnumKeybind.RELOAD, false);
    }

    // rounds back as items: none while an infinite bag holds that ammo; taken from a bag: into the bags holding it
    // first, then the inventory; what does not fit drops
    private static void unload(EntityPlayerMP player, BulletConfig type, int rounds, boolean fromBag) {
        if (type == null) return;
        int items = rounds / Math.max(1, type.ammoReloadCount);
        ItemStack proto = ammoStack(type);
        if (items <= 0 || proto == null) return;
        IInventory inv = player.inventory;
        if (count(type, inv) == INFINITE) return;
        ItemStack left = KIT.copy(proto, items);
        if (fromBag) left = AmmoLogic.toBags(type, left, slots(inv), KIT);
        // port: InventoryPlayer.addItemStackToInventory takes what fits off the stack (26.2: Inventory.add)
        if (left != null && left.stackSize > 0) player.inventory.addItemStackToInventory(left);
        if (left != null && left.stackSize > 0) player.dropPlayerItemWithRandomChoice(left, false);
    }
}
