package acup.gunfeel.water;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;

// port only: the blast of an NTM CE old bullet with a charge (OldBullets) is the game's own (World.newExplosion), not
// NTM's ExplosionVNT, which WaterBlast widens. Its creatures by 26.2's rule all the same (MixinOldBlast on
// Explosion.doExplosionA): with its centre in water, creatures in water take it out to waterBlastRadius × its range,
// with the same strongest hit at its centre; those out of the water take it by its own range, half of it, and half the
// push. Blocks break as the game breaks them. Only a blast set off by such a bullet's impact (MixinOldBulletWater).
public final class OldBlast {
    private OldBlast() {
    }

    // this thread's old bullet impact (MixinOldBulletWater: onBlockImpact, onEntityImpact): [0] how deep in one, [1]
    // its blast taken already. Only its first: another blast it sets off (a barrel, a chain of TNT) is the game's
    private static final ThreadLocal<int[]> IMPACT = ThreadLocal.withInitial(() -> new int[2]);
    private static final ThreadLocal<Entity> BULLET = new ThreadLocal<>();

    public static void impact(Entity bullet, Runnable r) {
        int[] s = IMPACT.get();
        int taken = s[1];
        Entity outer = BULLET.get();
        s[0]++;
        s[1] = 0;
        BULLET.set(bullet);
        try {
            r.run();
        } finally {
            s[0]--;
            s[1] = taken;
            BULLET.set(outer);
        }
    }

    // the blast about to hurt creatures: waterBlastRadius when it is an old bullet's with its centre in water, else 0.
    // At water the players near are told, as of NTM's blasts (WaterBlast.announce): the column, the bubbles
    public static float wet(World world, double x, double y, double z, float size) {
        int[] s = IMPACT.get();
        if (s[0] <= 0 || s[1] != 0 || world == null || world.isRemote) return 0F;
        s[1] = 1;
        Vec3d at = new Vec3d(x, y, z);
        WaterBlast.Place p = WaterBlast.place(world, at);
        if (p == null) return 0F;
        Entity b = BULLET.get();
        boolean incendiary = b instanceof com.hbm.entity.projectile.EntityBulletBase && ((com.hbm.entity.projectile.EntityBulletBase) b).getConfig() != null
                && ((com.hbm.entity.projectile.EntityBulletBase) b).getConfig().incendiary > 0;
        WaterBlast.tell(world, at, size, incendiary, p);
        if (!WaterSettings.ballistics() || !p.inWater() || p.liquid() != WaterEvents.Liquid.WATER) return 0F;
        return Math.max(1F, WaterSettings.blastRadius());
    }

    // a creature's distance as the game divides it by the range (its size × 2, here × mult): one out of the water goes by
    // the blast's own range
    public static double distance(double d, Entity e, float mult) {
        return mult <= 0 || WaterBlast.inWater(e) ? d : d * mult;
    }

    // the game's hurt of a creature (Explosion.doExplosionA): (int)((q² + q) / 2 × 7 × range + 1), q = (1 - d / range) ×
    // the part of it the blocks let through. In water the range is mult × the blast's, the strongest hit the blast's
    // own; out of the water the blast's own range, half
    public static float damage(Explosion ex, Entity e, float mult) {
        float range = ex.size * 2F;
        Vec3d at = ex.getPosition();
        boolean wet = WaterBlast.inWater(e);
        double d = e.getDistance(at.x, at.y, at.z) / (wet ? range * mult : range);
        if (d > 1) return 0F;
        double q = (1 - d) * ex.world.getBlockDensity(at, e.getEntityBoundingBox());
        float hurt = (float) (int) ((q * q + q) / 2.0 * 7.0 * range + 1.0);
        return wet ? hurt : hurt * WaterBlast.ABOVE;
    }

    // its push: one out of the water half (a creature's motion; a player's push goes to them as its own vector)
    public static double push(double push, Entity e, float mult) {
        return mult <= 0 || WaterBlast.inWater(e) ? push : push * WaterBlast.ABOVE;
    }

    public static Vec3d push(Vec3d push, Entity e, float mult) {
        return mult <= 0 || WaterBlast.inWater(e) ? push : push.scale(WaterBlast.ABOVE);
    }
}
