package acup.gunfeel.ai;

import java.util.function.DoubleSupplier;

/**
 * An AI mob's eye and hand on a target, as a person's (research.md §1.1). Plain data, no Minecraft: the tests drive it
 * by hand.
 *
 * <p>The eye ({@link #see}, {@link #lead}): it acts on where it saw the target {@link AiSkill#lag} ticks ago, from the
 * chest's places a tick apart. From them it gets the motion it saw, smoothed over {@link #TAU} ticks; the spot it
 * means to hit is where that motion carries the target by the time the bullet gets there. A strafe with a steady
 * rhythm (the last turns sideways evenly spaced) it reads as a person who has watched a few swings: the target will be
 * where it was one full swing (both ways) before, plus whatever it drifted since.
 *
 * <p>The hand ({@link #turn}): every {@link AiSkill#period} ticks it corrects towards that spot, and lands off by
 * {@link AiSkill#share} of how far it moved plus {@link AiSkill#floor}, at random (the one random thing in its aim);
 * in between it follows the motion it believes in, and the error stays. The trigger may be down only while the spot it
 * expects is on the barrel as far as it knows ({@link #onTarget} with {@link #seenYaw}, {@link #seenPitch}).
 *
 * <p>Recoil ({@link #kick}): a shot throws the view off the hand; part of the offset comes back by itself a share a
 * tick (the gun's rebound), part stays (a player's camera keeps it too). The hand learns of it lag ticks late, and then
 * takes back what it reckons is still left of it.
 */
public final class AiAim {
    /** ticks of places kept */
    static final int KEEP = 64;
    /** ticks the seen motion is smoothed over */
    static final double TAU = 3;
    /** sideways speed that counts as going one way, blocks a tick */
    static final double SIDE = 0.03;
    /** turns evenly spaced: no spacing more than this many ticks off another */
    static final int EVEN = 2;
    /** out of sight longer than this many ticks, it starts over */
    static final int GAP = 4;
    /** half a body's width and the chest to the top of the head, blocks */
    static final double HALF_WIDTH = 0.3, HALF_HEIGHT = 0.6;

    private final double[] xs = new double[KEEP], ys = new double[KEEP], zs = new double[KEEP];
    private final long[] at = new long[KEEP];
    private int target = Integer.MIN_VALUE;
    private long since, last = Long.MIN_VALUE;
    private double vx, vy, vz;
    // the last four turns sideways, as seen-ticks, oldest first; which way it went last
    private final long[] turns = new long[4];
    private int turnCount, side;

    /** {@link #lead}'s answer: the spot and how it moves a tick; whether the rhythm gave it */
    public double spotX, spotY, spotZ, moveX, moveY, moveZ;
    public boolean byRhythm;

    // the hand: the spot it follows (set at a correction, moved since by the motion it believes in), the error it
    // landed with
    private long nextFix = Long.MIN_VALUE, lastTurn = Long.MIN_VALUE;
    private double gx, gy, gz;
    private float errYaw, errPitch;
    /** {@link #turn}'s answer: where it looks now; where it thinks it looks (the recoil it has not noticed left out) */
    public float yaw, pitch, seenYaw, seenPitch;

    // recoil: the view's offset from the hand, degrees, the part that comes back (and the share a tick) and the part
    // that stays; both each tick
    private float offYaw, offPitch, stayYaw, stayPitch, rebound = 0.25F;
    private final float[] offYaws = new float[KEEP], offPitches = new float[KEEP], stayYaws = new float[KEEP], stayPitches = new float[KEEP];
    private final long[] offAt = new long[KEEP];

    /** starts over: a new target, or the old one after a while out of sight */
    public void forget() {
        target = Integer.MIN_VALUE;
        last = Long.MIN_VALUE;
    }

