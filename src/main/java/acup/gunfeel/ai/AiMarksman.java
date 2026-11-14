package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The squad's marksman (stage 5, R2; Killzone's position by weighted sums, The Division's sniper that keeps its
 * distance). A soldier with a scoped gun (AiRole.MARKSMAN, or by word) fights a target from afar its own way: it
 * picks a perch among the cells of the tactical map round it, one that stands high over the target, sees far towards
 * it, is BAND_MIN..BAND_MAX blocks off it and BEHIND_MIN..BEHIND_MAX behind its squad's middle, with cover at its
 * side rather than in front (cover in front would shut its line), and only one from which the target is seen (rays,
 * RAYS at most a pick). There it fires single shots GAP ticks apart, kneels between them behind low cover and lies
 * down on open ground; after SHOTS_MIN..SHOTS_MAX shots, or when it is shot at, it takes another perch, never one
 * of its last few; one where it has had no shot for STALL, too. A target nearer than NEAR, no rounds left, or no
 * perch to be had: it fights as a rifleman. It never goes round the side, and while a rifleman of its squad knows of
 * the target it does not look for it (AiSquads). Its gun, aim and the damage are as anyone's: a marksman's shots are
 * fewer and from farther, not truer.
 */
public final class AiMarksman {
    /** the server setting: marksmen fight from perches */
    public static final String KEY = "aiMarksman";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a target nearer than this, blocks: it fights as a rifleman; back to perches from this much farther */
    static final double NEAR = 24, NEAR_BACK = 4;
    /** a perch this far from the target, blocks (scored, not ruled: outside costs OFF_BAND a block); the hard bounds */
    static final double BAND_MIN = 40, BAND_MAX = 90, HARD_MIN = 26, HARD_MAX = 110;
    /** this far behind its squad's middle along the line to the target, blocks */
    static final double BEHIND_MIN = 15, BEHIND_MAX = 40;
    /** it looks for a perch this far round it; cells this much under it and over it */
    static final int RADIUS = 20, DOWN = 6, UP = 12;
    /** the weights: a block over the target (up to UP_MAX), a block of sight towards it (AiTerrain, up to SIGHT_MAX),
     *  a block out of the band, out of the place behind the squad, a block of the way there; cover at its side */
    static final double UP_W = 0.6, UP_MAX = 12, SIGHT_W = 0.04, SIGHT_MAX = 90, OFF_BAND = 0.15, OFF_BEHIND = 0.08, WAY = 0.3, SIDE_COVER = 1.5;
    /** rays a pick may spend; the best this many are tried */
    static final int RAYS = 10, KEEP = 48;
    /** cells this near one whose line was shut (across, the same height) are not tried, blocks */
    static final int SHUT_NEAR = 2;
    /** ticks between two shots; shots from one perch; a perch given up after this long on the way */
    static final int GAP = 20, SHOTS_MIN = 2, SHOTS_MAX = 3, WAY_MAX = 300;
    /** after a pick that found none, the next; after a perch given up, the next, ticks */
    static final int AGAIN = 60;
    /** after it was shot at, the next pick, ticks (no new perch each hit under a machine gun) */
    static final int HIT_AGAIN = 15;
    /** at its perch this long with no shot (its own in the line, the target gone from it), ticks: another */
    static final int STALL = 100;
    /** perches remembered so as not to go back to them; none within this of one, blocks */
    static final int LAST = 4;
    static final double APART = 4;
    /** its perch counts as long as it was a marksman in a fight this many ticks ago at most */
    static final int FRESH = 40;

    /** the stands: perches taken, picks that found none, shots from perches, perches left for being shot at */
    public static int perches, none, shots, shotAt;
    /** the stands: steps it was a marksman in a fight; cells scored; rays tried, rays shut */
    public static int steps, scored, tried, shut;
    /** the stands: its ticks on the way to a perch, at one, at one holding fire for one of its own in the line, lying there */
    public static int wayTicks, perchTicks, blockedTicks, lyingTicks;

