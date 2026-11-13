package acup.gunfeel;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;

import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// The stands' look at NTM's aim (BalanceCheck, SpreadCheck; Fable №3, 0.1). Its own class, listed as the stands' only
// (NtmApiTest.DEV_ONLY): what it takes from NTM is not the mod's, and an NTM that renames it turns nothing off.
final class BalanceAim {
    /** how a gun aims in NTM: the standard aim key (LAMBDA_TOGGLE_AIM), a key of its own, or none */
    enum Key {
        TOGGLE, OWN, NONE
    }

    private BalanceAim() {
    }

    /**
     * the gun's aim key: none for two guns in NTM's two configs (maresleg_akimbo, uzi_akimbo, minigun_dual and the
     * rest; owner 2026-11-13) and for a gun whose third key is nothing (the SPAS-12: its second key fires)
     */
    static Key key(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemGunBaseNT gun) || gun.getConfigCount() > 1) return Key.NONE;
        var pt = gun.getConfig(stack, 0).getPressTertiary(stack);
        return pt == null ? Key.NONE : pt == Lego.LAMBDA_TOGGLE_AIM ? Key.TOGGLE : Key.OWN;
    }

    /** the aim key (GUN_TERTIARY) pressed or let go, as a player's */
    static void press(ItemGunBaseNT gun, LivingEntity who, Container inv, ItemStack stack, boolean down) {
        gun.handleKeybind(who, inv, stack, EnumKeybind.GUN_TERTIARY, down);
    }

    /** NTM's spread as Lego.calcSpread works it out with no wear: aimed (the gun's and the round's) and from the hip */
    static float[] spread(Receiver r, ItemStack stack, BulletConfig ammo) {
        float base = r.getInnateSpread(stack) + ammo.spread * r.getAmmoSpread(stack);
        return new float[] {base, base + r.getHipfireSpread(stack)};
    }
}
