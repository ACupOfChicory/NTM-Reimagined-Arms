package acup.gunfeel;

import java.util.Map;

// Power armour's shares of the spread and the recoil by the set (PowerArmor in 26.2 reads the wearer's chestplate)
public class PowerArmorBase {
    PowerArmorBase() {
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

    static float factor(boolean worn, float key, boolean cameraMin) {
        if (!worn || Float.isNaN(key)) return 1F;
        float k = Math.clamp(key, 0F, 1F);
        return cameraMin ? Math.max(0.5F, k) : k;
    }
}
