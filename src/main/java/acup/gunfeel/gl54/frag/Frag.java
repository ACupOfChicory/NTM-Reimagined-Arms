package acup.gunfeel.gl54.frag;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.gl54.GrenadeKind;
import acup.gunfeel.gl54.GrenadeType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// The way into the fragment grenades (airburst, armour-piercing airburst, incendiary) from the grenade's flight
// (Gl54Grenade): a kind that has a FragSpec goes off as a burst of rays, any other is left to the caller.
public final class Frag {
    private Frag() {
    }

    // the armed grenade `bullet` hit something: if it is a fragment kind it goes off here (and is gone), true; else false
    public static boolean onImpact(EntityBulletBaseMK4 bullet, HitResult hit) {
        FragSpec spec = FragSpec.of(bullet.config instanceof GrenadeType t ? t.grenade() : null);
        if (spec == null || !(bullet.level() instanceof ServerLevel level)) return false;
        Vec3 dir = bullet.getDeltaMovement();
        if (dir.lengthSqr() < 1e-8) dir = bullet.getLookAngle();
        dir = dir.normalize();
        // a little back from where it hit, so the rays do not start inside the block
        Vec3 point = hit.getLocation().subtract(dir.scale(hit instanceof EntityHitResult ? 0.0 : 0.05));
        FragBurst.burst(spec, level, bullet.getOwner(), bullet, point, dir, level.getRandom().nextLong());
        bullet.discard();
        return true;
    }

    // a grenade of this kind goes off at `point` flying along `dir` (the range fuse's way in); `grenade` is the entity it
    // is (its owner and its id go with the damage). False for a kind that is not a fragment one.
    public static boolean burst(@Nullable GrenadeKind kind, Entity grenade, Vec3 point, Vec3 dir) {
        FragSpec spec = FragSpec.of(kind);
        if (spec == null || !(grenade.level() instanceof ServerLevel level)) return false;
        Entity owner = grenade instanceof EntityBulletBaseMK4 b ? b.getOwner() : null;
        FragBurst.burst(spec, level, owner, grenade, point, dir, level.getRandom().nextLong());
        return true;
    }
}
