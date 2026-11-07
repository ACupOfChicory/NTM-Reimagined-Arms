package acup.gunfeel;

import com.hbm.items.armor.ModArmorItem;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// powerArmorRecoil: NTM's power armour takes up part of the recoil. Power armour is a chestplate NTM makes as a
// battery-powered suit (ModArmorItemPowered, ModArmorItem.isPowered): T-51, AJR, AJRO, RPA, NCRPA, BJ (with or without
// the jetpack), Envsuit, HEV, FAU, DNS. Not the fuelled steam and diesel suits, not the rest of NTM's armour; the
// chestplate is enough (it carries the arms), charged or not. All of the recoil is the client's: the camera kick, the
// gun model kick the arms follow, NTM's own recoil animation. powerArmorSpread is the server's: the same suits take up
// part of the living spread (Spread), for any shooter wearing one.
public final class PowerArmor {
    private PowerArmor() {
    }

    public static boolean worn(LivingEntity wearer) {
        return wearer != null && wearer.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ModArmorItem armor && armor.isPowered();
    }

    // the recoil multiplier: 1 out of power armour; in it the key, 0..1, and no lower than half for the camera on a
    // server that sets a minimum (cameraScaleMin), so the key does not take the kick under it
    static float factor(boolean worn, float key, boolean cameraMin) {
        if (!worn || Float.isNaN(key)) return 1F;
        float k = Math.clamp(key, 0F, 1F);
        return cameraMin ? Math.max(0.5F, k) : k;
    }

    public static float recoil(Player player) {
        return factor(worn(player), Config.powerArmorRecoil, false);
    }

    public static float cameraRecoil(Player player) {
        return factor(worn(player), Config.powerArmorRecoil, ReaimTimes.number("cameraScaleMin", 0F) > 0F);
    }
}
