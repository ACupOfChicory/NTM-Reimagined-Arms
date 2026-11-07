package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What one mob knows of its targets: only what it saw and heard itself. A target it has seen long enough is known
 * (VISIBLE) and its place follows it while it stays in sight. Out of sight it is LOST: the mob keeps the place where
 * it last saw it and waits for it there (the hold), then SEARCHes outward from that place, then forgets it. The target's
 * real place never comes in here; a target that walks on behind a wall is still where it went out of sight.
 *
 * <p>Looks come in rounds: {@link #look} starts one, {@link #seen} for each target in sight, {@link #endLook} turns the
 * known ones not seen this round into lost ones. Plain data, no Minecraft: the tests drive it by hand.
 */
public final class TargetMemory {
    public enum State {
        /** in sight, not yet noticed */
        GLIMPSED,
        VISIBLE,
        LOST,
        SEARCH
    }

    public static final class Contact {
        public final int id;
        public State state = State.GLIMPSED;
        /** where it was last seen (feet), and its motion then, blocks per tick */
        public double x, y, z, vx, vz;
        public long seenTick = Long.MIN_VALUE;
        /** ticks in sight while being noticed, and the ticks it needed at its last look (the debug view) */
        int sightTicks, notice;
        boolean seenThisLook;
        /** where and when it went out of sight */
        public double lostX, lostY, lostZ;
        public long lostTick;

        Contact(int id) {
            this.id = id;
        }

        public boolean known() {
            return state != State.GLIMPSED;
        }
    }

    public static final class Noise {
        public final double x, y, z;
        public final long tick;
        public final boolean loud, hurt;

        Noise(double x, double y, double z, long tick, boolean loud, boolean hurt) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.tick = tick;
            this.loud = loud;
            this.hurt = hurt;
        }
    }

    /** a sound from the same block within this many ticks is the same sound going on: not news */
    static final int HABITUATE = 600;
    static final int MAX_CONTACTS = 8;

    private final AiProfile profile;
    private final List<Contact> contacts = new ArrayList<>();
    private @Nullable Noise noise;
    private final long[] heardBlocks = new long[8];
    private final long[] heardTicks = new long[8];
    private int heardNext;

    public TargetMemory(AiProfile profile) {
        this.profile = profile;
        java.util.Arrays.fill(heardTicks, Long.MIN_VALUE / 2);
    }

    public List<Contact> contacts() {
        return contacts;
    }

    public @Nullable Contact contact(int id) {
        for (Contact c : contacts) if (c.id == id) return c;
        return null;
    }

    public @Nullable Noise noise() {
        return noise;
    }

    // ------------------------------------------------------------------ sight

    public void look() {
        for (Contact c : contacts) c.seenThisLook = false;
    }

    /**
     * The target is in sight this round, at x y z (feet). notice: the ticks it must stay in sight to be noticed (by
     * distance and where in the field of view). Its motion comes from where it was seen before: the server does not
     * know a player's own. Returns its contact.
     */
    public Contact seen(int id, double x, double y, double z, long tick, int notice) {
        Contact c = contact(id);
        if (c == null) {
            if (contacts.size() >= MAX_CONTACTS) dropWeakest();
            c = new Contact(id);
            contacts.add(c);
        }
        c.seenThisLook = true;
        c.notice = c.state == State.GLIMPSED ? notice : notice / 2;
        if (c.state == State.GLIMPSED) {
            c.sightTicks += profile.period();
            if (c.sightTicks >= notice) c.state = State.VISIBLE;
        } else if (c.state != State.VISIBLE) {
            // one it already knows is about: noticed again in half the time
            c.sightTicks += profile.period();
            if (c.sightTicks >= notice / 2) c.state = State.VISIBLE;
        }
        if (c.state == State.VISIBLE) {
            long dt = c.seenTick == Long.MIN_VALUE ? 0 : tick - c.seenTick;
            if (dt > 0 && dt <= 4L * profile.period()) {
                c.vx = (x - c.x) / dt;
                c.vz = (z - c.z) / dt;
            } else {
                c.vx = 0;
                c.vz = 0;
            }
            c.x = x;
            c.y = y;
            c.z = z;
            c.seenTick = tick;
            c.sightTicks = 0;
        }
        return c;
    }

    public void endLook(long tick) {
        for (int i = contacts.size() - 1; i >= 0; i--) {
            Contact c = contacts.get(i);
            if (c.seenThisLook) continue;
            switch (c.state) {
                case GLIMPSED -> {
                    c.sightTicks -= profile.period();
                    if (c.sightTicks <= 0) contacts.remove(i);
                }
                case VISIBLE -> {
                    c.state = State.LOST;
                    c.lostX = c.x;
                    c.lostY = c.y;
                    c.lostZ = c.z;
                    c.lostTick = tick;
                    c.sightTicks = 0;
                }
                default -> c.sightTicks = Math.max(0, c.sightTicks - profile.period());
            }
        }
    }

    /** a known target's state moves on with time: LOST to SEARCH to forgotten */
    public void update(long tick) {
        for (int i = contacts.size() - 1; i >= 0; i--) {
            Contact c = contacts.get(i);
            long gone = tick - c.lostTick;
            if (c.state == State.LOST && gone > profile.hold()) c.state = State.SEARCH;
            if (c.state == State.SEARCH && gone > profile.hold() + profile.search()) contacts.remove(i);
        }
        if (noise != null && tick - noise.tick > profile.search()) noise = null;
    }

    private void dropWeakest() {
        Contact worst = null;
        for (Contact c : contacts) {
            if (c.state == State.VISIBLE) continue;
            if (worst == null || c.lostTick < worst.lostTick) worst = c;
        }
        contacts.remove(worst != null ? worst : contacts.get(0));
    }

    // ------------------------------------------------------------------ hearing

    /**
     * A sound reached the mob, at the place it seems to come from (the caller adds the error of the ear). A sound from
     * the same block within half a minute is not news (a machine running, a door left banging). Returns whether it
     * was taken.
     */
    public boolean heard(double x, double y, double z, long tick, boolean loud, boolean hurt) {
        if (!hurt) {
            long block = ((long) Math.floor(x) & 0x3FFFFFF) << 38 | ((long) Math.floor(z) & 0x3FFFFFF) << 12 | ((long) Math.floor(y) & 0xFFF);
            for (int i = 0; i < heardBlocks.length; i++)
                if (heardBlocks[i] == block && tick - heardTicks[i] < HABITUATE) {
                    heardTicks[i] = tick;
                    return false;
                }
            heardBlocks[heardNext] = block;
            heardTicks[heardNext] = tick;
            heardNext = (heardNext + 1) % heardBlocks.length;
        }
        // a quiet sound does not drown a loud one heard just before
        if (noise != null && !loud && !hurt && noise.loud && tick - noise.tick < profile.hold()) return false;
        noise = new Noise(x, y, z, tick, loud, hurt);
        return true;
    }

    // ------------------------------------------------------------------ what to act on

    /** the target to deal with: the nearest in sight, else the one lost last; null when it knows of none */
    public @Nullable Contact focus(double fromX, double fromZ) {
        Contact best = null;
        double bestD = Double.MAX_VALUE;
        for (Contact c : contacts)
            if (c.state == State.VISIBLE) {
                double d = (c.x - fromX) * (c.x - fromX) + (c.z - fromZ) * (c.z - fromZ);
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
        if (best != null) return best;
        for (Contact c : contacts)
            if (c.known() && (best == null || c.lostTick > best.lostTick)) best = c;
        return best;
    }

    /** how far from where it was lost a target may be by now, blocks (searchSpeed for the time past the hold) */
    public double searchRadius(Contact c, long tick) {
        double seconds = Math.max(0, tick - c.lostTick - profile.hold()) / 20.0;
        return Math.min(32, 2 + profile.searchSpeed() * seconds);
    }
}
