package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.CoreServices;
import acup.gunfeel.GameMath;
import acup.gunfeel.ReaimTimes;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;
import acup.gunfeel.host.NtmGuns;
import acup.gunfeel.host.Shell;

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
public abstract class AiGrenadierBase {
    private static final NtmGuns GUNS = CoreServices.load(NtmGuns.class, NtmGuns.NONE);
    /** the server setting: grenadiers lob their shells */
    public static final String KEY = "aiGrenadier";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a place nearer than this, blocks: its own shell's rule (AiGrenades: SAFE, OWN_MARGIN and one more) */
    static final double MIN = 15 + 2 + 1;
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
        final double[] land = new double[3], direction = new double[3];
        long next = Long.MIN_VALUE, failed = Long.MIN_VALUE, aimUntil = Long.MIN_VALUE, lostTick = Long.MIN_VALUE, stepped = Long.MIN_VALUE;
        int firedBefore, lostShots;
        /** the lob it is turned for: along the barrel; at a place gone out of sight */
        @Nullable Vec3d along;
        boolean atLost;
        /** where its last lob is to burst (the stands) */
        @Nullable Vec3d burst;
    }

    protected AiGrenadierBase() {
    }

    private static State state(AiBotBase bot) {
        return bot.grenadier == null ? bot.grenadier = new State() : bot.grenadier;
    }

    public static boolean enabled() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** 26.2: AiLean, AiGrenades, AiBots, AiSoldier and the gunner's aim/fire. */
    interface Staff<B extends AiBotBase, S> {
        Vec3d eye(B bot);
        boolean mayPlan(Object level, long tick);
        VoxelRay.Source voxels(Object level);
        @Nullable Vec3d burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz,
                int ticks, int minY, double gravity, double[] land);
        boolean ownNear(Object level, VoxelRay.Source src, B bot, Vec3d burst);
        CoverQuery.@Nullable Spot spot(S soldier);
        void note(S soldier, B bot, long tick, String text);
        EntityView entities(Object level);
        void aim(B bot, Vec3d at);
        void fire(B bot, boolean on);
    }

    /**
     * A grenadier with a target it knows of, this tick (AiSoldier: in sight, or gone out of sight a moment ago): true
     * while it turns for a lob (nothing else then); false: the rest of its fight goes on as a rifleman's, cover and
     * all, its launcher silent between lobs (AiGunner).
     */
    static <B extends AiBotBase, S> boolean fight(Staff<B, S> staff, S s, B bot, Object level, TargetMemory.Contact c, long tick) {
        return fight(staff, GUNS, s, bot, level, c, tick);
    }

    static <B extends AiBotBase, S> boolean fight(Staff<B, S> staff, NtmGuns guns, S s, B bot, Object level, TargetMemory.Contact c, long tick) {
        if (!(bot.role() == AiRole.GRENADIER && enabled() && guns.grenadeShell(bot.body, bot.grenadeShell))) return false;
        AiGunnerBase g = bot.gunner();
        Shell shell = bot.grenadeShell;
        if (!guns.grenadeShell(bot.body, shell)) return false;
        State m = state(bot);
        boolean steady = m.stepped == tick - 1;
        m.stepped = tick;
        // turned for a lob: on till it goes or AIM is up (the turn kept a tick at a time: off by itself once it is
        // not here, AiGunner)
        if (m.aimUntil != Long.MIN_VALUE) {
            if (g.shots != m.firedBefore) {
                shots++;
                if (m.atLost) m.lostShots++;
                m.aimUntil = Long.MIN_VALUE;
                g.lob(false, 0, 0, 0, tick);
                m.next = tick + GAP;
            } else if (steady && tick <= m.aimUntil && m.along != null) {
                staff.aim(bot, m.along);
                g.lob(true, m.along.x(), m.along.y(), m.along.z(), tick + 1);
                staff.fire(bot, true);
                return true;
            } else {
                // given up: no shot in AIM (penalised), or it was away from here meanwhile (the turn is gone)
                if (steady) m.failed = tick + AGAIN;
                m.aimUntil = Long.MIN_VALUE;
                g.lob(false, 0, 0, 0, tick);
            }
        }
        // no lob found a moment ago: a rifleman meanwhile
        if (tick < m.failed) return false;
        Vec3d at;
        if (c.state == TargetMemory.State.VISIBLE) {
            Body seen = staff.entities(level).byId(c.id);
            at = seen != null ? new Vec3d(seen.x(), seen.y(), seen.z()) : new Vec3d(c.x, c.y, c.z);
        } else if (c.state == TargetMemory.State.LOST && tick - c.lostTick <= LOST_FRESH) {
            if (m.lostTick != c.lostTick) {
                m.lostTick = c.lostTick;
                m.lostShots = 0;
            }
            if (m.lostShots >= LOST_SHOTS) return false;
            at = new Vec3d(c.lostX, c.lostY, c.lostZ);
        } else return false;
        double d = Math.hypot(at.x() - bot.body.x(), at.z() - bot.body.z());
        if (d < MIN || d > MAX) return false;
        // to its cover first; reloading, or nothing in the gun, or between lobs: as a rifleman (it reloads, keeps to
        // cover and changes it; its launcher silent)
        CoverQuery.Spot spot = staff.spot(s);
        if (spot != null && !bot.mover().arrived() || g.reloading() || g.loaded() == 0 || tick < m.next || !staff.mayPlan(level, tick)) return false;
        Lob lob = trajectory(staff, level, bot, at, shell, d, m);
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
        staff.aim(bot, lob.aim);
        g.lob(true, lob.aim.x(), lob.aim.y(), lob.aim.z(), tick + 1);
        staff.fire(bot, true);
        if (bot.mover().goal() != Long.MIN_VALUE && spot == null) bot.mover().stop();
        staff.note(s, bot, tick, String.format(java.util.Locale.ROOT, "grenadier: a lob at %.0f blocks, pitch %.1f", d, lob.pitch));
        return true;
    }

    /** a lob: the point it turns to (along the barrel), its pitch, where it is to burst */
    record Lob(Vec3d aim, float pitch, Vec3d burst) {
    }

    /**
     * The flattest pitch at which the shell, fired from its eye at this place (off by the hand's error), bursts within
     * LAND of it, none of its own near; fired from LOW under its eye too (the muzzle is lower, a crest just cleared
     * from the eye may not be from there), within LAND and 2, none near; null if none does.
     */
    static <B extends AiBotBase, S> @Nullable Lob trajectory(Staff<B, S> staff, Object level, B bot, Vec3d target, Shell shell, double d, State m) {
        plans++;
        var rng = bot.body.random();
        Vec3d at = target.add(rng.nextGaussian() * ERR * d, 0, rng.nextGaussian() * ERR * d);
        Vec3d eye = staff.eye(bot);
        double dx = at.x() - eye.x(), dz = at.z() - eye.z();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        // as AiGunner.bulletSpeed: fast bullets faster by bulletSpeed (MixinBulletServer); grenades are slow, as NTM has them
        double speed = shell.velocity >= 5F ? shell.velocity * ReaimTimes.number("bulletSpeed", 1.6F) : shell.velocity, gravity = shell.gravity * speed;
        int ticks = shell.expires > 0 ? Math.min(FLIGHT, shell.expires) : FLIGHT;
        VoxelRay.Source src = staff.voxels(level);
        int minY = bot.world().minY();
        double g = Math.toRadians(-yaw);
        double[] land = m.land;
        java.util.Arrays.fill(land, 0);
        Vec3d best = null;
        float bestPitch = Float.NaN;
        boolean any = false;
        // the flattest first (from down to up): the shortest flight, the least to misjudge. The steps go -59.5, then
        // -61: never -60 itself, so >= and > at PITCH_UP are the same here
        for (float p = PITCH_DOWN; p >= PITCH_UP; p -= PITCH_STEP) {
            double f = Math.toRadians(p);
            double vx = Math.sin(g) * Math.cos(f) * speed, vy = -Math.sin(f) * speed, vz = Math.cos(g) * Math.cos(f) * speed;
            Vec3d b = staff.burst(src, eye.x(), eye.y(), eye.z(), vx, vy, vz, ticks, minY, gravity, land);
            if (b == null || Double.isNaN(land[0]) || b.distanceTo(at) > LAND) continue;
            any = true;
            if (staff.ownNear(level, src, bot, b)) continue;
            Vec3d low = staff.burst(src, eye.x(), eye.y() - LOW, eye.z(), vx, vy, vz, ticks, minY, gravity, land);
            if (low == null || Double.isNaN(land[0]) || low.distanceTo(at) > LAND + 2 || staff.ownNear(level, src, bot, low)) continue;
            best = b;
            bestPitch = p;
            break;
        }
        if (best == null) {
            if (any) unsafe++;
            else none++;
            return null;
        }
        double[] direction = m.direction;
        GameMath.direction(bestPitch, yaw, direction);
        Vec3d aim = eye.add(new Vec3d(direction[0], direction[1], direction[2]).scale(Math.max(8, d)));
        return new Lob(aim, bestPitch, best);
    }

    /** the stands: where this grenadier's last lob was to burst */
    static @Nullable Vec3d burstOf(AiBotBase bot) {
        return bot.grenadier == null ? null : bot.grenadier.burst;
    }

    /** /armsai info */
    public static String status() {
        return String.format(java.util.Locale.ROOT, "grenadiers: %d lobs of %d plans (%d none, %d near its own)", shots, plans, none, unsafe);
    }
}

