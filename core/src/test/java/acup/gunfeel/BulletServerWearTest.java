package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import static acup.gunfeel.BulletBlockFake.BULLET;
import static acup.gunfeel.BulletBlockFake.HIT;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import acup.gunfeel.BulletBlockFake.Position;
import acup.gunfeel.BulletRuleText.Break;
import acup.gunfeel.BulletServerBase.Shooter;

/** 4cbbacf's block flow, with game accesses replaced by the same fakes as core's. */
class BulletServerWearTest {
    private static final Object LEVEL = BulletBlockFake.LEVEL;
    private static final Position POS = new Position(-7, 63, 93);

    private static class Old {
        final BlockWear<Object> WEAR = new BlockWear<>();
        final BulletBlockFake f;
        final BlockWear.Cracks<Object> CRACKS;
        final BlockWear.Gone<Object> GONE;

        Old(BulletBlockFake f) {
            this.f = f;
            CRACKS = (level, pos, stage) -> {
                Position at = (Position) f.position(pos);
                f.cracks(level, crackId(at), at, stage);
            };
            GONE = new BlockWear.Gone<>() {
                @Override public boolean gone(Object level, long pos, Object block) { return f.gone(level, pos, block); }
                @Override public long time(Object level) { return f.time(level); }
            };
        }

        static int crackId(Position pos) {
            int hash = pos.y() + pos.z() * 31;
            hash = hash * 31 + pos.x();
            return -1_000_000 - (hash & 0x3fffffff);
        }

        void worn(Shooter bullet, Position pos, int state, String result, Object... more) {
            if (!TestLog.server) return;
            Object[] kv = new Object[8 + more.length];
            Object[] head = {"block", f.stateObject(state), "pos", pos, "result", result, "damage", bullet.damage()};
            System.arraycopy(head, 0, kv, 0, 8);
            System.arraycopy(more, 0, kv, 8, more.length);
            TestLog.s(bullet.id(), "wear", kv);
        }

        boolean wear(Shooter bullet, Position pos, int state, Break rule, float k) {
            float damage = bullet.damage() * k;
            float hardness = f.view.destroySpeed(state, pos.x(), pos.y(), pos.z());
            if (hardness < 0F) {
                worn(bullet, pos, state, "unbreakable");
                return false;
            }
            int permission = f.mayBreak(LEVEL, bullet.owner(), pos, state);
            if (permission == 1) {
                worn(bullet, pos, state, "protected");
                return false;
            } else if (permission == 2) {
                worn(bullet, pos, state, "no_mob_griefing");
                return false;
            }
            if (rule.tooWeak(damage)) {
                worn(bullet, pos, state, "too_weak", "min", rule.min());
                return false;
            }
            float[] worn = new float[2];
            int stage = WEAR.hit(LEVEL, PosLong.block(pos.x(), pos.y(), pos.z()), f.blockToken(state), damage, hardness, rule, f.view.gameTime(), CRACKS, worn);
            if (stage != BlockWear.BREAKS) {
                worn(bullet, pos, state, "wear", "done", worn[0], "need", worn[1], "min", rule.min());
                f.cracks(LEVEL, crackId(pos), pos, stage);
                return false;
            }
            worn(bullet, pos, state, "broke", "min", rule.min());
            f.destroy(LEVEL, pos, rule.drop(), bullet.owner());
            if (rule.replace() != null) {
                int with = f.parseState(LEVEL, rule.replace());
                if (with != -1) f.set(LEVEL, pos, with, 3);
            }
            return true;
        }

        int blockHit(Position pos, boolean mk4) {
            int state = f.view.state(pos.x(), pos.y(), pos.z());
            if (f.view.air(state)) return 0;
            Break rule = f.rule(state);
            if (rule == null || rule.mode() == 0) return 0;
            float water = mk4 ? f.water(BULLET, HIT) : 1F;
            if (!wear(mk4 ? f.shooter(BULLET) : new Shooter(17, null, f.damage), pos, state, rule, water)) return 0;
            if (TestLog.server) TestLog.s(mk4 ? f.shotId(BULLET) : 17, "end", "by", rule.pass() ? "broke_pass" : "broke", "block", f.stateObject(state), "pos", pos, "at", f.location(HIT));
            return rule.pass() ? 2 : 1;
        }

