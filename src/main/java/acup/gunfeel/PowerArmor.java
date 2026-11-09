package acup.gunfeel;

import java.util.Map;

import com.hbm.items.armor.ModArmorItem;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// powerArmorRecoil: NTM's power armour takes up part of the recoil. Power armour is a chestplate NTM makes as a
// battery-powered suit (ModArmorItemPowered, ModArmorItem.isPowered): T-51, AJR, AJRO, RPA, NCRPA, BJ (with or without
// the jetpack), Envsuit, HEV, FAU, DNS. Not the fuelled steam and diesel suits, not the rest of NTM's armour; the
// chestplate is enough (it carries the arms), charged or not. All of the recoil is the client's: the camera kick, the
// gun model kick the arms follow, NTM's own recoil animation. powerArmorSpread is the server's: a share of the living
// spread (Spread) by the chestplate's set, for any shooter wearing one (spread below).
public final class PowerArmor {
    private PowerArmor() {
    }

    /**
     * powerArmorSpread.<set>: what moving, the air and the bloom add, as a share, by the chestplate's set (the balance's
     * tiers: the later the set, the steadier). Trenchmaster is not power armour to NTM but is on the list; Security, the
     * steam and diesel suits are not (1).
     */
    static final Map<String, Float> SPREAD = Map.ofEntries(Map.entry("t51", 0.8F), Map.entry("hev", 0.7F), Map.entry("ajr", 0.7F),
            Map.entry("ajro", 0.7F), Map.entry("envsuit", 0.7F), Map.entry("trenchmaster", 0.7F), Map.entry("bj", 0.6F),
            Map.entry("ncrpa", 0.6F), Map.entry("rpa", 0.5F), Map.entry("fau", 0.4F), Map.entry("dns", 0.4F));

    /**
     * The share of the spread moving, the air and the bloom add for this shooter with this gun: 1 out of the sets. In
     * order: the gun's own {@code <gun>.powerArmorSpread}; the server file's {@code powerArmorSpread.<set>}; the
     * server file's {@code powerArmorSpread} (the key of before the table: one number for every set); the mod's
     * {@code powerArmorSpread.<set>}; for a power armour chestplate with no line (another mod's, a set NTM adds later)
     * {@code powerArmorSpread}, 0.5.
     */
    public static float spread(LivingEntity e, String gun) {
        if (e == null) return 1F;
        String set = BalanceArmor.setOf(e.getItemBySlot(EquipmentSlot.CHEST), 1);
        float line = set == null ? Float.NaN : ReaimTimes.number("powerArmorSpread." + set, SPREAD.getOrDefault(set, Float.NaN));
        if (Float.isNaN(line) && !worn(e)) return 1F;
        float k = ReaimTimes.number(gun + ".powerArmorSpread", Float.NaN);
        // the file's own line of the set first: a file copied whole from the mod's keeps the table
        if (Float.isNaN(k) && !(set != null && ReaimTimes.inFile("powerArmorSpread." + set)) && ReaimTimes.inFile("powerArmorSpread"))
            k = ReaimTimes.number("powerArmorSpread", 1F);
        if (Float.isNaN(k)) k = Float.isNaN(line) ? ReaimTimes.number("powerArmorSpread", 0.5F) : line;
        return factor(true, k, false);
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
