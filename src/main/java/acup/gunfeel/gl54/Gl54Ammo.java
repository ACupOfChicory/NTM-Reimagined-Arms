package acup.gunfeel.gl54;

import java.util.LinkedHashMap;
import java.util.Map;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.mags.IMagazine;

// The GL-54's ammunition: a kind of grenade (GrenadeType) per GrenadeKind, made once in the wheel's order, and the magazine of the gun that takes them (GrenadeMagazine). Only HE has a flight of its own so far.
public final class Gl54Ammo {
    public static final int CAPACITY = MagContents.CAPACITY;
    private static Map<String, GrenadeType> types;

    private Gl54Ammo() {
    }

    private static synchronized Map<String, GrenadeType> types() {
        if (types == null) {
            Map<String, GrenadeType> m = new LinkedHashMap<>();
            for (GrenadeKind k : GrenadeKind.values()) m.put(k.key, new GrenadeType(k.key));
            types = m;
        }
        return types;
    }

    // the type of a kind or a grenade's key; the HE grenade for a key not known
    public static GrenadeType type(String kind) {
        GrenadeType t = types().get(kind);
        return t != null ? t : types().get(GrenadeKind.HE.key);
    }

    // the type of the kind or null (for "empty" and the like)
    public static GrenadeType typeOrNull(String kind) {
        return types().get(kind);
    }

    // the high-explosive grenade
    public static BulletConfig he() {
        return type(GrenadeKind.HE.key);
    }

    public static IMagazine<BulletConfig> magazine() {
        GrenadeMagazine mag = new GrenadeMagazine();
        mag.addConfigs(types().values().toArray(new BulletConfig[0]));
        return mag;
    }
}
