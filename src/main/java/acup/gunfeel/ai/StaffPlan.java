package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A side's staff's tasks for its squads (stage 7, C2), no game in it: from the side's squads (where, how strong, what
 * they were doing) and its enemies' groups as its picture has them (where, how strong, how lately seen), each squad
 * one task. The groups taken nearest the side's middle first; for each, the nearest free squads within reach:
 * <ul>
 * <li>one at least half as strong again as the group: it advances on it (or scouts its area if the side has not seen
 * any of it for STALE ticks);
 * <li>else, a second free one within reach too: one pins it from a place PIN blocks off it toward that squad, the
 * other goes round (which does which: the one less in the enemy's sight on the way there, on the map; else the nearer
 * pins) to a place FLANK_D blocks off it, FLANK_ANGLE off the line from the pinning one;
 * <li>else, one weaker than a third less than the group: it holds a place near it, higher, from which it sees it;
 * <li>else it advances (scouts).
 * </ul>
 * PER squads to a group at most; more where the side loses soldiers to it (R5b: the group's room). A squad that lost
 * more than half of it, or most of which is out of rounds, falls back: from the nearest group to the nearest squad of
 * its own side not falling back, or BACK blocks away from the group. With no group at all, each free squad scouts a
 * sector of the ring round the side's middle (RING blocks off), one after another as the side's sector turns. Any
 * other squad left is the reserve and stays where it is. A squad keeps its task KEEP ticks at least while its group is
 * still there (falling back aside); one that went round and got there advances next. A pair (C4) keeps its tasks till
 * its time is up rather than KEEP: the one going round, there or in a fight, advances; its time up, both advance. A squad is not sent to a group it
 * gave up lately (R5: none of it could get there). A squad asleep (no player near, C2b) is sent as far as the staff
 * knows, FAR, not reach; with no group known it waits (no scouting).
 */
final class StaffPlan {
    enum Kind {
        ADVANCE, PIN, FLANK, HOLD, SCOUT, RESERVE, BACK
    }

    /** a squad at least this many times as strong as the group advances alone; one weaker than this part of it holds */
    static final double ALONE = 1.5, WEAK = 0.67;
    /** no one of the group seen this lately: the squad sent there scouts it rather than advances, ticks */
    static final long STALE = 1200;
    /** the pinning squad's place this far off the group, blocks; the one going round's, and its angle off the line */
    static final double PIN = 60, FLANK_D = 40, FLANK_ANGLE = Math.toRadians(75);
    /** pinned down this long with no one come round, or losing more: it breaks contact (C5), ticks */
    static final long BREAK_AFTER = 400;
    /**
     * the pinning place got to out of sight (C4b): looked for PIN_NEAR..PIN_FAR off the group by PIN_STEP, PIN_TURNS
     * of PIN_TURN each way of the line to the squad; seeing the group (its cell seen PIN_SEES at least); the way's last
     * PIN_LAST blocks not counted (it comes into sight to fire); taken for one at most PIN_BETTER of the blocks in sight
     * and PIN_LESS fewer
     */
    static final double PIN_NEAR = 50, PIN_FAR = 80, PIN_STEP = 15, PIN_TURN = Math.toRadians(20), PIN_SEES = 0.5, PIN_LAST = 20, PIN_BETTER = 0.8, PIN_LESS = 8;
    static final int PIN_TURNS = 2;
    /** going round: a side this many blocks farther to go by the map weighs as one wholly in the enemy's sight */
    static final double FAR_SIDE = 100;
    /**
     * going round: a side farther than this to go by the map is not got round in a pair's time (AiCommander.PAIR_MOST
     * by bounds, half the squad going at a time: some 0.085 blocks a tick on the war's stand): not gone round to
     */
    static final double FAR_MOST = AiCommander.PAIR_MOST * 0.085;
    /** a squad holding looks for its place this far round it at most, blocks */
    static final double HOLD_NEAR = 24;
    /** a squad falling back with no squad of its own to fall back on goes this far from the group, blocks */
    static final double BACK = 64;
    /** with no group known, the sectors scouted round the side's middle, this far off */
    static final double RING = 96;
    static final int SECTORS = 6;
    /** a task kept this long at least, ticks */
    static final long KEEP = 400;
    /** a squad asleep is sent to a group this far off at most: as far as the staff knows (AiIntel.KNOW), blocks */
    static final double FAR = AiIntel.KNOW;
    /** the place of a task to hold, pin, go round to or fall back to: reached this near, blocks */
    static final double PLACE = 6;

    /**
     * A squad: where its middle is; its strength; soldiers alive, how many it had when given its task; most of it out
     * of rounds; its task now (null: none), since when, the group it is for (an index into the groups, -1: none), got
     * to its place; the group it gave up lately (-1: none); asleep, far from any player (C2b: sent as far as the staff
     * knows, FAR, the staff moving it); in a fight now; a pair's (C4: pinning and going round) time up at this tick
     * (Long.MAX_VALUE: none); pinned down since (C5: its report; Long.MIN_VALUE: not), losing more since, its pair's
     * other alive and in time (help on its way).
     */
    record Squad(double x, double z, double strength, int alive, int was, boolean out, @Nullable Kind kind, long since, int group, boolean there, int avoid,
            boolean far, boolean contact, long due, long pinned, boolean worse, boolean helped) {
        Squad(double x, double z, double strength, int alive, int was, boolean out, @Nullable Kind kind, long since, int group, boolean there, int avoid,
                boolean far, boolean contact, long due) {
            this(x, z, strength, alive, was, out, kind, since, group, there, avoid, far, contact, due, Long.MIN_VALUE, false, false);
        }

        Squad(double x, double z, double strength, int alive, int was, boolean out, @Nullable Kind kind, long since, int group, boolean there) {
            this(x, z, strength, alive, was, out, kind, since, group, there, -1, false);
        }

        Squad(double x, double z, double strength, int alive, int was, boolean out, @Nullable Kind kind, long since, int group, boolean there, int avoid,
                boolean far) {
            this(x, z, strength, alive, was, out, kind, since, group, there, avoid, far, false, Long.MAX_VALUE);
        }

        /** one of a pair (C4) whose time is up */
        boolean late(long now) {
            return (kind == Kind.PIN || kind == Kind.FLANK) && now >= due;
        }

        /** one of a pair (C4): its task kept till the pair's time is up, not KEEP */
        boolean paired() {
            return (kind == Kind.PIN || kind == Kind.FLANK) && due != Long.MAX_VALUE;
        }

        /** how far off a group it is sent at most: reach awake, FAR asleep */
        double reach(double reach) {
            return far ? Math.max(reach, FAR) : reach;
        }
    }

    /** a group: its circle's middle and radius, its strength, ticks since one of the side saw any of it (Long.MAX_VALUE: never), room for squads (R5b) */
    record Group(double x, double z, double r, double strength, long unseen, int room) {
    }

    /** a squad's task: what, for which group (-1: none), the place (blocks), how near is there, where to face */
    record Task(Kind kind, int group, double x, double z, double r, double faceX, double faceZ) {
    }

    private StaffPlan() {
    }

    /**
     * Each squad's task (in the squads' order). grid: the staff's map for the places (null: the places straight
     * off). sideX, sideZ: the side's middle; reach: how far off a group a squad is sent at most; per: squads to a
     * group at most; sector: the side's sector to scout next (with no group known).
     */
    static Task[] plan(List<Squad> squads, List<Group> groups, @Nullable HqGrid grid, double sideX, double sideZ, double reach, int per, long now, int sector) {
        Task[] out = new Task[squads.size()];
        int[] sent = new int[groups.size()];
        // falling back first
        for (int i = 0; i < squads.size(); i++) {
            Squad s = squads.get(i);
            if (!breaks(s)) continue;
            out[i] = back(squads, groups, i);
        }
        pinned(squads, groups, grid, reach, now, out, sent);
        // those keeping their tasks
        for (int i = 0; i < squads.size(); i++) {
            Squad s = squads.get(i);
            if (out[i] != null || s.kind == null || s.group < 0 || s.group >= groups.size() || s.group == s.avoid || !s.paired() && now - s.since >= KEEP) continue;
            if (s.kind == Kind.RESERVE || s.kind == Kind.BACK || s.kind == Kind.FLANK && s.there) continue;
            // a pair (C4): the one going round there or in a fight advances; the time up, both do
            if (s.kind == Kind.FLANK && s.contact || s.late(now)) continue;
            if (sent[s.group] >= groups.get(s.group).room) continue;
            Group g = groups.get(s.group);
            out[i] = switch (s.kind) {
                case ADVANCE, SCOUT -> area(g, s.group);
                case PIN -> pin(grid, g, s.group, s.x, s.z);
                case HOLD -> hold(grid, g, s.group, s);
                case FLANK -> {
                    // the pinning squad of the same group, else the side's middle
                    double px = sideX, pz = sideZ;
                    for (int j = 0; j < squads.size(); j++)
                        if (j != i && squads.get(j).group == s.group && squads.get(j).kind == Kind.PIN) {
                            px = squads.get(j).x;
                            pz = squads.get(j).z;
                        }
                    Task f = flank(grid, g, s.group, px, pz, s.x, s.z);
                    yield f != null ? f : area(g, s.group);
                }
                default -> null;
            };
            if (out[i] != null) sent[s.group]++;
        }
        // the groups, nearest the side first: the nearest free squads within reach of each
        Integer[] order = new Integer[groups.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Double.compare(d2(groups.get(a), sideX, sideZ), d2(groups.get(b), sideX, sideZ)));
        for (int k : order) {
            Group g = groups.get(k);
            while (sent[k] < Math.min(per, g.room)) {
                int a = nearest(squads, out, g, k, reach, -1);
                if (a < 0) break;
                Squad sa = squads.get(a);
                if (sent[k] > 0 || sa.strength >= ALONE * g.strength) {
                    out[a] = area(g, k);
                    sent[k]++;
                    continue;
                }
                int b = sent[k] + 1 < Math.min(per, g.room) ? nearest(squads, out, g, k, reach, a) : -1;
                if (b >= 0) {
                    Squad sb = squads.get(b);
                    // the pair keeps the parts it had (a squad going round does not turn back to pin); a new pair takes
                    // the parts that keep it out of the enemy's sight the more on the way to their places
                    boolean had = sa.group == k && (sa.kind == Kind.PIN || sa.kind == Kind.FLANK) || sb.group == k && (sb.kind == Kind.PIN || sb.kind == Kind.FLANK);
                    // a pair whose time is up (C4): both advance, not paired again
                    if (had && (sa.group == k && sa.late(now) || sb.group == k && sb.late(now))) {
                        out[a] = area(g, k);
                        out[b] = area(g, k);
                        sent[k] += 2;
                        continue;
                    }
                    Task p = pin(grid, g, k, sa.x, sa.z), f = flank(grid, g, k, p.x(), p.z(), sb.x, sb.z);
                    Task p2 = pin(grid, g, k, sb.x, sb.z), f2 = flank(grid, g, k, p2.x(), p2.z(), sa.x, sa.z);
                    boolean swap = had ? sa.group == k && sa.kind == Kind.FLANK || sb.group == k && sb.kind == Kind.PIN
                            : exposed(grid, sb, p2) + exposed(grid, sa, f2) < exposed(grid, sa, p) + exposed(grid, sb, f) - 1;
                    if (swap) {
                        int c = a;
                        a = b;
                        b = c;
                        p = p2;
                        f = f2;
                    }
                    out[a] = p;
                    // no side to go round to in time (a river between, its ford far: C4), or no map yet to tell (it comes
                    // soon, the pairs on): it holds the best place about it, rather than be gone minutes on the way round
                    // or go up in the open to pin beside the other
                    // (asleep, C2b: no pair for it, moved on to the fight as before)
                    Squad sq = squads.get(b);
                    boolean blind = grid == null && AiCommander.mapped() && !sq.far;
                    out[b] = sq.far ? f != null ? f : area(g, k) : f != null && !blind ? f : grid == null && !blind ? pin(grid, g, k, sq.x, sq.z) : hold(grid, g, k, sq);
                    sent[k] += 2;
                    continue;
                }
                out[a] = sa.strength < WEAK * g.strength ? hold(grid, g, k, sa) : area(g, k);
                sent[k]++;
            }
        }
        // more where the side loses soldiers (R5b): the rest, each to the nearest group with room for it
        for (int i = 0; i < squads.size(); i++) {
            if (out[i] != null) continue;
            Squad s = squads.get(i);
            int best = -1;
            double bestD = s.reach(reach) * s.reach(reach);
            for (int k = 0; k < groups.size(); k++) {
                if (sent[k] >= groups.get(k).room || k == s.avoid) continue;
                double d = d2(groups.get(k), s.x, s.z);
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            if (best >= 0) {
                out[i] = area(groups.get(best), best);
                sent[best]++;
            }
        }
        // no group known: the sectors round the side, one after another; else the reserve
        int turn = 0;
        for (int i = 0; i < squads.size(); i++) {
            if (out[i] != null) continue;
            Squad s = squads.get(i);
            // a squad asleep is not sent scouting (the staff moves those asleep to a fight only, C2b)
            if (groups.isEmpty() && !s.far) {
                // one scouting a sector already keeps it (AiCommander): the next sectors to the new ones
                boolean keeps = s.kind == Kind.SCOUT && s.group < 0;
                double a = 2 * Math.PI * Math.floorMod(sector + (keeps ? 0 : turn++), SECTORS) / SECTORS;
                double x = sideX + RING * Math.cos(a), z = sideZ + RING * Math.sin(a);
                out[i] = new Task(Kind.SCOUT, -1, x, z, IntelBook.R_MAX, x, z);
            } else out[i] = new Task(Kind.RESERVE, -1, s.x, s.z, PLACE, s.x, s.z);
        }
        return out;
    }

    /**
     * blocks of the squad's walk to the task's place in the enemy's sight on the map, straight there and short of the
     * last HqGrid.END (the place itself may be in its sight: a lookout is); none with no map or no place
     */
    static double exposed(@Nullable HqGrid grid, Squad s, @Nullable Task t) {
        if (grid == null || t == null) return 0;
        double len = Math.hypot(t.x() - s.x, t.z() - s.z);
        return len <= HqGrid.END ? 0 : grid.seenPart(s.x, s.z, new double[] {t.x(), t.z()}, HqGrid.END) * (len - HqGrid.END);
    }

    /**
     * C5, by the field manual's drills: a squad pinned down by its group (its report) is the base of fire, holding where
     * it is and firing back (React to contact: PIN at its own place); the free squad nearest goes round the group as its
     * pair (Squad attack: one holds, one moves; C4's signal and time), or pins as well where the way round is not made
     * in time. Pinned BREAK_AFTER ticks and losing more: it breaks contact (BACK, smoke first: AiSoldier).
     */
    static void pinned(List<Squad> squads, List<Group> groups, @Nullable HqGrid grid, double reach, long now, Task[] out, int[] sent) {
        for (int i = 0; i < squads.size(); i++) {
            Squad s = squads.get(i);
            if (out[i] != null || s.pinned == Long.MIN_VALUE) continue;
            // broken contact: back till it is over (its report's REST), not sent on again meanwhile
            if (s.kind == Kind.BACK) {
                out[i] = back(squads, groups, i);
                continue;
            }
            if (s.group < 0 || s.group >= groups.size()) continue;
            Group g = groups.get(s.group);
            boolean helped = s.paired() && s.helped && !s.late(now);
            // pinned BREAK_AFTER and losing more, helped or not: it breaks contact (held fire with no losses is no reason)
            if (now - s.pinned >= BREAK_AFTER && s.worse) {
                out[i] = back(squads, groups, i);
                continue;
            }
            out[i] = new Task(Kind.PIN, s.group, s.x, s.z, PLACE, g.x, g.z);
            sent[s.group]++;
            // the one going round pinned: the two change over, its pair's pinning one goes round
            if (helped && s.kind == Kind.FLANK) {
                for (int j = 0; j < squads.size(); j++) {
                    Squad o = squads.get(j);
                    if (j == i || out[j] != null || o.group != s.group || o.kind != Kind.PIN || !o.paired() || o.pinned != Long.MIN_VALUE) continue;
                    Task f = flank(grid, g, s.group, s.x, s.z, o.x, o.z);
                    if (f != null) {
                        out[j] = f;
                        sent[s.group]++;
                    }
                    break;
                }
                continue;
            }
            if (helped) continue;
            // the free squad nearest, not one of a pair (C4) nor one to fall back
            int h = -1;
            double best = Double.MAX_VALUE;
            for (int j = 0; j < squads.size(); j++) {
                Squad o = squads.get(j);
                if (j == i || out[j] != null || o.avoid == s.group || o.paired() || breaks(o) || o.far || o.pinned != Long.MIN_VALUE || !idle(o)) continue;
                double d = d2(g, o.x, o.z), r = o.reach(reach);
                if (d < best && d <= r * r) {
                    best = d;
                    h = j;
                }
            }
            // none free: one pinning the same group (not pinned itself) goes round, the pinned one its base of fire now
            if (h < 0) for (int j = 0; j < squads.size(); j++) {
                Squad o = squads.get(j);
                if (j != i && out[j] == null && o.group == s.group && o.kind == Kind.PIN && o.pinned == Long.MIN_VALUE && !breaks(o)) {
                    h = j;
                    break;
                }
            }
            if (h < 0) continue;
            Squad sh = squads.get(h);
            Task f = flank(grid, g, s.group, s.x, s.z, sh.x, sh.z);
            out[h] = f != null ? f : pin(grid, g, s.group, sh.x, sh.z);
            sent[s.group]++;
        }
    }

    /** not at a group already (C5's help is not taken off an advance, a pin, a way round): none, holding, scouting, in reserve */
    private static boolean idle(Squad o) {
        return o.kind == null || o.group < 0 || o.kind == Kind.HOLD || o.kind == Kind.RESERVE || o.kind == Kind.SCOUT;
    }

    /** lost more than half of it, or most of it out of rounds */
    static boolean breaks(Squad s) {
        return s.out || s.was > 0 && s.alive * 2 < s.was;
    }

    private static double d2(Group g, double x, double z) {
        double dx = g.x - x, dz = g.z - z;
        return dx * dx + dz * dz;
    }

    /** the free squad nearest the group (k) within reach, not this one, not one that gave it up (-1: none) */
    private static int nearest(List<Squad> squads, Task[] out, Group g, int k, double reach, int not) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < squads.size(); i++) {
            if (i == not || out[i] != null || squads.get(i).avoid == k) continue;
            double d = d2(g, squads.get(i).x, squads.get(i).z), r = squads.get(i).reach(reach);
            if (d < bestD && d <= r * r) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** advance on the group (scout its area when the side has not seen it lately): its circle */
    static Task area(Group g, int k) {
        return new Task(g.unseen > STALE ? Kind.SCOUT : Kind.ADVANCE, k, g.x, g.z, g.r, g.x, g.z);
    }

    /** pin the group: a place PIN off it toward the squad (where it is, if nearer), the best about it on the map */
    static Task pin(@Nullable HqGrid grid, Group g, int k, double sx, double sz) {
        double dx = sx - g.x, dz = sz - g.z, d = Math.hypot(dx, dz);
        double x = d <= PIN ? sx : g.x + dx / d * PIN, z = d <= PIN ? sz : g.z + dz / d * PIN;
        double[] at = lookout(grid, x, z, HqGrid.CELL * 2, g, Double.MAX_VALUE);
        if (d > PIN && grid != null) at = hidden(grid, g, sx, sz, at);
        return new Task(Kind.PIN, k, at[0], at[1], PLACE, g.x, g.z);
    }

    /**
     * a place to pin from that the squad gets to less in the enemy's sight (C4b): of those about the group within
     * PIN_NEAR..PIN_FAR on the squad's side that see it (seen PIN_SEES at least), the one whose way there (the enemy's
     * sight weighing) is least in its sight short of its last PIN_LAST blocks; the place found by lookout unless one is
     * clearly so (PIN_BETTER of its blocks in sight, PIN_LESS fewer at least)
     */
    static double[] hidden(HqGrid grid, Group g, double sx, double sz, double[] at) {
        int si = grid.cellX(sx), sk = grid.cellZ(sz), ai = grid.cellX(at[0]), ak = grid.cellZ(at[1]);
        if (si < 0 || sk < 0 || ai < 0 || ak < 0) return at;
        int[] from = grid.tree(si, sk, HqGrid.SEEN);
        double was = seenOnWay(grid, from, HqGrid.index(ai, ak)), best = was;
        double[] out = at;
        double base = Math.atan2(sz - g.z, sx - g.x);
        for (double r = PIN_NEAR; r <= PIN_FAR + 1e-9; r += PIN_STEP)
            for (int a = -PIN_TURNS; a <= PIN_TURNS; a++) {
                double x = g.x + r * Math.cos(base + a * PIN_TURN), z = g.z + r * Math.sin(base + a * PIN_TURN);
                int i = grid.cellX(x), c = grid.cellZ(z);
                if (i < 0 || c < 0) continue;
                int cell = HqGrid.index(i, c);
                if (!grid.known[cell] || grid.deep[cell] >= 0.5 || grid.wet[cell] >= 0.5 || grid.seen[cell] < PIN_SEES) continue;
                double s = seenOnWay(grid, from, cell);
                if (s < best) {
                    best = s;
                    out = new double[] {grid.midX(i), grid.midZ(c)};
                }
            }
        return best <= was * PIN_BETTER && best <= was - PIN_LESS ? out : at;
    }

    /** blocks of the way to a cell in the enemy's sight (each cell's part seen by its length), short of its last PIN_LAST blocks; infinite: no way */
    static double seenOnWay(HqGrid grid, int[] from, int goal) {
        if (from[goal] == -2) return Double.POSITIVE_INFINITY;
        double sum = 0, back = 0;
        for (int c = goal; from[c] >= 0; c = from[c]) {
            int p = from[c];
            double len = (p % HqGrid.N != c % HqGrid.N && p / HqGrid.N != c / HqGrid.N ? Math.sqrt(2) : 1) * HqGrid.CELL;
            back += len;
            if (back > PIN_LAST) sum += grid.seen[c] * len;
        }
        return sum;
    }

    /**
     * go round the group: a place FLANK_D off it, FLANK_ANGLE off the line from the pinning place, on the side the
     * enemy sees less of and the nearer to go to over the map (FAR_SIDE blocks farther weigh as all in its sight;
     * farther than FAR_MOST, not got round in time: none); null: neither side on the map, known and near enough
     */
    static @Nullable Task flank(@Nullable HqGrid grid, Group g, int k, double px, double pz, double sx, double sz) {
        double base = Math.atan2(pz - g.z, px - g.x);
        double[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int side = -1; side <= 1; side += 2) {
            double a = base + side * FLANK_ANGLE, x = g.x + FLANK_D * Math.cos(a), z = g.z + FLANK_D * Math.sin(a);
            double score = Math.hypot(x - sx, z - sz) / 1000;
            if (grid != null) {
                int i = grid.cellX(x), c = grid.cellZ(z);
                if (i < 0 || c < 0 || !grid.known[HqGrid.index(i, c)] || grid.deep[HqGrid.index(i, c)] >= 0.5) continue;
                score += grid.seen[HqGrid.index(i, c)];
                // the way there over the map rather than the straight line (a river between: by its ford, C4): a side
                // much farther to go round loses its pair's time
                int si = grid.cellX(sx), sk = grid.cellZ(sz);
                int[] p = si < 0 || sk < 0 ? null : grid.path(si, sk, i, c, 0);
                // no way there at all (the squad off the map aside), one through water to swim (the soldiers stop at
                // it), or too far: not gone round to
                if (si >= 0 && sk >= 0 && (p == null || swims(grid, p))) continue;
                double way = p == null ? 0 : HqGrid.cells(p) * HqGrid.CELL;
                if (way > FAR_MOST) continue;
                score += way / FAR_SIDE;
            }
            if (score < bestScore) {
                bestScore = score;
                best = new double[] {x, z};
            }
        }
        return best == null ? null : new Task(Kind.FLANK, k, best[0], best[1], PLACE, g.x, g.z);
    }

    private static boolean swims(HqGrid grid, int[] p) {
        for (int c : p) if (grid.deep[c] >= 0.5) return true;
        return false;
    }

    /** hold: the best place about the squad within HOLD_NEAR, no farther from the group than it is */
    static Task hold(@Nullable HqGrid grid, Group g, int k, Squad s) {
        double[] at = lookout(grid, s.x, s.z, HOLD_NEAR, g, Math.hypot(s.x - g.x, s.z - g.z));
        return new Task(Kind.HOLD, k, at[0], at[1], PLACE, g.x, g.z);
    }

    /**
     * the place within near of x, z that sees the group best and stands highest (the enemy's sight of it on the map:
     * sight goes both ways), no farther from the group than most, on dry known ground; x, z where there is no map or
     * nothing better
     */
    static double[] lookout(@Nullable HqGrid grid, double x, double z, double near, Group g, double most) {
        if (grid == null) return new double[] {x, z};
        int ci = grid.cellX(x), ck = grid.cellZ(z);
        if (ci < 0 || ck < 0) return new double[] {x, z};
        int n = (int) Math.ceil(near / HqGrid.CELL);
        double h0 = grid.known[HqGrid.index(ci, ck)] ? grid.h[HqGrid.index(ci, ck)] : 0;
        double[] best = {x, z};
        double bestScore = grid.known[HqGrid.index(ci, ck)] ? score(grid, HqGrid.index(ci, ck), h0) : Double.NEGATIVE_INFINITY;
        for (int i = ci - n; i <= ci + n; i++)
            for (int k = ck - n; k <= ck + n; k++) {
                if (!grid.inside(i, k)) continue;
                int c = HqGrid.index(i, k);
                double mx = grid.midX(i), mz = grid.midZ(k);
                if (!grid.known[c] || grid.deep[c] >= 0.5 || grid.wet[c] >= 0.5 || Math.hypot(mx - x, mz - z) > near) continue;
                if (Math.hypot(mx - g.x, mz - g.z) > most) continue;
                double sc = score(grid, c, h0);
                if (sc > bestScore + 0.5) {
                    bestScore = sc;
                    best = new double[] {mx, mz};
                }
            }
        return best;
    }

    /** a place's worth to look out from: seen by (so seeing) the enemy, and higher (a block a point, at most 6) */
    private static double score(HqGrid grid, int c, double h0) {
        return 6 * grid.seen[c] + Math.max(-6, Math.min(6, grid.h[c] - h0));
    }

    /** fall back: from the nearest group to the nearest squad of the side not falling back; else BACK blocks away */
    static Task back(List<Squad> squads, List<Group> groups, int i) {
        Squad s = squads.get(i);
        Group g = null;
        double gd = Double.MAX_VALUE;
        for (Group x : groups) {
            double d = d2(x, s.x, s.z);
            if (d < gd) {
                gd = d;
                g = x;
            }
        }
        Squad to = null;
        double td = Double.MAX_VALUE;
        for (int j = 0; j < squads.size(); j++) {
            Squad o = squads.get(j);
            if (j == i || breaks(o)) continue;
            double d = Math.hypot(o.x - s.x, o.z - s.z);
            // not toward the group: one nearer it than this squad is no cover
            if (g != null && d2(g, o.x, o.z) < gd) continue;
            if (d < td) {
                td = d;
                to = o;
            }
        }
        if (to != null) return new Task(Kind.BACK, -1, to.x, to.z, PLACE * 2, g == null ? to.x : g.x, g == null ? to.z : g.z);
        if (g == null) return new Task(Kind.RESERVE, -1, s.x, s.z, PLACE, s.x, s.z);
        double dx = s.x - g.x, dz = s.z - g.z, d = Math.max(1e-6, Math.hypot(dx, dz));
        return new Task(Kind.BACK, -1, s.x + dx / d * BACK, s.z + dz / d * BACK, PLACE * 2, g.x, g.z);
    }

    /** the strength of a kind of soldier (a role's ordinal; PLAYER or any other: a player) */
    static double weight(int kind) {
        AiRole[] roles = AiRole.values();
        if (kind < 0 || kind >= roles.length) return 3;
        return switch (roles[kind]) {
            case RIFLE -> 1;
            case BASE -> 1.5;
            case MARKSMAN, GRENADIER -> 1.2;
            case MEDIC -> 0.7;
            case SUPPLIER -> 0.8;
        };
    }

    /** the tasks as words, for /armsai and the stands */
    static List<String> names(Task[] tasks) {
        List<String> out = new ArrayList<>();
        for (Task t : tasks) out.add(t.kind().name().toLowerCase(java.util.Locale.ROOT));
        return out;
    }
}
