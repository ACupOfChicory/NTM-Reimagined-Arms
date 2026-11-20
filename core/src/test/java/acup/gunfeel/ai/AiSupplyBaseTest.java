package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.PosLong;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;
import acup.gunfeel.host.WorldView;

class AiSupplyBaseTest {
    final Object level = new Object();
    final AiBotsBase.PerLevelBase<Bot> registry = new AiBotsBase.PerLevelBase<>();
    final List<String> events = new ArrayList<>();
    int grenadeLimit = 3, smokeLimit = 2;
    long now = 1000;
    Body lookupOverride;
    final acup.gunfeel.GameRandom random = new acup.gunfeel.GameRandom() {
        @Override public int nextInt(int bound) { throw new AssertionError(); }
        @Override public double nextDouble() { throw new AssertionError(); }
        @Override public boolean nextBoolean() { throw new AssertionError(); }
        @Override public float nextFloat() { throw new AssertionError(); }
        @Override public double nextGaussian() { throw new AssertionError(); }
    };

    final class Person {
        final int id;
        String team = "a";
        double x, y = 64, z;
        boolean alive = true, removed;
        final Body body;

        Person(int id, double x) {
            this.id = id; this.x = x;
            body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
                case "id" -> id;
                case "team" -> team;
                case "x" -> this.x;
                case "y" -> y;
                case "z" -> z;
                case "alive" -> alive;
                case "removed" -> removed;
                case "level" -> level;
                case "random" -> random;
                default -> throw new AssertionError(m.getName());
            });
        }
    }

    final class Bot extends AiBotBase {
        final Person person;
        Bot target;
        AiSupplyBase.Left fetching;
        int[] kept;
        boolean spot, lying, pinned;
        boolean failed, arrived;
        long goal = Long.MIN_VALUE;
        int walks;
        int capacity = 30;
        final AiGunnerBase gun;
        final AiProneBase prone;

        Bot(Person person) {
            super(person.body, AiProfile.SOLDIER);
            this.person = person;
            gun = new AiGunnerBase(body, new AiAmmo(0)) {
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
                @Override public boolean lying() { return lying; }
                @Override public boolean pinned(long tick) { return pinned; }
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
        @Override public acup.gunfeel.host.WorldView world() { throw new AssertionError(); }
        final AiMoverBase mover = new AiMoverBase(body) {
            @Override public boolean failed() { return failed; }
            @Override public boolean arrived() { return arrived; }
            @Override public boolean goalIs(long cell) { return goal == cell; }
            @Override public void costs(PathSearch.Cost cost) { events.add("costs"); }
            @Override public void goTo(long cell, double radius, double speed) {
                assertEquals(1.5, radius); assertEquals(1.25, speed);
                goal = cell; walks++; events.add("walk");
            }
        };
        @Override public AiMoverBase mover() { return mover; }
        @Override public AiGunnerBase gunner() { return gun; }
        @Override public AiProneBase prone() { return prone; }
        @Override public AiStoopBase stoop() { throw new AssertionError(); }
        @Override public AiDashBase dash() { throw new AssertionError(); }
    }

    final EntityView entities = new EntityView() {
        @Override public Body byId(int id) { Bot b = registry.bots.get(id); return lookupOverride != null ? lookupOverride : b == null ? null : b.body; }
        @Override public int players(Body[] out) { throw new AssertionError(); }
        @Override public int near(int kind, double x0, double y0, double z0, double x1, double y1, double z1, Body[] out) { throw new AssertionError(); }
    };

    final AiSupplyBase.Staff<Bot, String> staff = new AiSupplyBase.Staff<>() {
        @Override public int[] readStock(Bot b) { return b.kept; }
        @Override public void writeStock(Bot b, int[] stock) { b.kept = stock; events.add("stock:" + Arrays.toString(stock)); }
        @Override public Bot target(Bot b) { return b.target; }
        @Override public void target(Bot b, Bot to) { b.target = to; }
        @Override public AiSupplyBase.Left fetching(Bot b) { return b.fetching; }
        @Override public void fetching(Bot b, AiSupplyBase.Left left) { b.fetching = left; }
        @Override public EntityView entities(Object l) { assertSame(level, l); return entities; }
        @Override public boolean hasSpot(Bot b) { return b.spot; }
        @Override public void assignSupplier(Bot b) { b.role = AiRole.SUPPLIER; events.add("assign"); }
        @Override public int grenadeStock() { return grenadeLimit; }
        @Override public int smokeStock() { return smokeLimit; }
        @Override public void fire(Bot b, boolean firing) { assertFalse(firing); events.add("hold"); }
        @Override public PathSearch.Cost fireLines(Object l, Bot b) { assertSame(level, l); events.add("lines"); return PathSearch.Cost.NONE; }
        @Override public double runSpeed() { return 1.25; }
        @Override public void keepAmmo(Bot b) { events.add("ammo:" + b.gun.ammo.reserve()); }
        @Override public void keepGrenades(Bot b) { events.add("grenades:" + b.grenades); }
        @Override public void keepSmokes(Bot b) { events.add("smokes:" + b.smokes); }
        @Override public void note(String s, Bot b, long tick, String text) { assertEquals("brain", s); events.add(text); }
    };

    @BeforeEach
    void setup() {
        AiBotsBase.LEVELS.put(level, registry);
        AiSupplyBase.on = AiSupplyBase.outOn = true;
        AiSupplyBase.runs = AiSupplyBase.gaveUp = AiSupplyBase.mags = AiSupplyBase.grenades = AiSupplyBase.smokes = AiSupplyBase.left = AiSupplyBase.taken = 0;
        Arrays.fill(AiSupplyBase.idle, 0);
        AiSupplyBase.outs.clear();
    }

    @AfterEach
    void forget() {
        AiBotsBase.LEVELS.remove(level);
        AiSquadsBase.forgetLevel(level);
        AiSupplyBase.forgetLevel(level);
        AiSupplyBase.on = AiSupplyBase.outOn = true;
    }

    Bot bot(int id, double x) {
        Bot b = new Bot(new Person(id, x)); registry.bots.put(id, b); return b;
    }

    Bot supplier(int id, double x, int... stock) {
        Bot b = bot(id, x); b.role = AiRole.SUPPLIER; b.supply = stock; b.gun.ammo.set(100); b.grenades = b.smokes = 1; return b;
    }

    void squad(Bot... bots) {
        AiSquadsBase.forgetLevel(level);
        WorldView w = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class}, (p, m, a) -> {
            if (m.getName().equals("gameTime")) return now;
            throw new AssertionError(m.getName());
        });
        AiSquadsBase.make(new AiSquadsBase.Staff<Bot>() {
            @Override public AiSquadsBase.Squad<Bot> squad() { return new AiSquadsBase.Squad<>(); }
            @Override public void supply(List<Bot> bs, long tick) { }
            @Override public boolean leashed(Bot b, int id, long tick) { return false; }
            @Override public boolean presses(Bot b, long tick) { return false; }
        }, level, w, List.of(bots));
    }

    Bot pick(Bot b) { return AiSupplyBase.pickRecipient(staff, b, b.supply, now); }
    boolean step(Bot b, long tick) { return AiSupplyBase.supplyStep(staff, "brain", b, level, tick); }
    boolean fetch(Bot b, long tick) { return AiSupplyBase.fetchStock(staff, "brain", b, level, tick); }
    void hand(Bot b, Bot to) { AiSupplyBase.handStock(staff, "brain", b, to, b.supply, now); }
    void fell(Bot b, long tick) { AiSupplyBase.leaveStock(staff, level, b, tick); b.person.alive = false; }
    TargetMemory.Contact foe(Bot b, double x) {
        TargetMemory.Contact c = new TargetMemory.Contact(70); c.state = TargetMemory.State.VISIBLE; c.x = x; c.y = 64;
        b.memory.contacts().add(c); return c;
    }

    @Test
    void stockCopiesBothWaysAndRejectsWrongLength() {
        Bot b = bot(1, 0); b.kept = new int[] {4, 5, 6};
        assertArrayEquals(b.kept, AiSupplyBase.supplies(staff, b)); assertNotSame(b.kept, b.supply);
        b.kept[0] = 20; assertEquals(4, b.supply[0]);
        AiSupplyBase.saveStock(staff, b); assertNotSame(b.supply, b.kept);
        b.supply[0] = 8; assertEquals(4, b.kept[0]);
        b.supply = null; b.kept = new int[] {9};
        assertArrayEquals(new int[] {12, 6, 3}, AiSupplyBase.supplies(staff, b));
        assertArrayEquals(b.supply, b.kept); assertNotSame(b.supply, b.kept);
        assertEquals("12/6/3", AiSupplyBase.shownStock(staff, b));
        b.supply = null; b.kept = null; assertEquals(12, AiSupplyBase.supplies(staff, b)[0]);
    }

    @Test
    void needAmmoAndGrenadeLimits() {
        Bot b = bot(1, 0); int[] stock = {2, 9, 9};
        assertEquals(3.5, AiSupplyBase.supplyNeed(staff, b, stock));
        b.gun.ammo.set(30); assertEquals(3.5, AiSupplyBase.supplyNeed(staff, b, stock));
        b.gun.ammo.set(31); assertEquals(1.5, AiSupplyBase.supplyNeed(staff, b, stock));
        b.gun.ammo.setInfinite(); b.grenades = b.smokes = 1;
        assertEquals(0, AiSupplyBase.supplyNeed(staff, b, stock));
        b.grenades = b.smokes = 0; grenadeLimit = smokeLimit = 0;
        assertEquals(0, AiSupplyBase.supplyNeed(staff, b, stock));
        b.gun.ammo.set(0); b.capacity = 0; assertEquals(0, AiSupplyBase.supplyNeed(staff, b, stock));
    }

    @Test
    void handSpendsStockAndPersistsAfterGiving() {
        Bot b = supplier(10, 0, 4, 6, 3), to = bot(2, 1); to.gun.ammo.set(30);
        hand(b, to);
        assertEquals(90, to.gun.ammo.reserve()); assertEquals(3, to.grenades); assertEquals(2, to.smokes);
        assertArrayEquals(new int[] {2, 3, 1}, b.supply); assertArrayEquals(b.supply, b.kept);
        assertEquals(now, to.suppliedAt); assertEquals(2, AiSupplyBase.mags); assertEquals(3, AiSupplyBase.grenades); assertEquals(2, AiSupplyBase.smokes);
        assertEquals(List.of("ammo:90", "grenades:3", "smokes:2", "stock:[2, 3, 1]", "supplies: 2 magazines, 3 grenades, 2 smoke grenades to #2; left 2/3/1"), events);
        events.clear(); hand(b, to); assertArrayEquals(new int[] {2, 3, 1}, b.supply);
        assertEquals(List.of("stock:[2, 3, 1]"), events);
    }

    @Test
    void handUsesLastPortionAndDisabledLimits() {
        Bot b = supplier(10, 0, 1, 1, 1), to = bot(2, 0);
        hand(b, to); assertEquals(30, to.gun.ammo.reserve()); assertEquals(1, to.grenades); assertEquals(1, to.smokes);
        assertArrayEquals(new int[3], b.supply);
        b.supply = new int[] {2, 4, 4}; to.gun.ammo.setInfinite(); to.grenades = to.smokes = 0;
        grenadeLimit = smokeLimit = 0; hand(b, to); assertArrayEquals(new int[] {2, 4, 4}, b.supply);
    }

    @Test
    void pickKeepsFirstEqualCandidateAndNearerEqualNeed() {
        Bot b = supplier(10, 0, 4, 0, 0), a = bot(1, 4), c = bot(2, -4);
        squad(b, a, c);
        List<Bot> order = AiSquadsBase.squadOf(b).bots;
        Bot first = order.stream().filter(o -> o != b).findFirst().orElseThrow();
        assertSame(first, pick(b));
        c.person.x = 3; assertSame(c, pick(b));
        a.gun.ammo.set(100); c.gun.ammo.set(100); assertNull(pick(b)); assertEquals(1, AiSupplyBase.idle[2]);
    }

    @Test
    void pickHonorsAgainReachAndAlreadyServed() {
        Bot b = supplier(10, 0, 4, 0, 0), a = bot(1, 24), other = supplier(20, 12, 4, 0, 0);
        squad(b, a, other);
        a.suppliedAt = now - 399; assertNull(pick(b));
        a.suppliedAt--; assertSame(a, pick(b));
        a.person.x = 24.001; assertNull(pick(b));
        a.person.x = 24; other.target = a; assertNull(pick(b));
        other.person.alive = false; assertSame(a, pick(b));
        a.person.alive = false; assertNull(pick(b));
    }

    @Test
    void pickIdleReasonChecksRecencyBeforeDistance() {
        Bot b = supplier(10, 0, 4, 0, 0), a = bot(1, 2); squad(b, a);
        a.person.x = 25; a.suppliedAt = now;
        assertNull(pick(b)); assertEquals(1, AiSupplyBase.idle[5]); assertEquals(0, AiSupplyBase.idle[4]);
    }

    @Test
    void freedomExposureAndCellsKeepExactBoundaries() {
        Bot b = bot(1, -.01); b.person.y = 63.8; b.person.z = -1.01;
        assertEquals(PosLong.block(-1, 64, -2), AiSupplyBase.botCell(staff, b));
        TargetMemory.Contact c = foe(b, b.person.x + 12); c.y = b.person.y; c.z = b.person.z;
        assertTrue(AiSupplyBase.supplierFree(staff, b, now)); c.x -= .001;
        assertFalse(AiSupplyBase.supplierFree(staff, b, now));
        assertTrue(AiSupplyBase.foeVisible(staff, b)); assertTrue(AiSupplyBase.recipientExposed(staff, b));
        b.spot = true; assertTrue(AiSupplyBase.recipientExposed(staff, b));
        b.arrived = true; assertFalse(AiSupplyBase.recipientExposed(staff, b));
        b.memory.contacts().clear(); b.lying = true; assertFalse(AiSupplyBase.supplierFree(staff, b, now));
        b.lying = false; b.pinned = true; assertFalse(AiSupplyBase.supplierFree(staff, b, now));
    }

    @Test
    void stepRunDeadlineAndBackoffAreInclusive() {
        Bot b = supplier(10, 0, 4, 0, 0), to = bot(1, 10); b.target = to; b.supplyAt = 1000;
        assertTrue(step(b, 1300)); assertSame(to, b.target); assertEquals(1, b.walks);
        assertEquals(List.of("hold", "lines", "costs", "walk"), events);
        assertTrue(step(b, 1300)); assertEquals(1, b.walks);
        assertFalse(step(b, 1301)); assertNull(b.target); assertEquals(1401, b.supplyNext); assertEquals(1, AiSupplyBase.gaveUp);
    }

    @Test
    void stepHandsAtHorizontalBoundaryButNotTwoBlocksUp() {
        Bot b = supplier(10, 0, 4, 0, 0), to = bot(1, 2.2); b.target = to; b.supplyAt = now;
        to.person.y = 66; assertTrue(step(b, now)); assertEquals(0, to.gun.ammo.reserve());
        to.person.y = 65.999; assertFalse(step(b, now)); assertEquals(60, to.gun.ammo.reserve()); assertNull(b.target);
    }

    @Test
    void stepOwnPouchesBeforePickingAndOnlyOnSchedule() {
        Bot b = supplier(10, 0, 1, 0, 0), to = bot(1, 4); b.gun.ammo.set(0); squad(b, to);
        b.supplyNext = 1000;
        assertFalse(step(b, 999)); assertEquals(0, b.gun.ammo.reserve());
        assertFalse(step(b, 1001)); assertEquals(0, b.gun.ammo.reserve());
        assertFalse(step(b, 1000)); assertEquals(30, b.gun.ammo.reserve()); assertNull(b.target); assertEquals(0, AiSupplyBase.runs);
        b.supply = new int[] {4, 0, 0}; b.suppliedAt = 0; b.gun.ammo.set(100);
        assertTrue(step(b, 1000)); assertSame(to, b.target); assertEquals(1000, b.supplyAt); assertEquals(1, AiSupplyBase.runs);
        b.failed = true; assertFalse(step(b, 1001)); assertEquals(1101, b.supplyNext);
    }

    @Test
    void stepStopsForDisabledDeadEmptyAndPinned() {
        Bot b = supplier(10, 0, 4, 0, 0), to = bot(1, 10); b.target = to;
        AiSupplyBase.on = false; assertFalse(step(b, now)); assertSame(to, b.target);
        AiSupplyBase.on = true; b.supplyAt = now; to.person.alive = false; assertFalse(step(b, now)); assertNull(b.target);
        b.target = to; to.person.alive = true; to.gun.ammo.set(100); assertFalse(step(b, now));
        b.target = to; to.gun.ammo.set(0); b.pinned = true; assertFalse(step(b, now));
        b.target = null; b.supplyNext = Long.MIN_VALUE; assertFalse(step(b, now)); assertEquals(1, AiSupplyBase.idle[0]);
        b.role = AiRole.RIFLE; assertFalse(step(b, now + 1));
    }

    @Test
    void fellFetchCopiesStockAssignsAndForgets() {
        Bot fallen = supplier(10, -2.2, 4, 5, 3), heir = bot(40, 0);
        fell(fallen, now); assertEquals(1, AiSupplyBase.left); fallen.supply[0] = 60;
        assertFalse(fetch(heir, now)); assertEquals(AiRole.SUPPLIER, heir.role()); assertNull(heir.fetching);
        assertArrayEquals(new int[] {4, 5, 3}, heir.supply); assertArrayEquals(heir.supply, heir.kept); assertEquals(1, AiSupplyBase.taken);
        assertTrue(events.indexOf("assign") < events.indexOf("stock:[4, 5, 3]"));
        assertFalse(fetch(heir, now + 40)); assertEquals(1, AiSupplyBase.taken);
        Bot other = supplier(20, 10, 3, 0, 0); fell(other, now); AiSupplyBase.forgetLevel(level);
        assertFalse(fetch(heir, now + 40)); assertNull(heir.fetching);
    }

    @Test
    void fetchRunDeadlineAndFailureBackoff() {
        Bot fallen = supplier(10, 10, 4, 0, 0), heir = bot(40, 0); fell(fallen, now);
        assertTrue(fetch(heir, now)); AiSupplyBase.Left l = heir.fetching;
        assertEquals(40, l.heir); assertEquals(now, l.heirAt); assertEquals(1, heir.walks);
        assertTrue(fetch(heir, now + 300)); assertSame(l, heir.fetching);
        assertFalse(fetch(heir, now + 301)); assertNull(heir.fetching); assertEquals(1401, heir.supplyNext);
        assertEquals(-1, l.heir); assertTrue(l.failed.contains(40));
        assertFalse(fetch(heir, 1440));
        Bot next = bot(80, 0); assertTrue(fetch(next, 1440)); assertSame(l, next.fetching);
        next.failed = true; assertFalse(fetch(next, 1441)); assertTrue(l.failed.contains(80));
    }

    @Test
    void fetchPrioritizesOriginalSquadAndChecksBodyIdentity() {
        Bot fallen = supplier(10, 10, 4, 0, 0), mate = bot(40, 5), outsider = bot(80, 0);
        squad(fallen, mate); fell(fallen, now);
        assertFalse(fetch(outsider, now)); assertTrue(fetch(mate, now));
        assertFalse(fetch(outsider, now));
        mate.person.alive = false; assertTrue(fetch(outsider, now)); assertSame(mate.fetching, outsider.fetching);
        assertSame(outsider, AiSupplyBase.findBot(staff, level, 80));
        Body alien = new Person(80, 0).body;
        lookupOverride = alien; assertNull(AiSupplyBase.findBot(staff, level, 80));
        lookupOverride = null; assertNull(AiSupplyBase.findBot(staff, level, -1));
    }

    @Test
    void fetchExpiryReachScheduleRolesAndVisibleFoe() {
        Bot fallen = supplier(10, 24, 4, 0, 0), heir = bot(40, 0); fell(fallen, now);
        assertFalse(fetch(heir, now + 1)); heir.supplyNext = now + 1; assertFalse(fetch(heir, now)); heir.supplyNext = now;
        heir.role = AiRole.BASE; assertFalse(fetch(heir, now)); heir.role = AiRole.MEDIC; assertFalse(fetch(heir, now)); heir.role = AiRole.RIFLE;
        foe(heir, 100); assertFalse(fetch(heir, now)); heir.memory.contacts().clear();
        heir.person.x = -.001; assertFalse(fetch(heir, now)); heir.person.x = 0;
        assertTrue(fetch(heir, now + 2400)); heir.fetching = null;
        assertFalse(fetch(heir, now + 2440));
    }

    @Test
    void fetchMergesCapsAndHeightBoundary() {
        Bot fallen = supplier(10, 0, 10, 7, 8), heir = supplier(40, 0, 60, 63, 64); fell(fallen, now);
        heir.person.y = 66; assertTrue(fetch(heir, now));
        heir.person.y = 65.999; assertFalse(fetch(heir, now + 1));
        assertArrayEquals(new int[] {64, 64, 64}, heir.supply); assertArrayEquals(heir.supply, heir.kept); assertFalse(events.contains("assign"));
    }

    @Test
    void leftCellSquadFailureLimitAndLostRegistry() {
        AiSupplyBase.Left l = new AiSupplyBase.Left(-.1, 63.8, -2.1, new int[] {1, 0, 0}, "a", now, new int[] {4, 7});
        assertEquals(PosLong.block(-1, 64, -3), l.cell()); assertTrue(l.ofSquad(7)); assertFalse(l.ofSquad(8));
        Bot fallen = supplier(10, 10, 1, 0, 0), heir = bot(40, 0); fell(fallen, now); assertTrue(fetch(heir, now));
        AiSupplyBase.Left stock = heir.fetching; heir.fetching = null;
        stock.failed.add(1); stock.failed.add(2); stock.failed.add(3); stock.heir = -1;
        assertFalse(fetch(heir, now + 40));
        AiSupplyBase.forgetLevel(level); heir.fetching = stock;
        assertFalse(fetch(heir, now + 41)); assertNull(heir.fetching); assertEquals(now + 141, heir.supplyNext);
    }

    @Test
    void squadOutOnlyWhenFightingAndNoReserveOrSupplier() {
        Bot a = bot(1, 0), b = bot(2, 1); foe(a, 40);
        float before = b.confidence.value(now);
        AiSupplyBase.checkSquad(staff, List.of(a, b), now);
        assertEquals(1, AiSupplyBase.outs.get("a")); assertTrue(b.confidence.value(now) < before);
        a.gun.ammo.set(1); AiSupplyBase.checkSquad(staff, List.of(a, b), now); assertEquals(1, AiSupplyBase.outs.get("a"));
        a.gun.ammo.set(0); a.role = AiRole.SUPPLIER; a.supply = new int[] {1, 0, 0};
        AiSupplyBase.checkSquad(staff, List.of(a, b), now); assertEquals(1, AiSupplyBase.outs.get("a"));
        a.supply[0] = 0; a.memory.contacts().clear(); AiSupplyBase.checkSquad(staff, List.of(a, b), now); assertEquals(1, AiSupplyBase.outs.get("a"));
    }
}
