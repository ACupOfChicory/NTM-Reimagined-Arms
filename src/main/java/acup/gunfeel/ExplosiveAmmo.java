package acup.gunfeel;

import com.hbm.items.weapon.sedna.BulletConfig;
import net.minecraft.core.registries.BuiltInRegistries;

/** Семейство определяется боеприпасом, а не стартовой скоростью снаряда. */
public final class ExplosiveAmmo {
    private ExplosiveAmmo() {}
    public static boolean heavy(BulletConfig config) {
        if(config==null || config.ammoItem==null || config.onImpact==null)return false;
        var key=BuiltInRegistries.ITEM.getKey(config.ammoItem.get());
        return key.getNamespace().equals("hbm") && heavyPath(key.getPath());
    }
    /** a rocket (Panzerschreck, Stinger, Quadro, the missile launcher), not a 40 mm shell */
    public static boolean rocket(BulletConfig config) {
        if(!heavy(config))return false;
        return BuiltInRegistries.ITEM.getKey(config.ammoItem.get()).getPath().startsWith("ammo_standard_rocket_");
    }
    static boolean heavyPath(String path) {
        return path.startsWith("ammo_standard_g40_") || path.startsWith("ammo_standard_rocket_");
    }
    // the Jeremy turret's 240 mm shells (hbm:ammo_shell, ammo_shell_*): fast, but drawn by NTM as a model, not a tracer
    public static boolean shell(BulletConfig config) {
        if(config==null || config.ammoItem==null)return false;
        var key=BuiltInRegistries.ITEM.getKey(config.ammoItem.get());
        return key.getNamespace().equals("hbm") && (key.getPath().equals("ammo_shell") || key.getPath().startsWith("ammo_shell_"));
    }
    static float holeScale(BulletConfig config) { return heavy(config)?1.5F:1F; }
}
