package acup.gunfeel;

import com.hbm.items.armor.ArmorFSBPowered;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;

// powerArmorRecoil, as 26.2's PowerArmor: NTM's power armour takes up part of the recoil. On NTM CE power armour is a
// chestplate that is a battery-powered suit (ArmorFSBPowered): T-51, AJR, AJRO, RPA, NCRPA, BJ (with or without the
// jetpack), Envsuit, HEV, FAU, DNS, the same ten as 26.2. Not the fuelled steam and diesel suits (ArmorFSBFueled), not
// the rest of NTM's armour; the chestplate is enough (it carries the arms), charged or not. All of the recoil is the
// client's: the camera kick, the gun model kick the arms follow, NTM's own recoil animation.
public final class PowerArmor {
    private PowerArmor() {
    }

    public static boolean worn(EntityPlayer player) {
        return player != null && player.getItemStackFromSlot(EntityEquipmentSlot.CHEST).getItem() instanceof ArmorFSBPowered;
    }

    // the recoil multiplier: 1 out of power armour; in it the key, 0..1, and no lower than half for the camera on a
    // server that sets a minimum (cameraScaleMin), so the key does not take the kick under it
    static float factor(boolean worn, float key, boolean cameraMin) {
        if (!worn || Float.isNaN(key)) return 1F;
        float k = Math.max(0F, Math.min(1F, key));
        return cameraMin ? Math.max(0.5F, k) : k;
    }

    public static float recoil(EntityPlayer player) {
        return factor(worn(player), Config.powerArmorRecoil, false);
    }

    public static float cameraRecoil(EntityPlayer player) {
        return factor(worn(player), Config.powerArmorRecoil, ReaimTimes.number("cameraScaleMin", 0F) > 0F);
    }
}
