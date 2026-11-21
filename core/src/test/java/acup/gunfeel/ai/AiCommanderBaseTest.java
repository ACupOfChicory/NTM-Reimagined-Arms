package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;

class AiCommanderBaseTest {
    private int pairedAgain, keptTime, upAtOnce, otherDied, graced;

    private static final class Job extends AiCommanderBase.Job<AiBotBase, Job> {
        Job(StaffPlan.Kind kind, List<AiBotBase> bots) {
            super(kind, 42, bots, new int[] {7});
        }
    }

    @BeforeEach
    void saveCounters() {
        pairedAgain = AiCommanderBase.pairedAgain;
        keptTime = AiCommanderBase.keptTime;
        upAtOnce = AiCommanderBase.upAtOnce;
        otherDied = AiCommanderBase.otherDied;
        graced = AiCommanderBase.graced;
        AiCommanderBase.pairedAgain = AiCommanderBase.keptTime = AiCommanderBase.upAtOnce = AiCommanderBase.otherDied = AiCommanderBase.graced = 0;
    }

    @AfterEach
    void restoreCounters() {
        AiCommanderBase.pairedAgain = pairedAgain;
        AiCommanderBase.keptTime = keptTime;
        AiCommanderBase.upAtOnce = upAtOnce;
        AiCommanderBase.otherDied = otherDied;
        AiCommanderBase.graced = graced;
    }

    private static Job job() {
        return new Job(StaffPlan.Kind.FLANK, List.of());
    }

    private static HqGrid.Route route(double... points) {
        return new HqGrid.Route(0, 0, points[points.length - 2], points[points.length - 1], points, 30, 30, 0, 0);
    }

    @Test
    void aheadOnSegment() {
        HqGrid.Route r = route(10, 0, 10, 10);
        assertEquals(0, AiCommanderBase.ahead(r, 5, 0));
        assertEquals(1, AiCommanderBase.ahead(r, 10, 5));
    }

    @Test
    void aheadClampsBeforeStart() {
        // The first line's extension is nearer, but its segment is farther.
        assertEquals(1, AiCommanderBase.ahead(route(10, 0, -10, 2), -10, 0));
    }

    @Test
    void aheadClampsAfterEnd() {
        assertEquals(1, AiCommanderBase.ahead(route(10, 0, 20, 2), 20, 0));
    }

    @Test
    void aheadHandlesZeroLength() {
        assertEquals(0, AiCommanderBase.ahead(route(0, 0, 10, 0), 0, 0));
        assertEquals(1, AiCommanderBase.ahead(route(0, 0, 10, 0), 5, 0));
    }

    @Test
    void pastIncludesEndpoint() {
        assertFalse(AiCommanderBase.past(4, 6, 2, 3, 6, 9));
        assertTrue(AiCommanderBase.past(6, 9, 2, 3, 6, 9));
        assertTrue(AiCommanderBase.past(8, 12, 2, 3, 6, 9));
        assertTrue(AiCommanderBase.past(-100, -100, 2, 3, 2, 3));
        assertTrue(AiCommanderBase.past(-100, -100, 0, 0, 1e-5, 1e-5));
    }

    @Test
    void weightByRole() {
        for (AiRole role : AiRole.values()) {
            double expected = switch (role) {
                case RIFLE -> 1;
                case BASE -> 1.5;
                case MARKSMAN, GRENADIER -> 1.2;
                case MEDIC -> 0.7;
                case SUPPLIER -> 0.8;
            };
            assertEquals(expected, AiCommanderBase.weight(role.ordinal()), role.name());
        }
        assertEquals(3, AiCommanderBase.weight(-1));
        assertEquals(3, AiCommanderBase.weight(IntelBook.PLAYER));
        assertEquals(3, AiCommanderBase.weight(AiRole.values().length));
        assertEquals(3, AiCommanderBase.weight(Integer.MAX_VALUE));
    }

    private static void consumed(Job f, Job p) {
        assertEquals(Long.MAX_VALUE, f.dueWas);
        assertEquals(Long.MAX_VALUE, p.dueWas);
        assertFalse(f.otherDead);
        assertFalse(p.otherDead);
    }

    @Test
    void freshTimeWhenNeitherHadAPair() {
        Job f = job(), p = job();
        f.otherDead = true;
        assertEquals(1000, AiCommanderBase.time(f, p, 1000, 100));
        assertEquals(0, AiCommanderBase.pairedAgain);
        assertEquals(0, AiCommanderBase.keptTime);
        assertEquals(0, AiCommanderBase.otherDied);
        consumed(f, p);
    }

