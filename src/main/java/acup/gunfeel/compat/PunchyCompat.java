package acup.gunfeel.compat;

import java.lang.reflect.Method;
import java.util.function.Predicate;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.Config;
import acup.gunfeel.DebugLog;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

public final class PunchyCompat {
    private static final String MOD_ID = "punchy";
    private static boolean installed;

    private PunchyCompat() {
    }

    public static void install() {
        if (installed || !Feature.ARMS.on() || !ModList.get().isLoaded(MOD_ID)) return;
        installed = true;
        try {
            Class<?> api = Class.forName("punchy.api.PunchyApi");
            Method leave = api.getMethod("leaveItemsToMinecraft", String.class, Predicate.class);
            Predicate<Object> guns = value -> Config.enabled && Feature.ARMS.on() && isGun(value);
            leave.invoke(null, "ntmgunfeel", guns);
            DebugLog.LOGGER.info("[compat] Punchy leaves NTM guns to NTM Reimagined Arms");
        } catch (ReflectiveOperationException | LinkageError e) {
            DebugLog.LOGGER.warn("[compat] could not tell Punchy to leave NTM guns alone", e);
        }
    }

    private static boolean isGun(Object value) {
        if (value instanceof ItemStack stack) return stack.getItem() instanceof ItemGunBaseNT;
        if (value instanceof Item item) return item instanceof ItemGunBaseNT;
        return false;
    }
}
