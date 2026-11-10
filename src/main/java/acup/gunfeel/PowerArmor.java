package acup.gunfeel;

import java.util.Map;

import com.hbm.items.armor.ModArmorItem;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// powerArmorSpread and powerArmorRecoil (server settings, the balance's tiers): NTM's power armour holds the gun
// steadier and takes up part of the recoil, the later the set the more. Power armour is a chestplate NTM makes as a
// battery-powered suit (ModArmorItemPowered, ModArmorItem.isPowered): T-51, AJR, AJRO, RPA, NCRPA, BJ (with or without
// the jetpack), Envsuit, HEV, FAU, DNS; Trenchmaster is on the table too. Not the fuelled steam and diesel suits nor
// Security; the chestplate is enough (it carries the arms), charged or not. The spread is the server's (Spread, any
// shooter); the recoil is drawn by the client (the camera kick, the gun model kick the arms follow, NTM's own recoil
// animation) by the server's numbers: it is the mod's balance, not a player's setting.
public final class PowerArmor {
    private PowerArmor() {
    }

    /**
     * the tiers: what moving, the air and the bloom add (powerArmorSpread.<set>) and the recoil (powerArmorRecoil.<set>),
     * as a share, by the chestplate's set. Trenchmaster is not power armour to NTM but is on the list; Security, the
     * steam and diesel suits are not (1).
     */
    static final Map<String, Float> TIERS = Map.ofEntries(Map.entry("t51", 0.8F), Map.entry("hev", 0.7F), Map.entry("ajr", 0.7F),
            Map.entry("ajro", 0.7F), Map.entry("envsuit", 0.7F), Map.entry("trenchmaster", 0.7F), Map.entry("bj", 0.6F),
            Map.entry("ncrpa", 0.6F), Map.entry("rpa", 0.5F), Map.entry("fau", 0.4F), Map.entry("dns", 0.4F));

    /** the share for a power armour chestplate with no line of its own (another mod's, a set NTM adds later) */
    static final float NO_LINE = 0.5F;

    /**
     * The share of the spread moving, the air and the bloom add for this shooter with this gun: 1 out of the sets. In
     * order: the gun's own {@code <gun>.powerArmorSpread}; {@code powerArmorSpread.<set>} (the server's file, else the
     * mod's); for a power armour chestplate with no line {@code powerArmorSpread} (0.5).
     */
    public static float spread(LivingEntity e, String gun) {
        return share(e, "powerArmorSpread", gun);
    }

    /** {@code <gun>.<key>}, {@code <key>.<set>}, {@code <key>} for power armour with no line; 1 out of the sets */
    static float share(LivingEntity e, String key, String gun) {
        if (e == null) return 1F;
        String set = BalanceArmor.setOf(e.getItemBySlot(EquipmentSlot.CHEST), 1);
        float line = set == null ? Float.NaN : ReaimTimes.number(key + "." + set, TIERS.getOrDefault(set, Float.NaN));
        if (Float.isNaN(line) && !worn(e)) return 1F;
        float k = gun == null ? Float.NaN : ReaimTimes.number(gun + "." + key, Float.NaN);
        if (Float.isNaN(k)) k = Float.isNaN(line) ? ReaimTimes.number(key, NO_LINE) : line;
        return factor(true, k, false);
    }

    public static boolean worn(LivingEntity wearer) {
        return wearer != null && wearer.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ModArmorItem armor && armor.isPowered();
    }

    // the recoil multiplier: 1 out of the sets; in one the share, 0..1, and no lower than half for the camera on a
    // server that sets a minimum (cameraScaleMin), so the armour does not take the kick under it
    static float factor(boolean worn, float key, boolean cameraMin) {
        if (!worn || Float.isNaN(key)) return 1F;
        float k = Math.clamp(key, 0F, 1F);
        return cameraMin ? Math.max(0.5F, k) : k;
    }

    /** the recoil's share in the wearer's set: powerArmorRecoil.<set>, else powerArmorRecoil for power armour (0.5) */
    public static float recoil(Player player) {
        return share(player, "powerArmorRecoil", null);
    }

    public static float cameraRecoil(Player player) {
        return factor(true, recoil(player), ReaimTimes.number("cameraScaleMin", 0F) > 0F);
    }
}
