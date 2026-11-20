package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.GameMath;
import acup.gunfeel.GameRandom;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.*;

class AiGrenadierBaseTest {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, calls);
    }

    final class Rig implements AiGrenadierBase.Staff<Bot, Rig> {
        final Object level = new Object();
        double gx, gz;
        int draws, shellReads, budget, notes, aims, fires, stopped;
        int loaded = 1;
        long goal = Long.MIN_VALUE;
        boolean reload, allowed = true, arrived, hasSpot, shellMissing, secondMissing, ballistic;
        int highMode, lowMode;
        double highDistance, lowDistance;
        boolean highOwn, lowOwn;
        float accept = 20;
        Vec3d target = new Vec3d(30, 0, 0), eye = new Vec3d(0, 2, 0), aim;
        Body seen;
        Shell shell = new Shell(3, 0.1F, 100);
        final List<double[]> flights = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final GameRandom random = proxy(GameRandom.class, (p, m, a) -> {
            assertEquals("nextGaussian", m.getName());
            return draws++ % 2 == 0 ? gx : gz;
        });
        final Body body = proxy(Body.class, (p, m, a) -> switch (m.getName()) {
            case "random" -> random;
            case "x", "y", "z" -> 0.0;
            default -> throw new AssertionError(m.getName());
        });
        final WorldView world = proxy(WorldView.class, (p, m, a) -> {
            assertEquals("minY", m.getName());
            return -64;
        });
        final NtmGuns guns = proxy(NtmGuns.class, (p, m, a) -> {
            assertEquals("grenadeShell", m.getName());
            assertSame(body, a[0]);
            shellReads++;
            return shellMissing || secondMissing && shellReads % 2 == 0 ? null : shell;
        });
        final Bot bot = new Bot(this);
        final TargetMemory.Contact contact = new TargetMemory.Contact(7);

        Rig() {
            contact.state = TargetMemory.State.VISIBLE;
            contact.x = 30;
        }

        boolean step(long tick) { return AiGrenadierBase.fight(this, guns, this, bot, level, contact, tick); }
        AiGrenadierBase.Lob plan(double d) { return AiGrenadierBase.trajectory(this, level, bot, target, shell, d); }
        AiGrenadierBase.State state() {
            if (bot.grenadier == null) bot.grenadier = new AiGrenadierBase.State();
            return bot.grenadier;
        }
        void lost(long tick) {
            contact.state = TargetMemory.State.LOST;
            contact.lostTick = tick;
            contact.lostX = 30;
        }
        @Override public Vec3d eye(Bot b) { assertSame(bot, b); return eye; }
        @Override public boolean mayPlan(Object l, long tick) { assertSame(level, l); budget++; return allowed; }
        final VoxelRay.Source voxels = (x, y, z) -> 0;
        @Override public VoxelRay.Source voxels(Object l) { assertSame(level, l); return voxels; }
        @Override public Vec3d burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz,
                int ticks, int minY, double gravity, double[] land) {
            assertSame(voxels, src);
            assertEquals(-64, minY);
            flights.add(new double[] {x, y, z, vx, vy, vz, ticks, gravity});
            boolean low = y != eye.y();
            events.add(low ? "low" : "high");
            if (ballistic) {
                for (int t = 0; t < ticks; t++) {
                    x += vx; y += vy; z += vz; vy -= gravity;
                    if (y <= 0) break;
                }
                land[0] = x;
                return new Vec3d(x, y, z);
            }
            float pitch = (float) Math.toDegrees(Math.asin(-vy / Math.sqrt(vx * vx + vy * vy + vz * vz)));
            int mode = low ? lowMode : highMode;
            land[0] = mode == 2 ? Double.NaN : x;
            if (mode == 1 || pitch > accept + 0.01F) return null;
            return target.add(0, low ? lowDistance : highDistance, 0);
        }
        @Override public boolean ownNear(Object l, VoxelRay.Source src, Bot b, Vec3d at) {
            assertSame(level, l); assertSame(voxels, src); assertSame(bot, b);
            boolean low = events.get(events.size() - 1).equals("low");
            events.add(low ? "lowOwn" : "highOwn");
            return low ? lowOwn : highOwn;
        }
        @Override public CoverQuery.Spot spot(Rig s) { return hasSpot ? new CoverQuery.Spot(0, 0, 0, false, 0, new long[0]) : null; }
        @Override public void note(Rig s, Bot b, long tick, String text) { notes++; events.add("note"); }
        @Override public EntityView entities(Object l) {
            return proxy(EntityView.class, (p, m, a) -> { assertEquals("byId", m.getName()); assertEquals(7, a[0]); return seen; });
        }
        @Override public void aim(Bot b, Vec3d at) { aim = at; aims++; events.add("aim"); }
        @Override public void fire(Bot b, boolean on) {
            assertTrue(on);
            assertNotNull(aim);
            assertTrue(b.gun.hasExact);
            fires++;
            events.add("fire");
        }
    }

    final class Bot extends AiBotBase {
        final Rig r;
        final AiGunnerBase gun;
        final AiMoverBase mover;
        Bot(Rig r) {
            super(r.body, SensesTest.P);
            this.r = r;
            role = AiRole.GRENADIER;
            gun = new AiGunnerBase(body, new AiAmmo(10)) {
                @Override public int loaded() { return r.loaded; }
                @Override public boolean reloading() { return r.reload; }
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
            mover = new AiMoverBase(body) {
                @Override public boolean arrived() { return r.arrived; }
                @Override public long goal() { return r.goal; }
                @Override public void stop() { r.stopped++; }
            };
        }
        @Override public AiRole role() { return role; }
        @Override public boolean soldier() { return true; }
        @Override public boolean onTheMove() { return false; }
        @Override public boolean inCover() { return false; }
        @Override public boolean searching() { return false; }
        @Override public boolean fightsFallen(int id) { return false; }
        @Override public boolean fallingBack() { return false; }
        @Override public boolean marksman() { return false; }
        @Override public boolean perched() { return false; }
        @Override public boolean atPerch() { return false; }
        @Override public boolean grenadier() { return true; }
        @Override public Object serverLevel() { return r.level; }
        @Override public WorldView world() { return r.world; }
        @Override public AiMoverBase mover() { return mover; }
        @Override public AiGunnerBase gunner() { return gun; }
        @Override public AiProneBase prone() { throw new AssertionError(); }
        @Override public AiStoopBase stoop() { throw new AssertionError(); }
        @Override public AiDashBase dash() { throw new AssertionError(); }
    }

    @AfterEach void reset() { AiGrenadierBase.on = true; }

    @Test void gaussianOrderAndShellArithmetic() {
        for (float v : new float[] {Math.nextDown(5F), 5F, 5.123456F})
            for (int expires : new int[] {-1, 0, 1, 199, 200, 201}) {
                Rig r = new Rig();
                r.gx = 0.25; r.gz = -0.75;
                r.shell = new Shell(v, 0.123456789012345, expires);
                r.plan(30);
                assertEquals(2, r.draws);
                double[] f = r.flights.get(0);
                double speed = v >= 5F ? v * 1.6F : v;
                float yaw = (float) Math.toDegrees(Math.atan2(-0.75 * 0.04 * 30, 30 + 0.25 * 0.04 * 30)) - 90F;
                double g = Math.toRadians(-yaw), p = Math.toRadians(20F);
                assertArrayEquals(new double[] {0, 2, 0, Math.sin(g) * Math.cos(p) * speed, -Math.sin(p) * speed,
                        Math.cos(g) * Math.cos(p) * speed, expires > 0 ? Math.min(200, expires) : 200, r.shell.gravity() * speed}, f);
            }
    }

    @Test void pitchOrderAndAim() {
        Rig r = new Rig();
        r.accept = -59.5F;
        AiGrenadierBase.Lob lob = r.plan(30);
        assertNotNull(lob);
        assertEquals(-59.5F, lob.pitch());
        assertEquals(55, r.flights.size());
        for (int i = 0; i < 54; i++)
            assertEquals(-Math.sin(Math.toRadians(20F - 1.5F * i)) * 3, r.flights.get(i)[4]);
        double[] dir = new double[3];
        GameMath.direction(-59.5F, -90F, dir);
        assertEquals(r.eye.add(new Vec3d(dir[0], dir[1], dir[2]).scale(30)), lob.aim());
        assertEquals(2 - 0.3, r.flights.get(54)[1]);
        assertEquals(List.of("high", "highOwn", "low", "lowOwn"), r.events.subList(r.events.size() - 4, r.events.size()));
    }

    @Test void landingAndSafetyGates() {
        for (boolean low : new boolean[] {false, true})
            for (int mode = 0; mode < 5; mode++) {
                Rig r = new Rig();
                if (low) {
                    r.lowMode = mode < 3 ? mode : 0;
                    r.lowDistance = mode == 3 ? Math.nextUp(5.0) : 0;
                    r.lowOwn = mode == 4;
                } else {
                    r.highMode = mode < 3 ? mode : 0;
                    r.highDistance = mode == 3 ? Math.nextUp(3.0) : 0;
                    r.highOwn = mode == 4;
                }
                int none = AiGrenadierBase.none, unsafe = AiGrenadierBase.unsafe;
                assertEquals(mode == 0, r.plan(30) != null, low + " " + mode);
                if (mode != 0) {
                    assertEquals(none + (!low && mode < 4 ? 1 : 0), AiGrenadierBase.none);
                    assertEquals(unsafe + (low || mode == 4 ? 1 : 0), AiGrenadierBase.unsafe);
                }
            }
        Rig r = new Rig();
        r.highDistance = 3; r.lowDistance = 5;
        assertNotNull(r.plan(30));
        double[] direction = new double[3];
        GameMath.direction(20F, -90F, direction);
        assertEquals(r.eye.add(new Vec3d(direction[0], direction[1], direction[2]).scale(8)), r.plan(0).aim());
    }

    @Test void simpleBallisticsFindsALob() {
        Rig r = new Rig();
        r.ballistic = true;
        var lob = r.plan(30);
        assertNotNull(lob);
        assertTrue(lob.burst().distanceTo(r.target) <= 3);
        assertTrue(lob.pitch() < 0);
    }

    @Test void eligibilityAndBudgetShortCircuit() {
        for (int gate = 0; gate < 9; gate++) {
            Rig r = new Rig();
            switch (gate) {
                case 0 -> r.bot.role = AiRole.RIFLE;
                case 1 -> AiGrenadierBase.on = false;
                case 2 -> r.shellMissing = true;
                case 3 -> r.secondMissing = true;
                case 4 -> r.hasSpot = true;
                case 5 -> r.reload = true;
                case 6 -> r.loaded = 0;
                case 7 -> r.state().next = 101;
                case 8 -> r.allowed = false;
            }
            assertFalse(r.step(100), "gate " + gate);
            assertEquals(gate < 2 ? 0 : gate == 2 ? 1 : 2, r.shellReads);
            assertEquals(gate == 8 ? 1 : 0, r.budget);
            assertEquals(0, r.draws);
            AiGrenadierBase.on = true;
        }
        Rig r = new Rig();
        r.hasSpot = true; r.arrived = true;
        assertTrue(r.step(100));
        r = new Rig(); r.loaded = -1;
        assertTrue(r.step(100));
    }

    @Test void rangeAndContactEdges() {
        for (double d : new double[] {Math.nextDown(18.0), 18, 80, Math.nextUp(80.0)}) {
            Rig r = new Rig(); r.contact.x = d; r.target = new Vec3d(d, 0, 0);
            assertEquals(d >= 18 && d <= 80, r.step(100), "distance " + d);
        }
        Rig r = new Rig();
        r.contact.state = TargetMemory.State.GLIMPSED;
        r.contact.lostTick = 100;
        r.contact.lostX = 30;
        assertFalse(r.step(100));
        r = new Rig(); r.lost(100);
        assertTrue(r.step(300));
        r = new Rig(); r.lost(100);
        assertFalse(r.step(301));
        r = new Rig();
        r.seen = proxy(Body.class, (p, m, a) -> switch (m.getName()) {
            case "x" -> 81.0;
            case "y", "z" -> 0.0;
            default -> throw new AssertionError();
        });
        assertFalse(r.step(100));
    }

    @Test void aimGapAndLostShots() {
        Rig r = new Rig(); r.lost(80);
        int shots = AiGrenadierBase.shots;
        assertTrue(r.step(100));
        assertEquals(112, r.state().aimUntil);
        assertEquals(0, r.state().firedBefore);
        assertEquals(100, r.state().stepped);
        assertEquals(r.aim, r.state().along);
        assertEquals(r.target, AiGrenadierBase.burstOf(r.bot));
        for (long t = 101; t <= 112; t++) assertTrue(r.step(t));
        assertEquals(1, r.budget);
        assertEquals(13, r.aims);
        assertEquals(13, r.fires);
        assertEquals(1, r.notes);
        r.bot.gun.shots = 1;
        assertFalse(r.step(113));
        assertEquals(shots + 1, AiGrenadierBase.shots);
        assertEquals(1, r.state().lostShots);
        assertEquals(143, r.state().next);
        assertFalse(r.bot.gun.hasExact);
        assertFalse(r.step(142));
        assertTrue(r.step(143));
        r.bot.gun.shots++;
        assertFalse(r.step(144));
        assertFalse(r.step(174));
        assertEquals(2, r.state().lostShots);
        r.contact.lostTick = 81;
        assertTrue(r.step(175));
        assertEquals(0, r.state().lostShots);
    }

    @Test void failedAndInterruptedTimers() {
        Rig r = new Rig();
        assertTrue(r.step(100));
        for (long t = 101; t <= 112; t++) assertTrue(r.step(t));
        assertFalse(r.step(113));
        assertEquals(133, r.state().failed);
        assertFalse(r.step(132));
        assertTrue(r.step(133));
        r = new Rig(); assertTrue(r.step(100));
        r.allowed = false;
        assertFalse(r.step(102));
        assertEquals(Long.MIN_VALUE, r.state().failed);
        assertEquals(Long.MIN_VALUE, r.state().aimUntil);
        assertFalse(r.bot.gun.hasExact);
        r = new Rig(); r.highMode = 1;
        assertFalse(r.step(100));
        assertEquals(120, r.state().failed);
        r.highMode = 0;
        assertFalse(r.step(119)); assertTrue(r.step(120));
        r = new Rig(); assertTrue(r.step(100));
        r.state().along = null;
        assertFalse(r.step(101));
    }

    @Test void moveAndWriteOrder() {
        Rig r = new Rig(); r.goal = 7;
        assertTrue(r.step(100));
        assertEquals(1, r.stopped);
        assertEquals(List.of("aim", "fire", "note"), r.events.subList(r.events.size() - 3, r.events.size()));
        assertTrue(r.bot.gun.hasExact);
        assertEquals(101, r.bot.gun.exactUntil);
        assertEquals(r.aim.x(), r.bot.gun.exactX);
        r = new Rig(); r.hasSpot = true; r.arrived = true; r.goal = 7;
        assertTrue(r.step(100)); assertEquals(0, r.stopped);
        r = new Rig(); assertTrue(r.step(100)); assertEquals(0, r.stopped);
    }

    @Test void visibleShotAndFallback() {
        Rig r = new Rig();
        assertNull(AiGrenadierBase.burstOf(r.bot));
        assertNull(NtmGuns.NONE.grenadeShell(r.body));
        assertFalse(AiGrenadierBase.fight(r, r, r.bot, r.level, r.contact, 100));
        assertNull(r.bot.grenadier);
        r.bot.gun.shots = 7;
        int shots = AiGrenadierBase.shots, plans = AiGrenadierBase.plans;
        assertTrue(r.step(100));
        assertEquals(7, r.state().firedBefore);
        assertFalse(r.state().atLost);
        assertEquals(plans + 1, AiGrenadierBase.plans);
        r.bot.gun.shots = 8;
        assertFalse(r.step(101));
        assertEquals(shots + 1, AiGrenadierBase.shots);
        assertEquals(0, r.state().lostShots);
        assertEquals(131, r.state().next);
        assertFalse(r.bot.gun.hasExact);
        assertEquals("grenadiers: " + AiGrenadierBase.shots + " lobs of " + AiGrenadierBase.plans
                + " plans (" + AiGrenadierBase.none + " none, " + AiGrenadierBase.unsafe + " near its own)", AiGrenadierBase.status());
    }
}