    /** the target's chest seen this tick, from a mob at (fromX, fromZ) */
    public void see(int id, double x, double y, double z, long tick, AiSkill skill, double fromX, double fromZ) {
        if (id != target || last == Long.MIN_VALUE || tick - last > GAP || tick <= last) {
            target = id;
            since = tick;
            vx = vy = vz = 0;
            turnCount = side = 0;
        }
        int i = slot(tick);
        xs[i] = x;
        ys[i] = y;
        zs[i] = z;
        at[i] = tick;
        last = tick;
        long ts = tick - skill.lag();
        if (!has(ts) || !has(ts - 1)) return;
        int a = slot(ts), b = slot(ts - 1);
        vx += (xs[a] - xs[b] - vx) / TAU;
        vy += (ys[a] - ys[b] - vy) / TAU;
        vz += (zs[a] - zs[b] - vz) / TAU;
        // which way it goes across the line from the mob, and the turns
        double dx = xs[a] - fromX, dz = zs[a] - fromZ, len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) return;
        double across = (vx * -dz + vz * dx) / len;
        int s = across > SIDE ? 1 : across < -SIDE ? -1 : 0;
        if (s != 0 && s != side) {
            if (side != 0) addTurn(ts);
            side = s;
        }
    }

    private void addTurn(long ts) {
        if (turnCount == turns.length) {
            System.arraycopy(turns, 1, turns, 0, turns.length - 1);
            turnCount--;
        }
        turns[turnCount++] = ts;
    }

    /**
     * The spot to aim at now, into {@link #spotX} and on: what it saw lag ticks ago, carried on for those ticks and
     * the flight (ticks) of a bullet from the eye. False without a sight of it.
     */
    public boolean lead(long tick, AiSkill skill, double flight) {
        if (last == Long.MIN_VALUE) return false;
        // what it has seen most lately, lag ticks back (or its first sight, if it has watched less)
        long ts = Math.max(Math.min(tick, last) - skill.lag(), since);
        if (!has(ts)) return false;
        int a = slot(ts);
        double h = (tick - ts) + flight;
        spotX = xs[a] + vx * h;
        spotY = ys[a] + vy * h;
        spotZ = zs[a] + vz * h;
        moveX = vx;
        moveY = vy;
        moveZ = vz;
        byRhythm = skill.rhythm() && rhythm(ts, h);
        return true;
    }

    // a steady swing: the spot is where it was a swing before the hit, plus the drift since a swing before now
    private boolean rhythm(long ts, double h) {
        if (turnCount < turns.length) return false;
        long g1 = turns[1] - turns[0], g2 = turns[2] - turns[1], g3 = turns[3] - turns[2];
        if (Math.max(g1, Math.max(g2, g3)) - Math.min(g1, Math.min(g2, g3)) > EVEN) return false;
        double gap = (g1 + g2 + g3) / 3.0;
        // overdue for a turn that has not come: it stopped swinging
        if (ts - turns[3] > gap + EVEN + TAU) return false;
        double swing = 2 * gap, back = ts + h - swing, before = ts - swing;
        if (back > ts || !hasAt(before) || !hasAt(back) || !hasAt(back + 1)) return false;
        int now = slot(ts);
        spotX = x(back) + xs[now] - x(before);
        spotY = y(back) + ys[now] - y(before);
        spotZ = z(back) + zs[now] - z(before);
        moveX = x(back + 1) - x(back);
        moveY = y(back + 1) - y(back);
        moveZ = z(back + 1) - z(back);
        return true;
    }

    /**
     * Turns from where it looks (yaw0, pitch0) towards a spot moving by (mx, my, mz) a tick, from the eye at
     * (ex, ey, ez), into {@link #yaw} and {@link #pitch}: a correction every period ticks, the spot's motion followed
     * in between, at most the skill's turn a tick; the recoil comes back a share and the hand takes back what it knows
     * of the rest. Minecraft's angles: yaw 0 looks to +z, pitch up is less.
     */
    public void turn(long tick, AiSkill skill, double ex, double ey, double ez, float yaw0, float pitch0, double sx, double sy, double sz,
            double mx, double my, double mz, DoubleSupplier gauss) {
        if (lastTurn != Long.MIN_VALUE && (tick <= lastTurn || tick - lastTurn > 1)) {
            // it did not hold the gun on anything for a while (the head went where the game turned it): no kick is left
            offYaw = offPitch = stayYaw = stayPitch = 0F;
            java.util.Arrays.fill(offAt, Long.MIN_VALUE);
        }
        // the hand is the view without the recoil; the recoil comes back a share (but for what stays), and the hand
        // knows it as it was lag ticks ago, carried on as it comes back since
        float handYaw = yaw0 - offYaw - stayYaw, handPitch = pitch0 - offPitch - stayPitch;
        offYaw *= 1 - rebound;
        offPitch *= 1 - rebound;
        int k = slot(tick);
        offYaws[k] = offYaw;
        offPitches[k] = offPitch;
        stayYaws[k] = stayYaw;
        stayPitches[k] = stayPitch;
        offAt[k] = tick;
        long back = tick - skill.lag();
        int b = slot(back);
        float left = (float) Math.pow(1 - rebound, skill.lag());
        boolean knows = offAt[b] == back;
        if (knows && (stayYaws[b] != 0F || stayPitches[b] != 0F)) {
            // what stays and the hand knows of now is just where the hand is: moved over to it, so it does not pile up
            float y = stayYaws[b], p = stayPitches[b];
            for (long t = back; t <= tick; t++) {
                int j = slot(t);
                if (offAt[j] != t) continue;
                stayYaws[j] -= y;
                stayPitches[j] -= p;
            }
            stayYaw -= y;
            stayPitch -= p;
            handYaw += y;
            handPitch += p;
        }
        float knownYaw = knows ? offYaws[b] * left : 0F, knownPitch = knows ? offPitches[b] * left : 0F;
        if (tick >= nextFix || tick <= lastTurn || tick - lastTurn > 1) {
            nextFix = tick + skill.period();
            gx = sx;
            gy = sy;
            gz = sz;
            float e = (float) Math.hypot(wrap(yawTo(ex, ez, sx, sz) - handYaw - knownYaw), pitchTo(ex, ey, ez, sx, sy, sz) - handPitch - knownPitch);
            float sd = skill.share() * e + skill.floor();
            errYaw = (float) gauss.getAsDouble() * sd;
            errPitch = (float) gauss.getAsDouble() * sd * 0.6F;
        } else {
            gx += mx;
            gy += my;
            gz += mz;
        }
        lastTurn = tick;
        float goalYaw = yawTo(ex, ez, gx, gz) + errYaw - knownYaw, goalPitch = pitchTo(ex, ey, ez, gx, gy, gz) + errPitch - knownPitch;
        float t = skill.turn();
        float hy = handYaw + clamp(wrap(goalYaw - handYaw), t), hp = Math.max(-90F, Math.min(90F, handPitch + clamp(goalPitch - handPitch, t)));
        yaw = hy + offYaw + stayYaw;
        pitch = Math.max(-90F, Math.min(90F, hp + offPitch + stayPitch));
        seenYaw = hy + knownYaw;
        seenPitch = hp + knownPitch;
    }

    /**
     * A shot's kick: the view jumps up by up degrees and left by side (NTM's signs), that part coming back by the gun's
     * rebound share a tick, and by stayUp and staySide more that stay; all but the skill's absorb share. Returns how far
     * it moved the view, {yaw, pitch}.
     */
    public float[] kick(float up, float side, float stayUp, float staySide, float rebound, AiSkill skill) {
        float keep = 1 - skill.absorb();
        offYaw -= side * keep;
        offPitch -= up * keep;
        stayYaw -= staySide * keep;
        stayPitch -= stayUp * keep;
        this.rebound = Math.max(0F, Math.min(1F, rebound));
        return new float[] {-(side + staySide) * keep, -(up + stayUp) * keep};
    }

    /** a new aim: the next {@link #turn} corrects at once */
    public void retarget() {
        nextFix = Long.MIN_VALUE;
    }

    /**
     * Whether a shot from the eye looking at (yaw, pitch) would meet a body at the spot (sx, sy, sz): across within
     * the skill's gate times half its width, up and down within the gate times chest to head.
     */
    public static boolean onTarget(AiSkill skill, double ex, double ey, double ez, float yaw, float pitch, double sx, double sy, double sz) {
        double dx = sx - ex, dy = sy - ey, dz = sz - ez, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-6) return true;
        double across = Math.abs(wrap(yawTo(ex, ez, sx, sz) - yaw)) * Math.cos(Math.toRadians(pitch));
        double up = Math.abs(pitchTo(ex, ey, ez, sx, sy, sz) - pitch);
        return across <= skill.gate() * Math.toDegrees(Math.atan(HALF_WIDTH / d)) && up <= skill.gate() * Math.toDegrees(Math.atan(HALF_HEIGHT / d));
    }

    public static float yawTo(double ex, double ez, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(z - ez, x - ex)) - 90F;
    }

    public static float pitchTo(double ex, double ey, double ez, double x, double y, double z) {
        double dx = x - ex, dz = z - ez;
        return (float) -Math.toDegrees(Math.atan2(y - ey, Math.sqrt(dx * dx + dz * dz)));
    }

    static float wrap(float deg) {
        float d = deg % 360F;
        if (d >= 180F) d -= 360F;
        if (d < -180F) d += 360F;
        return d;
    }

    private static float clamp(float v, float max) {
        return Math.max(-max, Math.min(max, v));
    }

    private static int slot(long tick) {
        return (int) Math.floorMod(tick, (long) KEEP);
    }

    private boolean has(long tick) {
        return tick >= since && tick <= last && last - tick < KEEP && at[slot(tick)] == tick;
    }

    private boolean hasAt(double tick) {
        long i = (long) Math.floor(tick);
        return has(i) && (tick == i || has(i + 1));
    }

    private double x(double t) {
        return lerp(xs, t);
    }

    private double y(double t) {
        return lerp(ys, t);
    }

    private double z(double t) {
        return lerp(zs, t);
    }

    private double lerp(double[] v, double t) {
        long i = (long) Math.floor(t);
        double f = t - i, a = v[slot(i)];
        return f == 0 ? a : a + (v[slot(i + 1)] - a) * f;
    }
}
