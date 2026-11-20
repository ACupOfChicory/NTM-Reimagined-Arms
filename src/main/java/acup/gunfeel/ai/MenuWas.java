package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import acup.gunfeel.AmmoLock;
import acup.gunfeel.AmmoStats;
import net.minecraft.world.item.ItemStack;

// The stand's "before" for AiCheckKit's menus: RadialMenu.choices, statsFor and open's type as they were before
// AmmoChoices (2026-11-20), on a player given as the client's; dev only (NtmApiTest.DEV_ONLY), so its calls of NTM's are
// no system's
final class MenuWas {
    private MenuWas() {
    }

    // a stack of the round's ammo item, as many as it stacks to, into the player's inventory
    static void give(net.minecraft.world.entity.player.Player player, BulletConfig round) {
        ItemStack ammo = round.ammoStack();
        if (ammo != null && !ammo.isEmpty()) player.getInventory().add(ammo.copyWithCount(ammo.getMaxStackSize()));
    }

    static List<BulletConfig> choices(ItemStack stack, net.minecraft.world.entity.player.Player player) {
        List<BulletConfig> out = new ArrayList<>();
        IMagazine<?> mag = AmmoLock.primary(stack);
        if (mag == null || player == null) return out;
        if (mag instanceof acup.gunfeel.gl54.GrenadeMagazine) {
            var inv = player.getInventory();
            for (var e : acup.gunfeel.gl54.MagLogic.entries(acup.gunfeel.gl54.GrenadeType.magazines(inv), acup.gunfeel.gl54.GrenadeType.counts(inv))) {
                if (out.size() < 16) out.add(acup.gunfeel.gl54.GrenadeType.entry(e.contents(), e.magazines()));
            }
            return out;
        }
        for (BulletConfig c : AmmoLock.accepted(mag)) {
            if (out.size() < 16 && !out.contains(c) && AmmoLock.count(c, player.getInventory()) != 0) out.add(c);
        }
        return out;
    }

    static AmmoStats[] statsFor(ItemStack stack, List<BulletConfig> list) {
        AmmoStats[] out = new AmmoStats[list.size()];
        if (!(stack.getItem() instanceof ItemGunBaseNT gun) || gun.getConfigCount() == 0) return out;
        try {
            var cfg = gun.getConfig(stack, 0);
            var rec = cfg.getReceivers(stack)[0];
            float wear = AmmoStats.wear(ItemGunBaseNT.getWear(stack, 0), cfg.getDurability(stack));
            float base = rec.getBaseDamage(stack), split = rec.getSplitProjectiles(stack);
            for (int i = 0; i < out.length; i++) {
                BulletConfig b = list.get(i);
                if (b instanceof acup.gunfeel.gl54.GrenadeType) continue;
                out[i] = AmmoStats.of(base, wear, split, b.damageMult, b.projectilesMin, b.projectilesMax, b.headshotMult,
                        b.armorThresholdNegation, b.armorPiercingPercent);
            }
        } catch (RuntimeException e) {
            // a gun without receivers: no numbers
        }
        return out;
    }

    static Object type(ItemStack stack, net.minecraft.world.entity.player.Player player) {
        IMagazine<?> mag = AmmoLock.primary(stack);
        Object t = mag == null ? null : mag.getType(stack, player.getInventory());
        if (mag instanceof acup.gunfeel.gl54.GrenadeMagazine grenades) {
            var gm = acup.gunfeel.gl54.GrenadeMagazine.gun(stack);
            t = acup.gunfeel.gl54.GrenadeType.entry(gm.mag().isEmpty() ? grenades.wanted(stack, player.getInventory()) : gm.mag(), 0);
        }
        return t instanceof BulletConfig b ? b : null;
    }
}
