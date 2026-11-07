package acup.gunfeel;

import com.hbm.entity.projectile.EntityBullet;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;

// port: 26.2's LegacyBullets (X547). NTM's old bullet, EntityBullet: the hunter chopper's and the cyber crab's; no gun of
// a player fires it. NTM counts its hit at the creature's middle, leaves no hole and breaks only vanilla glass where it
// stopped. With npcShots (server, on by default; MixinLegacyBullet) it is counted where it met the model (head x1.75,
// limbs x0.5, as an NTM bullet), follows the bullet rules for blocks ("break", "pass"; not "pierce": it has no
// BulletConfig to pierce with) and leaves a hole with the hit's particles and sound where it stops in a block.
// port: the hole goes to the players around as TracerNet.Impact (26.2: ImpactHolePayload), as in the 1.12.2 port.
// port: NTM 1.7.10's other old bullet, EntityBulletBaseNT (the masked man's laser, the UFO, BOT Prime), is counted at
// the head and limbs under the same key (MixinOldBulletBase); its blocks stay NTM's.
public final class LegacyBullets {
    private LegacyBullets() {
    }

    public static boolean on() {
        return ReaimTimes.flag("npcShots", true);
    }

    // the round whose hole it leaves: a plain one (an Impact of a plain round is a hole and the hit's effects,
    // Tracers.impact); the hole's look comes from the block, not the round
    static BulletConfig holeRound() {
        return XFactory762mm.r762_fmj;
    }

    // its damage on `target` hit at `point` (null: NTM's middle, unchanged)
    public static float damage(Entity target, Vec3 point, float amount) {
        return damage(target, point, amount, amount);
    }

    // the stand (NpcShotsScenario): the zones throw, as a failure of theirs would
    static volatile boolean failZones;

    // `base` times the zone where `point` met the model; NTM's own `ntm` when the model gives no verdict
    public static float damage(Entity target, Vec3 point, float base, float ntm) {
        if (failZones) throw new IllegalStateException("zones failing for the stand");
        if (point == null || !(target instanceof EntityLivingBase)) return ntm;
        return HeadDamage.beamDamage(base, (EntityLivingBase) target, new MovingObjectPosition(target, point), ntm);
    }

    // NTM hurts the creature with the bullet's damage and, when that fails, again with the damage plus the creature's
    // last hit: past a fresh hit (cooldown) the game takes only a hit harder than the last one, and only what it is
    // harder by. Which of the two lands is NTM's to decide by its own damage, as without the zones; the change the head
    // or a limb makes (extra) goes to the one that lands. FIRST: the first one with the change. SECOND: the first one
    // without it (it fails), the second with it. NONE: the first one with the change, which a limb takes under the last
    // hit, and no second one, as NTM's first one would have landed (with less than nothing). (26.2 X577)
    public enum Hurt { FIRST, SECOND, NONE }

    public static Hurt hurt(float damage, float extra, boolean cooldown, float lastHurt) {
        if (!cooldown || extra == 0F) return Hurt.FIRST;
        if (damage <= lastHurt) return Hurt.SECOND;
        return damage + extra <= lastHurt ? Hurt.NONE : Hurt.FIRST;
    }

    // port: the same for `target` as EntityLivingBase.attackEntityFrom has its cooldown (hurtResistantTime past half of
    // maxHurtResistantTime) and its last hit (lastDamage, read as NTM reads it)
    public static Hurt hurt(Entity target, float damage, float extra) {
        if (extra == 0F || !(target instanceof EntityLivingBase)) return Hurt.FIRST;
        EntityLivingBase living = (EntityLivingBase) target;
        boolean cooldown = living.hurtResistantTime > living.maxHurtResistantTime / 2.0F;
        return hurt(damage, extra, cooldown, cooldown ? lastDamage(living) : 0F);
    }

    private static java.lang.reflect.Field lastDamage;

    private static float lastDamage(EntityLivingBase living) {
        java.lang.reflect.Field f = lastDamage;
        if (f == null) lastDamage = f = cpw.mods.fml.relauncher.ReflectionHelper.findField(EntityLivingBase.class, "lastDamage", "field_110153_bc");
        try {
            return f.getFloat(living);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // its zones failed: written to the log once, the hit goes as in NTM
    private static boolean warned;

    public static void zonesFailed(String bullet, RuntimeException e) {
        if (warned) return;
        warned = true;
        GunFeel.LOGGER.warn("NTM's {}: its head and limb zones failed, it hits as in NTM", bullet, e);
    }

    // it stopped in this block: its hole on the players' screens around
    public static void stuck(EntityBullet bullet, MovingObjectPosition hit) {
        if (!(bullet.worldObj instanceof WorldServer) || hit == null || hit.hitVec == null || hit.sideHit < 0 || hit.sideHit > 5) return;
        double dx = bullet.motionX, dy = bullet.motionY, dz = bullet.motionZ;
        if (dx * dx + dy * dy + dz * dz < 1e-12) {
            dx = -BlockRay.stepX(hit.sideHit);
            dy = -BlockRay.stepY(hit.sideHit);
            dz = -BlockRay.stepZ(hit.sideHit);
        }
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Vec3 at = hit.hitVec;
        int bx = hit.blockX, by = hit.blockY, bz = hit.blockZ;
        if (TestLog.server) TestLog.s(bullet.getEntityId(), "legacy_hole", "block", TestLog.block(bullet.worldObj, bx, by, bz),
                "pos", new int[] {bx, by, bz}, "at", TestLog.point(at.xCoord, at.yCoord, at.zCoord));
        TracerNet.send(bullet, new TracerNet.Impact(bullet.getEntityId(), holeRound().id, bx, by, bz, hit.sideHit, at.xCoord, at.yCoord,
                at.zCoord, dx / l, dy / l, dz / l));
    }
}
