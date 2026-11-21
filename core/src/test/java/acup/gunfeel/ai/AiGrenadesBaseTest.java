package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.LevelMap;
import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.GrenadeWorlds;
import acup.gunfeel.host.NtmGuns;
import acup.gunfeel.host.Worlds;

class AiGrenadesBaseTest {
    static final class Projectile {
        int kind = NtmGuns.UNIVERSAL, age, timer, fuze = NtmGuns.FUZE_S3, maxTimer = 60, expires = 4, ownerReads;
        double x = 0.5, y = 20, z = 0.5, vx = 0.123, vy = 0.04, vz = -0.07, bounce = 0.5, accel = 0.123456789123, gravity = 0.0123456789123;
        float velocity = 1.234567F;
        boolean removed, glue, config = true;
        Body owner;
        final Object position = new Object();
        final Body body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
            case "x" -> x;
            case "y" -> y;
            case "z" -> z;
            case "age" -> age;
            case "id" -> 7;
            case "removed" -> removed;
            case "owner" -> { ownerReads++; yield owner; }
            case "motion" -> { double[] v = (double[]) a[0]; v[0] = vx; v[1] = vy; v[2] = vz; yield null; }
            case "eyeHeight" -> 1.75F;
            case "poseName" -> "STANDING";
            case "equals" -> p == a[0];
            case "hashCode" -> System.identityHashCode(p);
            default -> throw new AssertionError(m.getName());
        });
    }

    static final class FlightRig implements AiGrenadesBase.Staff, AutoCloseable {
        final Object level = new Object(), other = new Object();
        final long[] time = {100};
        final java.util.IdentityHashMap<Body, Projectile> projectiles = new java.util.IdentityHashMap<>();
        final List<String> log = new ArrayList<>();
        VoxelRay.Source src = AIR;
        AiGrenadesBase.BurstObserver observer;
        final int partingsBefore = AiGrenadesBase.partings.size();
        final int tracedBefore = AiGrenadesBase.traced;
        final NtmGuns guns = (NtmGuns) Proxy.newProxyInstance(NtmGuns.class.getClassLoader(), new Class<?>[] {NtmGuns.class}, (p, m, a) -> {
            if (m.getName().equals("fragForce")) return 0.7;
            if (m.getName().equals("fragBounce")) return 0.5;
            Projectile g = projectiles.get((Body) a[0]);
            return switch (m.getName()) {
                case "liveKind" -> g.kind;
                case "grenadeMaxTimer" -> g.maxTimer;
                case "grenadeBounce" -> g.bounce;
                case "grenadeFuze" -> g.fuze;
                case "grenadeTimer" -> g.timer;
                case "grenadeGlue" -> g.glue;
                case "mk4Accel" -> g.accel;
                case "mk4Config" -> {
                    if (!g.config) yield false;
                    acup.gunfeel.host.Shell into = (acup.gunfeel.host.Shell) a[1];
                    into.velocity = g.velocity; into.gravity = g.gravity; into.expires = g.expires;
                    yield true;
                }
                default -> throw new AssertionError(m.getName());
            };
        });

        FlightRig() {
            GrenadeWorlds.put(level, -1000, time);
            GrenadeWorlds.put(other, -1000, time);
            AiGrenadesBase.journal = log::add;
        }
        Projectile add() { Projectile p = new Projectile(); projectiles.put(p.body, p); return p; }
        AiGrenadesBase.Live join(Projectile p) { return AiGrenadesBase.joined(level, p.body); }
        Vec3d flight(Projectile p) { return AiGrenadesBase.burstOf(this, guns, level, new AiGrenadesBase.Live(p.body)); }
        void tick() { AiGrenadesBase.tick(this, guns, level); }
        @Override public VoxelRay.Source voxels(Object level) { return src; }
        @Override public AiGrenadesBase.BurstObserver flightObserver() { return observer; }
        @Override public Object burstRef(Body body, Vec3d burst, boolean position) {
            return position ? projectiles.get(body).position : new Vec3d(burst.x(), burst.y(), burst.z());
        }
        @Override public boolean ownNear(Object l, VoxelRay.Source s, Body b, double x, double y, double z, double down) { return false; }
        @Override public void close() {
            AiGrenadesBase.forgetLevel(level); AiGrenadesBase.forgetLevel(other);
            Worlds.forget(level); Worlds.forget(other);
            AiGrenadesBase.journal = null; AiGrenadesBase.trace = false;
            AiGrenadesBase.partings.subList(partingsBefore, AiGrenadesBase.partings.size()).clear();
            AiGrenadesBase.traced = tracedBefore;
        }
    }

    @Test void liveRegistryKeepsOrderAndReferenceIdentityAcrossLevelsAndForget() {
        try (FlightRig r = new FlightRig()) {
            Projectile a = r.add(), b = r.add();
            AiGrenadesBase.Live first = r.join(a), second = r.join(b);
            assertSame(first, r.join(a));
            assertEquals(Long.MIN_VALUE, first.at); assertNull(first.burstRef);
            assertTrue(first.known.isEmpty()); first.known.add(42); assertTrue(second.known.isEmpty());
            assertEquals(List.of(first, second), AiGrenadesBase.LEVELS.get(r.level));
            assertInstanceOf(ArrayList.class, AiGrenadesBase.LEVELS.get(r.level));
            AiGrenadesBase.joined(r.other, a.body);
            assertTrue(AiGrenadesBase.any(r.level));
            a.removed = true;
            assertTrue(AiGrenadesBase.any(r.level));
            r.tick();
            assertEquals(List.of(second), AiGrenadesBase.LEVELS.get(r.level));
            assertTrue(AiGrenadesBase.any(r.other));
            b.removed = true;
            r.tick();
            assertNull(AiGrenadesBase.LEVELS.get(r.level));
            assertTrue(AiGrenadesBase.mayPlan(r.other, 100));
            assertTrue(AiGrenadesBase.mayRefuge(r.other, 100));
            AiGrenadesBase.forgetLevel(r.other);
            assertFalse(AiGrenadesBase.any(r.other));
            assertTrue(AiGrenadesBase.planFree(r.other, 100));
            assertTrue(AiGrenadesBase.mayRefuge(r.other, 100));
            assertEquals(List.of("JOIN 0", "JOIN 1", "DUP 0", "JOIN 0", "REMOVE 0", "AT 0 100", "BURST 0", "REMOVE 0", "EMPTY", "FORGET"), r.log);
        }
    }

    @Test void absentGunPortHasNeutralLiveValuesAndLeavesConfigUntouched() {
        Projectile p = new Projectile();
        acup.gunfeel.host.Shell into = new acup.gunfeel.host.Shell();
        into.velocity = 3.5F; into.gravity = 0.123456789123; into.expires = 42;
        assertEquals(NtmGuns.OTHER, NtmGuns.NONE.liveKind(p.body));
        assertEquals(0, NtmGuns.NONE.grenadeMaxTimer(p.body));
        assertEquals(0, NtmGuns.NONE.grenadeBounce(p.body));
        assertEquals(-1, NtmGuns.NONE.grenadeFuze(p.body));
        assertEquals(0, NtmGuns.NONE.grenadeTimer(p.body));
        assertFalse(NtmGuns.NONE.grenadeGlue(p.body));
        assertFalse(NtmGuns.NONE.mk4Config(p.body, into));
        assertEquals(3.5F, into.velocity); bits(0.123456789123, into.gravity); assertEquals(42, into.expires);
        assertEquals(0, NtmGuns.NONE.mk4Accel(p.body));
    }

    @Test void ownerIsReadOncePerCandidateAndTheFirstEqualAgeWins() {
        try (FlightRig r = new FlightRig()) {
            Projectile owner = r.add(), sameId = r.add(), a = r.add(), b = r.add();
            a.owner = owner.body; b.owner = sameId.body;
            a.age = 9; b.age = 10;
            var first = r.join(a); var second = r.join(b);
            first.start = "first"; second.start = "second";
            assertEquals("first; kept to it", AiGrenadesBase.parted(r.level, owner.body));
            assertEquals(1, a.ownerReads); assertEquals(1, b.ownerReads);
            b.owner = owner.body; b.age = 9;
            assertEquals("first; kept to it", AiGrenadesBase.parted(r.level, owner.body));
            assertEquals(2, a.ownerReads); assertEquals(2, b.ownerReads);
            Object[] bursts = AiGrenadesBase.bursting(r, r.level, owner.body);
            assertSame(a.position, bursts[0]); assertNull(bursts[1]);
            assertEquals(3, a.ownerReads); assertEquals(3, b.ownerReads);
            b.age++;
            second.parted = "left";
            assertEquals("second; left", AiGrenadesBase.parted(r.level, owner.body));
            assertNull(AiGrenadesBase.parted(r.other, owner.body));
            assertNull(AiGrenadesBase.bursting(r, r.other, owner.body));
            a.owner = b.owner = null;
            assertEquals("second; left", AiGrenadesBase.parted(r.level, null));
            assertSame(b.position, AiGrenadesBase.bursting(r, r.level, null)[0]);
        }
    }

    @Test void predictionTokensUseReferencesEvenAtEqualCoordinatesAndRenewOnlyWhenDue() {
        try (FlightRig r = new FlightRig()) {
            Projectile a = r.add(), b = r.add(), fired = r.add();
            fired.kind = NtmGuns.MK4;
            var first = r.join(a); var second = r.join(b); var bullet = r.join(fired);
            assertNull(AiGrenadesBase.predicted(r.level, a.body));
            r.tick();
            Object token = first.burstRef, fast = bullet.burstRef;
            assertSame(token, AiGrenadesBase.predicted(r.level, a.body));
            assertEquals(token, second.burstRef); assertNotSame(token, second.burstRef);
            assertTrue(AiGrenadesBase.sameBurst(first, token));
            assertFalse(AiGrenadesBase.sameBurst(first, second.burstRef));
            assertNull(AiGrenadesBase.predicted(r.other, a.body));
            r.tick(); assertSame(fast, bullet.burstRef);
            for (int i = 1; i <= 3; i++) {
                r.time[0]++; r.tick();
                assertSame(token, first.burstRef); assertNotSame(fast, bullet.burstRef); fast = bullet.burstRef;
            }
            r.time[0]++; r.tick();
            assertNotSame(token, first.burstRef);
            assertEquals(104, first.at); assertEquals(104, bullet.at);
            a.timer = 60; r.time[0] += 4; r.tick();
            assertSame(a.position, first.burstRef);
            assertEquals(new Vec3d(a.x, a.y, a.z), first.burst);
            a.fuze = -1; r.time[0] += 4; r.tick();
            assertNull(first.burst); assertNull(first.burstRef);
        }
    }

    @Test void liveFlightCoversEveryFamilyFuzeExpiryGlueAndDoublePrecision() {
        try (FlightRig r = new FlightRig()) {
            Projectile p = r.add();
            p.kind = NtmGuns.OTHER; assertNull(r.flight(p));
            p.kind = NtmGuns.BOUNCY; p.age = 58;
            assertEquals(AiGrenadesBase.burst(AIR, p.x, p.y, p.z, p.vx, p.vy, p.vz, 2, -1, p.bounce, -1000, Vec3d::new), r.flight(p));
            p.age = 60; assertEquals(new Vec3d(p.x, p.y, p.z), r.flight(p));
            p.kind = NtmGuns.MK4; p.age = 1; p.config = false; assertNull(r.flight(p));
            p.config = true;
            for (int expires : new int[] {4, 0, -1, 500}) {
                p.expires = expires;
                double m = p.velocity + p.accel;
                int ticks = expires <= 0 ? 200 : Math.min(200, expires - p.age);
                assertEquals(AiGrenadesBase.burst(AIR, p.x, p.y, p.z, p.vx * m, p.vy * m, p.vz * m, ticks, 0, 0, -1000, p.gravity * m, 1, Vec3d::new), r.flight(p));
            }
            p.expires = 1; assertEquals(new Vec3d(p.x, p.y, p.z), r.flight(p));
            p.kind = NtmGuns.UNIVERSAL; p.age = 999;
            for (int fuze : new int[] {NtmGuns.FUZE_S3, NtmGuns.FUZE_S7, NtmGuns.FUZE_S15, NtmGuns.FUZE_IMPACT}) {
                p.fuze = fuze;
                int full = fuze == NtmGuns.FUZE_S3 ? 60 : fuze == NtmGuns.FUZE_S7 ? 140 : fuze == NtmGuns.FUZE_S15 ? 300 : 200;
                for (int timer : new int[] {0, 9, 10, full - 1, full, full + 1}) {
                    p.timer = timer;
                    int ticks = fuze == NtmGuns.FUZE_IMPACT ? full : Math.max(0, full - timer);
                    for (boolean glue : new boolean[] {false, true}) {
                        p.glue = glue;
                        double[] land = new double[3];
                        Vec3d expected = AiGrenadesBase.burst(AIR, p.x, p.y, p.z, p.vx, p.vy, p.vz, ticks,
                                !glue && fuze == NtmGuns.FUZE_IMPACT ? Math.max(0, 10 - timer) : -1, p.bounce, -1000, 0.03, 0.99, null, land, Vec3d::new);
                        assertEquals(expected, r.flight(p));
                    }
                }
            }
            p.fuze = -1; assertNull(r.flight(p)); p.fuze = 77; assertNull(r.flight(p));
            p.fuze = NtmGuns.FUZE_S3; p.timer = 0; p.y = -1001; assertNull(r.flight(p));
            p.y = 2; p.x = 0.5; p.vx = 1; p.vy = 0; p.vz = 0; p.glue = true; r.src = WALL;
            p.fuze = NtmGuns.FUZE_IMPACT;
            assertEquals(new Vec3d(1, 2, p.z), r.flight(p));
            p.vy = 0.1; p.vz = 0.2;
            int[] reads = {0};
            r.src = (x, y, z) -> { reads[0]++; return WALL.voxel(x, y, z); };
            double[] land = new double[3];
            AiGrenadesBase.burst(r.src, p.x, p.y, p.z, p.vx, p.vy, p.vz, 200, -1, p.bounce, -1000, 0.03, 0.99, null, land, Vec3d::new);
            int expectedReads = reads[0]; reads[0] = 0;
            assertEquals(new Vec3d(land[0], land[1], land[2]), r.flight(p));
            assertEquals(expectedReads, reads[0]);
            p.glue = false;
            for (int timer : new int[] {0, 9, 10}) {
                p.timer = timer;
                assertEquals(AiGrenadesBase.burst(r.src, p.x, p.y, p.z, p.vx, p.vy, p.vz, 200, Math.max(0, 10 - timer), p.bounce, -1000, Vec3d::new), r.flight(p));
            }
            r.src = AIR; assertNotNull(r.flight(p));
        }
    }

    @Test void liveFlightReportsEachBurstVariantWithItsExactInputsAndUnchangedAnswer() {
        try (FlightRig r = new FlightRig()) {
            Projectile p = r.add();
            p.age = 7; p.timer = 9; p.expires = 23; p.fuze = NtmGuns.FUZE_IMPACT;
            p.x = -0.0; p.y = 2; p.z = 0.75; p.bounce = 0.47;
            r.src = FLOOR;
            p.kind = NtmGuns.BOUNCY;
            assertFlightObserved(r, p, 0, 53, -1, 1, 0.03, 0.99);
            p.kind = NtmGuns.MK4;
            double speed = p.velocity + p.accel;
            assertFlightObserved(r, p, 1, 16, 0, speed, p.gravity * speed, 1);
            p.kind = NtmGuns.UNIVERSAL;
            assertFlightObserved(r, p, 0, 200, 1, 1, 0.03, 0.99);
            p.glue = true;
            assertFlightObserved(r, p, 2, 200, -1, 1, 0.03, 0.99);
            r.src = AIR;
            assertFlightObserved(r, p, 2, 200, -1, 1, 0.03, 0.99);
            p.y = -1001;
            assertFlightObserved(r, p, 2, 200, -1, 1, 0.03, 0.99);
        }
    }

    private static void assertFlightObserved(FlightRig r, Projectile p, int variant, int ticks, int armed,
            double scale, double gravity, double drag) {
        double bounce = variant == 1 ? 0 : p.bounce;
        double[] expectedLand = variant == 2 ? new double[3] : null;
        Vec3d expected = AiGrenadesBase.burst(r.src, p.x, p.y, p.z, p.vx * scale, p.vy * scale, p.vz * scale,
                ticks, armed, bounce, -1000, gravity, drag, null, expectedLand, Vec3d::new);
        Vec3d selected = expectedLand == null || Double.isNaN(expectedLand[0]) ? expected
                : new Vec3d(expectedLand[0], expectedLand[1], expectedLand[2]);
        if (variant == 2 && r.src == FLOOR) assertNotEquals(expected, selected);
        r.observer = null;
        assertEquals(selected, r.flight(p));
        int[] calls = {0};
        r.observer = (src, x, y, z, vx, vy, vz, left, arm, b, minY, grav, air, path, land, answer, input) -> {
            calls[0]++;
            assertSame(r.src, src); assertEquals(variant, input);
            bits(p.x, x); bits(p.y, y); bits(p.z, z);
            bits(p.vx * scale, vx); bits(p.vy * scale, vy); bits(p.vz * scale, vz);
            assertEquals(ticks, left); assertEquals(armed, arm); assertEquals(-1000, minY);
            bits(bounce, b); bits(gravity, grav); bits(drag, air);
            assertNull(path);
            assertArrayEquals(expectedLand, land);
            assertEquals(expected, answer);
        };
        assertEquals(selected, r.flight(p));
        assertEquals(1, calls[0]);
        r.observer = null;
        assertEquals(selected, r.flight(p));
        assertEquals(1, calls[0]);
    }

    @Test void tracedTossJoinsOnceAndFollowRecordsOnlyTheFirstDeparture() {
        try (FlightRig r = new FlightRig()) {
            Projectile mob = r.add(), grenade = r.add();
            var live = r.join(grenade);
            AiGrenadesBase.tossed(r, r.guns, r.level, mob.body, grenade.body, 0, -45, 1);
            assertNull(live.path);
            AiGrenadesBase.trace = true;
            AiGrenadesBase.tossed(r, r.guns, r.level, mob.body, grenade.body, 0, -45, 1);
            assertEquals(1, AiGrenadesBase.LEVELS.get(r.level).size());
            assertNotNull(live.path); assertTrue(live.start.startsWith("thrown from"));
            grenade.age = 0; AiGrenadesBase.follow(r, r.level, live); assertNull(live.parted);
            grenade.age = live.path.size(); AiGrenadesBase.follow(r, r.level, live); assertNull(live.parted);
            grenade.age = 1;
            double[] nearby = live.path.get(2);
            grenade.x = nearby[0]; grenade.y = nearby[1]; grenade.z = nearby[2];
            AiGrenadesBase.follow(r, r.level, live); assertNull(live.parted);
            grenade.x += 10;
            AiGrenadesBase.follow(r, r.level, live);
            String departed = live.parted;
            assertNotNull(departed); assertTrue(departed.endsWith("blocks round it: none"));
            AiGrenadesBase.follow(r, r.level, live); assertSame(departed, live.parted);
            assertEquals(r.partingsBefore + 1, AiGrenadesBase.partings.size());
            assertEquals(r.tracedBefore + 1, AiGrenadesBase.traced);
        }
    }

    @Test void departureListsBlocksWithXFirstAndTheEmptyCollisionContext() {
        try (FlightRig r = new FlightRig()) {
            Worlds.forget(r.level);
            List<String> visited = new ArrayList<>();
            acup.gunfeel.host.WorldView world = (acup.gunfeel.host.WorldView) Proxy.newProxyInstance(
                    acup.gunfeel.host.WorldView.class.getClassLoader(), new Class<?>[] {acup.gunfeel.host.WorldView.class}, (p, m, a) -> switch (m.getName()) {
                        case "state" -> { visited.add(a[0] + "," + a[1] + "," + a[2]); yield 1; }
                        case "air" -> false;
                        case "facts" -> new acup.gunfeel.BlockFacts("test:stone", List.of(), "stone", 1, 1, false, true, 1, false, true, java.util.Set.of());
                        case "boxes" -> {
                            assertEquals(acup.gunfeel.host.WorldView.COLLIDER, a[3]); assertNull(a[4]);
                            yield (int) a[0] == 0 ? 0 : -1;
                        }
                        default -> throw new AssertionError(m.getName());
                    });
            GrenadeWorlds.put(r.level, world);
            r.src = (x, y, z) -> x == 1 ? Voxel.OPEN : Voxel.SOLID;
            Projectile p = r.add(); p.x = p.z = 1.5; p.y = 2; p.age = 1;
            var live = r.join(p); live.start = "start";
            live.path = List.of(new double[6], new double[6], new double[6]);
            AiGrenadesBase.follow(r, r.level, live);
            assertEquals(27, visited.size());
            assertEquals(List.of("0,1,0", "1,1,0", "2,1,0", "0,2,0"), visited.subList(0, 4));
            assertEquals("0,1,1", visited.get(9)); assertEquals("2,3,2", visited.get(26));
            assertTrue(live.parted.contains("blocks round it: 0 1 0 stone(no box) 1 1 0 stone(open to us) 2 1 0 stone 0 2 0 stone(no box)"));
            assertTrue(live.parted.endsWith(" 2 3 2 stone"));
        }
    }

    static final VoxelRay.Source AIR = (x, y, z) -> Voxel.OPEN;
    static final VoxelRay.Source FLOOR = (x, y, z) -> y < 0 ? Voxel.SOLID | Voxel.SIGHT : Voxel.OPEN;
    static final VoxelRay.Source WALL = (x, y, z) -> x == 1 ? Voxel.SOLID : Voxel.OPEN;
    static final AiGrenadesBase.Vectors<Vec3d> VECTORS = new AiGrenadesBase.Vectors<>() {
        @Override public Vec3d make(double x, double y, double z) { return new Vec3d(x, y, z); }
        @Override public double x(Vec3d v) { return v.x(); }
        @Override public double y(Vec3d v) { return v.y(); }
        @Override public double z(Vec3d v) { return v.z(); }
        @Override public double distanceTo(Vec3d a, Vec3d b) { return a.distanceTo(b); }
        @Override public double distanceToSqr(Vec3d a, Vec3d b) { return a.distanceToSqr(b); }
    };

    private static void bits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    @Test void impactArmsOnTheInclusiveTickAndKeepsTheFirstLand() {
        double[] land = new double[3];
        List<double[]> path = new ArrayList<>();
        path.add(new double[] {42});
        Vec3d hit = AiGrenadesBase.burst(WALL, 0.5, 2, 0.5, 1, 0, 0, 3, 1, 0.5, -64, 0.03, 0.99, path, land, Vec3d::new);
        assertEquals(new Vec3d(1, 2, 0.5), hit);
        assertArrayEquals(new double[] {1, 2, 0.5}, land);
        assertEquals(2, path.size());
        assertArrayEquals(new double[] {42}, path.get(0));
        assertArrayEquals(new double[] {0.5, 2, 0.5, 1, 0, 0}, path.get(1));
        Vec3d notArmed = AiGrenadesBase.burst(WALL, 0.5, 2, 0.5, 1, 0, 0, 3, 2, 0.5, -64, Vec3d::new);
        assertEquals(new Vec3d(1 - 1e-4, 2, 0.5), notArmed);

        VoxelRay.Source corner = (x, y, z) -> x == 1 || z == 1 ? Voxel.SOLID : Voxel.OPEN;
        path.clear();
        AiGrenadesBase.burst(corner, 0.5, 2, 0.1, 1, 0, 0.4, 8, -1, 0.5, -64, 0, 1, path, land, Vec3d::new);
        assertArrayEquals(new double[] {1, 2, 0.1 + 0.4 * 0.5}, land);
        assertTrue(path.size() > 1);
    }

    @Test void fastHitStopsAtTheRoundedFaceBeforeDragAndGravity() {
        List<double[]> path = new ArrayList<>();
        AiGrenadesBase.burst(WALL, 0.5, 2, 0.5, 1, 0.2, 0.1, 2, -1, 0.8, -64, 0.03, 0.99, path, null, Vec3d::new);
        assertEquals(2, path.size());
        double[] next = path.get(1);
        bits(1 - 1e-4, next[0]);
        bits(2 + 0.2 * 0.5 + 0.2, next[1]);
        bits(0.5 + 0.1 * 0.5 + 0.1, next[2]);
        bits(0, next[3]);
        bits(0.2 * 0.8 * 0.99 - 0.03, next[4]);
        bits(0.1 * 0.8 * 0.99, next[5]);
    }

    @Test void slowHitBouncesAndRestIsTestedBeforeGravity() {
        List<double[]> path = new ArrayList<>();
        AiGrenadesBase.burst(WALL, 0.98, 2, 0.5, 0.04, 0, 0.1, 2, -1, 0.5, -64, 0, 1, path, null, Vec3d::new);
        double[] next = path.get(1);
        bits(1 - 0.05 + 0.04, next[0]);
        bits(-0.04 * 0.5, next[3]);
        bits(0.1 * 0.8, next[5]);

        path.clear();
        Vec3d rest = AiGrenadesBase.burst(FLOOR, 0.5, 0.02, 0.5, 0, -0.04, 0, 60, -1, 0.5, -64,
                0.03, 0.99, path, null, Vec3d::new);
        assertEquals(1, path.size());
        bits(0 + 0.05 - 0.04, rest.y());
        assertEquals(0.5, rest.x());
    }

    @Test void minYIsCheckedAfterAppendingTheStartAndLandIsReset() {
        double[] land = {1, 2, 3};
        List<double[]> path = new ArrayList<>();
        assertNull(AiGrenadesBase.burst(AIR, 0, -65, 0, 0, 0, 0, 1, -1, 0, -64, 0, 1, path, land, Vec3d::new));
        assertEquals(1, path.size());
        for (double v : land) assertTrue(Double.isNaN(v));
        assertNotNull(AiGrenadesBase.burst(AIR, 0, -64, 0, 0, 0, 0, 1, -1, 0, -64, Vec3d::new));
        assertNotNull(AiGrenadesBase.burst(AIR, 0, -65, 0, 0, 0, 0, 0, -1, 0, -64, Vec3d::new));
    }

    @Test void ddaTiesVisitXThenYThenZInBothDirections() {
        for (int sign : new int[] {1, -1}) {
            List<String> cells = new ArrayList<>();
            VoxelRay.Source src = (x, y, z) -> { cells.add(x + "," + y + "," + z); return Voxel.OPEN; };
            double[] hit = {7, 8};
            assertFalse(AiGrenadesBase.first(src, sign * 0.5, sign * 0.5, sign * 0.5,
                    sign * 1.5, sign * 1.5, sign * 1.5, hit));
            assertEquals(sign > 0 ? List.of("1,0,0", "1,1,0", "1,1,1")
                    : List.of("-2,-1,-1", "-2,-2,-1", "-2,-2,-2"), cells);
            assertArrayEquals(new double[] {7, 8}, hit);
        }
        double[] hit = new double[2];
        assertTrue(AiGrenadesBase.first((x, y, z) -> Voxel.SOLID, 0.5, 0.5, 0.5, 1.5, 1.5, 1.5, hit));
        assertArrayEquals(new double[] {0.5, 0}, hit);
        assertTrue(AiGrenadesBase.first((x, y, z) -> x == 1 && y == 1 ? Voxel.SOLID : Voxel.OPEN,
                0.5, 0.5, 0.5, 1.5, 1.5, 1.5, hit));
        assertArrayEquals(new double[] {0.5, 1}, hit);
        assertTrue(AiGrenadesBase.first(WALL, 0.5, 0, 0, 1, 0, 0, hit));
        assertArrayEquals(new double[] {1, 0}, hit);
        assertFalse(AiGrenadesBase.first((x, y, z) -> { fail("starting cell"); return 0; },
                0.1, 0.1, 0.1, 0.9, 0.9, 0.9, hit));
    }

    @Test void fromAtUsesFloatYawArithmeticAndAppendsTheOriginalStart() {
        for (float yaw : new float[] {-137.12345F, -0F, 0F, 91.23456F}) {
            List<double[]> path = new ArrayList<>();
            double force = 1.23456789012345;
            AiGrenadesBase.fromAt(AIR, 2, 10, -3, yaw, -31.25F, force, 0.5, -1000, 0.1, -0.2, 0.3, path, null, -1, Vec3d::new);
            double r = Math.toRadians(180F - yaw), f = Math.toRadians(-31.25F), g = Math.toRadians(-yaw);
            assertArrayEquals(new double[] {2 + 0.25 * Math.cos(r) + 0.1, 10 - 0.25 - 0.2, -3 - 0.25 * Math.sin(r) + 0.3,
                    Math.sin(g) * Math.cos(f) * force, -Math.sin(f) * force, Math.cos(g) * Math.cos(f) * force}, path.get(0));
            assertEquals(60, path.size());
            path.clear();
            AiGrenadesBase.fromAt(AIR, 2, 10, -3, yaw, -31.25F, force, 0.5, -1000, 0, 0, 0, path, null, 10, Vec3d::new);
            assertEquals(100, path.size());
        }
    }

    final class Rig implements AiGrenadesBase.Staff, AutoCloseable {
        final Object level = new Object();
        VoxelRay.Source src = FLOOR;
        int eyes, ownCalls, forceReads, bounceReads, rejectFirst, rejectFrom = Integer.MAX_VALUE;
        float cornerEye = Float.NaN;
        double force;
        boolean unsafe;
        final Body body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
            case "x", "y", "z" -> 0.0;
            case "eyeHeight" -> { eyes++; yield eyes > 21 && !Float.isNaN(cornerEye) ? cornerEye : 1.75F; }
            default -> throw new AssertionError(m.getName());
        });
        final NtmGuns guns = (NtmGuns) Proxy.newProxyInstance(NtmGuns.class.getClassLoader(), new Class<?>[] {NtmGuns.class}, (p, m, a) -> switch (m.getName()) {
            case "fragForce" -> { forceReads++; yield force; }
            case "fragBounce" -> { bounceReads++; yield 0.5; }
            default -> throw new AssertionError(m.getName());
        });

        Rig() { GrenadeWorlds.put(level, -1000); }
        @Override public VoxelRay.Source voxels(Object l) { assertSame(level, l); return src; }
        @Override public boolean ownNear(Object l, VoxelRay.Source s, Body b, double bx, double by, double bz, double down) {
            assertSame(level, l); assertSame(src, s); assertSame(body, b); ownCalls++;
            return unsafe || ownCalls <= rejectFirst || ownCalls >= rejectFrom;
        }
        AiGrenadesBase.Throw<Vec3d> plan(Vec3d at) { return AiGrenadesBase.planNow(this, guns, level, body, at, false, 0, 0.55, VECTORS); }
        @Override public void close() { Worlds.forget(level); AiGrenadesBase.forgetLevel(level); }
    }

    @Test void equalCandidatesKeepTheFirstPitchAndBothRefinementsRun() {
        try (Rig r = new Rig()) {
            var t = r.plan(Vec3d.ZERO);
            assertNotNull(t);
            assertEquals(-90F, t.yaw());
            assertEquals(-70F, t.pitch());
            assertEquals(1, t.share());
            assertEquals(17 + 4 + 8, r.eyes);
            assertEquals(9, r.ownCalls);
            assertEquals(1, r.forceReads);
            assertEquals(1, r.bounceReads);
            r.unsafe = true;
            assertNull(r.plan(Vec3d.ZERO));
        }
    }

    @Test void aPitchBetweenCoarseSamplesIsRefinedTwice() {
        try (Rig r = new Rig()) {
            r.src = AIR;
            r.force = 1.2;
            Vec3d target = AiGrenadesBase.fromAt(AIR, 0, 1.75, 0, -90F, -61.25F, r.force, 0.5, -1000,
                    0, 0, 0, null, null, -1, Vec3d::new);
            var t = r.plan(target);
            assertNotNull(t);
            assertEquals(-61.25F, t.pitch());
            assertTrue(t.burst().distanceTo(target) < 0.3);
            assertEquals(29, r.eyes);
        }
    }

    @Test void equalRefinementsKeepMinusBeforePlusEvenOutsideTheInitialRange() {
        try (Rig r = new Rig()) {
            r.rejectFirst = 17;
            var t = r.plan(Vec3d.ZERO);
            assertNotNull(t);
            assertEquals(-72.5F, t.pitch());
            assertEquals(26, r.ownCalls);
        }
    }

    @Test void ddaEachAxisCanBeStrictlyFirst() {
        VoxelRay.Source solid = (x, y, z) -> Voxel.SOLID;
        double[] hit = new double[2];
        for (int axis = 0; axis < 3; axis++) {
            double[] end = {1.5, 1.5, 1.5};
            end[axis] = 2.5;
            assertTrue(AiGrenadesBase.first(solid, 0.5, 0.5, 0.5, end[0], end[1], end[2], hit));
            assertArrayEquals(new double[] {0.25, axis}, hit);
        }
    }

    @Test void everyCornerMustExistStayNearTheTargetAndKeepOffItsOwn() {
        try (Rig r = new Rig()) {
            r.cornerEye = -2000F;
            assertNull(r.plan(Vec3d.ZERO));
        }
        try (Rig r = new Rig()) {
            r.src = AIR;
            r.cornerEye = 20F;
            Vec3d target = AiGrenadesBase.fromAt(AIR, 0, 1.75, 0, -90F, -70F, 0, 0.5, -1000, 0, 0, 0, null, null, -1, Vec3d::new);
            assertNull(r.plan(target));
        }
        try (Rig r = new Rig()) {
            r.rejectFrom = 2;
            assertNull(r.plan(Vec3d.ZERO));
        }
    }

    @Test void aFlightCanHitThenFallOutOfTheWorld() {
        try (Rig r = new Rig()) {
            r.force = 1.2;
            r.src = (x, y, z) -> x == 1 && y < 3 ? Voxel.SOLID : Voxel.OPEN;
            Worlds.forget(r.level);
            GrenadeWorlds.put(r.level, 0);
            assertNull(r.plan(new Vec3d(8, 0, 8)));
        }
    }

    @Test void restNeedsBothSmallHorizontalAndSmallVerticalSpeed() {
        for (double[] v : new double[][] {{1, 0.2, 0}, {1, 0, 0.1}}) {
            List<double[]> path = new ArrayList<>();
            AiGrenadesBase.burst(WALL, 0.5, 2, 0.5, v[0], v[1], v[2], 2, -1, 0.5, -64, 0.03, 0.99, path, null, Vec3d::new);
            assertEquals(2, path.size());
        }
    }

    @Test void glueRejectsACornerThatMissesTheOnlyWallEvenWhenThePredicateAcceptsNaN() {
        try (Rig r = new Rig()) {
            r.force = 1.2;
            r.src = (x, y, z) -> x == 1 && z == 1 ? Voxel.SOLID : Voxel.OPEN;
            assertNull(AiGrenadesBase.planStick(r, r.guns, r.level, 0, 1.75, 0.75, new Vec3d(8, 0, 0.75), b -> true, VECTORS));
        }
    }

    @Test void glueKeepsEqualCandidatesInPitchOrderAndChecksEveryCornerWithoutLandLimit() {
        try (Rig r = new Rig()) {
            List<Vec3d> tried = new ArrayList<>();
            var t = AiGrenadesBase.planStick(r, r.guns, r.level, 0, 1.75, 0, new Vec3d(100, 0, 0),
                    b -> { tried.add(b); return true; }, VECTORS);
            assertNotNull(t);
            assertEquals(-70F, t.pitch());
            assertTrue(t.burst().distanceTo(new Vec3d(100, 0, 0)) > AiGrenadesBase.LAND);
            assertEquals(25, tried.size());
            assertSame(tried.get(0), t.burst());
            for (int i = 0; i < 17; i++) assertEquals(t.burst(), tried.get(i));
            int i = 17;
            double rad = Math.toRadians(180F - t.yaw());
            for (int sx : new int[] {-1, 1}) for (int sz : new int[] {-1, 1}) for (int h = 0; h < 2; h++) {
                Vec3d corner = tried.get(i++);
                bits(0.25 * Math.cos(rad) + sx * 0.175, corner.x());
                bits(-0.25 * Math.sin(rad) + sz * 0.175, corner.z());
            }
            int[] calls = {0};
            assertNull(AiGrenadesBase.planStick(r, r.guns, r.level, 0, 1.75, 0, Vec3d.ZERO, b -> ++calls[0] <= 17, VECTORS));
            assertEquals(34, calls[0]);
            r.src = AIR;
            assertNull(AiGrenadesBase.planStick(r, r.guns, r.level, 0, 1.75, 0, Vec3d.ZERO, b -> { fail("no land"); return true; }, VECTORS));
        }
    }

    @Test void behindUsesMovementButShieldRequiresSightAndAllBodyRays() {
        Vec3d burst = new Vec3d(8.5, 64, 0.5);
        VoxelRay.Source glass = (x, y, z) -> x == 1 ? Voxel.SOLID : Voxel.OPEN;
        VoxelRay.Source low = (x, y, z) -> y < 64 || x == 1 && z >= -3 && z <= 3 && y < 65 ? Voxel.SOLID | Voxel.SIGHT : Voxel.OPEN;
        assertTrue(AiGrenadesBase.behind(glass, burst, 0.5, 64, 0.5));
        assertFalse(AiGrenadesBase.shielded(glass, 0.5, 64, 0.5, 0.6, burst.x(), burst.y(), burst.z()));
        assertTrue(AiGrenadesBase.shielded(low, 0.5, 64, 0.5, 0.6, burst.x(), burst.y(), burst.z()));
        assertFalse(AiGrenadesBase.shielded(low, 0.5, 64, 0.5, 1.5, burst.x(), burst.y(), burst.z()));
        assertFalse(AiGrenadesBase.shielded(low, 0.5, 64, 3.5, 0.6, 8.5, 64, 3.5));
        assertFalse(AiGrenadesBase.cover(low, burst.x(), burst.y(), burst.z(), 0.5, 64, 0.5, false));
        assertFalse(AiGrenadesBase.cover(low, burst.x(), burst.y(), burst.z(), 0.5, 64, 0.5, true));
        assertFalse(AiGrenadesBase.shielded(low, 8.5, 64, 0.5, 0.6, burst.x(), burst.y(), burst.z()));
    }

    @Test void nearIsStrictAndRejectsMissingCellsWhileSafeUnpacksThem() {
        long cell = PosLong.block(-3, 4, 5);
        Vec3d burst = new Vec3d(-2.5, 4, 6.5);
        assertFalse(AiGrenadesBase.near(cell, burst.x(), burst.y(), burst.z(), 1));
        assertTrue(AiGrenadesBase.near(cell, burst.x(), burst.y(), burst.z(), Math.nextUp(1.0)));
        assertTrue(AiGrenadesBase.safe(AIR, burst.x(), burst.y(), burst.z(), cell, 1, true));
        assertFalse(AiGrenadesBase.safe(AIR, burst.x(), burst.y(), burst.z(), cell, Math.nextUp(1.0), true));
        Vec3d missing = new Vec3d(PosLong.blockX(Long.MIN_VALUE) + 0.5, PosLong.blockY(Long.MIN_VALUE), PosLong.blockZ(Long.MIN_VALUE) + 0.5);
        assertFalse(AiGrenadesBase.near(Long.MIN_VALUE, missing.x(), missing.y(), missing.z(), 10));
        assertFalse(AiGrenadesBase.safe(AIR, missing.x(), missing.y(), missing.z(), Long.MIN_VALUE, 10, true));
    }

    @Test void twoLevelsHaveIndependentBudgetsIncludingTickReversalAndForget() throws Exception {
        Object a = new Object(), b = new Object();
        try {
            for (Object level : new Object[] {a, b}) {
                assertTrue(AiGrenadesBase.planFree(level, 1000));
                assertTrue(AiGrenadesBase.planFree(level, 1000));
                assertTrue(AiGrenadesBase.mayPlan(level, 1000));
                assertFalse(AiGrenadesBase.planFree(level, 1000));
                Field plannedField = AiGrenadesBase.class.getDeclaredField("PLANNED");
                plannedField.setAccessible(true);
                LevelMap<?> planned = (LevelMap<?>) plannedField.get(null);
                Object before = planned.get(level);
                assertFalse(AiGrenadesBase.mayPlan(level, 1000));
                assertNotSame(before, planned.get(level));
                assertTrue(AiGrenadesBase.mayPlan(level, 999));
                assertTrue(AiGrenadesBase.mayPlan(level, 1000));
                for (int i = 0; i < 6; i++) assertTrue(AiGrenadesBase.mayRefuge(level, 1000));
                for (int i = 0; i < 3; i++) assertFalse(AiGrenadesBase.mayRefuge(level, 1000));
            }
            Field field = AiGrenadesBase.class.getDeclaredField("REFUGES");
            field.setAccessible(true);
            LevelMap<?> refuges = (LevelMap<?>) field.get(null);
            assertEquals(9, ((long[]) refuges.get(a))[1]);
            assertEquals(9, ((long[]) refuges.get(b))[1]);
            assertTrue(AiGrenadesBase.mayRefuge(a, 999));
            assertTrue(AiGrenadesBase.mayRefuge(a, 1000));
            assertFalse(AiGrenadesBase.mayRefuge(b, 1000));
            AiGrenadesBase.forgetLevel(a);
            assertTrue(AiGrenadesBase.planFree(a, 1000));
            assertTrue(AiGrenadesBase.mayPlan(a, 1000));
            for (int i = 0; i < 6; i++) assertTrue(AiGrenadesBase.mayRefuge(a, 1000));
            assertFalse(AiGrenadesBase.mayRefuge(a, 1000));
            assertFalse(AiGrenadesBase.planFree(b, 1000));
            assertFalse(AiGrenadesBase.mayRefuge(b, 1000));
            for (int i = 0; i < 6; i++) assertTrue(AiGrenadesBase.mayRefuge(a, Long.MIN_VALUE));
            assertFalse(AiGrenadesBase.mayRefuge(a, Long.MIN_VALUE));
            assertTrue(AiGrenadesBase.mayPlan(a, Long.MIN_VALUE));
            assertFalse(AiGrenadesBase.mayPlan(a, Long.MIN_VALUE));
            assertEquals(0, NtmGuns.NONE.fragForce());
            assertEquals(0, NtmGuns.NONE.fragBounce());
        } finally {
            AiGrenadesBase.forgetLevel(a);
            AiGrenadesBase.forgetLevel(b);
        }
    }
}
