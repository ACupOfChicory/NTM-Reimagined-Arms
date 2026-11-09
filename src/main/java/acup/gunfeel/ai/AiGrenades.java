package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.grenade.EntityGrenadeUniversal;
import com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling;
import com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze;
import com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell;
import com.hbm.items.weapon.grenade.ItemGrenadeUniversal;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Grenades, NTM's own: a frag with HE and a three seconds' fuze. An AI mob carries a few (server setting aiGrenades,
 * 2 by default, 0 to 8, kept on the mob) and throws one at a target it lost behind cover, or one that sits behind cover
 * with only its head out: over the cover, to burst within {@link #LAND} blocks of where the target is or went out of
 * sight, never within {@link #SAFE} of one of its own, nor of itself unless blocks keep all of it off the burst once it
 * is down ({@link #shielded}: a near throw, {@link #CLOSE} blocks on). Where it bursts is worked out here as NTM flies it ({@link #burst}), bounces and all, on the AI's
 * block bytes. And an AI mob that sees a grenade come (anyone's: NTM's grenades that a fuze sets off), or hears one
 * land near, runs from where it will burst: out of its reach, or behind a block. Server thread.
 */
public final class AiGrenades {
    public static final String KEY = "aiGrenades";
    /** where its grenades left are kept on the mob */
    static final String STOCK_KEY = "armsaiGrenades";
    /** throws (off only for the stands' "before") */
    static boolean on = true;
    /** runs from grenades (off only for the stands' "before") */
    static boolean dodgeOn = true;
    /** thrown at a place this far off, blocks */
    static final double NEAR = 15, FAR = 26;
    /**
     * nearer than NEAR, a near throw: a place this far off at least, the burst this far off it at least (a burst
     * worked out a little off), and blocks keep all of it off the burst once it is down behind its cover; blocks
     */
    static final double CLOSE = 8, CLOSE_BURST = 6;
    /** bursts within this of the place, blocks */
    static final double LAND = 3;
    /**
     * none of its own (nor it) within this of the burst, nor on its way to a place within it, blocks: HE's blast
     * (7.5) hurts mobs out to twice that, 25 at the burst less a fifteenth a block (stand: 23 at 1.5 blocks, 9.7 at
     * 9.5); at 12 its own were still hit (the battles)
     */
    static final double SAFE = 15;
    /**
     * its own, besides: this much more, for the way they go in the three seconds (out to a peek, to other cover: hit
     * from 13 and 15 blocks off where they stood, the battles)
     */
    static final double OWN_MARGIN = 2;
    /**
     * a mob running from its own side's grenade: to this far off it, blocks (no block keeps that one off: cover); a
     * block past where it starts to run, so that the burst worked out again a little nearer does not send it on
     */
    static final double OWN_CLEAR = SAFE + OWN_MARGIN + 1;
    /**
     * a refuge from one burst keeps out of the others' reach too: its own side's this far (all of its reach, NTM counting
     * from the mob's box, and the burst worked out within 1.5: a stand, its own grenade 15 blocks off where running from
     * another led; as far as it keeps from its own anyway, so it does not run again once the first has gone), blocks
     */
    static final double OTHER_OWN = SAFE + OWN_MARGIN;
    /** a mob running from a burst: to this far off it, or behind a block (2 of the blast's damage there), blocks */
    static final double CLEAR = 12;
    /** a burst this near: it runs (8 and more of the blast's damage), to CLEAR off or behind a block; blocks */
    static final double DANGER = 10;
    /** it sees a grenade this far off, hears one land this near, blocks */
    static final double SEE = 24, HEAR = 5;
    /** NTM's three seconds' fuze, ticks */
    static final int FUSE = 60;
    /** an impact fuze is set off by a hit from this many ticks in the air on (NTM) */
    static final int IMPACT_ARMED = 10;
    /** NTM's flight: gravity a tick, the air's drag, the step off a face it hits, the speed along a face kept */
    static final double GRAVITY = 0.03, DRAG = 0.99, OFF_FACE = 0.05, ALONG = 0.8;
    /** pitches tried: from up this much to down this much, by this, then halved twice round the best */
    static final float PITCH_UP = -70F, PITCH_DOWN = 10F, PITCH_STEP = 5F;

    private AiGrenades() {
    }

    /** the bench: time spent working out throws, and on grenades in the air (bursts, dangers, refuges), ns */
    public static long planNanos, liveNanos;

    /** the game tick a throw was last worked out on in each level: one a tick at most there, whoever asks */
    private static final Map<ServerLevel, Long> PLANNED = new WeakHashMap<>();

    /** whether a throw may be worked out this tick in this level (one a tick, for all its AI mobs) */
    static boolean mayPlan(ServerLevel level, long tick) {
        Long last = PLANNED.put(level, tick);
        return last == null || last != tick;
    }

    /** refuges looked for in a level this tick, and the tick: a few a tick at most (a grenade in a crowd) */
    private static final Map<ServerLevel, long[]> REFUGES = new WeakHashMap<>();
    static final int REFUGES_A_TICK = 6;

    /** whether one more refuge may be looked for this tick in this level */
    static boolean mayRefuge(ServerLevel level, long tick) {
        long[] r = REFUGES.computeIfAbsent(level, k -> new long[] {Long.MIN_VALUE, 0});
        if (r[0] != tick) {
            r[0] = tick;
            r[1] = 0;
        }
        return r[1]++ < REFUGES_A_TICK;
    }

    /** a block between the burst and this place (a mob's chest there): the blast is stopped */
    static boolean behind(VoxelRay.Source src, Vec3 burst, double x, double y, double z) {
        return !VoxelRay.clear(src, burst.x, burst.y + 0.3, burst.z, x, y + 0.9, z, Voxel.MOVE_MASK);
    }

    /**
     * Out of this burst's reach behind blocks: behind, but never from its own side's grenade, which must do it no harm
     * at all (behind a low wall, the head hidden too, NTM's blast still hurt them a little: 0.3 at 15 blocks, 0.5 at
     * 14.7, the battles); it keeps out of that one's reach instead
     */
    static boolean cover(VoxelRay.Source src, Vec3 burst, double x, double y, double z, boolean own) {
        return !own && behind(src, burst, x, y, z);
    }

    /** a shield's rays: from the burst and round it this far (it is worked out within a block or so), blocks */
    static final double SHIELD_ROUND = 0.6;

    /**
     * A mob down at x, y, z, this high (lying: AiProne.SIZE; crouching), all of it off a burst here: every ray from the
     * burst, and from round it (SHIELD_ROUND aside and up), to its box's corners, the middle of its top and a lying
     * body's head and feet (drawn longer than its box, BlastParts takes them) meets a block that hides what is behind
     * it (an opaque block, sandbags; not glass, bars, a fence or a slab). NTM's blast, and a blast by parts, do no harm
     * there (BlastParts' stand: lying behind a block's wall 2 blocks from the burst, 0).
     */
    static boolean shielded(VoxelRay.Source src, double x, double y, double z, double height, Vec3 burst) {
        double hx = burst.x - x, hz = burst.z - z, h = Math.sqrt(hx * hx + hz * hz);
        if (h < 1e-6) return false;
        double ux = hx / h, uz = hz / h, half = 0.3, low = y + 0.05, top = y + height - 0.05;
        double[][] to = { { x - half, low, z - half }, { x + half, low, z - half }, { x - half, low, z + half }, { x + half, low, z + half },
                { x - half, top, z - half }, { x + half, top, z - half }, { x - half, top, z + half }, { x + half, top, z + half },
                { x, top, z }, { x + ux * 0.9, y + 0.35, z + uz * 0.9 }, { x - ux * 0.9, y + 0.35, z - uz * 0.9 } };
        double r = SHIELD_ROUND;
        double[][] from = { { 0, 0.3, 0 }, { r, 0.3, 0 }, { -r, 0.3, 0 }, { 0, 0.3, r }, { 0, 0.3, -r }, { 0, 0.3 + r, 0 } };
        for (double[] f : from)
            for (double[] t : to)
                if (VoxelRay.clear(src, burst.x + f[0], burst.y + f[1], burst.z + f[2], t[0], t[1], t[2], Voxel.SIGHT)) return false;
        return true;
    }

        /** grenades a mob new to the AI carries */
    static int stock() {
        return Math.max(0, Math.min(8, Math.round(acup.gunfeel.ReaimTimes.number(KEY, 2F))));
    }

    /** a throw: its turn, and where it bursts */
    record Throw(float yaw, float pitch, Vec3 burst, double share) {
        Throw(float yaw, float pitch, Vec3 burst) {
            this(yaw, pitch, burst, 1);
        }
    }

    /**
     * A frag thrown as hard as NTM throws it bursts this far off at the nearest (a lob), blocks; a near throw is
     * softer, a share of that so that its nearest burst is some NEAR_SHORT of the place (the steep lobs fall short
     * of it, the flatter ones reach it)
     */
    static final double LOB = 16, NEAR_SHORT = 0.8;

    /** a near throw's share of NTM's force, for a place this far off */
    static double share(double far) {
        return Math.min(1, Math.sqrt(NEAR_SHORT * far / LOB));
    }

    /**
     * How to throw at this place from where the mob stands: the pitch (turned to the place) at which NTM's frag bursts
     * nearest it, within LAND of it; null if none does, or one of its own (or it) would be within SAFE of the burst.
     */
    static @Nullable Throw plan(ServerLevel level, Mob m, Vec3 at) {
        return plan(level, m, at, false, 0);
    }

    /**
     * The same; a smoke grenade (AiSmoke) bursts near its own as it will. {@code down} more than 0: a near throw, the
     * mob down this high behind its cover till the burst (a burst nearer it than SAFE, CLOSE_BURST on, only shielded).
     */
    static @Nullable Throw plan(ServerLevel level, Mob m, Vec3 at, boolean smoke, double down) {
        long t0 = System.nanoTime();
        try {
            return planNow(level, m, at, smoke, down);
        } finally {
            planNanos += System.nanoTime() - t0;
        }
    }

    private static @Nullable Throw planNow(ServerLevel level, Mob m, Vec3 at, boolean smoke, double down) {
        double dx = at.x - m.getX(), dz = at.z - m.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        // a smoke grenade: thrown softer, going off where it first hits once armed (AiSmoke), for the near throws it wants
        // a near throw: softer (a frag as hard as NTM throws it falls LOB blocks off at the nearest)
        double share = smoke || down <= 0 ? 1 : share(Math.hypot(dx, dz));
        double force = smoke ? AiSmoke.FORCE : EnumGrenadeShell.FRAG.getYeetForce() * share, bounce = EnumGrenadeShell.FRAG.getBounce();
        int armed = smoke ? IMPACT_ARMED : -1;
        VoxelRay.Source src = AiBots.voxels(level);
        int minY = level.getMinY();
        float best = Float.NaN;
        double bestD = Double.MAX_VALUE;
        List<Throw> near = new ArrayList<>();
        double[] land = new double[3];
        for (float p = PITCH_UP; p <= PITCH_DOWN; p += PITCH_STEP) {
            Vec3 b = rolled(src, m, yaw, p, force, bounce, minY, land, armed);
            if (b == null) continue;
            near.add(new Throw(yaw, p, b, share));
            if (b.distanceTo(at) < bestD) {
                bestD = b.distanceTo(at);
                best = p;
            }
        }
        if (Float.isNaN(best)) return null;
        for (float step = PITCH_STEP / 2; step >= PITCH_STEP / 4; step /= 2) {
            float mid = best;
            for (float p : new float[] {mid - step, mid + step}) {
                Vec3 b = rolled(src, m, yaw, p, force, bounce, minY, land, armed);
                if (b == null) continue;
                near.add(new Throw(yaw, p, b, share));
                if (b.distanceTo(at) < bestD) {
                    bestD = b.distanceTo(at);
                    best = p;
                }
            }
        }
        // the nearest to the place of those within LAND that burst far from its own
        near.sort(java.util.Comparator.comparingDouble(t -> t.burst().distanceToSqr(at)));
        for (Throw t : near) {
            if (t.burst().distanceTo(at) > LAND) break;
            if ((smoke || !ownNear(level, src, m, t.burst(), down)) && steady(level, src, m, t, at, force, bounce, minY, armed, smoke, down)) return t;
        }
        return null;
    }

    /**
     * NTM's grenade box: half its width, and its height over where it is (0.25 by 0.25), and a little more round it;
     * blocks
     */
    static final double BOX_HALF = 0.125, BOX_HIGH = 0.25, BOX_MORE = 0.05;

    /** the box's corners (and BOX_MORE) as starts off NTM's: the box, not its middle, bumps into a block's edge */
    private static final double[][] CORNERS = new double[8][];

    static {
        int i = 0;
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                for (double y : new double[] {-BOX_MORE, BOX_HIGH + BOX_MORE})
                    CORNERS[i++] = new double[] {sx * (BOX_HALF + BOX_MORE), y, sz * (BOX_HALF + BOX_MORE)};
    }

    /**
     * Whether the throw holds started from each of the box's corners (the grenade's box clips a corner its middle
     * passes: the battles, one burst 22 blocks short, among the thrower's own; its box's corner caught a wall's top
     * edge on its second tick, one off it each way alone passed over it): each such flight bursts near the place too
     * (LAND and 2 more), none near its own.
     */
    private static boolean steady(ServerLevel level, VoxelRay.Source src, Mob m, Throw t, Vec3 at, double force, double bounce, int minY, int armed,
            boolean smoke, double down) {
        for (double[] o : CORNERS) {
            Vec3 b = from(src, m, t.yaw(), t.pitch(), force, bounce, minY, o[0], o[1], o[2], null, null, armed);
            if (b == null || b.distanceTo(at) > LAND + 2 || !smoke && ownNear(level, src, m, b, down)) return false;
        }
        return true;
    }

    /**
     * A grenade that comes down rolls only so far past where it first hits a block before it bursts, blocks: past that
     * its flight is not known (a flat throw sinks into the ground where it lands and stops dead or bounces back off
     * the next block's side, NTM's box against the ground: bursts 5 and 19 blocks short, its own hit, the battles;
     * a high one rolls on as worked out)
     */
    static final double ROLL = 2.5;

    /** where a grenade thrown so bursts; null if it rolls more than ROLL past where it first hits a block */
    private static @Nullable Vec3 rolled(VoxelRay.Source src, Mob m, float yaw, float pitch, double force, double bounce, int minY, double[] land, int armed) {
        Vec3 b = from(src, m, yaw, pitch, force, bounce, minY, 0, 0, 0, null, land, armed);
        return b == null || !Double.isNaN(land[0]) && Math.hypot(b.x - land[0], b.z - land[2]) > ROLL ? null : b;
    }

    /** where a grenade thrown by the mob with this turn bursts (NTM's start: by its eye, a quarter block off; moved by o) */
    /** an impact fuze's flight is followed this far at most, ticks */
    static final int IMPACT_TICKS = 100;

    private static @Nullable Vec3 from(VoxelRay.Source src, Mob m, float yaw, float pitch, double force, double bounce, int minY, double ox, double oy,
            double oz, @Nullable List<double[]> path, double @Nullable [] land, int armed) {
        double r = Math.toRadians(180F - yaw);
        double x = m.getX() + 0.25 * Math.cos(r) + ox, y = m.getY() + m.getEyeHeight() - 0.25 + oy, z = m.getZ() - 0.25 * Math.sin(r) + oz;
        double f = Math.toRadians(pitch), g = Math.toRadians(-yaw);
        return burst(src, x, y, z, Math.sin(g) * Math.cos(f) * force, -Math.sin(f) * force, Math.cos(g) * Math.cos(f) * force, armed >= 0 ? IMPACT_TICKS : FUSE, armed, bounce, minY,
                GRAVITY, DRAG, path, land);
    }

    /**
     * It within SAFE and OWN_MARGIN of the burst (out of the reach it runs from its own grenade in), or one of its own
     * (an AI mob, a player of its team) within that too: where it is, where it is on its way to, the peek it looks out
     * from. Down more than 0: it, down that high where it is till the burst, may be nearer, CLOSE_BURST on, shielded.
     */
    private static boolean ownNear(ServerLevel level, VoxelRay.Source src, Mob m, Vec3 b, double down) {
        double s2 = (SAFE + OWN_MARGIN) * (SAFE + OWN_MARGIN), d2 = m.distanceToSqr(b);
        if (d2 < s2 && !(down > 0 && d2 >= CLOSE_BURST * CLOSE_BURST)) return true;
        for (AiBot o : AiBots.bots(level)) {
            if (o.mob == m || !o.mob.isAlive() || !AiTeams.own(m, o.mob)) continue;
            if (o.mob.distanceToSqr(b) < s2 || near(o.mover.goal(), b, s2)) return true;
            if (o.brain instanceof AiSoldier s && s.spot() != null && (near(s.spot().cell(), b, s2) || s.spot().hasPeek() && near(s.spot().peek(), b, s2))) return true;
        }
        for (Player p : level.players())
            if (!p.isSpectator() && p.isAlive() && AiTeams.own(m, p) && p.distanceToSqr(b) < s2) return true;
        // last, the dearest: a near throw's shield
        if (d2 < s2 && !shielded(src, m.getX(), m.getY(), m.getZ(), down, b)) return true;
        return false;
    }

    private static boolean near(long cell, Vec3 b, double d2) {
        return cell != Long.MIN_VALUE && b.distanceToSqr(BlockPos.getX(cell) + 0.5, BlockPos.getY(cell), BlockPos.getZ(cell) + 0.5) < d2;
    }

    /**
     * Where a grenade bursts after ticks more ticks (an impact fuze, armed after so many more ticks: where it first hits
     * from then on; -1: no impact fuze), flown as NTM flies it
     * (EntityThrowableNT, EntityGrenadeUniversal): each tick it goes its speed; a block on the way (any block a mob
     * bumps into, as a whole block) puts it a little off that face, its speed off the face turned back times bounce and
     * along it 0.8; then NTM moves it by the speed it had: into the face it stops, its speed off the face gone (unless
     * that was too slow to reach it), along it it goes on. Then the air takes 1% of its speed and gravity 0.03. Null:
     * it fell out of the world.
     */
    static @Nullable Vec3 burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY) {
        return burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, GRAVITY, DRAG);
    }

    /** the same with other gravity and air (a tick's speed taken off, a tick's speed left) */
    static @Nullable Vec3 burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY, double gravity, double drag) {
        return burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, gravity, drag, null, null);
    }

    /**
     * The same, each tick's start put down in path (x y z, its speed: the stands), where it first hits a block in land
     * (x y z; NaN if it never does)
     */
    static @Nullable Vec3 burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY, double gravity, double drag, @Nullable List<double[]> path, double @Nullable [] land) {
        double[] hit = new double[2];
        boolean landed = false;
        if (land != null) java.util.Arrays.fill(land, Double.NaN);
        for (int t = 0; t < ticks; t++) {
            if (path != null) path.add(new double[] {x, y, z, vx, vy, vz});
            if (y < minY) return null;
            if (first(src, x, y, z, x + vx, y + vy, z + vz, hit)) {
                double s = hit[0];
                int axis = (int) hit[1];
                double hx = x + vx * s, hy = y + vy * s, hz = z + vz * s;
                if (land != null && !landed) {
                    land[0] = hx;
                    land[1] = hy;
                    land[2] = hz;
                }
                if (armedAfter >= 0 && t + 1 >= armedAfter) return new Vec3(hx, hy, hz);
                double[] p = {hx, hy, hz}, v = {vx, vy, vz};
                for (int a = 0; a < 3; a++) {
                    if (a == axis) {
                        double n = v[a] > 0 ? -1 : 1;
                        if (Math.abs(v[a]) > OFF_FACE) {
                            // NTM's box against the face: a hair off it here, so the next tick starts out of the block
                            p[a] = Math.round(p[a]) + n * 1e-4;
                            v[a] = 0;
                        } else {
                            p[a] += n * OFF_FACE + v[a];
                            v[a] = -v[a] * bounce;
                        }
                    } else {
                        p[a] += v[a];
                        v[a] *= ALONG;
                    }
                }
                x = p[0];
                y = p[1];
                z = p[2];
                vx = v[0];
                vy = v[1];
                vz = v[2];
                landed = true;
            } else {
                x += vx;
                y += vy;
                z += vz;
            }
            // come to rest (it only hops a hair on the ground): where it lies is where it bursts
            if (landed && vx * vx + vz * vz < 1e-5 && Math.abs(vy) < 2 * gravity) return new Vec3(x, y, z);
            vx *= drag;
            vy = vy * drag - gravity;
            vz *= drag;
        }
        return new Vec3(x, y, z);
    }

    /**
     * The first block a mob bumps into on the line (the block the line starts in not asked): out[0] how far along
     * (0..1), out[1] the axis of the face it comes in by (0 x, 1 y, 2 z). Whether there is one.
     */
    private static boolean first(VoxelRay.Source src, double x0, double y0, double z0, double x1, double y1, double z1, double[] out) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int x = net.minecraft.util.Mth.floor(x0), y = net.minecraft.util.Mth.floor(y0), z = net.minecraft.util.Mth.floor(z0);
        int ex = net.minecraft.util.Mth.floor(x1), ey = net.minecraft.util.Mth.floor(y1), ez = net.minecraft.util.Mth.floor(z1);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tdy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tdz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx;
        double ty = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy;
        double tz = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz;
        int steps = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z);
        for (int i = 0; i < steps; i++) {
            double t;
            int axis;
            if (tx <= ty && tx <= tz) {
                t = tx;
                x += sx;
                tx += tdx;
                axis = 0;
            } else if (ty <= tz) {
                t = ty;
                y += sy;
                ty += tdy;
                axis = 1;
            } else {
                t = tz;
                z += sz;
                tz += tdz;
                axis = 2;
            }
            if (t > 1) break;
            if (!Voxel.open(src.voxel(x, y, z))) {
                out[0] = t;
                out[1] = axis;
                return true;
            }
        }
        return false;
    }

    /** NTM's frag with HE and a three seconds' fuze, thrown by the mob with this turn */
    static EntityGrenadeUniversal toss(ServerLevel level, Mob m, Throw t) {
        EntityGrenadeUniversal g = launch(level, m, t, ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, EnumGrenadeFilling.HE, EnumGrenadeFuze.S3), t.share());
        if (!trace) return g;
        // the stands: the flight it was planned to take, to set against the one it takes
        traced++;
        joined(level, g);
        List<double[]> path = new ArrayList<>();
        from(AiBots.voxels(level), m, t.yaw(), t.pitch(), EnumGrenadeShell.FRAG.getYeetForce() * t.share(), EnumGrenadeShell.FRAG.getBounce(), level.getMinY(), 0, 0, 0, path, null, -1);
        for (Live l : LEVELS.get(level))
            if (l.e == g) {
                l.path = path;
                l.start = String.format(java.util.Locale.ROOT, "thrown from %.2f %.2f %.2f (%s, eye %.2f), grenade at %.2f %.2f %.2f moving %.3f %.3f %.3f, planned %.2f %.2f %.2f"
                        + " moving %.3f %.3f %.3f", m.getX(), m.getY(), m.getZ(), m.getPose(), m.getEyeHeight(), g.getX(), g.getY(), g.getZ(), g.getDeltaMovement().x,
                        g.getDeltaMovement().y, g.getDeltaMovement().z, path.get(0)[0], path.get(0)[1], path.get(0)[2], path.get(0)[3], path.get(0)[4], path.get(0)[5]);
            }
        return g;
    }

    /** the stands: thrown grenades' planned flights set against the ones they take */
    static boolean trace;
    /** the stands: grenades thrown so far, and how each one that left its planned flight was thrown and left it */
    static int traced;
    static final List<String> partings = new ArrayList<>();

    /** the stands: how the thrower's grenade going off left its planned flight; null if it is not known */
    static @Nullable String parted(ServerLevel level, net.minecraft.world.entity.Entity owner) {
        List<Live> l = LEVELS.get(level);
        Live best = null;
        if (l != null) for (Live g : l) if (g.e.getOwner() == owner && (best == null || g.e.tickCount > best.e.tickCount)) best = g;
        return best == null || best.start == null ? null : best.start + "; " + (best.parted == null ? "kept to it" : best.parted);
    }

    /** the stands: the first tick the grenade is half a block off its planned flight, and what is there */
    private static void follow(ServerLevel level, Live g) {
        if (g.path == null || g.parted != null) return;
        int k = g.e.tickCount;
        if (k <= 0 || k >= g.path.size()) return;
        double[] p = g.path.get(k), q = g.path.get(k - 1);
        Vec3 at = g.e.position(), v = g.e.getDeltaMovement();
        // a tick either way: when in the server's tick its own ticks are counted is not asked
        for (int j = Math.max(0, k - 1); j <= Math.min(g.path.size() - 1, k + 1); j++) {
            double[] o = g.path.get(j);
            if (at.distanceToSqr(o[0], o[1], o[2]) < 0.25) return;
        }
        StringBuilder near = new StringBuilder();
        BlockPos c = BlockPos.containing(at);
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) {
            var st = level.getBlockState(b);
            if (st.isAir()) continue;
            near.append(String.format(java.util.Locale.ROOT, " %d %d %d %s%s", b.getX(), b.getY(), b.getZ(), BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(),
                    st.getCollisionShape(level, b).isEmpty() ? "(no box)" : Voxel.open(AiBots.voxels(level).voxel(b.getX(), b.getY(), b.getZ())) ? "(open to us)" : ""));
        }
        g.parted = String.format(java.util.Locale.ROOT, "tick %d it is at %.2f %.2f %.2f moving %.3f %.3f %.3f, planned at %.2f %.2f %.2f moving %.3f %.3f %.3f"
                + " (a tick before %.2f %.2f %.2f); blocks round it:%s", k, at.x, at.y, at.z, v.x, v.y, v.z, p[0], p[1], p[2], p[3], p[4], p[5], q[0], q[1], q[2],
                near.length() == 0 ? " none" : near);
        partings.add(g.start + "; " + g.parted);
    }

    // ------------------------------------------------------------------ grenades in the air

    /**
     * A grenade in the level (NTM's grenades, its bouncing ones: dynamite and the like, 40 mm grenades and rockets fired
     * from a gun): where it will burst (worked out again every few ticks, a fired one's every tick), the AI mobs that know
     * of it.
     */
    static final class Live {
        final net.minecraft.world.entity.projectile.Projectile e;
        @Nullable Vec3 burst;
        long at = Long.MIN_VALUE;
        final IntOpenHashSet known = new IntOpenHashSet();
        /** the stands: its planned flight (each tick's start), how it was thrown, where it first left that flight */
        @Nullable List<double[]> path;
        @Nullable String start, parted;

        Live(net.minecraft.world.entity.projectile.Projectile e) {
            this.e = e;
        }
    }

    /** this grenade (a frag's shell: its flight) thrown by the mob with this turn, this share of a frag's force */
    static EntityGrenadeUniversal launch(ServerLevel level, Mob m, Throw t, net.minecraft.world.item.ItemStack item, double force) {
        m.setYRot(t.yaw());
        m.setYHeadRot(t.yaw());
        m.setYBodyRot(t.yaw());
        m.setXRot(t.pitch());
        EntityGrenadeUniversal g = new EntityGrenadeUniversal(level, m, item);
        if (force != 1) g.setDeltaMovement(g.getDeltaMovement().scale(force));
        level.addFreshEntity(g);
        return g;
    }

    /**
     * Whether this is a grenade to run from: NTM's grenade (any shell and filling), its bouncing ones (dynamite, the
     * old grenades), a 40 mm grenade or a rocket fired from a gun (the GL-54, Congo Lake, MK108, rocket launchers). Not
     * a nuke's: no running from that; not the AI's smoke grenade (AiSmoke).
     */
    public static boolean threat(net.minecraft.world.entity.Entity e) {
        return e instanceof EntityGrenadeUniversal && !AiSmoke.smoke(e) || e instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric
                || e instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 b
                        && (b.config instanceof acup.gunfeel.gl54.GrenadeType || acup.gunfeel.ExplosiveAmmo.heavy(b.config));
    }

    /** its burst worked out again this often, ticks */
    static final int AGAIN = 4;

    private static final Map<ServerLevel, List<Live>> LEVELS = new WeakHashMap<>();

    /** the stands: the thrower's grenade on its way the longest (the one going off): where it is, where it was to burst */
    static Vec3 @Nullable [] bursting(ServerLevel level, net.minecraft.world.entity.Entity owner) {
        List<Live> l = LEVELS.get(level);
        Live best = null;
        if (l != null) for (Live g : l) if (g.e.getOwner() == owner && (best == null || g.e.tickCount > best.e.tickCount)) best = g;
        return best == null ? null : new Vec3[] {best.e.position(), best.burst};
    }

    /** the stands: where this grenade is to burst as last worked out; null if not known */
    static @Nullable Vec3 predicted(ServerLevel level, net.minecraft.world.entity.Entity e) {
        List<Live> l = LEVELS.get(level);
        if (l != null) for (Live g : l) if (g.e == e) return g.burst;
        return null;
    }

    /** a grenade comes into a level (thrown or fired by anyone; threat) */
    static void joined(ServerLevel level, net.minecraft.world.entity.projectile.Projectile g) {
        List<Live> l = LEVELS.computeIfAbsent(level, k -> new ArrayList<>());
        for (Live o : l) if (o.e == g) return;
        l.add(new Live(g));
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
        PLANNED.remove(level);
        REFUGES.remove(level);
    }

    /** every server tick, from AiBots.tick: grenades gone let go, bursts worked out again */
    static void tick(ServerLevel level) {
        List<Live> l = LEVELS.get(level);
        if (l == null) return;
        l.removeIf(g -> g.e.isRemoved());
        if (l.isEmpty()) {
            LEVELS.remove(level);
            return;
        }
        long now = level.getGameTime();
        if (trace) for (Live g : l) follow(level, g);
        for (Live g : l) if (g.at == Long.MIN_VALUE || now - g.at >= (g.e instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 ? 1 : AGAIN)) {
            g.at = now;
            g.burst = burstOf(level, g.e);
        }
    }

    /** where this grenade will burst; null with a fuze other than a timer or impact (or out of the world) */
    private static @Nullable Vec3 burstOf(ServerLevel level, net.minecraft.world.entity.projectile.Projectile p) {
        Vec3 v = p.getDeltaMovement();
        if (p instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric b) {
            // its own timer, as many ticks as it has been in the level (saved with it, but near enough); gravity 0.03
            int left = b.getGrenade().getMaxTimer() - b.tickCount;
            if (left <= 0) return b.position();
            return burst(AiBots.voxels(level), b.getX(), b.getY(), b.getZ(), v.x, v.y, v.z, left, -1, b.getGrenade().getBounceMod(), level.getMinY());
        }
        if (p instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 b) {
            // fired: bursts where it first hits; a tick it goes its speed times the round's, gravity on the speed (no air)
            if (b.config == null) return null;
            double m = b.config.velocity + b.accel;
            int left = b.config.expires > 0 ? Math.min(200, b.config.expires - b.tickCount) : 200;
            if (left <= 0) return b.position();
            return burst(AiBots.voxels(level), b.getX(), b.getY(), b.getZ(), v.x * m, v.y * m, v.z * m, left, 0, 0, level.getMinY(), b.config.gravity * m, 1);
        }
        if (!(p instanceof EntityGrenadeUniversal e)) return null;
        EnumGrenadeFuze f = e.getFuze();
        int left = switch (f) {
            case S3 -> 60;
            case S7 -> 140;
            case S15 -> 300;
            case IMPACT -> 200;
            default -> -1;
        };
        if (left < 0) return null;
        if (f != EnumGrenadeFuze.IMPACT) left -= e.getTimer();
        if (left <= 0) return e.position();
        return burst(AiBots.voxels(level), e.getX(), e.getY(), e.getZ(), v.x, v.y, v.z, left,
                f == EnumGrenadeFuze.IMPACT ? Math.max(0, IMPACT_ARMED - e.getTimer()) : -1, e.getShell().getBounce(), level.getMinY());
    }

    /** a burst to run from, how far off it is out of its reach, and whether it is its own side's (no harm at all) */
    record Danger(Vec3 burst, double clear, boolean own) {
    }

    /**
     * The burst nearest this mob within so many blocks of it, of a grenade it knows of: one it has seen (in plain sight
     * within SEE of its eye) or heard land (within HEAR), or one of its own side's (it saw it thrown; its own come
     * near the place it was thrown at, so it keeps out of all its reach: SAFE and OWN_MARGIN); null if none.
     */
    static @Nullable Danger danger(ServerLevel level, AiBot bot, double within) {
        long t0 = System.nanoTime();
        try {
            return dangerNow(level, bot, within);
        } finally {
            liveNanos += System.nanoTime() - t0;
        }
    }

    private static @Nullable Danger dangerNow(ServerLevel level, AiBot bot, double within) {
        if (!dodgeOn) return null;
        List<Live> l = LEVELS.get(level);
        if (l == null) return null;
        Mob m = bot.mob;
        Vec3 eye = m.getEyePosition();
        Danger near = null;
        double best = Double.MAX_VALUE;
        VoxelRay.Source src = AiBots.voxels(level);
        for (Live g : l) {
            if (g.burst == null || g.e.isRemoved()) continue;
            boolean own = ownSide(m, g);
            // its own near throw, it down behind its cover till the burst: it stays down while blocks keep all of it
            // off the burst (worked out again as the grenade flies); else it runs as from any of its own side's
            if (own && g.e.getOwner() == m && bot.brain instanceof AiSoldier s && s.downFor(g.e) && s.downShield(bot, level.getGameTime()) > 0
                    && shielded(src, m.getX(), m.getY(), m.getZ(), s.downShield(bot, level.getGameTime()), g.burst)) continue;
            // its own side's: out to SAFE and OWN_MARGIN (as far as a throw keeps it from its own), 2 more once it runs
            // (no back and forth); it runs to a block past that (OWN_CLEAR)
            double r = own ? SAFE + OWN_MARGIN + (within > DANGER ? 2 : 0) : within;
            double d = m.distanceToSqr(g.burst);
            // its own side's: where it is on its way to as well (one went to other cover there after the throw and was
            // hit, too late to turn back: the battles)
            long goal = bot.mover.goal();
            if (own && goal != Long.MIN_VALUE && !bot.mover.arrived())
                d = Math.min(d, g.burst.distanceToSqr(BlockPos.getX(goal) + 0.5, BlockPos.getY(goal), BlockPos.getZ(goal) + 0.5));
            if (d >= r * r || d >= best) continue;
            int id = m.getId();
            if (!own && !g.known.contains(id)) {
                double e2 = g.e.position().distanceToSqr(eye);
                boolean knows = e2 <= HEAR * HEAR || e2 <= SEE * SEE && VoxelRay.clear(src, eye.x, eye.y, eye.z, g.e.getX(), g.e.getY(), g.e.getZ(), Voxel.SIGHT);
                if (!knows) continue;
                g.known.add(id);
            }
            best = d;
            near = new Danger(g.burst, own ? OWN_CLEAR : CLEAR, own);
        }
        return near;
    }

    /** its own side's grenade (it threw it, or one of its side) */
    private static boolean ownSide(Mob m, Live g) {
        return g.e.getOwner() instanceof net.minecraft.world.entity.LivingEntity o && (o == m || AiTeams.own(m, o));
    }

    /** a place in the reach of one of these bursts: its own side's out to OTHER_OWN, others' out to CLEAR unless behind a block */
    private static boolean inOthers(VoxelRay.Source src, Mob m, List<Live> others, double x, double y, double z) {
        for (Live g : others) {
            boolean own = ownSide(m, g);
            double r = own ? OTHER_OWN : CLEAR;
            if (g.burst.distanceToSqr(x, y, z) < r * r && !cover(src, g.burst, x, y, z, own)) return true;
        }
        return false;
    }

    /** whether any grenade is in the level (cheap: most ticks there is none) */
    static boolean any(ServerLevel level) {
        List<Live> l = LEVELS.get(level);
        return l != null && !l.isEmpty();
    }

    /** whether this cell is out of the burst's reach (clear) or behind a block from it */
    static boolean safe(VoxelRay.Source src, Vec3 burst, long cell, double clear, boolean own) {
        double cx = BlockPos.getX(cell) + 0.5, cy = BlockPos.getY(cell), cz = BlockPos.getZ(cell) + 0.5;
        return burst.distanceToSqr(cx, cy, cz) >= clear * clear || cover(src, burst, cx, cy, cz, own);
    }

    /**
     * Where to run from a burst here: the nearest cell (up to 17 blocks off, round it; 20 from its own side's) clear
     * off it or behind a block from it (cover), none tried already, none nearer the burst unless behind a block, none in
     * the reach of another burst it knows of (its own side's: OTHER_OWN; others': CLEAR, unless behind a block from it)
     * if there is one such; Long.MIN_VALUE if none is.
     */
    static long refuge(ServerLevel level, Mob m, Vec3 burst, double clear, boolean own, it.unimi.dsi.fastutil.longs.LongSet tried) {
        long t0 = System.nanoTime();
        try {
            return refugeNow(level, m, burst, clear, own, tried);
        } finally {
            liveNanos += System.nanoTime() - t0;
        }
    }

    private static final double[] RINGS = {2.5, 4.5, 6.5, 9, 11.5, 14, 17}, OWN_RINGS = {2.5, 4.5, 6.5, 9, 11.5, 14, 17, 20};

    private static long refugeNow(ServerLevel level, Mob m, Vec3 burst, double clear, boolean own, it.unimi.dsi.fastutil.longs.LongSet tried) {
        TacticalMap map = TacticalMap.of(level);
        VoxelRay.Source src = AiBots.voxels(level);
        List<Live> others = others(level, src, m, burst);
        long cell = rings(map, src, m, burst, clear, own, tried, others);
        // none out of the others' reach as well: out of this one's, as if there were no others
        return cell == Long.MIN_VALUE && !others.isEmpty() ? rings(map, src, m, burst, clear, own, tried, List.of()) : cell;
    }

    /** the bursts other than this one it knows of: its own side's (all of them), others' it sees or hears now or did */
    private static List<Live> others(ServerLevel level, VoxelRay.Source src, Mob m, Vec3 burst) {
        List<Live> others = new ArrayList<>();
        List<Live> l = LEVELS.get(level);
        if (l != null) for (Live g : l) {
            if (g.burst == null || g.e.isRemoved() || g.burst == burst) continue;
            if (ownSide(m, g) || knows(src, m, g)) others.add(g);
        }
        return others;
    }

    /** whether the refuge it runs to from this burst is in the reach of another it knows of now (one thrown since) */
    static boolean othersReach(ServerLevel level, Mob m, Vec3 burst, long cell) {
        VoxelRay.Source src = AiBots.voxels(level);
        List<Live> others = others(level, src, m, burst);
        return !others.isEmpty() && inOthers(src, m, others, BlockPos.getX(cell) + 0.5, BlockPos.getY(cell), BlockPos.getZ(cell) + 0.5);
    }

    /** whether the mob knows of this grenade: seen (in plain sight within SEE) or heard (within HEAR) now or before */
    private static boolean knows(VoxelRay.Source src, Mob m, Live g) {
        int id = m.getId();
        if (g.known.contains(id)) return true;
        Vec3 eye = m.getEyePosition();
        double e2 = g.e.position().distanceToSqr(eye);
        if (e2 > HEAR * HEAR && (e2 > SEE * SEE || !VoxelRay.clear(src, eye.x, eye.y, eye.z, g.e.getX(), g.e.getY(), g.e.getZ(), Voxel.SIGHT))) return false;
        g.known.add(id);
        return true;
    }

    private static long rings(TacticalMap map, VoxelRay.Source src, Mob m, Vec3 burst, double clear, boolean own, it.unimi.dsi.fastutil.longs.LongSet tried,
            List<Live> others) {
        long here = m.blockPosition().asLong(), best = Long.MIN_VALUE;
        double bestScore = Double.MAX_VALUE;
        double away = Math.atan2(m.getZ() - burst.z, m.getX() - burst.x);
        // its own side's: one more ring, OWN_CLEAR off the burst being past the last from a place by it
        for (double far : own ? OWN_RINGS : RINGS) {
            for (int k = 0; k < 16; k++) {
                double a = away + Math.PI * 2 * k / 16;
                long cell = PathSearch.snap(map, m.getX() + Math.cos(a) * far, m.getY(), m.getZ() + Math.sin(a) * far);
                if (cell == Long.MIN_VALUE || cell == here || tried.contains(cell)) continue;
                double cx = BlockPos.getX(cell) + 0.5, cy = BlockPos.getY(cell), cz = BlockPos.getZ(cell) + 0.5;
                double dx = cx - burst.x, dy = cy - burst.y, dz = cz - burst.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                boolean out = d2 >= clear * clear;
                boolean behind = cover(src, burst, cx, cy, cz, own);
                if (!out && !behind) continue;
                if (inOthers(src, m, others, cx, cy, cz)) continue;
                // not past the burst to get there: no nearer to it than it is now unless behind a block from it
                if (!behind && d2 < m.distanceToSqr(burst)) continue;
                // the way there: the shorter the better; behind a block a little better still
                double score = Math.hypot(cx - m.getX(), cz - m.getZ()) - (behind ? 1.5 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = cell;
                }
            }
            if (best != Long.MIN_VALUE) return best;
        }
        return best;
    }
}
