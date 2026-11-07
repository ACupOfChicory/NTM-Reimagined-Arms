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
// blast). The masked man's tracer (it calls a meteor down where it hits) and the worm's bolts are NTM's plain bullets on
// 26.2 (XFactoryNPC): here too they slow in water and are gone where it stops them, with no fuze. Other old bullets keep
// NTM's way with water (none).
public final class OldBullets {
    static final String[] IMPACT = {"MASKMAN_BOLT", "MASKMAN_METEOR", "MASKMAN_ROCKET", "MASKMAN_ORB", "UFO_ROCKET"};
    static final String[] PLAIN = {"MASKMAN_TRACER", "WORM_BOLT", "WORM_LASER"};

    // the listed configs (null: not found yet) and those of them with an impact fuze
    private static volatile Set<BulletConfiguration> configs, fuzed;

    // the listed configs' names (the stand's rows)
    public static String[] names() {
        String[] all = java.util.Arrays.copyOf(IMPACT, IMPACT.length + PLAIN.length);
        System.arraycopy(PLAIN, 0, all, IMPACT.length, PLAIN.length);
        return all;
    }

    private OldBullets() {
    }

    // the listed configs, found once NTM made them (none yet: asked again later)
    static Set<BulletConfiguration> configs() {
        Set<BulletConfiguration> c = configs;
        if (c != null) return c;
        Set<BulletConfiguration> withFuze = find(IMPACT);
        Set<BulletConfiguration> found = find(PLAIN);
        found.addAll(withFuze);
        if (!found.isEmpty()) {
            fuzed = withFuze;
            configs = found;
        }
        return found;
    }

    private static Set<BulletConfiguration> find(String[] names) {
        Set<BulletConfiguration> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : names) {
            try {
                int id = BulletConfigSyncingUtil.class.getField(name).getInt(null);
                BulletConfiguration config = BulletConfigSyncingUtil.pullConfig(id);
                if (config != null) found.add(config);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // not in this NTM: that one keeps NTM's way
            }
        }
        return found;
    }

    // a listed old bullet with an impact fuze (the masked man's charges, the UFO's rocket): it goes off on the surface
    // and sinks once stopped; the others are gone where water stops them
    public static boolean fuzed(Entity e) {
        if (!on(e)) return false;
        Set<BulletConfiguration> f = fuzed;
        return f != null && f.contains(((EntityBulletBase) e).getConfig());
    }

    public static boolean listed(BulletConfiguration config) {
        return config != null && !config.isSpectral && configs().contains(config);
    }

    // water's rules apply to this old bullet (its config is set on its first tick)
    public static boolean on(Entity e) {
        return e instanceof EntityBulletBase && listed(((EntityBulletBase) e).getConfig());
    }

    // by the numbers, as 26.2 classes an NTM config with no ammo of its own (WaterClass.ofConfig): its speed in blocks a
    // tick, and whether it does something on impact (a charge, or the tracer's meteor)
    public static WaterClass cls(BulletConfiguration config) {
        boolean impact = config.explosive > 0 || config.bImpact != null || fuzedConfig(config);
        return WaterClass.of("", false, 1, 0F, 0F, config.velocity, impact);
    }

    private static boolean fuzedConfig(BulletConfiguration config) {
        configs();
        Set<BulletConfiguration> f = fuzed;
        return f != null && f.contains(config);
    }

    public static double gravity(Entity e) {
        BulletConfiguration c = ((EntityBulletBase) e).getConfig();
        return c == null ? 0 : c.gravity;
    }

    // the client's effects of an old bullet's tick in liquid (MixinOldBulletWater, after WaterTick.end): its events and
    // its path's bubbles, to the client's listeners (WaterFx.old). NTM's bullets have theirs from their tracer; the old
    // one flies on the client itself. One whose fuze went off on the surface shows nothing of its own that tick, as an
    // NTM tracer's (Tracers: waterFuzed): its blast shows there (OldBlast: WaterBlastMessage)
    public static void effects(Entity bullet, WaterPath.Result r, boolean wentOff) {
        if (bullet.world == null || !bullet.world.isRemote || r == null || wentOff) return;
        WaterEvents.fire(bullet.world, r.events);
        if (!r.events.isEmpty() || r.water > 0) WaterEvents.tick(bullet.world, bullet, r);
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
