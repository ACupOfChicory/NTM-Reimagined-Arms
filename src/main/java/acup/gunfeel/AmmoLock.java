package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineBelt;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

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
    public static final int INFINITE = -1;
    // the gun a magazine is looking for ammo for (MixinAmmoLock: set where the search starts)
    public static final ThreadLocal<ItemStack> STACK = new ThreadLocal<>();

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
        if (!(stack.getItem() instanceof ItemGunBaseNT gun) || gun.getConfigCount() == 0) return null;
        Receiver[] receivers = gun.getConfig(stack, 0).getReceivers(stack);
        return receivers == null || receivers.length == 0 ? null : receivers[0].getMagazine(stack);
    }

    public static List<BulletConfig> accepted(IMagazine<?> mag) {
        if (mag instanceof MagazineSingleTypeBase s) return s.acceptedBullets;
        if (mag instanceof MagazineBelt b) return b.acceptedBullets;
        return List.of();
    }

    // rounds of that ammo (items) in the inventory and its ammo bags; INFINITE when an infinite bag holds it
    public static int count(BulletConfig type, Container inv) {
        if (type == null || inv == null) return 0;
        // the GL-54's grenades are counted, in the magazines of the inventory (not in ammo bags: those are not supported)
        if (type instanceof acup.gunfeel.gl54.GrenadeType g) return g.count(inv);
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (type.matchesAmmo(s)) n += s.getCount();
            var bag = IMagazine.ammoBag(s);
            if (bag == null) continue;
            boolean infinite = IMagazine.isInfiniteBag(s);
            for (int j = 0; j < bag.getContainerSize(); j++) {
                ItemStack in = bag.getItem(j);
                if (in.isEmpty() || !type.matchesAmmo(in)) continue;
                if (infinite) return INFINITE;
                n += in.getCount();
            }
        }
        return n;
    }

    // the type an empty magazine loads: a locked gun its own (none while there is none: it waits), else the type chosen
    // in the menu while there is any, else the first found as NTM does
    // found: in the inventory, then in ammo bags, then in infinite bags; null = none there
    public static BulletConfig pick(List<BulletConfig> accepted, BulletConfig current, ItemStack gun, Container inv) {
        if (locked(gun)) return current != null && count(current, inv) != 0 ? current : null;
        BulletConfig next = chosen(gun);
        if (next != null && accepted.contains(next) && count(next, inv) != 0) return next;
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) continue;
                if (pass == 0) {
                    for (BulletConfig c : accepted) if (c.matchesAmmo(s)) return c;
                    continue;
                }
                var bag = IMagazine.ammoBag(s);
                if (bag == null || IMagazine.isInfiniteBag(s) != (pass == 2)) continue;
                for (int j = 0; j < bag.getContainerSize(); j++) {
                    ItemStack in = bag.getItem(j);
                    if (!in.isEmpty()) for (BulletConfig c : accepted) if (c.matchesAmmo(in)) return c;
                }
            }
        }
        return null;
    }

    // what a belt may feed: on a locked gun only the type it fed
    public static List<BulletConfig> belt(MagazineBelt mag, ItemStack stack) {
        if (stack == null || !locked(stack)) return mag.acceptedBullets;
        int id = MagazineBelt.getMagType(stack);
        BulletConfig c = id >= 0 && id < BulletConfig.configs.size() ? BulletConfig.configs.get(id) : null;
        return c != null && mag.acceptedBullets.contains(c) ? List.of(c) : mag.acceptedBullets;
    }

    // NTM's standardReload in our order (inventory, bags, infinite bags); false = left to NTM (no inventory)
    public static boolean reload(MagazineSingleTypeBase mag, ItemStack gun, Container inv, int loadLimit) {
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
        int want = Math.min((int) Math.ceil((double) (mag.getCapacity(gun) - loaded) / per), loadLimit);
        int took = 0;
        boolean fromBag = false;
        for (int pass = 0; pass < 3 && took < want; pass++) {
            for (int i = 0; i < inv.getContainerSize() && took < want; i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) continue;
                if (pass == 0) {
                    if (!type.matchesAmmo(s)) continue;
                    int n = Math.min(want - took, s.getCount());
                    inv.removeItem(i, n);
                    took += n;
                    continue;
                }
                var bag = IMagazine.ammoBag(s);
                boolean infinite = IMagazine.isInfiniteBag(s);
                if (bag == null || infinite != (pass == 2)) continue;
                for (int j = 0; j < bag.getContainerSize() && took < want; j++) {
                    ItemStack in = bag.getItem(j);
                    if (in.isEmpty() || !type.matchesAmmo(in)) continue;
                    int n = infinite ? want - took : Math.min(want - took, in.getCount());
                    if (!infinite) {
                        bag.removeItem(j, n);
                        fromBag = true;
                    }
                    took += n;
                }
            }
        }
        if (took == 0) return true;
        mag.setAmount(gun, Math.min(loaded + took * per, mag.capacity));
        String key = FROM_BAG + mag.index;
        ItemGunBaseNT.setValueInt(gun, key, fromBag || (loaded > 0 && ItemGunBaseNT.getValueInt(gun, key) != 0) ? 1 : 0);
        return true;
    }

    // the server: the held gun's lock on or off
    public static void toggle(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun) || primary(stack) == null) return;
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
                    if (mag instanceof MagazineBelt belt) belt.getType(stack, player.getInventory());
                    if (icon == null) icon = mag.getIconForHUD(stack, player);
                }
            }
        }
        ItemGunBaseNT.setValueInt(stack, KEY, on ? 1 : 0);
        ItemGunBaseNT.setValueInt(stack, NEXT, 0); // locked: keeps its type; unlocked: back to NTM's choice
        player.sendOverlayMessage(on
                ? Component.translatable("message.ntmgunfeel.ammo_locked", icon == null || icon.isEmpty() ? Component.literal("?") : icon.getHoverName())
                : Component.translatable("message.ntmgunfeel.ammo_unlocked"));
    }

    // the server: the radial menu chose `id` for the held gun: unload the magazine, reload with it (a locked gun stays
    // locked, now on that type)
    public static void select(ServerPlayer player, int id) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun) || id < 0 || id >= BulletConfig.configs.size()) return;
        for (int c = 0; c < gun.getConfigCount(); c++) if (ItemGunBaseNT.getState(stack, c) != ItemGunBaseNT.GunState.IDLE) return;
        IMagazine<?> mag = primary(stack);
        BulletConfig target = BulletConfig.configs.get(id);
        if (mag == null || !accepted(mag).contains(target) || count(target, player.getInventory()) == 0) return;
        ItemGunBaseNT.setValueInt(stack, NEXT, id + 1);
        // the GL-54's wheel sends magazines' contents (Gl54SelectPayload), not a config
        if (mag instanceof acup.gunfeel.gl54.GrenadeMagazine) return;
        if (mag instanceof MagazineBelt) {
            MagazineBelt.setMagType(stack, id);
            return;
        }
        if (!(mag instanceof MagazineSingleTypeBase single)) return;
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
    private static void unload(ServerPlayer player, BulletConfig type, int rounds, boolean fromBag) {
        if (type == null) return;
        int items = rounds / Math.max(1, type.ammoReloadCount);
        ItemStack proto = type.ammoStack();
        if (items <= 0 || proto == null || proto.isEmpty()) return;
        var inv = player.getInventory();
        if (count(type, inv) == INFINITE) return;
        ItemStack left = proto.copyWithCount(items);
        if (fromBag) {
            List<com.hbm.inventory.ItemStackContainer> bags = new ArrayList<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                var bag = s.isEmpty() || IMagazine.isInfiniteBag(s) ? null : IMagazine.ammoBag(s);
                if (bag == null) continue;
                for (int j = 0; j < bag.getContainerSize(); j++) {
                    if (type.matchesAmmo(bag.getItem(j))) {
                        bags.add(bag);
                        break;
                    }
                }
            }
            // onto its stacks there, then into the empty slots of those bags
            for (int pass = 0; pass < 2 && !left.isEmpty(); pass++) {
                for (var bag : bags) {
                    for (int j = 0; j < bag.getContainerSize() && !left.isEmpty(); j++) {
                        ItemStack in = bag.getItem(j);
                        if (pass == 0 && !in.isEmpty() && ItemStack.isSameItemSameComponents(in, left)) {
                            int n = Math.min(left.getCount(), bag.getMaxStackSize(in) - in.getCount());
                            if (n <= 0) continue;
                            bag.setItem(j, in.copyWithCount(in.getCount() + n));
                            left.shrink(n);
                        } else if (pass == 1 && in.isEmpty()) {
                            int n = Math.min(left.getCount(), bag.getMaxStackSize(left));
                            bag.setItem(j, left.copyWithCount(n));
                            left.shrink(n);
                        }
                    }
                }
            }
        }
        if (!left.isEmpty()) inv.add(left);
        if (!left.isEmpty()) player.drop(left, false);
    }

    /** NtmGuns' reads of the ammo menu (NtmGunsPort): RadialMenu's reads as they were, on the holder's hand */
    public static final class Reads {
        private Reads() {
        }

        private static ItemStack stack(acup.gunfeel.host.Body holder, int hand) {
            if (!(EntityBody.entity(holder) instanceof net.minecraft.world.entity.LivingEntity l)) return ItemStack.EMPTY;
            return hand == acup.gunfeel.host.Body.MAIN_HAND ? l.getMainHandItem() : l.getOffhandItem();
        }

        // a player's inventory; none for a mob
        private static Container inventory(acup.gunfeel.host.Body holder) {
            return EntityBody.entity(holder) instanceof net.minecraft.world.entity.player.Player p ? p.getInventory() : null;
        }

        // RadialMenu.choices' magazine and its rounds
        public static int menuRounds(acup.gunfeel.host.Body holder, int hand, int[] out) {
            IMagazine<?> mag = primary(stack(holder, hand));
            if (mag == null) return -1;
            if (mag instanceof acup.gunfeel.gl54.GrenadeMagazine) return acup.gunfeel.host.NtmGuns.ROUNDS_GL54;
            List<BulletConfig> accepted = accepted(mag);
            int n = accepted.size();
            for (int i = 0; i < n && i < out.length; i++) out[i] = RoundNumbers.number(accepted.get(i));
            return n;
        }

        // RadialMenu.choices' count
        public static int reserve(acup.gunfeel.host.Body holder, int round) {
            Container inv = inventory(holder);
            return inv == null ? 0 : count(RoundNumbers.round(round), inv);
        }

        // RadialMenu.open's type: what the first magazine has with the holder's inventory
        public static int loadedRound(acup.gunfeel.host.Body holder, int hand) {
            ItemStack stack = stack(holder, hand);
            IMagazine<?> mag = primary(stack);
            Object t = mag == null ? null : mag.getType(stack, inventory(holder));
            return t instanceof BulletConfig b ? RoundNumbers.number(b) : 0;
        }

        // RadialMenu.statsFor's reads of the gun, in its order, under its catch
        public static boolean choiceStats(acup.gunfeel.host.Body holder, int hand, float[] out) {
            ItemStack stack = stack(holder, hand);
            if (!(stack.getItem() instanceof ItemGunBaseNT gun) || gun.getConfigCount() == 0) return false;
            try {
                GunConfig cfg = gun.getConfig(stack, 0);
                Receiver rec = cfg.getReceivers(stack)[0];
                out[acup.gunfeel.host.NtmGuns.C_WEAR] = ItemGunBaseNT.getWear(stack, 0);
                out[acup.gunfeel.host.NtmGuns.C_DURABILITY] = cfg.getDurability(stack);
                out[acup.gunfeel.host.NtmGuns.C_BASE_DAMAGE] = rec.getBaseDamage(stack);
                out[acup.gunfeel.host.NtmGuns.C_SPLIT] = rec.getSplitProjectiles(stack);
                return true;
            } catch (RuntimeException e) {
                // a gun without receivers: no numbers
                return false;
            }
        }

        // RadialMenu.statsFor's reads of a round
        public static void roundStats(int round, float[] out) {
            BulletConfig b = RoundNumbers.round(round);
            out[acup.gunfeel.host.NtmGuns.S_DAMAGE_MULT] = b.damageMult;
            out[acup.gunfeel.host.NtmGuns.S_PROJECTILES_MIN] = b.projectilesMin;
            out[acup.gunfeel.host.NtmGuns.S_PROJECTILES_MAX] = b.projectilesMax;
            out[acup.gunfeel.host.NtmGuns.S_HEADSHOT_MULT] = b.headshotMult;
            out[acup.gunfeel.host.NtmGuns.S_THRESHOLD_NEGATION] = b.armorThresholdNegation;
            out[acup.gunfeel.host.NtmGuns.S_ARMOR_PIERCING] = b.armorPiercingPercent;
        }
    }

}
