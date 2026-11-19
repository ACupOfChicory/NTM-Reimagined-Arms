package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

/**
 * What one side's staff knows of its enemies (stage 7, C0), with nothing of the game in it: the book {@link AiIntel}
 * keeps for each side. A report comes in every {@link #REPORT} ticks with the enemies the side can know of
 * (where they truly are, when one of the side last saw each); the book makes groups of them (those within
 * {@link #GROUP} of one another) and gives each group a rough whereabouts only: a circle of radius 16 blocks if one of
 * the side saw one of them lately, a block wider every {@link #WIDEN} ticks none did, 32 at most. They are within the
 * circle; its middle is off theirs by up to half the radius, the same way for {@link #SHIFT} ticks, and it moves half
 * the way to where it should be at each report (a picture that drifts towards the truth, not one that jumps to it).
 * The soldiers never read it: they get orders.
 */
public final class IntelBook {
    /** enemies this near one another are one group, blocks */
    public static final double GROUP = 24;
    /** the circle: one of them seen within the last report; at most, blocks */
    public static final double R_MIN = 16, R_MAX = 32;
    /** a block wider every this many ticks none of the side has seen any of them; the middle off the same way this long */
    public static final int WIDEN = 40, SHIFT = 600;
    /** a report this often, ticks */
    public static final int REPORT = 100;
    /** the staff knows of enemies this near one of its side, blocks (the owner: 256) */
    public static final double KNOW = 256;
    /** the part of the way to where the middle should be that it goes at each report */
    public static final double DRIFT = 0.5;
    /** sightings and losses kept this long, ticks */
    static final int KEEP = (int) (R_MAX * WIDEN * 4);

    /** an enemy as the report has it: where it is (the truth, the book's alone), when the side last saw it (Long.MIN_VALUE: never), its kind (a role's ordinal, PLAYER), its health (0 to 1), whether it thinks now */
    public record Foe(int id, double x, double y, double z, long seen, int kind, float health, boolean awake) {
    }

    public static final int PLAYER = -1;

    /** a group as the side knows it */
    public static final class Group {
        /** its members' ids, the least first; its lead (the least) */
        public final int[] ids;
        public final int lead;
        /** the middle of its circle (y: theirs), the circle's radius */
        public double x, y, z, r;
        /** the last tick one of the side saw one of them (Long.MIN_VALUE: never); the last one of them downed one of the side */
        public long seen = Long.MIN_VALUE, lost = Long.MIN_VALUE;
        /** how many, of each kind (by role's ordinal, players last), their health in all */
        public final int[] kinds;
        public float health;
        /** one of them thinks now (near a player, or all awake) */
        public boolean awake;

        Group(int[] ids, int kinds) {
            this.ids = ids;
            this.lead = ids[0];
            this.kinds = new int[kinds + 1];
        }

        public boolean has(int id) {
            for (int i : ids) if (i == id) return true;
            return false;
        }

        public boolean any(int @Nullable [] other) {
            if (other != null) for (int i : other) if (has(i)) return true;
            return false;
        }

        public int players() {
            return kinds[kinds.length - 1];
        }
    }

    private final int roles;
    private final Int2LongOpenHashMap seen = new Int2LongOpenHashMap(), lost = new Int2LongOpenHashMap();
    private List<Group> groups = List.of();
    /** reports taken */
    public long reports;

    /** roles: how many kinds of soldier there are (AiRole) */
    public IntelBook(int roles) {
        this.roles = roles;
        seen.defaultReturnValue(Long.MIN_VALUE);
        lost.defaultReturnValue(Long.MIN_VALUE);
    }

    /** the groups as of the last report */
    public List<Group> groups() {
        return groups;
    }

    /** one of this enemy's downed one of the side now (R5b): its group is where the side loses soldiers */
    public void lost(int id, long tick) {
        lost.put(id, tick);
        // the groups as known now: where the side loses soldiers at once, not from the next report
        for (Group g : groups) if (g.has(id)) g.lost = Math.max(g.lost, tick);
    }

    /** the radius of the circle for a group last seen at this tick */
    public static double radius(long seen, long now) {
        if (seen == Long.MIN_VALUE) return R_MAX;
        return Math.max(R_MIN, Math.min(R_MAX, R_MIN + Math.max(0, now - seen - REPORT) / (double) WIDEN));
    }

    /** a report: the enemies the side may know of now, with where they truly are */
    public List<Group> report(List<Foe> foes, long now) {
        reports++;
        for (Foe f : foes) if (f.seen() > seen.get(f.id())) seen.put(f.id(), f.seen());
        seen.values().removeIf(t -> now - t > KEEP);
        lost.values().removeIf(t -> now - t > KEEP);
        List<Group> before = groups, out = new ArrayList<>();
        boolean[] taken = new boolean[foes.size()];
        for (int i = 0; i < foes.size(); i++) {
            if (taken[i]) continue;
            List<Foe> g = new ArrayList<>();
            g.add(foes.get(i));
            taken[i] = true;
            for (int k = 0; k < g.size(); k++)
                for (int j = 0; j < foes.size(); j++) {
                    if (taken[j]) continue;
                    Foe a = g.get(k), b = foes.get(j);
                    double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
                    if (dx * dx + dy * dy + dz * dz <= GROUP * GROUP) {
                        taken[j] = true;
                        g.add(b);
                    }
                }
            g.sort((a, b) -> Integer.compare(a.id(), b.id()));
            int[] ids = new int[g.size()];
            for (int k = 0; k < ids.length; k++) ids[k] = g.get(k).id();
            Group n = new Group(ids, roles);
            double tx = 0, ty = 0, tz = 0;
            for (Foe f : g) {
                tx += f.x() / g.size();
                ty += f.y() / g.size();
                tz += f.z() / g.size();
                n.seen = Math.max(n.seen, seen.get(f.id()));
                n.lost = Math.max(n.lost, lost.get(f.id()));
                n.kinds[f.kind() < 0 || f.kind() >= roles ? roles : f.kind()]++;
                n.health += f.health();
                n.awake |= f.awake();
            }
            n.r = radius(n.seen, now);
            // where its middle should be: theirs, off by up to half the radius, the same way for a while
            Random rng = new Random(n.lead * 31L + now / SHIFT * 1_000_003L);
            double a = rng.nextDouble() * Math.PI * 2, off = rng.nextDouble() * n.r * 0.5;
            double wx = tx + Math.cos(a) * off, wz = tz + Math.sin(a) * off;
            // known before (one of its members in a group of the last report): half the way there from where it was
            Group old = null;
            for (Group o : before) if (o.any(ids)) {
                old = o;
                break;
            }
            double x = old == null ? wx : old.x + (wx - old.x) * DRIFT, z = old == null ? wz : old.z + (wz - old.z) * DRIFT;
            // never farther from them than the radius: they are within the circle
            double dx = x - tx, dz = z - tz, d = Math.hypot(dx, dz);
            if (d > n.r) {
                x = tx + dx / d * n.r;
                z = tz + dz / d * n.r;
            }
            n.x = x;
            n.y = ty;
            n.z = z;
            out.add(n);
        }
        groups = out;
        return out;
    }
}
