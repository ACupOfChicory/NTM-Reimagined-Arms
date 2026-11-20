package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import acup.gunfeel.ReaimTimes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A squad's grenadier (stage 5, R3): a soldier whose gun fires 40 mm grenades (NTM's Congo Lake and MK108, the mod's
 * GL-54 with a magazine of them in), or one given the role by word. It lobs its shells as one who knows the
 * launcher: the barrel raised as far as the shell drops on its way (worked out by flying it over the blocks, as
 * AiGrenades flies a hand grenade), over low cover onto one sitting behind it, onto the place one went out of sight
 * a moment ago, at one in the open. Its own grenade's rule as for hand grenades: no burst within SAFE and OWN_MARGIN
 * of it, of its own, of where they go or hide (AiGrenades.ownNear); it never fires its launcher straight (AiGunner),
 * so nearer than that, and between lobs, it holds its fire and fights on as a rifleman would, cover and all. Its
 * hand misjudges the place the more the farther it is (ERR of the distance); the gun's own spread stays NTM's. A
 * shot every GAP ticks at most; at a place gone out of sight, LOST_SHOTS. It neither flanks nor goes after a target
 * (near it its shells cannot go; AiSquads).
 */
public final class AiGrenadier {
    /** the server setting: grenadiers lob their shells */
    public static final String KEY = "aiGrenadier";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a place nearer than this, blocks: its own shell's rule (AiGrenades: SAFE, OWN_MARGIN and one more) */
    static final double MIN = AiGrenades.SAFE + AiGrenades.OWN_MARGIN + 1;
    /** a place farther than this, blocks: no lob */
    static final double MAX = 80;
    /** the hand's error: the place it means is off by this share of the distance, about (a Gaussian, each way) */
    static final double ERR = 0.04;
    /** pitches tried, degrees (up is minus); a burst this near the place will do, blocks */
    static final float PITCH_UP = -60F, PITCH_DOWN = 20F, PITCH_STEP = 1.5F;
    static final double LAND = 3;
    /** a lob holds fired from this much under its eye as well, blocks */
    static final double LOW = 0.3;
    /** ticks: between two shots at least; a shot it turns for, given up after; after no lob was found */
    static final int GAP = 30, AIM = 12, AGAIN = 20;
    /** a place gone out of sight: shells at it at most; its news this many ticks at most */
    static final int LOST_SHOTS = 2, LOST_FRESH = 200;
    /** a shell's flight followed this long at most, ticks */
    static final int FLIGHT = 200;

    /** the stands: lobs fired, plans, plans with no lob, plans with one that would hit its own */
    public static int shots, plans, none, unsafe;

    /** a grenadier's own state */
    static final class State {
        long next = Long.MIN_VALUE, failed = Long.MIN_VALUE, aimUntil = Long.MIN_VALUE, lostTick = Long.MIN_VALUE, stepped = Long.MIN_VALUE;
        int firedBefore, lostShots;
        /** the lob it is turned for: along the barrel; at a place gone out of sight */
        @Nullable Vec3 along;
        boolean atLost;
        /** where its last lob is to burst (the stands) */
        @Nullable Vec3 burst;
    }

    private AiGrenadier() {
    }

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** it is a grenadier that lobs: the role, and 40 mm grenades in its gun (else it is as a rifleman) */
    static boolean is(AiBot bot) {
        return AiRoles.of(bot) == AiRole.GRENADIER && on() && shell(bot.mob.getMainHandItem()) != null;
    }

    /** the round in this gun's magazine, if it is a 40 mm grenade (not a rocket: those fly straight) */
    @SuppressWarnings("rawtypes")
    static @Nullable BulletConfig shell(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return null;
        try {
            IMagazine mag = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
            if (mag == null || !(mag.getType(stack, null) instanceof BulletConfig b)) return null;
            return b instanceof acup.gunfeel.gl54.GrenadeType || acup.gunfeel.ExplosiveAmmo.heavy(b) && !acup.gunfeel.ExplosiveAmmo.rocket(b) ? b : null;
        } catch (RuntimeException e) {
            // a magazine that wants an inventory
            return null;
        }
    }

    /** its gun's shells go as its lobs only (AiGunner): a grenadier with 40 mm grenades in its gun */
    static boolean lobsOnly(Mob mob, ItemStack stack) {
        AiBot b = AiBots.get(mob);
        return b != null && is(b);
    }

