package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// aimOnRightButton: NTM puts the aim on the gun's third key (the middle mouse button) and the gun's second action on
// its second key (the right button). For a gun that aims, the two keys swap: the right button aims and the second
// action (the mode switch, Tau's charge, ...) goes to the middle button. A gun that does not aim (SPAS-12, the akimbo
// pairs) keeps its second action on the right button. The swap a key got when pressed holds until it is let go, so a
// gun switched with the key held still gets the release of what it pressed.
public final class AimButton {
    private AimButton() {
    }

    // NTM's gun keys by index: 0 primary, 1 secondary, 2 tertiary, 3 reload
    private static final boolean[] SWAPPED_AT_PRESS = new boolean[4];

    // the key NTM is told about for this physical gun key
    public static int map(int index, boolean down) {
        if (index != 1 && index != 2) return index;
        if (down) SWAPPED_AT_PRESS[index] = swapFor(Minecraft.getInstance().player);
        return SWAPPED_AT_PRESS[index] ? 3 - index : index;
    }

    static boolean swapFor(Player player) {
        if (player == null || !acup.gunfeel.compat.Feature.HOLD_AIM.on()) return false;
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof ItemGunBaseNT gun)) return false;
        Config.select(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        return Config.enabled && Config.aimOnRightButton && aims(gun, held);
    }

    // the gun aims: one of its configs has something on the third key (NTM puts only the aim there)
    static boolean aims(ItemGunBaseNT gun, ItemStack stack) {
        for (int i = 0; i < gun.getConfigCount(); i++)
            if (gun.getConfig(stack, i).getPressTertiary(stack) != null) return true;
        return false;
    }
}
