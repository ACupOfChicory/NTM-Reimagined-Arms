package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.StaffMoveBase.Mode;
import acup.gunfeel.ai.StaffMoveBase.Pace;

// How a squad goes along its staff's way (stage 7, C3): its mode, a bound's halves, places along the way.
class StaffMoveBaseTest {
    @Test
    void modeByTheEnemy() {
        // far and unseen: a march; nearer, or seen: bounds; seen and near: creeping
        assertEquals(Mode.MARCH, StaffMoveBase.movementMode(150, false, true, 150));
        assertEquals(Mode.BOUND, StaffMoveBase.movementMode(90, false, true, 90));
        assertEquals(Mode.BOUND, StaffMoveBase.movementMode(150, true, true, 150));
        assertEquals(Mode.CREEP, StaffMoveBase.movementMode(50, true, true, 50));
        // unseen and near: still bounds, not creeping
        assertEquals(Mode.BOUND, StaffMoveBase.movementMode(30, false, true, 30));
        // the last of an advance: the assault, seen or not; a place to hold: no assault
        assertEquals(Mode.ASSAULT, StaffMoveBase.movementMode(20, true, true, 20));
        assertEquals(Mode.CREEP, StaffMoveBase.movementMode(20, true, false, 20));
    }

    @Test
    void aMarchIsAJogAndFallingBackARun() {
        assertEquals(speed(Pace.MARCH), StaffMoveBase.marchSpeed(staff, StaffPlan.Kind.ADVANCE));
        assertEquals(speed(Pace.MARCH), StaffMoveBase.marchSpeed(staff, StaffPlan.Kind.FLANK));
        assertEquals(speed(Pace.RUN), StaffMoveBase.marchSpeed(staff, StaffPlan.Kind.BACK));
    }

    @Test
    void halvesCoverWithTheBaseOfFireFirst() {
        // the base of fire and marksman cover first, half of the squad (the larger half when odd)
        assertArrayEquals(new int[] {1, 0, 0}, StaffMoveBase.roleHalves(List.of(AiRole.RIFLE, AiRole.BASE, AiRole.MARKSMAN)));
        assertArrayEquals(new int[] {0, 0, 1}, StaffMoveBase.roleHalves(List.of(AiRole.RIFLE, AiRole.RIFLE, AiRole.RIFLE)));
        assertArrayEquals(new int[] {0, 1, 0, 1}, StaffMoveBase.roleHalves(List.of(AiRole.RIFLE, AiRole.RIFLE, AiRole.BASE, AiRole.RIFLE)));
        assertArrayEquals(new int[] {0}, StaffMoveBase.roleHalves(List.of(AiRole.RIFLE)));
    }

    @Test
    void rushingOneOrTwo() {
        assertEquals(1, StaffMoveBase.rushCount(1));
        assertEquals(1, StaffMoveBase.rushCount(3));
        assertEquals(2, StaffMoveBase.rushCount(4));
        assertEquals(2, StaffMoveBase.rushCount(8));
    }

    @Test
    void placesAlongTheWay() {
        // from (0, 0) east 10, then north 20
        HqGrid.Route r = new HqGrid.Route(0, 0, 10, 20, new double[] {10, 0, 10, 20}, 30, Math.hypot(10, 20), 0, 0);
        assertEquals(5, StaffMoveBase.distanceAlong(r, 5, 3), 1e-9);
        assertEquals(20, StaffMoveBase.distanceAlong(r, 12, 10), 1e-9);
        assertEquals(30, StaffMoveBase.distanceAlong(r, 10, 40), 1e-9);
        assertArrayEquals(new double[] {4, 0}, StaffMoveBase.pointAt(r, 4), 1e-9);
        assertArrayEquals(new double[] {10, 5}, StaffMoveBase.pointAt(r, 15), 1e-9);
        assertArrayEquals(new double[] {10, 20}, StaffMoveBase.pointAt(r, 99), 1e-9);
        assertArrayEquals(new double[] {0, 0}, StaffMoveBase.pointAt(r, -3), 1e-9);
    }

