package acup.gunfeel.water;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBase;
import com.hbm.handler.BulletConfigSyncingUtil;
import com.hbm.handler.BulletConfiguration;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

// port only: water for NTM CE's old EntityBulletBase (MixinOldBulletWater). 26.2 X560 counts the masked man's bolts,
// meteors, rockets and orbs and the UFO's rockets as going off on impact in water; NTM CE fires them as the old bullet,
// so here they get the same rules as NTM's bullets (WaterTick): an impact fuze on the surface, a skip under the fuze's
// angle, slowing in water, sinking once stopped and going off where they touch. The same configs as 26.2's list, by
// their names in BulletConfigSyncingUtil: the UFO's rocket has no explosive of its own (its impact behaviour is its
// blast), the masked man's tracer only calls a meteor down. Other old bullets keep NTM's way with water (none).
public final class OldBullets {
    static final String[] IMPACT = {"MASKMAN_BOLT", "MASKMAN_METEOR", "MASKMAN_ROCKET", "MASKMAN_ORB", "UFO_ROCKET"};

    private static volatile Set<BulletConfiguration> configs;

    // the listed configs' names (the stand's rows)
    public static String[] names() {
        return IMPACT.clone();
    }

    private OldBullets() {
    }

    // the listed configs, found once NTM made them (none yet: asked again later)
    static Set<BulletConfiguration> configs() {
        Set<BulletConfiguration> c = configs;
        if (c != null) return c;
        Set<BulletConfiguration> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : IMPACT) {
            try {
                int id = BulletConfigSyncingUtil.class.getField(name).getInt(null);
                BulletConfiguration config = BulletConfigSyncingUtil.pullConfig(id);
                if (config != null) found.add(config);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // not in this NTM: that one keeps NTM's way
            }
        }
        if (!found.isEmpty()) configs = found;
        return found;
    }

    public static boolean listed(BulletConfiguration config) {
        return config != null && !config.isSpectral && configs().contains(config);
    }

    // water's rules apply to this old bullet (its config is set on its first tick)
    public static boolean on(Entity e) {
        return e instanceof EntityBulletBase && listed(((EntityBulletBase) e).getConfig());
    }

    // by the numbers, as 26.2 classes an NTM config with no ammo of its own: an impact charge, blocks a tick
    public static WaterClass cls(BulletConfiguration config) {
        return WaterClass.of("", false, 1, 0F, 0F, config.velocity, true);
    }

    public static double gravity(Entity e) {
        BulletConfiguration c = ((EntityBulletBase) e).getConfig();
        return c == null ? 0 : c.gravity;
    }

    private static volatile boolean warned;

    // water's damage failed on a hit (MixinOldBulletBase): NTM's damage stays, written to the log once
    public static void failed(RuntimeException e) {
        if (warned) return;
        warned = true;
        acup.gunfeel.GunFeel.LOGGER.warn("NTM's old bullet: its damage in water failed, it hits as in NTM", e);
    }

    // what is left of a hit's damage where it met `target` (at: the point, null: the creature's middle): (f there)²,
    // as an NTM bullet's damage falls in water (WaterTick.k on its damage, which the end of each tick moves to f²)
    public static float damage(Entity bullet, Entity target, Vec3d at) {
        if (!on(bullet)) return 1F;
        double f = WaterBullet.f(bullet);
        if (at == null) at = new Vec3d(target.posX, target.posY + target.height / 2, target.posZ);
        return (float) (f * f) * WaterTick.k(bullet, at);
    }
}