        void wearBy(Position pos) {
            int state = f.view.state(pos.x(), pos.y(), pos.z());
            if (f.view.air(state)) return;
            Break rule = f.rule(state);
            if (rule == null || rule.mode() == 0) return;
            wear(new Shooter(17, null, f.damage), pos, state, rule, 1F);
        }

        void passed(Position pos) {
            int state = f.view.state(pos.x(), pos.y(), pos.z());
            if (TestLog.server) TestLog.s(f.shotId(BULLET), "through", "block", f.stateObject(state), "pos", pos, "at", f.location(HIT));
            Break rule = f.rule(state);
            if (rule == null || rule.mode() == 0) return;
            float water = f.water(BULLET, HIT);
            wear(f.shooter(BULLET), pos, state, rule, water);
        }

        void tick() { WEAR.tick(GONE, CRACKS); }
    }

    private record Result(Object value, Class<?> failure, String log) {}

    private static Result capture(Supplier<Object> action) {
        StringWriter log = new StringWriter();
        Object previous = field(TestLog.class, null, "out");
        set(TestLog.class, null, "out", new BufferedWriter(log));
        try {
            Object value = action.get();
            return new Result(value, null, log.toString().replaceAll("(?m)^\\d+\\t", ""));
        } catch (RuntimeException ex) {
            return new Result(null, ex.getClass(), log.toString().replaceAll("(?m)^\\d+\\t", ""));
        } finally {
            set(TestLog.class, null, "out", previous);
        }
    }

    private static final class Pair {
        final BulletBlockFake a = new BulletBlockFake(), b = new BulletBlockFake();
        final Old old = new Old(a);
        final BulletServerBase core = new BulletServerBase(b, b);

        void run(Supplier<Object> x, Supplier<Object> y) {
            assertEquals(capture(x), capture(y));
            assertEquals(a.calls, b.calls, "ordered calls, arguments and counts");
            assertEquals(a.state, b.state);
            assertEquals(a.shooterRequests, b.shooterRequests);
            assertEquals(a.shootersCreated, b.shootersCreated);
            assertEquals(old.WEAR.now(), core.wearNow());
            assertEquals(field(BlockWear.class, old.WEAR, "ticks"), field(BlockWear.class,
                    field(BulletServerBase.class, core, "WEAR"), "ticks"));
        }

        void entry(int which, Position pos) {
            run(() -> switch (which) {
                case 0 -> old.blockHit(pos, true);
                case 1 -> { old.wearBy(pos); yield null; }
                case 2 -> { old.passed(pos); yield null; }
                case 4 -> old.blockHit(pos, false);
                default -> old.wear(a.shooter(BULLET), pos, a.state, a.rule, a.k);
            }, () -> switch (which) {
                case 0 -> core.blockHit(LEVEL, b.view, pos, pos.x(), pos.y(), pos.z(), BULLET, 17, null, b.damage, HIT);
                case 1 -> { core.wearBy(LEVEL, b.view, pos, pos.x(), pos.y(), pos.z(), null, 17, null, b.damage, HIT); yield null; }
                case 2 -> { core.passed(LEVEL, b.view, pos, pos.x(), pos.y(), pos.z(), BULLET, 17, null, b.damage, HIT); yield null; }
                case 4 -> core.blockHit(LEVEL, b.view, pos, pos.x(), pos.y(), pos.z(), null, 17, null, b.damage, HIT);
                default -> core.wearShot(LEVEL, b.view, BULLET, pos, pos.x(), pos.y(), pos.z(), b.state, b.rule, b.k);
            });
        }

        void tick() { run(() -> { old.tick(); return null; }, () -> { core.tick(); return null; }); }
        void state(int state) { a.state = b.state = state; }
        void time(long time) { a.now = b.now = time; }
        void rule(Break rule) { a.rule = b.rule = rule; }
    }

