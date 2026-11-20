package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.GameRandom;
import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;
import acup.gunfeel.host.WorldView;

class AiMarksmanBaseTest {
    final Object level = new Object();
    final Map<Long, List<int[]>> cells = new HashMap<>();
    final List<String> events = new ArrayList<>();
    final List<Integer> bounds = new ArrayList<>();
    long now = 1000;
    double x = .5, y = 64, z = .5;
    int loaded = 8, draw;
    boolean arrived, failed, reloading, lying, wantedLie;
    long goal = Long.MIN_VALUE;
    AiRole role = AiRole.MARKSMAN;
    LongPredicate claims = p -> false;
    Vec3d middle;
    Body seen;
    AiMarksmanBase.Sight terrain;
    VoxelRay.Source voxels = (bx, by, bz) -> 0;
    TargetMemory.Contact target = new TargetMemory.Contact(7);
    int[] saved;
    boolean wasOn;
    final GameRandom random = (GameRandom) Proxy.newProxyInstance(GameRandom.class.getClassLoader(), new Class<?>[] {GameRandom.class}, (p, m, a) -> {
        if (!m.getName().equals("nextInt")) throw new AssertionError(m.getName());
        bounds.add((int) a[0]);
        events.add("random");
        return draw;
    });
    final WorldView world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class}, (p, m, a) -> {
        if (m.getName().equals("gameTime")) return now;
        throw new AssertionError(m.getName());
    });
    final Body body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
        case "x" -> x;
        case "y" -> y;
        case "z" -> z;
        case "random" -> random;
        default -> throw new AssertionError(m.getName());
    });
    final AiMoverBase mover = new AiMoverBase(body) {
        @Override public boolean arrived() { return arrived; }
        @Override public boolean failed() { return failed; }
        @Override public boolean goalIs(long cell) { return goal == cell; }
        @Override public void costs(PathSearch.Cost cost) { assertSame(PathSearch.Cost.NONE, cost); events.add("costs"); }
        @Override public void goTo(long cell, double radius, double speed) {
            goal = cell; assertEquals(.35, radius); assertEquals(1.7, speed); events.add("go");
        }
    };
    final AiGunnerBase gun = new AiGunnerBase(body, new AiAmmo(16)) {
        @Override public int loaded() { return loaded; }
        @Override public boolean reloading() { return reloading; }
        @Override boolean swims() { throw new AssertionError(); }
        @Override boolean lobsOnly() { throw new AssertionError(); }
        @Override boolean friendInLine() { throw new AssertionError(); }
        @Override void traceLine(boolean shoot, boolean friend) { throw new AssertionError(); }
        @Override void countSuppressHold() { throw new AssertionError(); }
        @Override boolean ownCover() { throw new AssertionError(); }
        @Override boolean recoilLying() { throw new AssertionError(); }
        @Override void leanEye(double[] out) { throw new AssertionError(); }
        @Override float[] rubberDrift() { throw new AssertionError(); }
        @Override boolean proneBlocks() { throw new AssertionError(); }
    };
    final AiProneBase prone = new AiProneBase(body, () -> true) {
        @Override public boolean lying() { return lying; }
        @Override public void want() { wantedLie = true; }
    };

    final class Bot extends AiBotBase {
        Bot() { super(AiMarksmanBaseTest.this.body, SensesTest.P); }
        @Override public AiRole role() { return AiMarksmanBaseTest.this.role; }
        @Override public boolean soldier() { throw new AssertionError(); }
        @Override public boolean onTheMove() { throw new AssertionError(); }
        @Override public boolean inCover() { throw new AssertionError(); }
        @Override public boolean searching() { throw new AssertionError(); }
        @Override public boolean fightsFallen(int id) { throw new AssertionError(); }
        @Override public boolean fallingBack() { throw new AssertionError(); }
        @Override public boolean marksman() { throw new AssertionError(); }
        @Override public boolean perched() { throw new AssertionError(); }
        @Override public boolean atPerch() { throw new AssertionError(); }
        @Override public boolean grenadier() { throw new AssertionError(); }
        @Override public Object serverLevel() { return level; }
        @Override public WorldView world() { return world; }
        @Override public AiMoverBase mover() { return mover; }
        @Override public AiGunnerBase gunner() { return gun; }
        @Override public AiProneBase prone() { return prone; }
        @Override public AiStoopBase stoop() { throw new AssertionError(); }
        @Override public AiDashBase dash() { throw new AssertionError(); }
    }
    final Bot bot = new Bot();
    final AiMarksmanBase.Staff<Bot, List<String>> staff = new AiMarksmanBase.Staff<>() {
        @Override public CellSource map(Object l) {
            assertSame(level, l);
            return new CellSource() {
                @Override public SectionCells cells(long key) {
                    List<int[]> of = cells.get(key);
                    if (of == null) return null;
                    short[] pos = new short[of.size()], flags = new short[of.size()];
                    int[] cover = new int[of.size()];
                    for (int i = 0; i < of.size(); i++) {
                        int[] c = of.get(i);
                        pos[i] = (short) ((c[1] & 15) << 8 | (c[2] & 15) << 4 | c[0] & 15);
                        cover[i] = c[3]; flags[i] = (short) c[4];
                    }
                    return new SectionCells(key, 0, pos, cover, flags, new short[257], 0);
                }
                @Override public byte voxel(int x, int y, int z) { throw new AssertionError(); }
            };
        }
        @Override public AiMarksmanBase.Sight terrain(Object l) { assertSame(level, l); return terrain; }
        @Override public EntityView entities(Object l) {
            assertSame(level, l);
            return (EntityView) Proxy.newProxyInstance(EntityView.class.getClassLoader(), new Class<?>[] {EntityView.class}, (p, m, a) -> {
                assertEquals("byId", m.getName()); assertEquals(target.id, a[0]); return seen;
            });
        }
        @Override public Vec3d middle(Bot b) { assertSame(bot, b); return middle; }
        @Override public LongPredicate claims(Bot b) { assertSame(bot, b); return claims; }
        @Override public VoxelRay.Source voxels(Object l) { assertSame(level, l); return voxels; }
        @Override public PathSearch.Cost fireLines(Object l, Bot b) {
            assertSame(level, l); assertSame(bot, b); events.add("lines"); return PathSearch.Cost.NONE;
        }
        @Override public void crouch(List<String> s, boolean low) { assertSame(events, s); s.add("crouch " + low); }
        @Override public void note(List<String> s, Bot b, long tick, String text) {
            assertSame(events, s); assertSame(bot, b); assertEquals(now, tick); s.add(text);
        }
        @Override public double run() { return 1.7; }
    };

    @BeforeEach
    void setup() {
        wasOn = AiMarksmanBase.on;
        AiMarksmanBase.on = true;
        saved = counters();
        AiMarksmanBase.perches = AiMarksmanBase.none = AiMarksmanBase.shots = AiMarksmanBase.shotAt = 0;
        AiMarksmanBase.steps = AiMarksmanBase.scored = AiMarksmanBase.tried = AiMarksmanBase.shut = 0;
        AiMarksmanBase.wayTicks = AiMarksmanBase.perchTicks = AiMarksmanBase.blockedTicks = AiMarksmanBase.lyingTicks = 0;
        target.x = 70; target.y = 64; target.z = .5;
    }

    static int[] counters() {
        return new int[] {AiMarksmanBase.perches, AiMarksmanBase.none, AiMarksmanBase.shots, AiMarksmanBase.shotAt,
                AiMarksmanBase.steps, AiMarksmanBase.scored, AiMarksmanBase.tried, AiMarksmanBase.shut,
                AiMarksmanBase.wayTicks, AiMarksmanBase.perchTicks, AiMarksmanBase.blockedTicks, AiMarksmanBase.lyingTicks};
    }

    @AfterEach
    void restore() {
        AiMarksmanBase.on = wasOn;
        AiMarksmanBase.perches = saved[0]; AiMarksmanBase.none = saved[1]; AiMarksmanBase.shots = saved[2]; AiMarksmanBase.shotAt = saved[3];
        AiMarksmanBase.steps = saved[4]; AiMarksmanBase.scored = saved[5]; AiMarksmanBase.tried = saved[6]; AiMarksmanBase.shut = saved[7];
        AiMarksmanBase.wayTicks = saved[8]; AiMarksmanBase.perchTicks = saved[9]; AiMarksmanBase.blockedTicks = saved[10]; AiMarksmanBase.lyingTicks = saved[11];
    }

    long cell(int x, int y, int z) { return cell(x, y, z, 0, 0); }
    long cell(int x, int y, int z, int cover, int flags) {
        cells.computeIfAbsent(PosLong.section(x >> 4, y >> 4, z >> 4), k -> new ArrayList<>()).add(new int[] {x, y, z, cover, flags});
        return PosLong.block(x, y, z);
    }
    AiMarksmanBase.State state() {
        var m = bot.marksman = new AiMarksmanBase.State();
        m.target = target.id; m.stepped = m.at = m.busy = now; m.want = 3;
        return m;
    }
    AiMarksmanBase.State perched() {
        var m = state(); m.perch = goal = PosLong.block(0, 64, 0); arrived = true; return m;
    }
    long pick(AiMarksmanBase.State m) { return AiMarksmanBase.choosePerch(staff, bot, level, target, m); }
    boolean step() { return AiMarksmanBase.fightFrom(staff, events, bot, level, target, now); }

    @Test
    void stateFieldKeepsNameAndPackageAccess() throws Exception {
        var field = AiBotBase.class.getDeclaredField("marksman");
        assertEquals(AiMarksmanBase.State.class, field.getType());
        assertEquals(0, field.getModifiers());
        assertNull(bot.marksman);
    }

    @Test
    void ranksByWayAndStableTies() {
        long farther = cell(10, 64, 0), nearer = cell(1, 64, 0);
        assertEquals(nearer, pick(state()));
        cells.clear();
        long first = cell(1, 64, 1); cell(1, 64, -1);
        // The negative section is visited first, regardless of insertion order.
        assertEquals(PosLong.block(1, 64, -1), pick(state()));
        cells.clear();
        z = 8.5;
        first = cell(1, 64, 9); cell(1, 64, 7);
        assertEquals(first, pick(state()));
        assertNotEquals(farther, first);
    }

    @Test
    void heightCoverSightAndSquadBandChangeRank() {
        long a = cell(1, 64, 0), high = cell(2, 70, 0);
        assertEquals(high, pick(state()));
        cells.clear();
        a = cell(1, 64, 0);
        long covered = cell(2, 64, 0, 1 << 2, 0);
        assertEquals(covered, pick(state())); assertTrue(bot.marksman.cover);
        cells.clear();
        a = cell(1, 64, 0); long b = cell(2, 64, 0);
        terrain = (cx, cz, tx, tz) -> cx == 2 ? 90 : Integer.MIN_VALUE;
        assertEquals(b, pick(state()));
        terrain = null; middle = new Vec3d(20.5, 64, .5);
        assertEquals(a, pick(state()));
    }

    @Test
    void filtersWaterFrontCoverClaimsHistoryAndHardRange() {
        cell(0, 64, 0, 0, SectionCells.WATER);
        cell(1, 64, 0, 1 << 8, 0);
        long held = cell(2, 64, 0); claims = p -> p == held;
        var m = state(); m.last[0] = cell(3, 64, 0);
        long allowed = cell(8, 64, 0);
        assertEquals(allowed, pick(m));
        target.x = 200; assertEquals(Long.MIN_VALUE, pick(m));
    }

    @Test
    void historyUsesStrictHorizontalDistanceAndIgnoresEmptySlots() {
        var m = state();
        assertFalse(AiMarksmanBase.nearHistory(m, 0, 0));
        m.last[2] = PosLong.block(-3, 200, -5);
        assertTrue(AiMarksmanBase.nearHistory(m, -3, -2));
        assertFalse(AiMarksmanBase.nearHistory(m, 1, -5));
        assertFalse(AiMarksmanBase.nearHistory(m, 0, -2));
    }

    @Test
    void raysStopAtTenAndSkipShutNeighbours() {
        for (int h = 64; h < 76; h++) cell(0, h, 0);
        voxels = (bx, by, bz) -> Voxel.SIGHT;
        assertEquals(Long.MIN_VALUE, pick(state()));
        assertEquals(10, AiMarksmanBase.tried); assertEquals(10, AiMarksmanBase.shut);
        cells.clear(); AiMarksmanBase.tried = 0;
        for (int cx = 0; cx <= 2; cx++) cell(cx, 64, 0);
        assertEquals(Long.MIN_VALUE, pick(state())); assertEquals(1, AiMarksmanBase.tried);
    }

    @Test
    void keepsOnlyFortyEightBestIncludingEqualScores() {
        // Equal scores retain the first KEEP cells, rather than replacing the earliest.
        z = target.z = 8.5;
        for (int i = 0; i < 48; i++) cell(1, 64, 9);
        long last = cell(1, 64, 7);
        voxels = (bx, by, bz) -> bz >= 9 ? Voxel.SIGHT : 0;
        assertEquals(Long.MIN_VALUE, pick(state()));
        assertEquals(49, AiMarksmanBase.scored); assertEquals(1, AiMarksmanBase.tried);
        cells.clear();
        for (int i = 0; i < 48; i++) cell(1, 64, 9);
        last = cell(1, 70, 7);
        assertEquals(last, pick(state()));
    }

    @Test
    void hysteresisUsesTwentyFourAndTwentyEightExactly() {
        var m = perched(); target.x = x + 24;
        assertTrue(step());
        target.x = x + 23.999; assertFalse(step()); assertEquals(Long.MIN_VALUE, m.perch);
        target.x = x + 27.999; assertFalse(step()); assertEquals(0, AiMarksmanBase.none);
        target.x = x + 28; assertFalse(step()); assertEquals(1, AiMarksmanBase.none);
    }

    @Test
    void picksRecordHistoryBeforeAdvancingAndDrawOnce() {
        long chosen = cell(0, 64, 0);
        draw = 1;
        assertTrue(step());
        var m = bot.marksman;
        assertEquals(chosen, m.perch); assertEquals(chosen, m.last[0]); assertEquals(1, m.lastAt);
        assertEquals(3, m.want); assertEquals(List.of(2), bounds);
        assertEquals(List.of("random", "marksman: to a perch 0 64 0", "lines", "costs", "go"), events);
        assertEquals(now, m.at); assertEquals(now, m.busy);
        now++; assertTrue(step()); assertEquals(List.of(2), bounds);
        m.perch = Long.MIN_VALUE; m.lastAt = 3; cells.clear(); long next = cell(8, 64, 0);
        now++; assertTrue(step()); assertEquals(next, m.last[3]); assertEquals(0, m.lastAt);
    }

    @Test
    void shotGapIncludesDeadlineAndCountsDeltaBeforeFire() {
        var m = perched(); m.cover = true;
        gun.shots = 2; m.firedBefore = 1;
        assertTrue(step());
        assertEquals(1, m.shotsHere); assertEquals(1, AiMarksmanBase.shots);
        assertEquals(now + 20, m.nextShot); assertFalse(gun.wantFire); assertTrue(events.contains("crouch true"));
        now += 19; assertTrue(step()); assertFalse(gun.wantFire);
        now++; assertTrue(step()); assertTrue(gun.wantFire); assertEquals("crouch false", events.get(events.size() - 1));
    }

    @Test
    void shotsMoveOnNextTickAndWaitTwentyTicks() {
        var m = perched(); m.want = 2;
        gun.shots = 2;
        assertTrue(step()); assertEquals(2, m.shotsHere);
        now++; assertFalse(step()); assertEquals(Long.MIN_VALUE, m.perch); assertEquals(now + 20, m.next);
        long next = cell(8, 64, 0);
        now += 19; assertFalse(step());
        now++; assertTrue(step()); assertEquals(next, m.perch); assertEquals(0, m.shotsHere);
    }

    @Test
    void noPerchWaitsSixtyTicksWithoutRandomDraws() {
        assertFalse(step()); assertEquals(now + 60, bot.marksman.next); assertEquals(1, AiMarksmanBase.none);
        now += 59; assertFalse(step()); assertEquals(1, AiMarksmanBase.none);
        now++; assertFalse(step()); assertEquals(2, AiMarksmanBase.none); assertTrue(bounds.isEmpty());
    }

    @Test
    void stallWayTimeoutFreshnessAndFailedGoalUseTheirBoundaries() {
        var m = perched(); m.busy = now - 100;
        assertTrue(step()); now++; assertFalse(step()); assertEquals(now + 20, m.next);
        m = perched(); arrived = false; m.at = now - 300; m.busy = now;
        assertTrue(step()); now++; assertFalse(step());
        m = perched(); m.stepped = now - 40;
        assertTrue(step());
        m.stepped = now - 41; assertFalse(step()); assertEquals(Long.MIN_VALUE, m.perch);
        m = perched(); failed = true; goal = PosLong.block(1, 64, 0);
        assertTrue(step()); goal = m.perch; assertFalse(step());
    }

    @Test
    void hitAtPerchWaitsFifteenAndIsSeenOnlyOnce() {
        var m = perched();
        bot.memory.shot(now);
        assertFalse(step()); assertEquals(now + 15, m.next); assertEquals(now, m.shotSeen);
        assertEquals(1, AiMarksmanBase.shotAt);
        cell(8, 64, 0);
        now += 15; assertTrue(step()); assertNotEquals(Long.MIN_VALUE, m.perch);
        now++; assertTrue(step()); assertEquals(1, AiMarksmanBase.shotAt);
    }

    @Test
    void targetChangeDropsPerchWithoutWaitAndDryGunFallsBack() {
        var m = perched(); m.target = 8;
        long next = cell(8, 64, 0);
        assertTrue(step()); assertEquals(next, m.perch); assertEquals(7, m.target);
        loaded = 0; gun.ammo.set(0);
        now++; assertFalse(step()); assertEquals(Long.MIN_VALUE, m.perch);
    }

    @Test
    void reloadHoldsFireAndOpenGroundWantsProne() {
        var m = perched(); loaded = 0;
        assertTrue(step()); assertTrue(gun.wantReload); assertFalse(gun.wantFire); assertTrue(wantedLie);
        loaded = 1; reloading = true; now++; assertTrue(step()); assertFalse(gun.wantFire);
        reloading = false; lying = true; gun.blockedTicks = 1; now++; assertTrue(step()); assertTrue(gun.wantFire);
        assertEquals(1, AiMarksmanBase.lyingTicks); assertEquals(1, AiMarksmanBase.blockedTicks);
    }

    @Test
    void leaveKeepsEmptyDeadlineAndWaitFlag() {
        var m = state(); m.next = 123;
        AiMarksmanBase.abandonPerch(m, now, true);
        assertEquals(123, m.next); assertEquals(Long.MIN_VALUE, m.perch);
        m.perch = 42; AiMarksmanBase.abandonPerch(m, now, false);
        assertEquals(Long.MIN_VALUE, m.perch); assertEquals(123, m.next);
        m.perch = 43; AiMarksmanBase.abandonPerch(m, now, true);
        assertEquals(Long.MIN_VALUE, m.perch); assertEquals(now + 20, m.next);
        assertTrue(bounds.isEmpty());
    }

    @Test
    void pureAnswersUseFreshnessAndDoNotChangeState() {
        var m = perched(); m.stepped = now - 39;
        assertTrue(AiMarksmanBase.usesPerches(staff, bot));
        assertTrue(AiMarksmanBase.hasPerch(staff, bot)); assertTrue(AiMarksmanBase.atPerch(staff, bot));
        now++; assertFalse(AiMarksmanBase.hasPerch(staff, bot));
        m.stepped = now; arrived = false; assertFalse(AiMarksmanBase.atPerch(staff, bot));
        role = AiRole.RIFLE; assertFalse(step()); assertEquals(0, AiMarksmanBase.steps);
        assertEquals(goal, m.perch);
        AiMarksmanBase.on = false; role = AiRole.MARKSMAN; assertFalse(step()); assertTrue(bounds.isEmpty());
    }

    @Test
    void infoKeepsExactTextAndAllCounters() {
        assertFalse(step());
        assertEquals("marksmen: steps 1, perches 0, none found 1 (cells scored 0, rays 0, shut 0), shots 0, left when shot at 0;"
                + " ticks on the way 0, at a perch 0 (a friend in the line 0, lying 0)", AiMarksmanBase.statistics());
    }
}
