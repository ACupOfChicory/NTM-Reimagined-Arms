package acup.gunfeel;

// port: 26.2's AimButton.java (f982b26) for NTM 1.7.10. 1.7.10's NTM has no single place every gun key goes through:
// HbmKeybinds polls ClientProxy.getIsKeyPressed for each EnumKeybind and sends the changes. So the swap is made there,
// on the keys' states: GUN_SECONDARY reads the middle button and GUN_TERTIARY the right one for a gun that aims
// (MixinAimButton). The swap a button got when it went down holds until it is let go.

import com.hbm.handler.HbmKeybinds;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

// aimOnRightButton: NTM puts the aim on the gun's third key (the middle mouse button) and the gun's second action on
// its second key (the right button). For a gun that aims, the two keys swap: the right button aims and the second
// action goes to the middle button. A gun that does not aim (SPAS-12, the akimbo pairs) keeps its second action on
// the right button.
public final class AimButton {
    private AimButton() {
    }

    // 0: the second key's button (right), 1: the third key's (middle)
    private static final boolean[] WAS_DOWN = new boolean[2], SWAPPED = new boolean[2];

    // the state NTM gets for its second (secondary true) or third gun key
    public static boolean pressed(boolean secondary) {
        boolean right = button(0, HbmKeybinds.gunSecondaryKey.getIsKeyPressed());
        boolean middle = button(1, HbmKeybinds.gunTertiaryKey.getIsKeyPressed());
        if (secondary) return right && !SWAPPED[0] || middle && SWAPPED[1];
        return middle && !SWAPPED[1] || right && SWAPPED[0];
    }

    private static boolean button(int i, boolean down) {
        if (down && !WAS_DOWN[i]) SWAPPED[i] = swapFor(Minecraft.getMinecraft().thePlayer);
        WAS_DOWN[i] = down;
        return down;
    }

    static boolean swapFor(EntityPlayer player) {
        if (player == null || !acup.gunfeel.compat.Feature.HOLD_AIM.on()) return false;
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemGunBaseNT)) return false;
        Config.select(GunFeelClient.gunId(held.getItem()));
        return Config.enabled && Config.aimOnRightButton && aims((ItemGunBaseNT) held.getItem(), held);
    }

    // the gun aims: one of its configs has something on the third key (NTM puts only the aim there)
    static boolean aims(ItemGunBaseNT gun, ItemStack stack) {
        for (int i = 0; i < gun.getConfigCount(); i++)
            if (gun.getConfig(stack, i).getPressTertiary(stack) != null) return true;
        return false;
    }
}