    private static Object field(Class<?> type, Object target, String name) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private static void set(Class<?> type, Object target, String name, Object value) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private static Break rule(int mode, float min, String replace, boolean pass) {
        return new Break(mode, 12F, true, pass, false, replace, 0F, 1F, min);
    }

    @Test
    void allWearBranchesAndEntriesKeepCallsLogsAndState() {
        boolean saved = TestLog.server;
        try {
            for (boolean logging : new boolean[] {false, true}) {
                TestLog.server = logging;
                for (int entry = 0; entry < 5; entry++)
                    for (float hardness : new float[] {-1F, -0F, 0F, 1F, 2.71F, Float.NaN})
                        for (int permission = 0; permission < 3; permission++)
                            for (float damage : new float[] {0F, 5F, 6F, 12F, 13F, Float.NaN})
                                for (int mode : new int[] {1, 2, 3, 4})
                                    for (String replace : new String[] {null, "good", "bad"}) {
                                        Pair p = new Pair();
                                        p.a.hardness = p.b.hardness = hardness;
                                        p.a.permission = p.b.permission = permission;
                                        p.a.damage = p.b.damage = damage;
                                        p.a.parsed = p.b.parsed = "bad".equals(replace) ? -1 : 2;
                                        p.rule(rule(mode, 5F, replace, (mode & 1) == 0));
                                        p.entry(entry, POS);
                                        p.entry(entry, POS);
                                    }
            }
        } finally { TestLog.server = saved; }
    }

    @Test
    void earlyEntriesAndWaterAreLazyAndPassedDoesNotReadAir() {
        boolean saved = TestLog.server;
        try {
            for (boolean logging : new boolean[] {false, true}) {
                TestLog.server = logging;
                for (int entry : new int[] {0, 1, 2, 4}) for (int state : new int[] {0, 1})
                    for (Break rule : new Break[] {null, rule(0, 0F, null, false), rule(4, 0F, null, true)})
                        for (float water : new float[] {1F, .31F, 0F, -1F, Float.POSITIVE_INFINITY, Float.NaN}) {
                            Pair p = new Pair();
                            p.state(state); p.rule(rule); p.a.k = p.b.k = water;
                            p.entry(entry, POS);
                        }
            }
        } finally { TestLog.server = saved; }
    }

    @Test
    void earlyReturnsNeverRequestOrCreateAShooter() {
        boolean saved = TestLog.server;
        try {
            TestLog.server = false;
            for (int entry : new int[] {0, 1, 2, 4}) for (int state : new int[] {0, 1})
                for (Break rule : new Break[] {null, rule(0, 0F, null, false), rule(4, 0F, null, true)}) {
                    if (rule != null && rule.mode() != 0 && (state != 0 || entry == 2)) continue;
                    BulletBlockFake f = new BulletBlockFake();
                    f.state = state;
                    f.rule = rule;
                    BulletServerBase core = new BulletServerBase(f, f);
                    switch (entry) {
                        case 0 -> assertEquals(0, core.blockHit(LEVEL, f.view, POS, POS.x(), POS.y(), POS.z(), BULLET, 17, null, f.damage, HIT));
                        case 1 -> core.wearBy(LEVEL, f.view, POS, POS.x(), POS.y(), POS.z(), null, 17, null, f.damage, HIT);
                        case 2 -> core.passed(LEVEL, f.view, POS, POS.x(), POS.y(), POS.z(), BULLET, 17, null, f.damage, HIT);
                        case 4 -> assertEquals(0, core.blockHit(LEVEL, f.view, POS, POS.x(), POS.y(), POS.z(), null, 17, null, f.damage, HIT));
                        default -> throw new AssertionError(entry);
                    }
                    assertEquals(0, f.shooterRequests);
                    assertEquals(0, f.shootersCreated);
                    List<Object> read = List.of("state", POS.x(), POS.y(), POS.z());
                    List<Object> air = List.of("air", state), ask = List.of("rule", state);
                    assertEquals(entry == 2 ? List.of(read, ask) : state == 0 ? List.of(read, air) : List.of(read, air, ask), f.calls);
                }
        } finally { TestLog.server = saved; }
    }