    @Test
    void timeKeepsEarlierDeadlineFromEitherJob() {
        for (boolean flank : new boolean[] {true, false}) {
            Job f = job(), p = job();
            f.dueWas = flank ? 500 : 700;
            p.dueWas = flank ? 700 : 500;
            assertEquals(500, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        assertEquals(2, AiCommanderBase.pairedAgain);
        assertEquals(2, AiCommanderBase.keptTime);
        assertEquals(0, AiCommanderBase.graced);
    }

    @Test
    void timeGrantsGraceAfterDeath() {
        for (boolean flank : new boolean[] {true, false}) {
            Job f = job(), p = job();
            f.dueWas = 120;
            f.otherDead = flank;
            p.otherDead = !flank;
            assertEquals(300, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        assertEquals(2, AiCommanderBase.pairedAgain);
        assertEquals(2, AiCommanderBase.keptTime);
        assertEquals(2, AiCommanderBase.otherDied);
        assertEquals(2, AiCommanderBase.graced);
        assertEquals(0, AiCommanderBase.upAtOnce);
    }

    @Test
    void timeGraceKeepsLaterDeadline() {
        Job f = job(), p = job();
        p.dueWas = 600;
        p.otherDead = true;
        assertEquals(600, AiCommanderBase.time(f, p, 1000, 100));
        assertEquals(0, AiCommanderBase.graced);
        assertEquals(1, AiCommanderBase.otherDied);
        consumed(f, p);
    }

    @Test
    void timeHasNoGraceWhenNewPartnerIsThere() {
        for (boolean flank : new boolean[] {true, false}) {
            Job f = job(), p = job();
            f.dueWas = 120;
            f.otherDead = flank;
            p.otherDead = !flank;
            p.there = flank;
            f.there = !flank;
            assertEquals(120, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        assertEquals(0, AiCommanderBase.graced);
        assertEquals(2, AiCommanderBase.otherDied);
    }

    @Test
    void timeNeverExceedsFresh() {
        for (long was : new long[] {1000, 1100}) {
            Job f = job(), p = job();
            f.dueWas = was;
            assertEquals(1000, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        Job f = job(), p = job();
        f.dueWas = 120;
        f.otherDead = true;
        assertEquals(200, AiCommanderBase.time(f, p, 200, 100));
        consumed(f, p);
        assertEquals(3, AiCommanderBase.pairedAgain);
        assertEquals(0, AiCommanderBase.keptTime);
        assertEquals(0, AiCommanderBase.graced);
        assertEquals(1, AiCommanderBase.otherDied);
    }

    @Test
    void timeCountsAlreadyExpiredPairs() {
        for (long was : new long[] {99, 100}) {
            Job f = job(), p = job();
            p.dueWas = was;
            assertEquals(was, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        assertEquals(2, AiCommanderBase.upAtOnce);
        assertEquals(2, AiCommanderBase.keptTime);
        assertEquals(0, AiCommanderBase.otherDied);
    }

    private static StaffPlan.Squad squad(int group, long pinned) {
        return new StaffPlan.Squad(0, 0, 1, 1, 1, false, null, 0, group, false, -1, false, false, Long.MAX_VALUE, pinned, false, false);
    }

    @Test
    void helpsPinnedSquadAtSameGroup() {
        List<StaffPlan.Squad> own = List.of(squad(1, Long.MIN_VALUE), squad(7, 100));
        for (StaffPlan.Kind kind : StaffPlan.Kind.values()) {
            StaffPlan.Task[] plan = {new StaffPlan.Task(kind, 7, 0, 0, 1, 0, 0), null};
            assertEquals(kind == StaffPlan.Kind.FLANK || kind == StaffPlan.Kind.PIN, AiCommanderBase.helps(own, plan, 0), kind.name());
        }
        StaffPlan.Task[] plan = {new StaffPlan.Task(StaffPlan.Kind.FLANK, 7, 0, 0, 1, 0, 0), null};
        assertFalse(AiCommanderBase.helps(List.of(squad(7, 100), squad(7, 100)), plan, 0));
        assertFalse(AiCommanderBase.helps(List.of(squad(1, Long.MIN_VALUE), squad(8, 100)), plan, 0));
        assertFalse(AiCommanderBase.helps(List.of(squad(1, Long.MIN_VALUE), squad(7, Long.MIN_VALUE)), plan, 0));
        plan[0] = null;
        assertFalse(AiCommanderBase.helps(own, plan, 0));
        plan[0] = new StaffPlan.Task(StaffPlan.Kind.PIN, -1, 0, 0, 1, 0, 0);
        assertFalse(AiCommanderBase.helps(own, plan, 0));
    }

    @Test
    void containsAndShares() {
        assertTrue(AiCommanderBase.contains(new int[] {2, 7, 9}, 7));
        assertFalse(AiCommanderBase.contains(new int[] {2, 7, 9}, 3));
        assertFalse(AiCommanderBase.contains(new int[0], 7));
        assertTrue(AiCommanderBase.shares(new int[] {2, 7}, new int[] {9, 7}));
        assertFalse(AiCommanderBase.shares(new int[] {2, 7}, new int[] {9, 8}));
        assertFalse(AiCommanderBase.shares(new int[0], new int[] {7}));
        assertFalse(AiCommanderBase.shares(new int[] {7}, new int[0]));
    }

    @Test
    void givenSinceIsStrict() {
        AiCommanderBase.Order<AiBotBase, Job> o = new AiCommanderBase.Order<>();
        assertFalse(AiCommanderBase.givenSince(null, 100));
        o.givenAt = 100;
        assertTrue(AiCommanderBase.givenSince(o, 99));
        assertFalse(AiCommanderBase.givenSince(o, 100));
        assertFalse(AiCommanderBase.givenSince(o, 101));
    }

    @Test
    void jobDefaultsAndPlace() {
        List<AiBotBase> bots = List.of();
        for (StaffPlan.Kind kind : StaffPlan.Kind.values()) {
            Job j = new Job(kind, bots);
            assertSame(kind, j.kind);
            assertEquals(42, j.since);
            assertArrayEquals(new int[] {7}, j.ids);
            assertSame(bots, j.bots);
            assertSame(j.bots, j.members);
            assertEquals(kind != StaffPlan.Kind.ADVANCE && kind != StaffPlan.Kind.SCOUT, j.place(), kind.name());
            assertEquals(Long.MIN_VALUE, j.foundAt);
            assertEquals(Long.MAX_VALUE, j.due);
            assertEquals(Long.MIN_VALUE, j.ready);
            assertEquals(Long.MAX_VALUE, j.dueWas);
            assertEquals(Long.MIN_VALUE, j.givenAt);
            assertEquals(Long.MIN_VALUE, j.tailNot);
            assertEquals(Long.MIN_VALUE, j.pinFrom);
            assertEquals(Long.MIN_VALUE, j.pinnedAt);
            assertEquals(Long.MIN_VALUE, j.quietFrom);
            assertEquals(Long.MIN_VALUE, j.noFoeFrom);
            assertEquals(Long.MIN_VALUE, j.move.next);
            assertNull(j.move.mode);
        }
    }

    @Test
    void aliveReadsOnlyTheBodyEachTime() {
        boolean[] living = {true, false, true};
        List<AiBotBase> bots = java.util.stream.IntStream.range(0, living.length).mapToObj(i -> AiBotBaseTest.bot((Body) Proxy.newProxyInstance(
                Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
                    if (m.getName().equals("alive")) return living[i];
                    throw new AssertionError("unexpected body call: " + m.getName());
                }))).toList();
        Job j = new Job(StaffPlan.Kind.HOLD, bots);
        assertEquals(2, j.alive());
        living[0] = false;
        assertEquals(1, j.alive());
        assertEquals(0, job().alive());
    }

    @Test
    void orderDefaults() {
        AiCommanderBase.Order<AiBotBase, Job> o = new AiCommanderBase.Order<>();
        assertNull(o.job);
        assertEquals(Long.MIN_VALUE, o.nextLeg);
        assertEquals(Long.MIN_VALUE, o.lastInside);
        assertEquals(Long.MIN_VALUE, o.givenAt);
        assertEquals(Double.MAX_VALUE, o.failedAt);
        assertEquals(Long.MIN_VALUE, o.detourTick);
        assertEquals(Double.MAX_VALUE, o.legD);
        assertEquals(Double.MAX_VALUE, o.bestD);
        assertTrue(Double.isNaN(o.legX));
        assertTrue(Double.isNaN(o.legZ));
        assertEquals(Long.MIN_VALUE, o.lookUntil);
        assertEquals(Long.MIN_VALUE, o.coverCell);
        assertEquals(Long.MIN_VALUE, o.coverSince);
        assertEquals(Long.MIN_VALUE, o.halfSince);
        assertEquals(Long.MIN_VALUE, o.badCell);
        assertEquals(AiRooms.OUTSIDE, o.room);
        assertTrue(Double.isNaN(o.roomX));
        assertTrue(Double.isNaN(o.roomZ));
        assertEquals(Long.MIN_VALUE, o.moveAt);
        assertArrayEquals(new int[0], o.ids);
        assertTrue(o.badPlaces.isEmpty());
        AiCommanderBase.Order<AiBotBase, Job> other = new AiCommanderBase.Order<>();
        assertNotSame(o.ids, other.ids);
        assertNotSame(o.badPlaces, other.badPlaces);
    }

    @Test
    void movementStateDefaults() {
        StaffMoveBase.State state = new StaffMoveBase.State();
        assertEquals(Long.MIN_VALUE, state.next);
        assertNull(state.mode);
    }

    @org.junit.jupiter.api.Nested
    class StaffLogic {
        final Object level = new Object();
        final AiBotsBase.PerLevelBase<Bot> registry = new AiBotsBase.PerLevelBase<>();
        final java.util.List<Person> people = new java.util.ArrayList<>();
        final java.util.List<AiReportsBase.Report> reports = new java.util.ArrayList<>();
        final java.util.List<String> events = new java.util.ArrayList<>();
        final java.util.Map<Long, acup.gunfeel.water.LiquidPath.Cell> liquids = new java.util.HashMap<>();
        long now = 1000;
        boolean loaded = true, supply, emptyCells, traceStep;
        Bot stepping;
        acup.gunfeel.Vec3d aimed;
        boolean crouched;
        long heldAt = Long.MIN_VALUE;
        int mapCalls;
        final java.util.Map<Long, SectionCells> sections = new java.util.HashMap<>();
        final SectionCells empty = new SectionCells(0, 1, new short[0], new int[0], new short[0], new short[257], 0);
        final TacticalMapBase terrain = new TacticalMapBase(-4, 8) {
            @Override public SectionCells cells(long key) { return emptyCells ? empty : sections.get(key); }
            @Override public SectionCells cellsAt(int x, int y, int z) { return cells(acup.gunfeel.PosLong.section(x >> 4, y >> 4, z >> 4)); }
            @Override protected SectionSnapshot copy(long key, int x, int y, int z, int stamp) { throw new AssertionError(); }
        };
        int top = 64, draw = 7, draws, bound;
        HqGrid grid;
        final acup.gunfeel.GameRandom random = new acup.gunfeel.GameRandom() {
            @Override public int nextInt(int n) { draws++; bound = n; return draw; }
            @Override public double nextDouble() { throw new AssertionError("not asked"); }
            @Override public boolean nextBoolean() { throw new AssertionError("not asked"); }
            @Override public float nextFloat() { throw new AssertionError("not asked"); }
            @Override public long nextLong() { throw new AssertionError("not asked"); }
            @Override public double nextGaussian() { throw new AssertionError("not asked"); }
        };
        final acup.gunfeel.host.WorldView world = (acup.gunfeel.host.WorldView) Proxy.newProxyInstance(Body.class.getClassLoader(),
                new Class<?>[] {acup.gunfeel.host.WorldView.class}, (p, m, a) -> switch (m.getName()) {
                    case "gameTime" -> now;
                    case "random" -> random;
                    case "hasChunk" -> loaded;
                    case "minY" -> -64;
                    case "height" -> { assertEquals(acup.gunfeel.host.WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES, a[2]); yield top; }
                    default -> throw new AssertionError("unexpected world call: " + m.getName());
                });
        final acup.gunfeel.host.EntityView entities = new acup.gunfeel.host.EntityView() {
            @Override public Body byId(int id) { return people.stream().filter(p -> p.id == id).findFirst().map(p -> p.body).orElse(null); }
            @Override public int near(int k, double x0, double y0, double z0, double x1, double y1, double z1, Body[] out) { throw new AssertionError(); }
            @Override public int players(Body[] out) {
                List<Body> all = people.stream().filter(p -> p.player).map(p -> p.body).toList();
                if (out.length < all.size()) return -all.size();
                for (int i = 0; i < all.size(); i++) out[i] = all.get(i);
                return all.size();
            }
        };
        final acup.gunfeel.water.LiquidPath.Fluids fluids = new acup.gunfeel.water.LiquidPath.Fluids() {
            @Override public acup.gunfeel.water.LiquidPath.Cell at(int x, int y, int z) { return liquids.get(acup.gunfeel.PosLong.block(x, y, z)); }
            @Override public acup.gunfeel.Vec3d flow(int x, int y, int z) { throw new AssertionError(); }
        };
        final acup.gunfeel.host.BodyControl control = (acup.gunfeel.host.BodyControl) Proxy.newProxyInstance(Body.class.getClassLoader(),
                new Class<?>[] {acup.gunfeel.host.BodyControl.class}, (p, m, a) -> {
                    Person b = people.stream().filter(v -> v.body == a[0]).findFirst().orElseThrow();
                    if (m.getName().equals("snap")) {
                        b.x = (double) a[1]; b.y = (double) a[2]; b.z = (double) a[3]; b.yaw = (float) a[4]; b.pitch = (float) a[5];
                        events.add("drift");
                    } else if (m.getName().equals("yHeadRot")) b.head = (float) a[1];
                    else throw new AssertionError(m.getName());
                    return null;
                });

        final class Person {
            final int id;
            String team;
            double x, y = 64, z;
            float yaw, pitch, head;
            boolean alive = true, removed, player, spectator;
            Body hurt, credit;
            final Body body;
            double[] numbers = {0.25, 0.25};
            int randomDraws;
            final acup.gunfeel.GameRandom ownRandom = new acup.gunfeel.GameRandom() {
                @Override public double nextDouble() { return numbers[randomDraws++]; }
                @Override public int nextInt(int n) { throw new AssertionError(); }
                @Override public boolean nextBoolean() { throw new AssertionError(); }
                @Override public float nextFloat() { throw new AssertionError(); }
                @Override public long nextLong() { throw new AssertionError("not asked"); }
                @Override public double nextGaussian() { throw new AssertionError(); }
            };

            Person(int id, String team, double x, double z) {
                this.id = id; this.team = team; this.x = x; this.z = z;
                body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
                    case "id" -> id;
                    case "team" -> this.team;
                    case "x" -> this.x;
                    case "y" -> y;
                    case "z" -> this.z;
                    case "yRot" -> yaw;
                    case "xRot" -> pitch;
                    case "stance" -> Stance.STAND;
                    case "yHeadRot" -> head;
                    case "blockY" -> acup.gunfeel.GameMath.floor(y);
                    case "alive" -> alive;
                    case "removed" -> removed;
                    case "living" -> true;
                    case "player" -> player;
                    case "spectator" -> spectator;
                    case "creative", "tagged" -> false;
                    case "health", "maxHealth" -> 20F;
                    case "lastHurtBy" -> hurt;
                    case "killCredit" -> credit;
                    case "level" -> level;
                    case "random" -> ownRandom;
                    case "eye" -> { double[] out = (double[]) a[0]; out[0] = this.x; out[1] = y + 1.6; out[2] = this.z; yield null; }
                    default -> throw new AssertionError("unexpected body call: " + m.getName());
                });
                people.add(this);
            }
        }

        final class Bot extends AiBotBase {
            final Person person;
            AiCommanderBase.Order<Bot, Task> order;
            int capacity = 30;
            float suppressed;
            final AiGunnerBase gun;
            final AiProneBase prone;
            final Walk walk;

            Bot(Person person) {
                super(person.body, SensesTest.P);
                this.person = person;
                walk = new Walk(body);
                gun = new AiGunnerBase(body, new AiAmmo(120)) {
                    @Override public int capacity() { return capacity; }
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
                prone = new AiProneBase(body, () -> true) {
                    @Override public float suppressed() { return suppressed; }
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
            @Override public boolean grenadier() { return false; }
            @Override public Object serverLevel() { return level; }
            @Override public acup.gunfeel.host.WorldView world() { return world; }
            @Override public AiMoverBase mover() { return walk; }
            @Override public AiGunnerBase gunner() { return gun; }
            @Override public AiProneBase prone() { return prone; }
            @Override public AiStoopBase stoop() { throw new AssertionError(); }
            @Override public AiDashBase dash() { throw new AssertionError(); }
            @Override void gesture(byte kind, int ticks, long tick) { super.gesture(kind, ticks, tick); events.add("couple"); }
        }

        final class Walk extends AiMoverBase {
            long goal = Long.MIN_VALUE;
            boolean arrived, failed;
            double radius, speed;
            PathSearch.Cost cost;
            Walk(Body b) { super(b); }
            @Override public long goal() { return goal; }
            @Override public boolean arrived() { return arrived; }
            @Override public boolean failed() { return failed; }
            @Override public void stop() { goal = Long.MIN_VALUE; events.add("stop"); }
            @Override public void costs(PathSearch.Cost c) {
                cost = c;
                if (traceStep) {
                    assertEquals(Double.MAX_VALUE, stepping.order.legD);
                    assertEquals(now, stepping.order.moveAt);
                    events.add("leg");
                }
            }
            @Override public void goTo(long c, double r, double s) { goal = c; radius = r; speed = s; events.add("go"); }
        }

        final class Task extends AiCommanderBase.Job<Bot, Task> {
            Task(StaffPlan.Kind kind, long since, List<Bot> bots, int[] ids) { super(kind, since, bots, ids); }
        }

        final AiCommanderBase.Staff<Bot, Task, AiCommanderBase.Order<Bot, Task>> staff = new AiCommanderBase.Staff<>() {
            @Override public AiCommanderBase.Order<Bot, Task> order(Bot b) {
                if (traceStep && b != stepping && stepping.order.fails == AiCommanderBase.FAILS - 1) events.add("mateIn");
                return b.order;
            }
            @Override public void order(Bot b, AiCommanderBase.Order<Bot, Task> o) { b.order = o; }
            @Override public AiCommanderBase.Order<Bot, Task> order() { return new AiCommanderBase.Order<>(); }
            @Override public Task job(StaffPlan.Kind kind, long since, List<Bot> bots, int[] ids) { return new Task(kind, since, bots, ids); }
            @Override public AiSquadsBase.Squad<Bot> squad() { return new AiSquadsBase.Squad<>(); }
            @Override public HqGrid hq(Object l, String team) { assertSame(level, l); if (traceStep) events.add("tail"); return grid; }
            @Override public void hqReport(Object l, String team, List<Bot> own, List<AiIntelBase.Group> groups) { events.add("hq"); }
            @Override public void report(Object l, String team, AiReportsBase.Report r) { reports.add(r); events.add("look"); }
            @Override public long intel(Object l, java.util.Map<String, List<Bot>> own, long tick) { events.add("intel"); return tick + 500; }
            @Override public void lost(Object l, String team, int id, long tick) { AiIntelBase.lostTo(l, team, id, tick); }
            @Override public double radius() { return 256; }
            @Override public boolean supplyOn() { return supply; }
            @Override public List<Bot> moveSquad(Task j) { return j.members; }
            @Override public void moveAfresh(Task j) { events.add("afresh"); }
            @Override public boolean inCrossing(Bot b) { return false; }
            @Override public boolean terrainOn() { return true; }
            @Override public acup.gunfeel.host.WorldView world(Object l) { assertSame(level, l); return world; }
            @Override public acup.gunfeel.host.EntityView entities(Object l) { assertSame(level, l); return entities; }
            @Override public acup.gunfeel.water.LiquidPath.Fluids fluids(Object l) { assertSame(level, l); return fluids; }
            @Override public TacticalMapBase map(Object l) { assertSame(level, l); mapCalls++; return terrain; }
            @Override public VoxelRay.Source voxels(Object l) { throw new AssertionError(); }
            @Override public java.util.function.LongPredicate heldByOthers(Bot b) { throw new AssertionError(); }
            @Override public void aim(Bot b, acup.gunfeel.Vec3d at) { aimed = at; events.add("aim"); }
            @Override public void crouch(Bot b, boolean on) { crouched = on; events.add("crouch"); }
            @Override public double speed(StaffMoveBase.Pace p) { return switch (p) { case WALK -> 1; case MARCH -> 1.5; case RUN -> 2; case CREEP -> 0.5; }; }
            @Override public void held(Bot b, long tick) { heldAt = tick; events.add("held"); }
            @Override public boolean movingHeld(Bot b) { throw new AssertionError(); }
        };

        @BeforeEach
        void registry() { AiBotsBase.LEVELS.put(level, registry); }

        @AfterEach
        void forget() {
            AiCommanderBase.forgetLevel(level);
            AiIntelBase.forgetLevel(level);
            AiSquadsBase.forgetLevel(level);
            AiBotsBase.LEVELS.remove(level);
            AiCommanderBase.on = AiCommanderBase.tasks = AiCommanderBase.staff = AiCommanderBase.pairs = AiCommanderBase.help = true;
            AiCommanderBase.snap = AiCommanderBase.unstuck = StaffMoveBase.on = true;
        }

        Bot bot(int id, String team, double x, double z) {
            Bot b = new Bot(new Person(id, team, x, z));
            registry.bots.put(id, b);
            return b;
        }

        AiSquadsBase.Squad<Bot> squad(Bot... bots) {
            AiSquadsBase.Squad<Bot> s = staff.squad();
            s.bots.addAll(List.of(bots));
            return s;
        }

        void squads() {
            AiSquadsBase.make(new AiSquadsBase.Staff<Bot>() {
                @Override public AiSquadsBase.Squad<Bot> squad() { return staff.squad(); }
                @Override public void supply(List<Bot> bs, long tick) { }
                @Override public boolean leashed(Bot b, int id, long tick) { return false; }
                @Override public boolean presses(Bot b, long tick) { return false; }
            }, level, world, registry.bots.values());
        }

        AiIntelBase.Group group(int id, double x, double z) {
            IntelBook.Group k = new IntelBook.Group(new int[] {id}, AiRole.values().length);
            k.x = x; k.z = z; k.r = 8; k.awake = true; k.seen = now;
            k.kinds[0] = 2; k.health = 2;
            return new AiIntelBase.Group(List.of(new Person(id, "b", x, z).body), k);
        }

        List<AiIntelBase.Group> intel(Bot b) {
            AiIntelBase.tickSides(level, entities, new AiIntelBase.Scout() {
                @Override public double radius() { return 256; }
                @Override public boolean allAwake() { return true; }
            }, java.util.Map.of("a", List.of(b)), now);
            return AiIntelBase.groupsOf(level, "a");
        }

        AiCommanderBase.Order<Bot, Task> order(Bot b, StaffPlan.Kind kind, double x, double z, int... ids) {
            b.order = AiCommanderBase.freshOrder(staff, now);
            b.order.job = new Task(kind, now, List.of(b), ids);
            b.order.x = x; b.order.z = z; b.order.r = 2; b.order.ids = ids; b.order.until = now + 500;
            b.order.of = 1;
            return b.order;
        }

        @SuppressWarnings("unchecked")
        <V> acup.gunfeel.LevelMap<V> map(Class<?> type, String name) throws Exception {
            var f = type.getDeclaredField(name); f.setAccessible(true);
            return (acup.gunfeel.LevelMap<V>) f.get(null);
        }

        AiCommanderBase.Side side() throws Exception {
            var map = this.<java.util.Map<String, AiCommanderBase.Side>>map(AiCommanderBase.class, "LEVELS");
            return map.computeIfAbsent(level, k -> new java.util.HashMap<>()).computeIfAbsent("a", k -> new AiCommanderBase.Side());
        }

        @Test
        void stepRejectsMissingExpiredAndDisabledOrdersBeforeFire() {
            Bot b = bot(1, "a", 0, 0);
            b.gun.wantFire = true;
            assertFalse(AiCommanderBase.stepOrder(staff, b, level, now));
            var o = order(b, StaffPlan.Kind.HOLD, 0, 0);
            o.until = now - 1;
            assertFalse(AiCommanderBase.stepOrder(staff, b, level, now));
            o.until = now;
            AiCommanderBase.on = false;
            assertFalse(AiCommanderBase.stepOrder(staff, b, level, now));
            assertTrue(b.gun.wantFire);
            assertTrue(events.isEmpty());
        }

        @Test
        void stepChecksMateBeforeTailLegAndGo() {
            Bot b = bot(1, "a", 0, 0), mate = bot(2, "a", 2, 0);
            var o = order(b, StaffPlan.Kind.ADVANCE, 80, 0);
            var m = order(mate, StaffPlan.Kind.ADVANCE, 80, 0);
            m.lastInside = now;
            o.fails = AiCommanderBase.FAILS - 1;
            o.failedAt = 80;
            o.noRoute = true;
            o.job.move.mode = StaffMoveBase.Mode.MARCH;
            o.job.move.next = Long.MAX_VALUE;
            b.walk.failed = true;
            b.gun.wantFire = true;
            grid = HqGrid.around(0, 0);
            java.util.Arrays.fill(grid.known, true);
            stepping = b; traceStep = true;
            assertTrue(AiCommanderBase.stepOrder(staff, b, level, now));
            assertFalse(b.gun.wantFire);
            assertEquals(List.of("mateIn", "tail", "leg", "go", "aim"), events);
            assertTrue(o.onTail);
            assertSame(o.job.tail, o.route);
            assertEquals(AiCommanderBase.FAILS - 1, o.fails);
            assertEquals(now, o.moveAt);
            assertFalse(o.moveHold);
            assertTrue(o.moveLeg);
            assertEquals(now + AiCommanderBase.LEG_AGAIN, o.nextLeg);
            assertEquals(1, o.at);
            assertEquals(acup.gunfeel.PosLong.block(53, 64, 0), b.walk.goal);
            assertEquals(1.5, b.walk.speed);
        }

        @Test
        void stepStraightLegAndFailedGoKeepTheirOrder() {
            Bot b = bot(1, "a", 3, 4);
            var o = order(b, StaffPlan.Kind.ADVANCE, 103, 4);
            o.job = null;
            b.gun.wantFire = true;
            emptyCells = true;
            assertTrue(AiCommanderBase.stepOrder(staff, b, level, now));
            assertFalse(b.gun.wantFire);
            assertEquals(27, o.legTX, 1e-12);
            assertEquals(4, o.legTZ, 1e-12);
            assertEquals(24, o.legD, 1e-12);
            assertEquals(1, o.fails);
            assertEquals(now + 1, o.nextLeg);
            assertEquals(Long.MIN_VALUE, b.walk.goal);
            assertTrue(events.isEmpty());
            emptyCells = false;
            o.fails = 0; o.nextLeg = now;
            assertTrue(AiCommanderBase.stepOrder(staff, b, level, now));
            assertEquals(List.of("go", "aim"), events);
            assertEquals(1, b.walk.speed);
        }

        @Test
        void stepUsesEntityRandomForAreaSearchAndRecordsFailedPlace() {
            Bot b = bot(1, "a", 10, 20);
            b.person.numbers = new double[] {0.25, 0.5625};
            var o = order(b, StaffPlan.Kind.ADVANCE, 10, 20);
            o.job = null; o.r = 8;
            b.walk.failed = true;
            b.walk.goal = acup.gunfeel.PosLong.block(-2, 70, 9);
            b.gun.wantFire = true;
            assertTrue(AiCommanderBase.stepOrder(staff, b, level, now));
            assertFalse(b.gun.wantFire);
            assertEquals(2, b.person.randomDraws);
            assertEquals(0, draws);
            assertEquals(new acup.gunfeel.Vec3d(10, 65.5, 26), aimed);
            assertEquals(acup.gunfeel.PosLong.block(10, 64, 26), b.walk.goal);
            assertArrayEquals(new double[] {-1.5, 9.5}, o.badPlaces.get(0));
            assertEquals(1, o.inside);
            assertEquals(now, o.lastInside);
            assertEquals(now + AiCommanderBase.LOOK_AGAIN, o.nextLeg);
            assertEquals(o.nextLeg, o.lookUntil);
        }

        @Test
        void stepRetriesRandomInPairsAndFallsBackToRoomMiddle() {
            Bot b = bot(1, "a", 0, 0);
            var o = order(b, StaffPlan.Kind.SCOUT, 0, 0);
            o.job = null; o.r = 8;
            o.roomX = o.roomZ = 0;
            o.room = 0;
            var cells = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            cells.defaultReturnValue(AiRooms.OUTSIDE);
            o.rooms = new AiRooms.Plan(new int[0], new long[0], new AiRooms.Way[0], false, cells, new byte[0], new long[0], new long[0], new AiRooms.Window[0]);
            b.person.numbers = new double[2 * AiCommanderBase.PLACE_TRIES];
            java.util.Arrays.fill(b.person.numbers, 0.25);
            assertTrue(AiCommanderBase.stepOrder(staff, b, level, now));
            assertEquals(2 * AiCommanderBase.PLACE_TRIES, b.person.randomDraws);
            assertEquals(new acup.gunfeel.Vec3d(0, 65.5, 0), aimed);
        }

        @Test
        void stepAtPlaceStopsOnceFacesAndKeepsHysteresis() {
            Bot b = bot(1, "a", 2, 0);
            var o = order(b, StaffPlan.Kind.HOLD, 0, 0);
            o.job.faceX = 20; o.job.faceZ = 3;
            assertTrue(AiCommanderBase.stepOrder(staff, b, level, now));
            assertTrue(o.job.there);
            assertTrue(o.stood);
            assertEquals(List.of("stop", "aim"), events);
            assertEquals(new acup.gunfeel.Vec3d(20, 65.6, 3), aimed);
            events.clear();
            b.person.x = 4;
            assertTrue(AiCommanderBase.stepOrder(staff, b, level, ++now));
            assertEquals(List.of("aim"), events);
            assertEquals(0, o.inside);
            assertEquals(0, b.person.randomDraws);
            o.job.faceX = 4; o.job.faceZ = 0;
            assertTrue(AiCommanderBase.stepOrder(staff, b, level, ++now));
            assertNull(aimed);
        }

        @Test
        void stepGivesUpBeforeTailWhenNoMateIsInside() {
            Bot b = bot(1, "a", 0, 0);
            var o = order(b, StaffPlan.Kind.ADVANCE, 80, 0);
            o.job = null; o.fails = AiCommanderBase.FAILS - 1;
            o.failedAt = 80;
            b.walk.failed = true;
            assertFalse(AiCommanderBase.stepOrder(staff, b, level, now));
            assertNull(b.order);
            assertEquals(now + AiCommanderBase.AVOID, b.orderFailedUntil);
            assertTrue(events.isEmpty());
        }

        @Test
        void legHoldsCrouchesStopsAndFacesWithoutGoing() {
            Bot b = bot(1, "a", 0, 0);
            var o = order(b, StaffPlan.Kind.ADVANCE, 80, 0);
            o.job.move.foe = true; o.job.move.foeX = 50; o.job.move.foeZ = 2;
            o.legD = 3;
            b.walk.goal = 123;
            AiCommanderBase.walkLeg(staff, b, level, o, new StaffMoveBase.Leg(3, 4, 123, 2, true, true, true, 7), false, false, now);
            assertEquals(List.of("crouch", "held", "stop", "aim"), events);
            assertTrue(crouched);
            assertEquals(now, heldAt);
            assertEquals(Double.MAX_VALUE, o.legD);
            assertEquals(Long.MIN_VALUE, b.walk.goal);
            assertEquals(new acup.gunfeel.Vec3d(50, 65.6, 2), aimed);
            assertFalse(o.moveLeg);
            assertEquals(Long.MIN_VALUE, o.nextLeg);
        }

        @Test
        void legUsesCellHeightRadiusPaceAndFreshness() {
            Bot b = bot(1, "a", 0, 0);
            var o = order(b, StaffPlan.Kind.ADVANCE, 80, 0);
            long c = acup.gunfeel.PosLong.block(-3, -12, 9);
            o.fails = 4; b.walk.arrived = true;
            var leg = new StaffMoveBase.Leg(-2.5, 9.5, c, 0.5, false, true, true, 7);
            AiCommanderBase.walkLeg(staff, b, level, o, leg, false, false, now);
            assertEquals(List.of("crouch", "go", "aim"), events);
            assertEquals(0, o.fails);
            assertEquals(now + 7, o.nextLeg);
            assertEquals(-2.5, o.legX); assertEquals(9.5, o.legZ);
            assertTrue(o.moveLeg);
            assertEquals(c, b.walk.goal);
            assertEquals(0.35, b.walk.radius);
            assertEquals(0.5, b.walk.speed);
            assertSame(PathSearch.Cost.NONE, b.walk.cost);
            assertEquals(new acup.gunfeel.Vec3d(-2.5, -10.5, 9.5), aimed);
            events.clear();
            leg = new StaffMoveBase.Leg(-2.5, 9.5, c, 0.5, false, false, false, 7);
            AiCommanderBase.walkLeg(staff, b, level, o, leg, false, false, now);
            assertTrue(events.isEmpty());
            b.person.alive = false;
            AiCommanderBase.walkLeg(staff, b, level, o, leg, true, false, now);
            assertEquals(List.of("go"), events);
        }

        @Test
        void legWithoutCellRetriesFailedStandSoon() {
            Bot b = bot(1, "a", 0, 0);
            var o = order(b, StaffPlan.Kind.ADVANCE, 80, 0);
            emptyCells = true;
            AiCommanderBase.walkLeg(staff, b, level, o, new StaffMoveBase.Leg(3, 4, Long.MIN_VALUE, 2, false, false, true, 7), false, false, now);
            assertEquals(now + 1, o.nextLeg);
            assertTrue(o.moveLeg);
            assertEquals(Long.MIN_VALUE, b.walk.goal);
        }

        @Test
        void goUnloadedUsesOwnHeightAndFloorsNegativeCoordinates() {
            Bot b = bot(1, "a", 0, 0);
            var o = order(b, StaffPlan.Kind.ADVANCE, 80, 0);
            o.moveLeg = true;
            loaded = false; b.person.y = -2.2; top = 99;
            assertTrue(AiCommanderBase.goGround(staff, b, level, -16.2, -0.1, 1.75));
            assertEquals(acup.gunfeel.PosLong.block(-17, -3, -1), b.walk.goal);
            assertEquals(2, b.walk.radius); assertEquals(1.75, b.walk.speed);
            assertSame(PathSearch.Cost.NONE, b.walk.cost);
            assertFalse(o.moveLeg);
            assertEquals(new acup.gunfeel.Vec3d(-16.2, -1.5, -0.1), aimed);
            assertEquals(0, mapCalls);
        }

        @Test
        void goLoadedUsesHeightOrStandAndLeavesFailureUntouched() {
            Bot b = bot(1, "a", 0, 0);
            var o = order(b, StaffPlan.Kind.ADVANCE, 80, 0);
            AiCommanderBase.snap = false; top = 73;
            assertTrue(AiCommanderBase.goGround(staff, b, level, 8.2, 9.3, 1));
            assertEquals(acup.gunfeel.PosLong.block(8, 73, 9), b.walk.goal);
            assertEquals(new acup.gunfeel.Vec3d(8.2, 74.5, 9.3), aimed);
            assertEquals(0, mapCalls);
            AiCommanderBase.snap = true;
            assertTrue(AiCommanderBase.goGround(staff, b, level, 8.2, 9.3, 2));
            assertEquals(1, mapCalls);
            emptyCells = true; o.moveLeg = true;
            events.clear(); aimed = null;
            assertFalse(AiCommanderBase.goGround(staff, b, level, 1, 2, 3));
            assertTrue(events.isEmpty()); assertNull(aimed);
            assertTrue(o.moveLeg);
            assertEquals(acup.gunfeel.PosLong.block(8, 73, 9), b.walk.goal);
            assertEquals(2, b.walk.speed);
            emptyCells = false; b.person.alive = false; b.order = null;
            assertTrue(AiCommanderBase.goGround(staff, b, level, 1, 2, 3));
            assertEquals(List.of("go"), events);
            assertNull(aimed);
        }

        @Test
        void mateInsideUsesRecencyTeamLifeAndDestination() {
            Bot b = bot(1, "a", 0, 0), m = bot(2, "a", 10, 0);
            var o = order(b, StaffPlan.Kind.ADVANCE, 80, 0);
            var other = order(m, StaffPlan.Kind.HOLD, 81, -1);
            assertFalse(AiCommanderBase.mateInside(staff, b, o, now));
            other.lastInside = now - AiCommanderBase.MATE_IN;
            assertTrue(AiCommanderBase.mateInside(staff, b, o, now));
            assertFalse(AiCommanderBase.mateInside(staff, b, o, now + 1));
            other.lastInside = now;
            m.person.team = "b"; assertFalse(AiCommanderBase.mateInside(staff, b, o, now));
            m.person.team = "a"; m.active = false; assertFalse(AiCommanderBase.mateInside(staff, b, o, now));
            m.active = true; m.person.alive = false; assertFalse(AiCommanderBase.mateInside(staff, b, o, now));
            m.person.alive = true; m.person.removed = true; assertFalse(AiCommanderBase.mateInside(staff, b, o, now));
            m.person.removed = false; other.x = 81.001; assertFalse(AiCommanderBase.mateInside(staff, b, o, now));
            other.x = 80; other.z = -1.001; assertFalse(AiCommanderBase.mateInside(staff, b, o, now));
            m.order = null; assertFalse(AiCommanderBase.mateInside(staff, b, o, now));
            o.lastInside = now - AiCommanderBase.MATE_IN;
            assertTrue(AiCommanderBase.mateInside(staff, b, o, now));
            assertFalse(AiCommanderBase.mateInside(staff, b, o, now + 1));
        }

        @Test
        void roomRefreshesOnlyAfterMovingAndBadUsesStrictDistance() {
            Bot b = bot(1, "a", 0, 0);
            var o = order(b, StaffPlan.Kind.SCOUT, -0.1, 2.9);
            b.person.y = 63.9;
            AiCommanderBase.findRoom(staff, o, b, level);
            assertEquals(-0.1, o.roomX); assertEquals(2.9, o.roomZ);
            assertEquals(64, o.roomY); assertNull(o.rooms); assertEquals(AiRooms.OUTSIDE, o.room);
            assertEquals(1, mapCalls);
            assertTrue(AiCommanderBase.insideRoom(staff, o, 100, 200));
            var cells = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            cells.defaultReturnValue(AiRooms.OUTSIDE);
            cells.put(acup.gunfeel.PosLong.block(-1, 64, 2), 3);
            o.rooms = new AiRooms.Plan(new int[0], new long[0], new AiRooms.Way[0], false, cells, new byte[0], new long[0], new long[0], new AiRooms.Window[0]);
            o.room = 3;
            assertTrue(AiCommanderBase.insideRoom(staff, o, -0.01, 2.99));
            assertFalse(AiCommanderBase.insideRoom(staff, o, 0, 2.99));
            o.x += 1;
            AiCommanderBase.findRoom(staff, o, b, level);
            assertEquals(1, mapCalls); assertEquals(3, o.room);
            o.x += 0.01;
            AiCommanderBase.findRoom(staff, o, b, level);
            assertEquals(2, mapCalls); assertNull(o.rooms); assertEquals(AiRooms.OUTSIDE, o.room);
            o.badPlaces.add(new double[] {10, 20});
            assertTrue(AiCommanderBase.badPlace(staff, o, 10, 20));
            assertTrue(AiCommanderBase.badPlace(staff, o, 10 + AiCommanderBase.BAD_NEAR - 0.001, 20));
            assertFalse(AiCommanderBase.badPlace(staff, o, 10 + AiCommanderBase.BAD_NEAR, 20));
        }

        @Test
        void roomFindsRoofedCellsAtTheBodyHeight() {
            Bot b = bot(1, "a", 5, 5);
            var o = order(b, StaffPlan.Kind.SCOUT, 5.5, 5.5);
            short[] positions = new short[16], flags = new short[16], columns = new short[257];
            int n = 0;
            for (int col = 0; col < 256; col++) {
                columns[col] = (short) n;
                int x = col & 15, z = col >> 4;
                if (x >= 4 && x <= 7 && z >= 4 && z <= 7) {
                    positions[n] = (short) col;
                    flags[n++] = SectionCells.ROOF;
                }
            }
            columns[256] = (short) n;
            long key = acup.gunfeel.PosLong.section(0, 4, 0);
            sections.put(key, new SectionCells(key, 1, positions, new int[16], flags, columns, 0));
            boolean shell = AiRooms.shell;
            try {
                AiRooms.shell = false;
                AiCommanderBase.findRoom(staff, o, b, level);
                org.junit.jupiter.api.Assertions.assertNotNull(o.rooms);
                assertTrue(o.room >= 0);
                assertTrue(AiCommanderBase.insideRoom(staff, o, 6.5, 6.5));
                assertFalse(AiCommanderBase.insideRoom(staff, o, 8, 6.5));
            } finally {
                AiRooms.shell = shell;
            }
        }

        @Test
        void tailUsesSquadMeanAndEvenPointsAndReusesAtBoundary() {
            Bot b = bot(1, "a", 2, 4), m = bot(2, "a", 6, 8);
            var o = order(b, StaffPlan.Kind.ADVANCE, 84, 66);
            order(m, StaffPlan.Kind.ADVANCE, 84, 66).job = o.job;
            o.job.members = List.of(b, m);
            grid = HqGrid.around(0, 0); java.util.Arrays.fill(grid.known, true);
            int made = AiCommanderBase.tails;
            var t = AiCommanderBase.tailRoute(staff, level, o.job, o, now);
            org.junit.jupiter.api.Assertions.assertNotNull(t);
            assertEquals(4, t.fromX()); assertEquals(6, t.fromZ());
            assertEquals(84, t.toX()); assertEquals(66, t.toZ());
            assertEquals(100, t.length()); assertEquals(4, t.size());
            for (int k = 0; k < 4; k++) {
                assertEquals(4 + 20 * (k + 1), t.x(k));
                assertEquals(6 + 15 * (k + 1), t.z(k));
            }
            assertEquals(made + 1, AiCommanderBase.tails);
            o.x += AiCommanderBase.REROUTE;
            grid = null;
            assertSame(t, AiCommanderBase.tailRoute(staff, level, o.job, o, now));
            o.x += 0.001;
            assertNull(AiCommanderBase.tailRoute(staff, level, o.job, o, now));
            assertNull(o.job.tail); assertEquals(now, o.job.tailNot);
        }

        @Test
        void tailFailureCooldownAndEmptySquadHaveNoExtraAttempts() {
            Bot b = bot(1, "a", 0, 0);
            var o = order(b, StaffPlan.Kind.ADVANCE, 80, 0);
            int missed = AiCommanderBase.tailsNot;
            assertNull(AiCommanderBase.tailRoute(staff, level, o.job, o, now));
            assertEquals(missed + 1, AiCommanderBase.tailsNot);
            grid = HqGrid.around(0, 0); java.util.Arrays.fill(grid.known, true);
            assertNull(AiCommanderBase.tailRoute(staff, level, o.job, o, now + AiCommanderBase.TAIL_AGAIN - 1));
            org.junit.jupiter.api.Assertions.assertNotNull(AiCommanderBase.tailRoute(staff, level, o.job, o, now + AiCommanderBase.TAIL_AGAIN));
            o.job.tail = null; o.job.tailNot = Long.MIN_VALUE; o.job.members = List.of();
            assertNull(AiCommanderBase.tailRoute(staff, level, o.job, o, now));
            assertEquals(Long.MIN_VALUE, o.job.tailNot);
            assertEquals(missed + 1, AiCommanderBase.tailsNot);
            o.job.members = List.of(b);
            grid.deep[HqGrid.index(grid.cellX(40), grid.cellZ(0))] = 1;
            assertNull(AiCommanderBase.tailRoute(staff, level, o.job, o, now));
            assertEquals(missed + 2, AiCommanderBase.tailsNot);
        }

        @Test
        void commandNearestPerReserveAndHelp() throws Exception {
            AiCommanderBase.tasks = false;
            Bot a = bot(1, "a", 0, 0), foe = bot(80, "b", 60, 0);
            AiIntelBase.Group g = intel(a).get(0);
            g.known().x = 60; g.known().z = 0;
            List<AiSquadsBase.Squad<Bot>> own = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) own.add(squad(bot(10 + i, "a", i * 2, 0)));
            AiCommanderBase.commandSide(staff, level, "a", side(), own, now);
            assertEquals(2, own.stream().filter(s -> s.bots.get(0).order != null).count());
            assertNull(own.get(2).bots.get(0).order);
            assertNull(own.get(3).bots.get(0).order);
            g.known().lost = now;
            AiCommanderBase.commandSide(staff, level, "a", side(), own, now + 1);
            assertEquals(3, own.stream().filter(s -> s.bots.get(0).order != null).count());
            assertNull(own.get(3).bots.get(0).order);
            // The nearest of two groups is chosen, independent of report order.
            bot(81, "b", -100, 0);
            AiIntelBase.forgetLevel(level);
            intel(a);
            AiSquadsBase.Squad<Bot> fresh = squad(bot(30, "a", 0, 0));
            AiCommanderBase.commandSide(staff, level, "a", side(), List.of(fresh), now + 2);
            assertTrue(fresh.bots.get(0).order.x > 0);
            assertSame(foe, registry.bots.get(80));
        }

        @Test
        void tasksKeepJobThenReplaceWhenKindChanges() throws Exception {
            Bot a = bot(1, "a", 0, 0), b = bot(2, "a", 1, 0);
            var s = squad(a, b);
            List<AiIntelBase.Group> groups = new java.util.ArrayList<>();
            AiCommanderBase.planTasks(staff, level, "a", side(), List.of(s), groups, now);
            Task first = a.order.job;
            assertSame(first, b.order.job);
            AiCommanderBase.planTasks(staff, level, "a", side(), List.of(s), groups, now + 1);
            assertSame(first, a.order.job);
            var g = group(70, 80, 0);
            var t = new StaffPlan.Task(StaffPlan.Kind.HOLD, 0, 15, 0, 3, 80, 0);
            AiCommanderBase.assignTask(staff, level, "a", side(), s, first, t, g, now + 2);
            Task second = a.order.job;
            assertNotSame(first, second);
            AiCommanderBase.assignTask(staff, level, "a", side(), s, second, t, g, now + 3);
            assertSame(second, a.order.job);
            assertEquals(now + 2, second.since);
        }

        @Test
        void giveKeepsOrdersAndLimitsWaysToThree() throws Exception {
            AiCommanderBase.tasks = false;
            Bot a = bot(1, "a", 0, 0);
            for (int i = 0; i < 4; i++) bot(70 + i, "b", 60 + i * 35, 0);
            List<AiIntelBase.Group> foes = intel(a);
            grid = HqGrid.around(0, 0);
            for (int i = 0; i < HqGrid.N; i++) for (int k = 0; k < HqGrid.N; k++) grid.set(i, k, 64, 0, 0, 0);
            List<AiSquadsBase.Squad<Bot>> own = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) own.add(squad(bot(10 + i, "a", i, 0)));
            AiCommanderBase.commandSide(staff, level, "a", side(), own, now);
            assertEquals(0, AiCommanderBase.waysLeft);
            for (int i = 0; i < 3; i++) assertTrue(own.get(i).bots.get(0).order.way != null);
            assertNull(own.get(3).bots.get(0).order.way);
            var o = own.get(0).bots.get(0).order;
            o.inside = 17;
            AiIntelBase.Group g = foes.stream().filter(v -> v.any(o.ids)).findFirst().orElseThrow();
            AiCommanderBase.giveArea(staff, level, "a", own.get(0), g, now + 1, false);
            assertSame(o, own.get(0).bots.get(0).order);
            assertEquals(17, o.inside);
        }

        @Test
        void giveAreaSetsExactDeadline() {
            Bot a = bot(1, "a", 0, 0), b = bot(2, "a", 1, 0);
            var s = squad(a, b);
            var g = group(70, 80, 0);
            AiCommanderBase.giveArea(staff, level, "a", s, g, now, true);
            assertEquals(now + AiCommanderBase.LASTS, a.order.until);
            assertEquals(now + AiCommanderBase.LASTS, b.order.until);
            var kept = a.order;
            AiCommanderBase.giveArea(staff, level, "a", s, g, now + 1, false);
            assertSame(kept, a.order);
            assertEquals(now + 1 + AiCommanderBase.LASTS, a.order.until);
            assertEquals(now + 1 + AiCommanderBase.LASTS, b.order.until);
        }

        @Test
        void straysFollowAwakeSquadsOnly() {
            Bot stray = bot(1, "a", 0, 0), asleep = bot(2, "a", 2, 0), awake = bot(3, "a", 20, 0);
            order(stray, StaffPlan.Kind.ADVANCE, 80, 0, 7);
            order(asleep, StaffPlan.Kind.HOLD, 40, 0, 8);
            order(awake, StaffPlan.Kind.PIN, 60, 0, 9);
            asleep.active = false;
            AiCommanderBase.gatherStrays(staff, level, "a", List.of(squad(asleep), squad(awake)), now);
            assertSame(awake.order.job, stray.order.job);
            var kept = stray.order;
            AiCommanderBase.gatherStrays(staff, level, "a", List.of(squad(awake)), now + 1);
            assertSame(kept, stray.order);
        }

        @Test
        void jobUsesHalfOrMoreAndGroupOrderBreaksTies() {
            Bot a = bot(1, "a", 0, 0), b = bot(2, "a", 1, 0), c = bot(3, "a", 2, 0);
            order(a, StaffPlan.Kind.HOLD, 50, 0, 7);
            order(b, StaffPlan.Kind.PIN, 60, 0, 8);
            assertNull(AiCommanderBase.squadJob(staff, squad(a, b, c), now));
            c.order = b.order;
            assertSame(b.order.job, AiCommanderBase.squadJob(staff, squad(a, b, c), now));
            var g = group(7, 50, 0); var h = group(8, 60, 0);
            assertSame(h, AiCommanderBase.goingTo(staff, squad(a, b, c), List.of(g, h), now));
            assertSame(g, AiCommanderBase.goingTo(staff, squad(a, b), List.of(g, h), now));
            var s = squad(a, b); s.target = 8;
            assertSame(h, AiCommanderBase.countedTo(staff, s, List.of(g, h), now));
            a.orderFailed = new int[] {7}; a.orderFailedUntil = now + 1;
            assertTrue(AiCommanderBase.squadFailed(staff, s, g, now));
            assertFalse(AiCommanderBase.squadFailed(staff, s, g, now + 1));
        }

        @Test
        void farGrowsByNearToMaxAndKeepsTeams() {
            Person p = new Person(90, "", 0, 0); p.player = true;
            for (int i = 0; i < 8; i++) bot(i + 1, "a", i * 15, 0).active = false;
            bot(30, "b", 0, 0).active = false;
            bot(31, "", 0, 0).active = false;
            int at = 0;
            for (Bot b : registry.bots.values()) if (b.person.team.equals("a")) b.person.x = at++ * 15;
            var far = AiCommanderBase.farSquads(staff, level);
            assertEquals(3, far.size());
            assertEquals(6, far.stream().mapToInt(s -> s.bots.size()).max().orElseThrow());
            assertEquals(9, far.stream().mapToInt(s -> s.bots.size()).sum());
            for (var s : far) assertEquals(1, s.bots.stream().map(b -> b.person.team).distinct().count());
            p.spectator = true;
            assertTrue(AiCommanderBase.farSquads(staff, level).isEmpty());
            p.spectator = false; p.y = 1000;
            assertTrue(AiCommanderBase.farSquads(staff, level).isEmpty());
        }

        void drift() { AiCommanderBase.driftBots(staff, level, now, control); }

        @Test
        void driftStepUnloadedClimbAndThere() {
            Bot b = bot(1, "a", .5, .5); b.active = false;
            order(b, StaffPlan.Kind.HOLD, 100, .5, 7);
            drift();
            assertEquals(4.5, b.body.x()); assertEquals(64, b.body.y()); assertEquals(.5, b.body.z());
            assertEquals(b.body.yRot(), b.body.yHeadRot());
            loaded = false; drift(); assertEquals(4.5, b.body.x()); assertEquals(0, b.order.fails);
            loaded = true; top = 68; drift(); assertEquals(1, b.order.fails);
            b.order.x = b.body.x(); b.order.z = b.body.z(); drift();
            assertTrue(b.order.job.there); assertEquals(0, b.order.fails);
        }

        @Test
        void driftStepSetsExactYaw() {
            Bot b = bot(1, "a", .5, .5); b.active = false;
            order(b, StaffPlan.Kind.HOLD, 100.5, 75.5, 7);
            drift();
            assertEquals(List.of("drift"), events);
            assertEquals(-53.130104F, b.body.yRot());
            assertEquals(b.body.yRot(), b.body.yHeadRot());
        }

        @Test
        void driftStopsAtExactlyDriftStop() {
            Bot b = bot(1, "a", .5, .5), enemy = bot(70, "b", 48.5, .5);
            AiIntelBase.Group g = intel(b).get(0);
            g.known().x = 48.5; g.known().z = .5;
            b.active = false;
            order(b, StaffPlan.Kind.ADVANCE, 150, .5, enemy.body.id());
            drift();
            assertEquals(.5, b.body.x());
            g.known().x = 48.5001;
            drift(); assertEquals(4.5, b.body.x());
        }

        void wet(int x, int y, int z, acup.gunfeel.water.LiquidEvents.Liquid liquid) {
            liquids.put(acup.gunfeel.PosLong.block(x, y, z), new acup.gunfeel.water.LiquidPath.Cell(liquid, 1F, false));
        }

        @Test
        void driftWadesOneBlockButDetoursAroundDeepWater() {
            Bot b = bot(1, "a", .5, .5); b.active = false;
            order(b, StaffPlan.Kind.ADVANCE, 100, .5, 7);
            wet(4, 63, 0, acup.gunfeel.water.LiquidEvents.Liquid.WATER);
            drift(); assertEquals(4.5, b.body.x());
            wet(8, 63, 0, acup.gunfeel.water.LiquidEvents.Liquid.WATER);
            wet(8, 62, 0, acup.gunfeel.water.LiquidEvents.Liquid.WATER);
            drift();
            assertTrue(b.order.detour != null); assertEquals(0, b.order.detourAt); assertEquals(4.5, b.body.x());
            long last = b.order.detour[Math.min(b.order.detour.length - 1, 3)];
            drift();
            assertEquals((int) (last >> 32) + .5, b.body.x()); assertEquals((int) last + .5, b.body.z());
        }

        @Test
        void driftDeepLiquidWithoutDetourFailsAndGivesUp() {
            Bot b = bot(1, "a", .5, .5); b.active = false;
            var o = order(b, StaffPlan.Kind.HOLD, 100, .5, 7);
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) wet(x, 63, z, acup.gunfeel.water.LiquidEvents.Liquid.LAVA);
            for (int i = 0; i < AiCommanderBase.FAILS; i++) { now++; drift(); }
            assertNull(b.order); assertEquals(now + AiCommanderBase.AVOID, b.orderFailedUntil);
            assertSame(o.ids, b.orderFailed);
            assertEquals(List.of(AiReportsBase.Kind.BLOCKED), reports.stream().map(AiReportsBase.Report::kind).toList());
        }

        @Test
        void driftWaitsWithinSameBlockAndWaterTightensRouteReach() {
            Bot b = bot(1, "a", .1, .1); b.active = false;
            var o = order(b, StaffPlan.Kind.ADVANCE, .8, .8, 7);
            o.r = .1;
            drift();
            assertEquals(.1, b.body.x()); assertEquals(.1, b.body.z()); assertEquals(0, o.fails);
            grid = HqGrid.around(0, 0);
            grid.set(grid.cellX(.5), grid.cellZ(.5), 64, 0, 1, 1);
            var r = route(.5, .5, 100, 0);
            assertEquals(AiCommanderBase.DRIFT_AT_WATER, AiCommanderBase.reachedAt(staff, grid, r, 0));
            assertTrue(AiCommanderBase.waterNear(staff, grid, .5, .5));
            assertEquals(AiCommanderBase.REACHED, AiCommanderBase.reachedAt(staff, null, r, 0));
        }

        @Test
        void lookReportsContactLossesPinnedAmmoAndThereOnce() {
            Bot a = bot(1, "a", 0, 0), b = bot(2, "a", 1, 0), c = bot(3, "a", 2, 0);
            squads();
            var o = order(a, StaffPlan.Kind.PIN, 10, 0, 7);
            o.job = new Task(StaffPlan.Kind.PIN, now, List.of(a, b, c), new int[] {7});
            b.order = c.order = o;
            for (var s : AiSquadsBase.squadsOf(level)) s.target = 7;
            a.suppressed = b.suppressed = 1;
            a.gun.ammo.set(30); b.gun.ammo.set(0); c.gun.ammo.set(0);
            o.job.there = true;
            AiCommanderBase.lookSquads(staff, level, now);
            assertEquals(List.of(AiReportsBase.Kind.CONTACT, AiReportsBase.Kind.AMMO, AiReportsBase.Kind.THERE), reports.stream().map(AiReportsBase.Report::kind).toList());
            AiCommanderBase.lookSquads(staff, level, now + AiCommanderBase.PIN_AFTER);
            assertEquals(AiReportsBase.Kind.PINNED, reports.get(reports.size() - 1).kind());
            c.person.alive = false;
            AiCommanderBase.lookSquads(staff, level, now + 61);
            assertEquals(1, reports.get(reports.size() - 1).value());
            b.person.alive = false;
            AiCommanderBase.lookSquads(staff, level, now + 62);
            assertEquals(2, reports.get(reports.size() - 1).value());
            int size = reports.size();
            AiCommanderBase.lookSquads(staff, level, now + 63); assertEquals(size, reports.size());
            a.suppressed = 0;
            AiCommanderBase.lookSquads(staff, level, now + 70);
            AiCommanderBase.lookSquads(staff, level, now + 70 + AiCommanderBase.PIN_QUIET);
            assertEquals(Long.MIN_VALUE, o.job.pinnedAt);
        }

        @Test
        void pairsKeepTimeAndSignalWhenReadyBeforeDeadline() throws Exception {
            Bot f = bot(1, "a", 0, 0), p = bot(2, "a", 10, 0);
            var fo = order(f, StaffPlan.Kind.FLANK, 0, 80, 7); var po = order(p, StaffPlan.Kind.PIN, 0, 0, 7);
            AiCommanderBase.pairSquads(staff, List.of(squad(p), squad(f)), null, now);
            assertSame(po.job, fo.job.partner); assertSame(fo.job, po.job.partner);
            assertEquals(now + Math.min(AiCommanderBase.PAIR_MOST, (long) (80 * HqGrid.DETOUR / AiCommanderBase.PAIR_PACE * 1.5) + AiCommanderBase.PAIR_MORE), fo.job.due);
            side().next = 5000;
            AiCommanderBase.signalReady(staff, level, fo.job, fo.job.due);
            assertEquals(Long.MIN_VALUE, fo.job.ready);
            AiCommanderBase.signalReady(staff, level, fo.job, now + 10);
            assertEquals(now + 10, fo.job.ready); assertEquals(fo.job.ready, po.job.ready);
            assertEquals(AiCommanderBase.SIGNAL_POINT, f.gesture); assertEquals(AiCommanderBase.SIGNAL_COME, p.gesture);
            assertEquals(AiCommanderBase.SIGN_TICKS, f.gestureTicks); assertEquals(now + 11, side().next);
            assertTrue(AiCommanderBase.pressesFor(staff, p, now + 10));
            assertFalse(AiCommanderBase.pressesFor(staff, p, fo.job.due));
        }

        @Test
        void coupleIgnoresTargetOutsideJobGroup() {
            Bot f = bot(1, "a", 0, 0), p = bot(2, "a", 1, 0);
            var fo = order(f, StaffPlan.Kind.FLANK, 100, 0, 7); var po = order(p, StaffPlan.Kind.PIN, 0, 0, 7);
            fo.job.partner = po.job; po.job.partner = fo.job;
            f.memory.heard(0, 64, 0, now, true, true);
            squads();
            var s = AiSquadsBase.squadOf(f); s.target = 8;
            AiCommanderBase.coupleSquads(staff, level, now);
            assertEquals(Long.MIN_VALUE, fo.job.ready); assertEquals(Long.MIN_VALUE, po.job.ready);
            assertTrue(events.isEmpty());
            s.target = 7;
            AiCommanderBase.coupleSquads(staff, level, now);
            assertEquals(now, fo.job.ready); assertEquals(now, po.job.ready);
            assertEquals(List.of("couple", "couple"), events);
        }

        @Test
        void leashRoundAndUnderFireUseTheirBoundaries() {
            Bot f = bot(1, "a", 0, 0), p = bot(2, "a", 0, 0);
            var fo = order(f, StaffPlan.Kind.FLANK, 100, 0, 7); var po = order(p, StaffPlan.Kind.PIN, 0, 0, 7);
            fo.job.partner = po.job; po.job.partner = fo.job;
            TargetMemory.Contact c = new TargetMemory.Contact(7); c.state = TargetMemory.State.VISIBLE; c.x = 25; c.lostX = 25;
            p.memory.contacts().add(c);
            assertTrue(AiCommanderBase.leashedAt(staff, p, 7, now));
            p.person.x = 24.001; assertFalse(AiCommanderBase.leashedAt(staff, p, 7, now));
            assertTrue(AiCommanderBase.goesAround(staff, f, c, now));
            c.x = 20; assertFalse(AiCommanderBase.goesAround(staff, f, c, now));
            c.x = 25;
            f.memory.heard(0, 64, 0, now, true, true);
            assertFalse(AiCommanderBase.goesAround(staff, f, c, now + AiCommanderBase.UNDER_FIRE));
            assertTrue(AiCommanderBase.goesAround(staff, f, c, now + AiCommanderBase.UNDER_FIRE + 1));
            fo.job.kind = StaffPlan.Kind.BACK; fo.job.breaking = true;
            assertTrue(AiCommanderBase.breakingContact(staff, f, now));
            assertFalse(AiCommanderBase.breakingContact(staff, f, fo.until + 1));
        }

        @Test
        void diedPrefersLastEnemyThenCreditThenSquadThenOrder() throws Exception {
            Bot fallen = bot(1, "a", 0, 0), mate = bot(2, "a", 1, 0);
            Bot last = bot(70, "b", 50, 0), credit = bot(71, "b", -50, 0), target = bot(72, "b", 0, 70), ordered = bot(73, "b", 0, -70);
            List<AiIntelBase.Group> groups = intel(fallen);
            side(); squads();
            var o = order(fallen, StaffPlan.Kind.ADVANCE, 0, -70, ordered.body.id());
            var s = AiSquadsBase.squadOf(fallen); s.target = target.body.id();
            fallen.person.hurt = last.body; fallen.person.credit = credit.body;
            AiCommanderBase.lostBot(staff, level, fallen, now);
            assertEquals(now, groups.stream().filter(g -> g.has(last.body.id())).findFirst().orElseThrow().known().lost);
            assertEquals(Long.MIN_VALUE, groups.stream().filter(g -> g.has(credit.body.id())).findFirst().orElseThrow().known().lost);
            fallen.person.hurt = mate.body;
            AiCommanderBase.lostBot(staff, level, fallen, now + 1);
            assertEquals(now + 1, groups.stream().filter(g -> g.has(credit.body.id())).findFirst().orElseThrow().known().lost);
            fallen.person.credit = null;
            AiCommanderBase.lostBot(staff, level, fallen, now + 2);
            assertEquals(now + 2, groups.stream().filter(g -> g.has(target.body.id())).findFirst().orElseThrow().known().lost);
            s.target = -1;
            AiCommanderBase.lostBot(staff, level, fallen, now + 3);
            assertEquals(now + 3, groups.stream().filter(g -> g.has(ordered.body.id())).findFirst().orElseThrow().known().lost);
            assertSame(o, fallen.order);
        }

        @Test
        void reportedRespectsAskedGapAndCountsFirstReport() throws Exception {
            var side = side(); side.commanded = now - 5; side.next = now + 100;
            AiCommanderBase.reportAsked(staff, level, "a");
            assertEquals(side.commanded + AiCommanderBase.ASKED_GAP, side.next); assertEquals(now, side.asked);
            now += 2;
            AiCommanderBase.reportAsked(staff, level, "a");
            assertEquals(now - 2, side.asked);
            long n = AiCommanderBase.askedN, lag = AiCommanderBase.askedLag;
            AiCommanderBase.tasks = false;
            AiCommanderBase.commandSide(staff, level, "a", side, List.of(), now);
            assertEquals(n + 1, AiCommanderBase.askedN); assertEquals(lag + 2, AiCommanderBase.askedLag);
            assertEquals(Long.MIN_VALUE, side.asked);
            assertEquals(now, AiCommanderBase.answeredAt);
        }

        @Test
        void tickUsesRandomBoundAndForgetsBothLevelMaps() throws Exception {
            AiCommanderBase.tasks = false;
            bot(1, "a", 0, 0); bot(2, "a", 1, 0); squads();
            AiCommanderBase.tickStaff(staff, level, control);
            assertEquals(1, draws); assertEquals(AiCommanderBase.EVERY_MAX - AiCommanderBase.EVERY_MIN + 1, bound);
            assertEquals(now + AiCommanderBase.EVERY_MIN + draw, side().next);
            now++;
            AiCommanderBase.tickStaff(staff, level, control); assertEquals(1, draws);
            AiCommanderBase.forgetLevel(level);
            assertNull(map(AiCommanderBase.class, "LEVELS").get(level)); assertNull(map(AiCommanderBase.class, "reportDue").get(level));
        }

        @Test
        void tickDriftsThenCouplesThenLooksAtTheirModules() throws Exception {
            Bot f = bot(1, "a", 0, 0), p = bot(2, "a", 1, 0), d = bot(3, "a", 0, 100);
            var fo = order(f, StaffPlan.Kind.FLANK, 100, 0, 7); var po = order(p, StaffPlan.Kind.PIN, 0, 0, 7);
            fo.job.partner = po.job; po.job.partner = fo.job;
            TargetMemory.Contact c = new TargetMemory.Contact(7); c.state = TargetMemory.State.VISIBLE; c.x = 10;
            f.memory.contacts().add(c); f.memory.focusId = p.memory.focusId = 7;
            d.active = false; order(d, StaffPlan.Kind.HOLD, 100, 100, 8);
            squads(); side().next = Long.MAX_VALUE;
            this.<Long>map(AiCommanderBase.class, "reportDue").put(level, Long.MAX_VALUE);
            AiCommanderBase.tickStaff(staff, level, control);
            assertEquals("drift", events.get(0));
            assertTrue(events.indexOf("couple") > events.indexOf("drift"));
            assertTrue(events.indexOf("look") > events.indexOf("couple"));
            side().next = Long.MAX_VALUE;
            events.clear(); now = 1001;
            AiCommanderBase.tickStaff(staff, level, control); assertTrue(events.isEmpty());
            fo.job.ready = po.job.ready = Long.MIN_VALUE;
            now = 1010;
            AiCommanderBase.tickStaff(staff, level, control);
            assertEquals(List.of("couple", "couple"), events);
        }
    }
}