    /** a marksman's own state */
    static final class State {
        long perch = Long.MIN_VALUE, at, next = Long.MIN_VALUE, nextShot, shotSeen = Long.MIN_VALUE, stepped = Long.MIN_VALUE / 2;
        int shotsHere, want, firedBefore, target;
        /** the last tick it was on its way or fired */
        long busy;
        boolean cover;
        final long[] last = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        int lastAt;
    }

    private AiMarksman() {
    }

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** it is a marksman that fights from perches */
    static boolean is(AiBot bot) {
        return on() && AiRole.of(bot) == AiRole.MARKSMAN;
    }

    /**
     * A marksman in a fight, this tick, the target in sight (AiSoldier.combat, after what keeps it alive and its fall
     * back): true while it goes to its perch or fires from it (nothing else then); false: it fights as a rifleman.
     */
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (!is(bot)) return false;
        steps++;
        State m = bot.marksman == null ? bot.marksman = new State() : bot.marksman;
        // a perch picked for another target, or before a while out of the fight (fallen back, the target lost): gone
        if (c.id != m.target || tick - m.stepped > FRESH) leave(m, tick, false);
        m.target = c.id;
        m.stepped = tick;
        double dist = Math.hypot(c.x - bot.mob.getX(), c.z - bot.mob.getZ());
        if (dist < (m.perch == Long.MIN_VALUE ? NEAR + NEAR_BACK : NEAR)) {
            leave(m, tick, false);
            return false;
        }
        AiGunner g = bot.gunner;
        // nothing left to fire: a rifleman's ways (its supplier, falling back)
        if (g.loaded() == 0 && !g.ammo.any()) {
            leave(m, tick, false);
            return false;
        }
        // shot at where it is: another perch (once a shot)
        long shot = bot.memory.shotTick();
        boolean hit = shot != Long.MIN_VALUE && shot != m.shotSeen && shot >= m.at;
        if (hit) m.shotSeen = shot;
        if (m.perch != Long.MIN_VALUE && (hit || m.shotsHere >= m.want || tick - m.at > WAY_MAX && !bot.mover.arrived()
                || bot.mover.failed() && bot.mover.goalIs(m.perch) || tick - m.busy > STALL)) {
            if (hit && bot.mover.arrived()) shotAt++;
            leave(m, tick, !hit);
            if (hit) m.next = tick + HIT_AGAIN;
        }
        if (m.perch == Long.MIN_VALUE) {
            if (tick < m.next) return false;
            long p = pick(bot, level, c, m);
            if (p == Long.MIN_VALUE) {
                none++;
                m.next = tick + AGAIN;
                return false;
            }
            m.perch = p;
            m.at = m.busy = tick;
            m.shotsHere = 0;
            m.want = SHOTS_MIN + bot.mob.getRandom().nextInt(SHOTS_MAX - SHOTS_MIN + 1);
            m.last[m.lastAt] = p;
            m.lastAt = (m.lastAt + 1) % LAST;
            perches++;
            s.note(bot, tick, "marksman: to a perch " + BlockPos.getX(p) + " " + BlockPos.getY(p) + " " + BlockPos.getZ(p));
        }
        if (!bot.mover.arrived() || !bot.mover.goalIs(m.perch)) {
            wayTicks++;
            g.fire(false);
            if (!bot.mover.goalIs(m.perch)) {
                bot.mover.costs(FireLines.of(level, bot.mob));
                bot.mover.goTo(m.perch, 0.35, AiSoldier.RUN);
            }
            m.firedBefore = g.shots;
            m.busy = tick;
            return true;
        }
        perchTicks++;
        if (g.blockedTicks > 0) blockedTicks++;
        if (bot.prone.lying()) lyingTicks++;
        // there: single shots GAP apart, down between them (kneeling behind its side cover, lying on open ground)
        if (g.shots != m.firedBefore) {
            m.shotsHere += g.shots - m.firedBefore;
            shots += g.shots - m.firedBefore;
            m.firedBefore = g.shots;
            m.nextShot = tick + GAP;
            m.busy = tick;
        }
        boolean reload = g.loaded() == 0 && g.ammo.any();
        if (reload) g.reload();
        boolean fire = !reload && !g.reloading() && tick >= m.nextShot;
        if (!m.cover) bot.prone.want();
        else s.crouch(!fire);
        g.fire(fire);
        return true;
    }

    /** off its perch; given up (no shot was its reason): not at once again */
    private static void leave(State m, long tick, boolean wait) {
        if (m.perch == Long.MIN_VALUE) return;
        m.perch = Long.MIN_VALUE;
        if (wait) m.next = tick + AGAIN / 3;
    }

    /** it has a perch it goes to or fires from (else it is as any rifleman, and its squad counts on it as one) */
    static boolean perched(AiBot bot) {
        return bot.marksman != null && bot.marksman.perch != Long.MIN_VALUE && is(bot) && bot.mob.level().getGameTime() - bot.marksman.stepped < FRESH;
    }

    /** at its perch: it covers its squad on the move from there (AiSquads.covering) */
    static boolean there(AiBot bot) {
        return perched(bot) && bot.mover.goalIs(bot.marksman.perch) && bot.mover.arrived();
    }

    /**
     * The best perch round it from which the target is seen, or Long.MIN_VALUE. Cells of the tactical map within
     * RADIUS, scored (cheap), the best KEEP kept; then, best first, a ray from its eyes there to the target's chest.
     */
    private static long pick(AiBot bot, ServerLevel level, TargetMemory.Contact c, State m) {
        TacticalMap map = TacticalMap.of(level);
        AiTerrain terrain = AiTerrain.ON ? AiTerrain.peek(level) : null;
        Vec3 self = bot.mob.position();
        Entity seen = level.getEntity(c.id);
        // its head: what shows of one behind low cover, and what its eyes see of it (Senses)
        Vec3 chest = seen != null ? seen.position().add(0, seen.getBbHeight() * 0.85, 0) : new Vec3(c.x, c.y + 1.5, c.z);
        // behind its squad: along the line from the target through the squad's middle
        Vec3 middle = AiSquads.middle(bot);
        double ux = 0, uz = 0;
        if (middle != null) {
            double dx = middle.x - c.x, dz = middle.z - c.z, l = Math.hypot(dx, dz);
            if (l > 1) {
                ux = dx / l;
                uz = dz / l;
            }
        }
        var held = AiBots.heldByOthers(bot);
        long[] cand = new long[KEEP];
        double[] score = new double[KEEP];
        boolean[] side = new boolean[KEEP];
        int count = 0;
        int x0 = (int) Math.floor(self.x) - RADIUS, x1 = (int) Math.floor(self.x) + RADIUS;
        int z0 = (int) Math.floor(self.z) - RADIUS, z1 = (int) Math.floor(self.z) + RADIUS;
        int y0 = (int) Math.floor(self.y) - DOWN, y1 = (int) Math.floor(self.y) + UP;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++)
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++)
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    SectionCells cells = map.cells(SectionPos.asLong(sx, sy, sz));
                    if (cells == null) continue;
                    for (int i = 0; i < cells.count(); i++) {
                        if ((cells.flags(i) & SectionCells.WATER) != 0) continue;
                        int x = (sx << 4) + cells.x(i), y = (sy << 4) + cells.y(i), z = (sz << 4) + cells.z(i);
                        if (y < y0 || y > y1) continue;
                        double cx = x + 0.5, cz = z + 0.5, wx = cx - self.x, wz = cz - self.z, way = Math.hypot(wx, wz);
                        if (way > RADIUS) continue;
                        double tx = c.x - cx, tz = c.z - cz, td = Math.hypot(tx, tz);
                        if (td < HARD_MIN || td > HARD_MAX) continue;
                        long key = BlockPos.asLong(x, y, z);
                        if (held.test(key) || near(m, x, z)) continue;
                        int dir = Dirs.of(tx, tz), cover = cells.cover(i);
                        // cover in front shuts its line: high cover that way is no perch; low it shoots over
                        if (SectionCells.height(cover, dir) == 2) continue;
                        boolean beside = SectionCells.height(cover, (dir + 2) & 7) > 0 || SectionCells.height(cover, (dir + 6) & 7) > 0
                                || SectionCells.height(cover, dir) == 1;
                        double s = UP_W * Math.max(-UP_MAX, Math.min(UP_MAX, y - c.y)) - WAY * way + (beside ? SIDE_COVER : 0);
                        s -= OFF_BAND * (td < BAND_MIN ? BAND_MIN - td : td > BAND_MAX ? td - BAND_MAX : 0);
                        if (ux != 0 || uz != 0) {
                            double behind = (cx - middle.x) * ux + (cz - middle.z) * uz;
                            s -= OFF_BEHIND * (behind < BEHIND_MIN ? BEHIND_MIN - behind : behind > BEHIND_MAX ? behind - BEHIND_MAX : 0);
                        }
                        if (terrain != null) {
                            int open = terrain.openTowards(x, z, c.x, c.z);
                            if (open != AiTerrain.NONE) s += SIGHT_W * Math.min(SIGHT_MAX, open);
                        }
                        scored++;
                        int worst;
                        if (count < KEEP) worst = count++;
                        else {
                            worst = 0;
                            for (int k = 1; k < KEEP; k++) if (score[k] < score[worst]) worst = k;
                            if (score[worst] >= s) continue;
                        }
                        cand[worst] = key;
                        score[worst] = s;
                        side[worst] = beside;
                    }
                }
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]));
        VoxelRay.Source voxels = AiBots.voxels(level);
        long[] shutAt = new long[RAYS];
        int rays = 0, nShut = 0;
        for (int k = 0; k < count && rays < RAYS; k++) {
            long key = cand[order[k]];
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            // the cells round one whose line was shut are shut the same way, mostly (a row behind a crest)
            boolean byShut = false;
            for (int j = 0; j < nShut; j++)
                byShut |= Math.abs(BlockPos.getX(shutAt[j]) - x) <= SHUT_NEAR && Math.abs(BlockPos.getZ(shutAt[j]) - z) <= SHUT_NEAR && BlockPos.getY(shutAt[j]) == y;
            if (byShut) continue;
            // with cover at hand it rises to fire (over low cover, as anyone does) and kneels between; on open
            // ground it lies (its eyes near the ground)
            double eye = side[order[k]] ? 1.6 : 0.4;
            rays++;
            tried++;
            if (!VoxelRay.clear(voxels, x + 0.5, y + eye, z + 0.5, chest.x, chest.y, chest.z, CoverQuery.SHOOT_MASK)) {
                shut++;
                shutAt[nShut++] = key;
                continue;
            }
            m.cover = side[order[k]];
            return key;
        }
        return Long.MIN_VALUE;
    }

    private static boolean near(State m, int x, int z) {
        for (long p : m.last) {
            if (p == Long.MIN_VALUE) continue;
            double dx = BlockPos.getX(p) - x, dz = BlockPos.getZ(p) - z;
            if (dx * dx + dz * dz < APART * APART) return true;
        }
        return false;
    }

    static String info() {
        return String.format(java.util.Locale.ROOT, "marksmen: steps %d, perches %d, none found %d (cells scored %d, rays %d, shut %d), shots %d, left when shot at %d;"
                + " ticks on the way %d, at a perch %d (a friend in the line %d, lying %d)", steps, perches, none, scored, tried, shut, shots, shotAt, wayTicks, perchTicks, blockedTicks, lyingTicks);
    }
}