    final Object level = new Object();
    long now;
    boolean loaded = true, movingHeld;
    HqGrid grid;
    java.util.function.LongPredicate held = c -> false;
    acup.gunfeel.Vec3d aimed;
    boolean crouched;
    final java.util.List<Long> heightCalls = new java.util.ArrayList<>();
    final acup.gunfeel.host.WorldView world = (acup.gunfeel.host.WorldView) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {acup.gunfeel.host.WorldView.class}, (p, m, a) -> switch (m.getName()) {
                case "gameTime" -> now;
                case "hasChunk" -> loaded;
                case "height" -> {
                    assertEquals(acup.gunfeel.host.WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES, a[2]);
                    heightCalls.add(acup.gunfeel.PosLong.block((int) a[0], 0, (int) a[1]));
                    yield 0;
                }
                default -> throw new AssertionError(m.getName());
            });
    SectionCells cells = new SectionCells(0, 1, new short[] {(short) (8 << 4 | 7), (short) (8 << 4 | 9)},
            new int[] {0xFF, 0xFF}, new short[2], new short[257], 0);
    final TacticalMapBase map = new TacticalMapBase(-1, 1) {
        @Override public SectionCells cells(long key) { return key == 0 ? cells : null; }
        @Override protected SectionSnapshot copy(long key, int x, int y, int z, int stamp) { throw new AssertionError(); }
    };

    @SuppressWarnings("unchecked")
    final AiCommanderBase.Staff<Bot, Task, Order> staff = (AiCommanderBase.Staff<Bot, Task, Order>) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {AiCommanderBase.Staff.class}, (p, m, a) -> switch (m.getName()) {
                case "order" -> { if (a.length != 1) throw new AssertionError(); yield ((Bot) a[0]).order; }
                case "world" -> { assertSame(level, a[0]); yield world; }
                case "hq" -> { assertSame(level, a[0]); assertEquals("own", a[1]); yield grid; }
                case "map" -> { assertSame(level, a[0]); yield map; }
                case "voxels" -> { assertSame(level, a[0]); yield (VoxelRay.Source) (x, y, z) -> Voxel.SIGHT; }
                case "heldByOthers" -> held;
                case "speed" -> speed((Pace) a[0]);
                case "movingHeld" -> movingHeld;
                case "aim" -> { aimed = (acup.gunfeel.Vec3d) a[1]; yield null; }
                case "crouch" -> { crouched = (boolean) a[1]; yield null; }
                default -> throw new AssertionError(m.getName());
            });

    static double speed(Pace p) {
        return switch (p) { case RUN -> 13; case MARCH -> 12; case WALK -> 10; case CREEP -> 7; };
    }

    final class Bot extends AiBotBase {
        double x, z;
        boolean alive = true, soldier = true;
        Order order;

        Bot(double x, double z) {
            this(new double[] {x, z});
            this.x = x; this.z = z;
        }

        private Bot(double[] at) {
            super((acup.gunfeel.host.Body) java.lang.reflect.Proxy.newProxyInstance(
                    StaffMoveBaseTest.class.getClassLoader(), new Class<?>[] {acup.gunfeel.host.Body.class}, (p, m, a) -> {
                        Bot b = bots.get(at);
                        return switch (m.getName()) {
                            case "x" -> b.x;
                            case "z" -> b.z;
                            case "alive" -> b.alive;
                            case "level" -> level;
                            case "team" -> "own";
                            case "eye" -> { ((double[]) a[0])[1] = 1.6; yield null; }
                            default -> throw new AssertionError(m.getName());
                        };
                    }), SensesTest.P);
            bots.put(at, this);
        }

        @Override public AiRole role() { return role; }
        @Override public boolean soldier() { return soldier; }
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
        @Override public acup.gunfeel.host.WorldView world() { return world; }
        @Override public AiMoverBase mover() { throw new AssertionError(); }
        @Override public AiGunnerBase gunner() { throw new AssertionError(); }
        @Override public AiProneBase prone() { throw new AssertionError(); }
        @Override public AiStoopBase stoop() { throw new AssertionError(); }
        @Override public AiDashBase dash() { throw new AssertionError(); }
    }

    final java.util.Map<double[], Bot> bots = new java.util.IdentityHashMap<>();

    static final class Order extends AiCommanderBase.Order<Bot, Task> {
    }

    static final class Task extends AiCommanderBase.Job<Bot, Task> {
        Task(List<Bot> bots) { super(StaffPlan.Kind.ADVANCE, 1, bots, new int[0]); }
    }

    Task task(Bot... bots) {
        Task j = new Task(List.of(bots));
        for (Bot b : bots) {
            b.order = new Order();
            b.order.job = j;
            b.order.x = 200;
            b.order.r = 10;
            b.order.route = route();
        }
        j.move.since = 100;
        return j;
    }

    static HqGrid.Route route() {
        return new HqGrid.Route(0, 0, 100, 0, new double[] {50, 0, 100, 0}, 100, 100, 0, 0);
    }

    void pick(Bot b, Task j, double x, double z) {
        StaffMoveBase.pickCover(staff, b, level, b.order, j.members, j.move, x, z);
    }

    void threat(Task j) {
        j.move.foe = true;
        j.move.foeX = 8.5;
        j.move.foeZ = -20;
        j.move.foeR = 0;
    }

    @Test
    void stableHalvesWriteOrdersAndKeepThemAcrossReordering() {
        Bot a = new Bot(0, 0), b = new Bot(0, 0), c = new Bot(0, 0);
        b.role = AiRole.BASE;
        Task j = task(a, b, c);
        assertArrayEquals(new int[] {0, 0, 1}, StaffMoveBase.stableHalves(staff, j.members, j.move));
        for (Bot bot : j.members) assertEquals(100, bot.order.halfSince);
        j.members = List.of(c, b, a);
        assertArrayEquals(new int[] {1, 0, 0}, StaffMoveBase.stableHalves(staff, j.members, j.move));
        Bot d = new Bot(0, 0); task(d); d.order.job = j;
        j.members = List.of(c, b, a, d);
        assertArrayEquals(new int[] {1, 0, 0, 1}, StaffMoveBase.stableHalves(staff, j.members, j.move));
        assertEquals(1, d.order.half);
        j.members = List.of(a, b);
        assertArrayEquals(new int[] {1, 0}, StaffMoveBase.stableHalves(staff, j.members, j.move));
        assertEquals(1, a.order.half);
        j.move.since++;
        assertArrayEquals(new int[] {1, 0}, StaffMoveBase.stableHalves(staff, j.members, j.move));
        assertEquals(101, a.order.halfSince);
    }

    @Test
    void updateWaitsForNextAndResetsTheTurnAtTheDeadline() {
        Bot b = new Bot(0, 0);
        Task j = task(b);
        var st = j.move;
        st.next = 40; st.turn = 4; st.moving = 1; st.to = 50; st.set = true;
        StaffMoveBase.refreshMode(staff, level, b.order, 39);
        assertNull(st.mode); assertEquals(40, st.next);
        StaffMoveBase.refreshMode(staff, level, b.order, 40);
        assertEquals(Mode.MARCH, st.mode);
        assertEquals(60, st.next); assertEquals(40, st.since);
        assertEquals(0, st.turn); assertEquals(0, st.moving);
        assertEquals(-1, st.to); assertFalse(st.set);
        assertFalse(st.foe);
    }

    @Test
    void updateKeepsAModeUntilKeepButAssaultBeginsAtOnce() {
        Bot b = new Bot(0, 0);
        Task j = task(b);
        var st = j.move;
        st.mode = Mode.CREEP; st.since = 100;
        StaffMoveBase.refreshMode(staff, level, b.order, 159);
        assertEquals(Mode.CREEP, st.mode);
        st.next = 160;
        StaffMoveBase.refreshMode(staff, level, b.order, 160);
        assertEquals(Mode.MARCH, st.mode); assertEquals(160, st.since);
        b.order.x = 30; b.order.r = 10; st.next = 161;
        StaffMoveBase.refreshMode(staff, level, b.order, 161);
        assertEquals(Mode.ASSAULT, st.mode); assertEquals(161, st.since);
        st.next = 181;
        StaffMoveBase.refreshMode(staff, level, b.order, 181);
        assertEquals(161, st.since);
        j.kind = StaffPlan.Kind.BACK; st.next = 221;
        StaffMoveBase.refreshMode(staff, level, b.order, 221);
        assertEquals(Mode.MARCH, st.mode);
    }

    @Test
    void updateReadsSightAtTheSquadsMiddleAndAfreshRechecks() {
        Bot a = new Bot(0, 0), b = new Bot(24, 0);
        Task j = task(a, b);
        grid = new HqGrid(0, 0);
        grid.seen[HqGrid.index(1, 0)] = 0.5F;
        StaffMoveBase.refreshMode(staff, level, a.order, 0);
        assertEquals(Mode.MARCH, j.move.mode);
        grid.seen[HqGrid.index(1, 0)] = 0.6F;
        StaffMoveBase.restartMode(staff, j);
        assertNull(j.move.mode); assertEquals(Long.MIN_VALUE, j.move.next);
        StaffMoveBase.refreshMode(staff, level, a.order, 1);
        assertEquals(Mode.BOUND, j.move.mode);
        j.members = List.of();
        StaffMoveBase.refreshMode(staff, level, a.order, 21);
        assertEquals(41, j.move.next); assertEquals(1, j.move.since);
    }

    @Test
    void marchingFollowsTheLeaderWithoutWalkingBack() {
        Bot a = new Bot(20, 0), b = new Bot(5, 0);
        Task j = task(a, b); j.move.mode = Mode.MARCH;
        var lead = StaffMoveBase.nextLeg(staff, a, level, a.order, route(), 1, false);
        assertEquals(new StaffMoveBase.Leg(50, 0, Long.MIN_VALUE, 12, false, false, false, 100), lead);
        var follow = StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 1, false);
        assertEquals(new StaffMoveBase.Leg(17, 0, Long.MIN_VALUE, 12, false, false, false, 20), follow);
        b.x = 25;
        assertEquals(25, StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 1, false).x());
        j.kind = StaffPlan.Kind.BACK;
        assertEquals(13, StaffMoveBase.nextLeg(staff, a, level, a.order, route(), 1, false).speed());
        b.order.job = null;
        assertNull(StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 1, false));
    }

    @Test
    void boundWaitsForCoverThenChangesHalvesAtItsDeadline() {
        Bot a = new Bot(0, 0), b = new Bot(0, 0);
        Task j = task(a, b); j.move.mode = Mode.BOUND;
        var st = j.move;
        StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 100, false);
        assertEquals(1, st.turn); assertEquals(1, st.moving); assertFalse(st.set);
        StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 159, false);
        assertFalse(st.set);
        var moving = StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 160, false);
        assertTrue(st.set); assertEquals(12, st.to); assertEquals(12, moving.x()); assertTrue(moving.fresh());
        StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 359, false);
        assertEquals(1, st.turn);
        StaffMoveBase.nextLeg(staff, a, level, a.order, route(), 360, false);
        assertEquals(2, st.turn); assertEquals(0, st.moving); assertFalse(st.set);
        b.x = b.order.coverX; b.z = b.order.coverZ;
        StaffMoveBase.nextLeg(staff, a, level, a.order, route(), 361, false);
        assertTrue(st.set); assertEquals(24, st.to);
    }

    @Test
    void creepRushesOnlyItsTurnAndRotatesAfterTimeout() {
        Bot a = new Bot(0, 0), b = new Bot(0, 0), c = new Bot(0, 0);
        Task j = task(a, b, c); j.move.mode = Mode.CREEP;
        var first = StaffMoveBase.nextLeg(staff, a, level, a.order, route(), 100, false);
        assertEquals(10, first.x()); assertTrue(first.low()); assertEquals(7, first.speed());
        var waits = StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 100, false);
        assertTrue(waits.hold()); assertEquals(0, waits.x());
        StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 259, false);
        assertEquals(1, j.move.turn);
        var second = StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 260, false);
        assertEquals(2, j.move.turn); assertEquals(1, j.move.moving); assertEquals(10, second.x());
        b.x = 10;
        StaffMoveBase.nextLeg(staff, c, level, c.order, route(), 261, false);
        assertEquals(3, j.move.turn); assertEquals(2, j.move.moving);
    }

    @Test
    void stuckCoverIsExcludedAndOtherHoldersKeepTheirCells() {
        Bot a = new Bot(8.5, 8.5);
        Task j = task(a); threat(j); j.move.mode = Mode.BOUND;
        a.order.coverCell = acup.gunfeel.PosLong.block(7, 0, 8);
        var step = StaffMoveBase.nextLeg(staff, a, level, a.order, route(), 100, true);
        assertEquals(acup.gunfeel.PosLong.block(7, 0, 8), a.order.badCell);
        assertEquals(acup.gunfeel.PosLong.block(9, 0, 8), step.cell());
        assertEquals(9.5, step.x());
        held = c -> c == acup.gunfeel.PosLong.block(9, 0, 8);
        a.order.coverSince = Long.MIN_VALUE;
        step = StaffMoveBase.nextLeg(staff, a, level, a.order, route(), 101, false);
        assertEquals(Long.MIN_VALUE, step.cell()); assertFalse(a.order.covered);
        assertEquals(8.5, step.x()); assertTrue(step.hold());
    }

    @Test
    void pickKeepsEqualCellsInTheirMapOrder() {
        Bot b = new Bot(8.5, 8.5);
        Task j = task(b); threat(j);
        pick(b, j, 8.5, 8.5);
        assertEquals(acup.gunfeel.PosLong.block(7, 0, 8), b.order.coverCell);
        assertEquals(7.5, b.order.coverX); assertEquals(8.5, b.order.coverZ);
        assertTrue(b.order.covered);
        cells = new SectionCells(0, 1, new short[] {(short) (8 << 4 | 9), (short) (8 << 4 | 7)},
                new int[] {0xFF, 0xFF}, new short[2], new short[257], 0);
        pick(b, j, 8.5, 8.5);
        assertEquals(acup.gunfeel.PosLong.block(9, 0, 8), b.order.coverCell);
    }

    @Test
    void pickRejectsHeldBadAndSquadCells() {
        Bot a = new Bot(8.5, 8.5), b = new Bot(8.5, 8.5);
        Task j = task(a, b); threat(j);
        long west = acup.gunfeel.PosLong.block(7, 0, 8), east = acup.gunfeel.PosLong.block(9, 0, 8);
        held = c -> c == west;
        pick(a, j, 8.5, 8.5);
        assertEquals(east, a.order.coverCell);
        held = c -> false; b.order.coverCell = west;
        pick(a, j, 8.5, 8.5);
        assertEquals(east, a.order.coverCell);
        a.order.badCell = east;
        pick(a, j, 8.5, 8.5);
        assertFalse(a.order.covered); assertEquals(Long.MIN_VALUE, a.order.coverCell);
        assertEquals(8.5, a.order.coverX); assertEquals(8.5, a.order.coverZ);
    }

    @Test
    void pickUsesKnownHiddenGroundAtTheInclusiveThreshold() {
        Bot b = new Bot(8.5, 8.5);
        Task j = task(b); threat(j);
        grid = new HqGrid(0, 0);
        grid.set(1, 1, 0, 0, 0, 0); grid.seen[HqGrid.index(1, 1)] = 0.25F;
        pick(b, j, 8.5, 8.5);
        assertTrue(b.order.covered); assertEquals(Long.MIN_VALUE, b.order.coverCell);
        assertEquals(8.5, b.order.coverX); assertTrue(heightCalls.isEmpty());
        grid.known[HqGrid.index(1, 1)] = false;
        pick(b, j, 8.5, 8.5);
        assertEquals(acup.gunfeel.PosLong.block(7, 0, 8), b.order.coverCell);
        assertEquals(2, heightCalls.size());
        loaded = false; heightCalls.clear();
        pick(b, j, -0.1, -0.1);
        assertFalse(b.order.covered); assertEquals(-0.1, b.order.coverX); assertTrue(heightCalls.isEmpty());
        loaded = true; j.move.foe = false;
        pick(b, j, 8.5, 8.5);
        assertFalse(b.order.covered); assertTrue(heightCalls.isEmpty());
    }

    @Test
    void quietReadsOnlyAliveMembersStillOnTheTask() {
        Bot a = new Bot(0, 0), b = new Bot(0, 0), c = new Bot(0, 0);
        Task j = task(a, b, c);
        assertTrue(StaffMoveBase.taskQuiet(staff, j));
        var contact = new TargetMemory.Contact(7); contact.state = TargetMemory.State.VISIBLE;
        b.memory.contacts().add(contact);
        assertFalse(StaffMoveBase.taskQuiet(staff, j)); assertEquals(7, b.memory.focusId);
        b.alive = false;
        assertTrue(StaffMoveBase.taskQuiet(staff, j));
        b.alive = true; b.order.job = null;
        c.order = null;
        assertEquals(List.of(a), StaffMoveBase.taskSquad(staff, j));
        assertTrue(StaffMoveBase.taskQuiet(staff, j));
    }

    @Test
    void coveredNeedsAQuietCoveringHalfAtRestInThisMode() {
        Bot a = new Bot(0, 0), b = new Bot(0, 0);
        Task j = task(a, b); var st = j.move;
        st.mode = Mode.BOUND; st.turn = 1; st.moving = 1;
        assertEquals(0, StaffMoveBase.covering(staff, j));
        a.order.coverSince = st.since;
        assertEquals(1, StaffMoveBase.covering(staff, j));
        a.order.covered = true;
        assertEquals(2, StaffMoveBase.covering(staff, j));
        movingHeld = true;
        assertEquals(0, StaffMoveBase.covering(staff, j));
        movingHeld = false; a.x = 3;
        assertEquals(0, StaffMoveBase.covering(staff, j));
        a.x = 0; a.order.coverCell = 0; a.x = 1.5;
        assertEquals(2, StaffMoveBase.covering(staff, j));
        assertFalse(StaffMoveBase.atPlace(staff, a, a.order));
        a.x = 1.50001;
        assertEquals(0, StaffMoveBase.covering(staff, j));
        a.x = 0;
        var contact = new TargetMemory.Contact(7); contact.state = TargetMemory.State.VISIBLE;
        b.memory.contacts().add(contact);
        assertEquals(0, StaffMoveBase.covering(staff, j));
        b.memory.contacts().clear(); st.turn = 0;
        assertEquals(0, StaffMoveBase.covering(staff, j));
        st.turn = 1; st.mode = Mode.MARCH;
        assertEquals(0, StaffMoveBase.covering(staff, j));
    }

    @Test
    void assaultAndPaceUseTheAdaptersAndKeepAimAtEyeHeight() {
        Bot b = new Bot(0, 0); Task j = task(b); threat(j);
        var st = j.move; st.mode = Mode.ASSAULT;
        b.order.of = 2; b.order.slot = 0;
        var step = StaffMoveBase.nextLeg(staff, b, level, b.order, route(), 100, false);
        assertEquals(53, step.x()); assertEquals(13, step.speed()); assertEquals(500, b.confidence.storm);
        StaffMoveBase.faceEnemy(staff, b, b.order);
        assertEquals(new acup.gunfeel.Vec3d(8.5, 1.6, -20), aimed);
        aimed = null; b.alive = false;
        StaffMoveBase.faceEnemy(staff, b, b.order); assertNull(aimed);
        st.mode = Mode.CREEP;
        StaffMoveBase.applyPace(staff, b, b.order, 101); assertTrue(crouched);
        crouched = false; b.soldier = false;
        StaffMoveBase.applyPace(staff, b, b.order, 102); assertFalse(crouched);
        for (Mode m : Mode.values()) {
            st.mode = m;
            assertEquals(speed(switch (m) { case MARCH -> Pace.MARCH; case ASSAULT -> Pace.RUN; case CREEP -> Pace.CREEP; case BOUND -> Pace.WALK; }),
                    StaffMoveBase.orderSpeed(staff, b.order));
        }
        b.order.job = null;
        assertEquals(speed(Pace.WALK), StaffMoveBase.orderSpeed(staff, b.order));
    }
}