    @Test
    void sameBlockPropertiesAccumulateOtherBlockResetsAndSnapshotsRestore() {
        Pair p = new Pair();
        p.entry(0, POS);
        Object before = p.old.WEAR.now(), after = p.core.wearNow();
        p.state(3); p.entry(0, POS);
        p.state(2); p.entry(0, POS);
        p.old.WEAR.back(before); p.core.wearBack(after);
        p.state(2); p.entry(0, POS);
        assertEquals(0, p.a.state, "two states of one block must reach the wear threshold");
        p.old.WEAR.clear(); p.core.forgetWear();
        p.state(1); p.entry(0, POS);
        p.old.WEAR.clear(); p.core.forgetWear();
        p.entry(0, POS);
        assertEquals(1, p.a.state);
    }

    @Test
    void hitCountThresholdAndHardnessThresholdKeepTheirExactBoundary() {
        for (int mode : new int[] {2, 3, 4}) {
            Pair p = new Pair(); p.rule(rule(mode, 0F, "good", false));
            p.a.hardness = p.b.hardness = 2F;
            int hits = mode == 2 ? 12 : mode == 3 ? 4 : 2;
            for (int i = 1; i < hits; i++) { p.entry(0, POS); assertEquals(1, p.a.state); }
            p.entry(0, POS);
            assertEquals(2, p.a.state, "replacement at the exact threshold");
        }
    }

    @Test
    void propertyChangeReachesTheThresholdWithoutResettingWear() {
        BulletBlockFake f = new BulletBlockFake();
        BulletServerBase core = new BulletServerBase(f, f);
        assertEquals(0, core.blockHit(LEVEL, f.view, POS, POS.x(), POS.y(), POS.z(), BULLET, 17, null, f.damage, HIT));
        f.state = 2;
        assertEquals(2, core.blockHit(LEVEL, f.view, POS, POS.x(), POS.y(), POS.z(), BULLET, 17, null, f.damage, HIT));
        assertEquals(0, f.state);
    }

    @Test
    void sweepAndHitForgetAt400AndGoneShortCircuitsTime() {
        for (boolean gone : new boolean[] {false, true}) for (long age : new long[] {0, 399, 400, 401}) {
            Pair p = new Pair(); p.entry(0, POS);
            p.time(age); p.a.gone = p.b.gone = gone;
            for (int i = 0; i < 5; i++) p.tick();
            p.entry(0, POS);
        }
        Pair p = new Pair(); p.entry(0, POS); p.time(400); p.entry(0, POS);
    }

    @Test
    void coordinateHashUsesIntOverflowBeforeMaskingAndDoesNotHashThePackedLong() {
        int[] edge = {0, -1, 1, -30_000_000, 30_000_000, Integer.MIN_VALUE, Integer.MAX_VALUE, 1 << 26};
        for (int x : edge) for (int y : edge) for (int z : edge) {
            Position pos = new Position(x, y, z);
            assertEquals(Old.crackId(pos), BulletServerBase.crackId(x, y, z));
            Pair p = new Pair(); p.entry(0, pos); p.time(400); p.entry(0, pos);
        }
    }

    @Test
    void failureAtEveryPortCallPropagatesWithoutCleanupAndCanResume() {
        boolean saved = TestLog.server;
        try {
            TestLog.server = true;
            for (int entry = 0; entry < 5; entry++) for (int mode : new int[] {1, 4}) {
                Pair baseline = new Pair(); baseline.rule(rule(mode, 0F, "good", true)); baseline.entry(entry, POS);
                int count = baseline.a.calls.size();
                for (int fail = 1; fail <= count; fail++) {
                    Pair p = new Pair(); p.rule(rule(mode, 0F, "good", true));
                    p.a.failAt = p.b.failAt = fail; p.entry(entry, POS);
                    p.a.failAt = p.b.failAt = -1; p.entry(entry, POS);
                }
            }
        } finally { TestLog.server = saved; }
    }
}
