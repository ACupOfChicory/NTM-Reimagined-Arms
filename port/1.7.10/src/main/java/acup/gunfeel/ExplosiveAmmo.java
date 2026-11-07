package acup.gunfeel;

import com.hbm.items.ModItems;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.GunFactory;

// port: 26.2's ExplosiveAmmo (e284339), on both sides (the server announces the heavy rounds' scorch, the client draws
// it). The family is the ammo's, not the round's speed. NTM 1.7.10 has one ammo item with the kinds as its meta
// (GunFactory.EnumAmmo); the names are 26.2's item ids.
public final class ExplosiveAmmo {
    private ExplosiveAmmo() {
    }

    public static boolean heavy(BulletConfig config) {
        if (config == null || config.ammo == null || config.onImpact == null || config.ammo.item != ModItems.ammo_standard) return false;
        GunFactory.EnumAmmo[] all = GunFactory.EnumAmmo.values();
        int meta = config.ammo.meta;
        if (meta < 0 || meta >= all.length) return false;
        return heavyPath("ammo_standard_" + all[meta].name().toLowerCase(java.util.Locale.ROOT));
    }

    static boolean heavyPath(String path) {
        return path.startsWith("ammo_standard_g40_") || path.startsWith("ammo_standard_rocket_");
    }

    // the Jeremy turret's 240 mm shells (ModItems.ammo_shell, every kind; 26.2: hbm:ammo_shell, ammo_shell_*): fast, but
    // drawn by NTM as a model, not a tracer
    public static boolean shell(BulletConfig config) {
        return config != null && config.ammo != null && config.ammo.item == ModItems.ammo_shell;
    }

    static float holeScale(BulletConfig config) {
        return heavy(config) ? 1.5F : 1F;
    }
}
