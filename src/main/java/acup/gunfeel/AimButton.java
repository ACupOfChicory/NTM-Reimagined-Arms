package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// aimOnRightButton: NTM puts the aim on the gun's third key (the middle mouse button) and the gun's second action on
// its second key (the right button). For a gun that aims, the two keys swap: the right button aims and the second
// action (the mode switch, Tau's charge, ...) goes to the middle button. A gun that does not aim (SPAS-12, the akimbo
// pairs) keeps its second action on the right button. The swap a button got when it went down holds until it is let
// go, and NTM's key is down while any button that stands for it is: a gun switched with a button held still gets the
// release of what that button pressed.
public final class AimButton {
    private AimButton() {
    }

    // by button: 0 = the second key's (right), 1 = the third key's (middle)
    private static final boolean[] DOWN = new boolean[2], SWAPPED = new boolean[2];

    // a button went down or up; returns NTM's second and third keys as they now stand
    public static boolean[] update(int button, boolean down) {
        if (down && !DOWN[button]) SWAPPED[button] = swapFor(Minecraft.getInstance().player);
        DOWN[button] = down;
        return keys();
    }

    static boolean[] keys() {
        return new boolean[] {DOWN[0] && !SWAPPED[0] || DOWN[1] && SWAPPED[1], DOWN[1] && !SWAPPED[1] || DOWN[0] && SWAPPED[0]};
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