    /**
     * A grenadier with a target it knows of, this tick (AiSoldier: in sight, or gone out of sight a moment ago): true
     * while it turns for a lob (nothing else then); false: the rest of its fight goes on as a rifleman's, cover and
     * all, its launcher silent between lobs (AiGunner).
     */
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (!is(bot)) return false;
        AiGunner g = bot.gunner;
        BulletConfig shell = shell(bot.mob.getMainHandItem());
        if (shell == null) return false;
        State m = bot.grenadier == null ? bot.grenadier = new State() : bot.grenadier;
        boolean steady = m.stepped == tick - 1;
        m.stepped = tick;
        // turned for a lob: on till it goes or AIM is up (the turn kept a tick at a time: off by itself once it is
        // not here, AiGunner)
        if (m.aimUntil != Long.MIN_VALUE) {
            if (g.shots != m.firedBefore) {
                shots++;
                if (m.atLost) m.lostShots++;
                m.aimUntil = Long.MIN_VALUE;
                g.lob(null, tick);
                m.next = tick + GAP;
            } else if (steady && tick <= m.aimUntil && m.along != null) {
                g.aim(m.along);
                g.lob(m.along, tick + 1);
                g.fire(true);
                return true;
            } else {
                // given up: no shot in AIM (penalised), or it was away from here meanwhile (the turn is gone)
                if (steady) m.failed = tick + AGAIN;
                m.aimUntil = Long.MIN_VALUE;
                g.lob(null, tick);
            }
        }
        // no lob found a moment ago: a rifleman meanwhile
        if (tick < m.failed) return false;
        Vec3 at;
        if (c.state == TargetMemory.State.VISIBLE) {
            Entity seen = level.getEntity(c.id);
            at = seen != null ? seen.position() : new Vec3(c.x, c.y, c.z);
        } else if (c.state == TargetMemory.State.LOST && tick - c.lostTick <= LOST_FRESH) {
            if (m.lostTick != c.lostTick) {
                m.lostTick = c.lostTick;
                m.lostShots = 0;
            }
            if (m.lostShots >= LOST_SHOTS) return false;
            at = new Vec3(c.lostX, c.lostY, c.lostZ);
        } else return false;
        double d = Math.hypot(at.x - bot.mob.getX(), at.z - bot.mob.getZ());
        if (d < MIN || d > MAX) return false;
        // to its cover first; reloading, or nothing in the gun, or between lobs: as a rifleman (it reloads, keeps to
        // cover and changes it; its launcher silent)
        CoverQuery.Spot spot = s.spot();
        if (spot != null && !bot.mover.arrived() || g.reloading() || g.loaded() == 0 || tick < m.next || !AiGrenades.mayPlan(level, tick)) return false;
        Lob lob = plan(level, bot.mob, at, shell, d);
        if (lob == null) {
            m.failed = tick + AGAIN;
            return false;
        }
        m.burst = lob.burst;
        m.aimUntil = tick + AIM;
        m.firedBefore = g.shots;
        m.along = lob.aim;
        m.atLost = c.state != TargetMemory.State.VISIBLE;
        // along the barrel: its own in that line hold the trigger (AiGunner), not those under the arc
        g.aim(lob.aim);
        g.lob(lob.aim, tick + 1);
        g.fire(true);
        if (bot.mover.goal() != Long.MIN_VALUE && spot == null) bot.mover.stop();
        s.note(bot, tick, String.format(java.util.Locale.ROOT, "grenadier: a lob at %.0f blocks, pitch %.1f", d, lob.pitch));
        return true;
    }

    /** a lob: the point it turns to (along the barrel), its pitch, where it is to burst */
    record Lob(Vec3 aim, float pitch, Vec3 burst) {
    }

    /**
     * The flattest pitch at which the shell, fired from its eye at this place (off by the hand's error), bursts within
     * LAND of it, none of its own near; fired from LOW under its eye too (the muzzle is lower, a crest just cleared
     * from the eye may not be from there), within LAND and 2, none near; null if none does.
     */
    static @Nullable Lob plan(ServerLevel level, Mob mob, Vec3 target, BulletConfig shell, double d) {
        plans++;
        var rng = mob.getRandom();
        Vec3 at = target.add(rng.nextGaussian() * ERR * d, 0, rng.nextGaussian() * ERR * d);
        Vec3 eye = AiLean.eye(mob);
        double dx = at.x - eye.x, dz = at.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        // as AiGunner.bulletSpeed: fast bullets faster by bulletSpeed (MixinBulletServer); grenades are slow, as NTM has them
        double speed = shell.velocity >= 5F ? shell.velocity * ReaimTimes.number("bulletSpeed", 1.6F) : shell.velocity, gravity = shell.gravity * speed;
        int ticks = shell.expires > 0 ? Math.min(FLIGHT, shell.expires) : FLIGHT;
        VoxelRay.Source src = AiBots.voxels(level);
        int minY = level.getMinY();
        double g = Math.toRadians(-yaw);
        double[] land = new double[3];
        Vec3 best = null;
        float bestPitch = Float.NaN;
        boolean any = false;
        // the flattest first (from down to up): the shortest flight, the least to misjudge
        for (float p = PITCH_DOWN; p >= PITCH_UP; p -= PITCH_STEP) {
            double f = Math.toRadians(p);
            double vx = Math.sin(g) * Math.cos(f) * speed, vy = -Math.sin(f) * speed, vz = Math.cos(g) * Math.cos(f) * speed;
            Vec3 b = AiGrenades.burst(src, eye.x, eye.y, eye.z, vx, vy, vz, ticks, 0, 0, minY, gravity, 1, null, land);
            if (b == null || Double.isNaN(land[0]) || b.distanceTo(at) > LAND) continue;
            any = true;
            if (AiGrenades.ownNear(level, src, mob, b, 0)) continue;
            Vec3 low = AiGrenades.burst(src, eye.x, eye.y - LOW, eye.z, vx, vy, vz, ticks, 0, 0, minY, gravity, 1, null, land);
            if (low == null || Double.isNaN(land[0]) || low.distanceTo(at) > LAND + 2 || AiGrenades.ownNear(level, src, mob, low, 0)) continue;
            best = b;
            bestPitch = p;
            break;
        }
        if (best == null) {
            if (any) unsafe++;
            else none++;
            return null;
        }
        Vec3 aim = eye.add(Vec3.directionFromRotation(bestPitch, yaw).scale(Math.max(8, d)));
        return new Lob(aim, bestPitch, best);
    }

    /** the stands: where this grenadier's last lob was to burst */
    static @Nullable Vec3 lastBurst(AiBot bot) {
        return bot.grenadier == null ? null : bot.grenadier.burst;
    }

    /** /armsai info */
    public static String info() {
        return String.format(java.util.Locale.ROOT, "grenadiers: %d lobs of %d plans (%d none, %d near its own)", shots, plans, none, unsafe);
    }
}
