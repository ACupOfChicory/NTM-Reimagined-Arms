package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.SuppressionScale;
import acup.gunfeel.SuppressionServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * The shots and blasts round the AI mobs, for their suppression (AiProne's scale: the players' own SuppressionScale,
 * with the server's forces and ranges). A player's client counts them by the tracers it flies; a mob has none, so the
 * server tells it: each stretch a shot flew in a tick ({@link #flew}: NTM's bullets from their own tick,
 * MixinBulletServer; anything else in flight from the game's tick of it), where it stopped, and each blast
 * ({@link #blast}, from SuppressionServer). A shot passing a mob's eyes within the players' range, one stopping near
 * them, a blast near: each adds to its scale as it would to a player's. Not its own shots and blasts nor its own
 * side's, not a creative or spectating player's, not a laser, not a grenade in flight (its blast counts). Server thread.
 */
public final class AiUnderFire {
    /** the server setting: AI mobs are suppressed by the players' scale; off: three shots close past pin one, as before */
    public static final String KEY = "aiSuppression";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** the last blast counted: the same one told twice (vanilla's within NTM's) counts once */
    private static @Nullable Vec3 lastBlast;
    private static long lastBlastTick = Long.MIN_VALUE / 2;
    private static float lastBlastSize;

    private AiUnderFire() {
    }

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    public static void init() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.EntityTickEvent.Post.class, e -> {
            if (!(e.getEntity() instanceof Projectile p) || p instanceof com.hbm.entity.projectile.EntityBulletBaseMK4
                    || !(p.level() instanceof ServerLevel level)) return;
            if (p instanceof com.hbm.entity.grenade.EntityGrenadeUniversal || p instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric) return;
            if (AiBots.count(level) == 0) return;
            Vec3 a = new Vec3(p.xo, p.yo, p.zo), b = p.position();
            double speed = a.distanceTo(b);
            // one that lies where it fell (an arrow in the ground) is no shot
            if (speed < 0.5) return;
            flew(level, p, a, b, speed, false, false);
        });
    }

    /**
     * NTM's bullet after its tick: `step` is what it flies a tick, `from` where the tick began. Gone, it stopped where
     * it hit something this tick (`impact`).
     */
    public static void bullet(ServerLevel level, com.hbm.entity.projectile.EntityBulletBaseMK4 bullet, Vec3 from, Vec3 step, @Nullable Vec3 impact,
            boolean explosive) {
        if (AiBots.count(level) == 0) return;
        // gone with no hit (its time over, out of the world): it stopped nowhere
        boolean stopped = bullet.isRemoved() && impact != null;
        Vec3 to = stopped ? impact : bullet.position();
        double speed = step.length();
        if (speed < 0.5) return;
        flew(level, bullet, from, to, speed, stopped, explosive);
    }

    /** a shot flew a to b this tick at `speed` blocks a tick, and stopped at b if `stopped`; an explosive one counts by its blast */
    static void flew(ServerLevel level, Projectile shot, Vec3 a, Vec3 b, double speed, boolean stopped, boolean explosive) {
        Entity owner = shot.getOwner();
        if (owner instanceof net.minecraft.world.entity.player.Player pl && (pl.isCreative() || pl.isSpectator())) return;
        boolean scale = on();
        double passRange = Math.max(AiProne.NEAR, scale ? SuppressionServer.passRange() : 0), impactRange = scale ? SuppressionServer.impactRange() : 0;
        double reach = Math.max(passRange, impactRange);
        Vec3 ab = b.subtract(a);
        double l2 = ab.lengthSqr();
        long tick = level.getGameTime();
        for (AiBot bot : AiBots.bots(level)) {
            if (!bot.active || owner == bot.mob || !bot.mob.isAlive()) continue;
            Vec3 eye = bot.mob.getEyePosition();
            double k = l2 < 1e-9 ? 0.0 : eye.subtract(a).dot(ab) / l2;
            double kk = Math.clamp(k, 0.0, 1.0);
            double nx = a.x + ab.x * kk - eye.x, ny = a.y + ab.y * kk - eye.y, nz = a.z + ab.z * kk - eye.z;
            double d2 = nx * nx + ny * ny + nz * nz;
            if (d2 > reach * reach) continue;
            if (owner instanceof LivingEntity o && AiTeams.own(bot.mob, o)) continue;
            // passing: the nearest point is along the stretch, not its end (else it is still coming or already gone)
            if (k > 0.0 && k < 1.0 && d2 <= passRange * passRange) bot.prone.pass(owner, Math.sqrt(d2), speed, scale, tick);
            if (stopped && !explosive && scale) {
                double d = eye.distanceTo(b);
                if (d <= impactRange) bot.prone.feel(SuppressionScale.impact(d, speed, impactRange) * SuppressionServer.impactShare(), true, tick);
            }
        }
    }

    /** a blast of `size` (how far it hurts) at `at`, set off by `by` */
    public static void blast(ServerLevel level, Vec3 at, float size, @Nullable LivingEntity by) {
        if (!on() || AiBots.count(level) == 0) return;
        long tick = level.getGameTime();
        // a creative or spectating player's blast is none to them, as their shots
        if (by instanceof net.minecraft.world.entity.player.Player pl && (pl.isCreative() || pl.isSpectator())) return;
        if (lastBlast != null && Math.abs(tick - lastBlastTick) <= 2 && lastBlast.distanceToSqr(at) <= 1.0 && size <= lastBlastSize) return;
        lastBlast = at;
        lastBlastTick = tick;
        lastBlastSize = size;
        double range = SuppressionServer.blastRange(), reach = SuppressionScale.blastRange(size, range);
        for (AiBot bot : AiBots.bots(level)) {
            // its own side's blast is none to it, as its shots
            if (!bot.active || by == bot.mob || !bot.mob.isAlive() || by != null && AiTeams.own(bot.mob, by)) continue;
            double d = bot.mob.getEyePosition().distanceTo(at);
            if (d < reach) bot.prone.feel(SuppressionScale.blast(d, size, range) * SuppressionServer.blastShare(), false, tick);
        }
    }
}
