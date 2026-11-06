package acup.gunfeel.compat;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

public final class PunchyMovesCompat {
    private static final String MOD_ID = "punchy_moves";
    private static boolean installed;
    private static Object identityItemPose;

    private PunchyMovesCompat() {
    }

    public static void install() {
        if (installed || !Feature.ARMS.on() || !ModList.get().isLoaded(MOD_ID)) return;
        installed = true;
        try {
            Class<?> pose = Class.forName("punchymoves.client.animation.LocomotionController$ItemPose");
            identityItemPose = pose.getMethod("identity").invoke(null);
            GunFeel.LOGGER.info("[compat] Punchy Moves leaves NTM guns' third-person pose alone");
        } catch (ReflectiveOperationException | LinkageError e) {
            GunFeel.LOGGER.warn("[compat] could not prepare the Punchy Moves gun pose", e);
        }
    }

    public static Object identityItemPose() {
        return identityItemPose;
    }

    public static boolean holdsGun(AbstractClientPlayer player) {
        if (!Config.enabled || !Feature.ARMS.on() || player == null) return false;
        return isGun(player.getMainHandItem()) || isGun(player.getOffhandItem());
    }

    private static boolean isGun(ItemStack stack) {
        return stack.getItem() instanceof ItemGunBaseNT;
    }
}
