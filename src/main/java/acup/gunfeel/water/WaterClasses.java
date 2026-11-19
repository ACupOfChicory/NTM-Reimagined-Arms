package acup.gunfeel.water;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.water.WaterClass.Expansion;
import acup.gunfeel.water.WaterClass.Fuze;
import net.minecraft.core.registries.BuiltInRegistries;

// What NTM's bullet config says of a bullet in water: its WaterClass, its expansion, its fuze and whether it sets
// alight, read off the config (its ammo item's name, its numbers, NTM's own configs). The classes themselves, by name
// and numbers, are core's (WaterClass).
public final class WaterClasses {
    private WaterClasses() {
    }

    // the class of a bullet NTM fires
    public static WaterClass of(@Nullable BulletConfig c) {
        if (c == null) return WaterClass.PISTOL;
        // a grenade's fragments and bomblets: no ammo of their own, they slow like buckshot
        if (c.ammoItem == null && (c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.fragmentation
                || c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.pellets
                || c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.pellets_heavy)) return WaterClass.SHOT;
        return WaterClass.of(ammoName(c), c.pType == BulletConfig.ProjectileType.BEAM, c.projectilesMax, c.armorThresholdNegation,
                c.armorPiercingPercent, c.velocity, c.onImpact != null);
    }

    // how the bullet expands in water (WaterClass.Expansion), null: it does not
    public static @Nullable Expansion expansion(@Nullable BulletConfig c) {
        return Expansion.of(ammoName(c));
    }

    private static final java.util.Map<BulletConfig, Fuze> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // how the bullet goes off (WaterClass.Fuze); asked every tick of a bullet: kept per config
    public static Fuze fuze(@Nullable BulletConfig c) {
        if (c == null) return Fuze.NONE;
        return CACHE.computeIfAbsent(c, WaterClasses::find);
    }

    private static Fuze find(BulletConfig c) {
        // a cluster grenade's bomblets blow up where they land (ItemGrenadeFilling.pellets)
        if (c.ammoItem == null && (c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.pellets
                || c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.pellets_heavy)) return Fuze.IMPACT;
        if (fuzeField(c) != null) return Fuze.IMPACT;
        return Fuze.of(ammoName(c));
    }

    private static volatile @Nullable Map<BulletConfig, String> fields;

    // the WaterClass.Fuze.IMPACT_FIELDS name of this config, or null
    public static @Nullable String fuzeField(BulletConfig c) {
        Map<BulletConfig, String> f = fields;
        if (f == null) {
            f = NtmFields.configs(Fuze.IMPACT_FIELDS);
            // none found: asked before NTM made its configs, try again later
            if (!f.isEmpty()) fields = f;
        }
        return f.get(c);
    }

    // sets a target alight where it hits (phosphorus, fire): under water it does not
    public static boolean incendiary(@Nullable BulletConfig c) {
        return c != null && (WaterClass.incendiary(ammoName(c)) || c.onImpact != null && c.onImpact == NtmFields.R556_INCENDIARY.get());
    }

    // the ammo item's path (hbm:<path>), "" when it has none
    static String ammoName(@Nullable BulletConfig c) {
        if (c == null) return "";
        // a round of the mod's: as the NTM round it is made after (a Duplex round its SP, the rubber slug the slug)
        c = acup.gunfeel.ammo.NewAmmo.waterLike(c);
        try {
            if (c.ammoItem != null) {
                var key = BuiltInRegistries.ITEM.getKey(c.ammoItem.get());
                if (key.getNamespace().equals("hbm")) return key.getPath();
            }
        } catch (RuntimeException e) {
            // no item: by the numbers alone
        }
        return "";
    }
}
