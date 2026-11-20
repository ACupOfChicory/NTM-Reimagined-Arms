package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;
import acup.gunfeel.host.WorldView;

/** Medic decisions on live fake bodies, without a game. */
class AiMedicBaseTest {
    private final Object level = new Object();
    private final Map<Integer, Unit> units = new HashMap<>();
    private final List<Body> players = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final AiBotsBase.PerLevelBase<Bot> registry = new AiBotsBase.PerLevelBase<>();
    private final Map<Bot, Integer> kept = new HashMap<>();
    private int ids, reads, writes, paths, aims;
    private Vec3d aimed;
    private long notedTo;
    private boolean crouched;

    private final EntityView entities = new EntityView() {
        @Override public @Nullable Body byId(int id) { Unit u = units.get(id); return u == null ? null : u.body; }
        @Override public int players(Body[] out) {
            if (out.length < players.size()) return -players.size();
            for (int i = 0; i < players.size(); i++) out[i] = players.get(i);
            return players.size();
        }
        @Override public int near(int kind, double x0, double y0, double z0, double x1, double y1, double z1, Body[] out) { throw new AssertionError(); }
    };

    private final AiMedicBase.Staff<Bot, Object> staff = new AiMedicBase.Staff<>() {
        @Override public @Nullable Integer keptKits(Bot b) { reads++; return kept.get(b); }
        @Override public void keepKits(Bot b, int kits) { writes++; kept.put(b, kits); events.add("keep:" + kits); }
        @Override public void heal(Body body, float amount) {
            events.add("heal:" + amount);
            Unit u = units.get(body.id());
            u.health = Math.min(u.max, u.health + amount);
        }
        @Override public boolean free(Bot b, long tick) { return b.free; }
        @Override public boolean exposed(Bot b) { return b.exposed; }
        @Override public PathSearch.Cost fireLines(Object in, Bot b) { assertSame(level, in); events.add("cost"); return PathSearch.Cost.NONE; }
        @Override public void fire(Bot b, boolean fire) { assertFalse(fire); events.add("fire"); }
        @Override public void aim(Bot b, @Nullable Vec3d at) { aims++; aimed = at; events.add("aim"); }
        @Override public void crouch(Object s, boolean crouch) { assertSame(AiMedicBaseTest.this, s); crouched = crouch; events.add("crouch"); }
        @Override public void note(Object s, Bot b, long tick, String text) {
            assertSame(AiMedicBaseTest.this, s);
            notedTo = b.medic.to;
            events.add(text);
        }
        @Override public double run() { return 1.75; }
        @Override public EntityView entities(Object in) { assertSame(level, in); return entities; }
    };

    private final class Unit {
        final int id = ++ids;
        double x, y, z;
        float health = 20, max = 20, height = 1.8F;
        boolean living = true, alive = true, removed, player, creative, spectator, hit;
        int now, hitAt;
        final List<String> hitReads = new ArrayList<>();
        byte stance;
        String team = "ours";
        final Body body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
            case "id" -> id;
            case "lastHurtBy" -> { hitReads.add("lastHurtBy"); yield hit ? p : null; }
            case "age" -> { hitReads.add("age"); yield now; }
            case "lastHurtTime" -> { hitReads.add("lastHurtTime"); yield hitAt; }
            case "x" -> x;
            case "y" -> y;
            case "z" -> z;
            case "health" -> health;
            case "maxHealth" -> max;
            case "bbHeight" -> height;
            case "living" -> living;
            case "alive" -> alive;
            case "removed" -> removed;
            case "player" -> player;
            case "creative" -> creative;
            case "spectator" -> spectator;
            case "stance" -> stance;
            case "team" -> team;
            case "level" -> level;
            case "tagged" -> false;
            case "hashCode" -> id;
            case "equals" -> p == a[0];
            case "toString" -> "body#" + id;
            default -> throw new AssertionError("not asked: " + m.getName());
        });

        Unit(double x, float health) {
            this.x = x;
            this.health = health;
            units.put(id, this);
        }
    }

    private final class Move extends AiMoverBase {
        long goal = Long.MIN_VALUE;
        boolean failed;
        int stops;
        Move(Body body) { super(body); }
        @Override public long goal() { return goal; }
        @Override public boolean goalIs(long cell) { return goal == cell; }
        @Override public boolean failed() { return failed; }
        @Override public void stop() { stops++; goal = Long.MIN_VALUE; events.add("stop"); }
        @Override public void costs(PathSearch.Cost cost) { assertSame(PathSearch.Cost.NONE, cost); events.add("costs"); }
        @Override public void goTo(long cell, double radius, double speed) {
            assertEquals(1.5, radius);
            assertEquals(1.75, speed);
            goal = cell;
            paths++;
            events.add("go");
        }
    }

    private final class Bot extends AiBotBase {
        final Unit unit;
        final Move mover;
        final AiProneBase prone;
        boolean free = true, exposed;

        Bot(Unit u, AiRole role) {
            super(u.body, SensesTest.P);
            unit = u;
            this.role = role;
            mover = new Move(body);
            prone = new AiProneBase(body, () -> true) {};
            registry.bots.put(u.id, this);
            AiBotsBase.LEVELS.put(level, registry);
        }
        @Override public AiRole role() { return role; }
        @Override public boolean soldier() { return true; }
        @Override public boolean onTheMove() { return false; }
        @Override public boolean inCover() { return false; }
        @Override public boolean searching() { return false; }
        @Override public boolean fightsFallen(int target) { return false; }
        @Override public boolean fallingBack() { return false; }
        @Override public boolean marksman() { return false; }
        @Override public boolean perched() { return false; }
        @Override public boolean atPerch() { return false; }
        @Override public boolean grenadier() { return false; }
        @Override public Object serverLevel() { return level; }
        @Override public WorldView world() { throw new AssertionError(); }
        @Override public Move mover() { return mover; }
        @Override public AiGunnerBase gunner() { throw new AssertionError(); }
        @Override public AiProneBase prone() { return prone; }
        @Override public AiStoopBase stoop() { throw new AssertionError(); }
        @Override public AiDashBase dash() { throw new AssertionError(); }
    }

    private Bot medic() { return new Bot(new Unit(0, 20), AiRole.MEDIC); }
    private Bot patient(double x, float health) { return new Bot(new Unit(x, health), AiRole.RIFLE); }
    private Unit player(double x, float health) {
        Unit u = new Unit(x, health);
        u.player = true;
        players.add(u.body);
        return u;
    }
    private long look(Bot b) { return 1000 - b.body.id(); }
    private boolean step(Bot b, long tick) { return AiMedicBase.tickMedic(staff, this, b, level, tick); }
    private Body pick(Bot b) { return AiMedicBase.choosePatient(staff, b, level); }
    private void target(Bot b, Unit u, long tick) {
        AiMedicBase.kitCount(staff, b);
        b.medic.to = u.id;
        b.medic.at = tick;
    }
    private void squad(Bot... bots) {
        AiSquadsBase.forgetLevel(level);
        AiSquadsBase.on = true;
        WorldView w = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class}, (p, m, a) -> {
            if (m.getName().equals("gameTime")) return 1000L;
            throw new AssertionError(m.getName());
        });
        double[] x = new double[bots.length];
        for (int i = 0; i < bots.length; i++) {
            x[i] = bots[i].unit.x;
            bots[i].unit.x = 0;
        }
        AiSquadsBase.make(new AiSquadsBase.Staff<Bot>() {
            @Override public AiSquadsBase.Squad<Bot> squad() { return new AiSquadsBase.Squad<>(); }
            @Override public void supply(List<Bot> all, long tick) {}
            @Override public boolean leashed(Bot b, int id, long tick) { return false; }
            @Override public boolean presses(Bot b, long tick) { return false; }
        }, level, w, List.of(bots));
        for (int i = 0; i < bots.length; i++) bots[i].unit.x = x[i];
        assertNotNull(AiSquadsBase.squadOf(bots[0]));
    }

    @AfterEach
    void reset() {
        AiBotsBase.LEVELS.remove(level);
        AiSquadsBase.forgetLevel(level);
        AiSquadsBase.on = AiMedicBase.on = AiMedicBase.cover = true;
        AiMedicBase.runs = AiMedicBase.gaveUp = AiMedicBase.treated = AiMedicBase.self = 0;
        AiMedicBase.toCover = AiMedicBase.underFire = 0;
        AiMedicBase.healed = 0;
        acup.gunfeel.ServerSettings.useRemote(null);
        java.util.Arrays.fill(AiMedicBase.idle, 0);
        java.util.Arrays.fill(AiMedicBase.down, 0);
    }

    @Test
    void absentKitsDifferFromZeroAndAreKeptOnce() {
        Bot b = medic();
        assertEquals(6, AiMedicBase.kitCount(staff, b));
        assertEquals(6, kept.get(b));
        assertEquals(1, reads);
        assertEquals(1, writes);
        assertEquals(6, AiMedicBase.kitCount(staff, b));
        assertEquals(1, reads);
        b.medic = null;
        kept.put(b, 0);
        assertEquals(0, AiMedicBase.kitCount(staff, b));
        b.medic = null;
        kept.put(b, -9);
        assertEquals(0, AiMedicBase.kitCount(staff, b));
        b.medic = null;
        kept.put(b, 100);
        assertEquals(100, AiMedicBase.kitCount(staff, b));
    }

    @Test
    void keepIgnoresMissingOrUninitializedState() {
        Bot b = medic();
        AiMedicBase.keepStock(staff, b);
        AiMedicBase.stateOf(staff, b);
        AiMedicBase.keepStock(staff, b);
        assertEquals(0, writes);
        b.medic.kits = 0;
        AiMedicBase.keepStock(staff, b);
        assertEquals(0, kept.get(b));
    }

    @Test
    void settingsClampOnlyNewStockAndShare() {
        Bot b = medic();
        for (String value : List.of("-2", "0", "2.6", "100")) {
            acup.gunfeel.ServerSettings.useRemote(new acup.gunfeel.ServerSettings.Snapshot("", Map.of(AiMedicBase.KITS_KEY, value, AiMedicBase.SHARE_KEY, value), Map.of(), ""));
            b.medic = null;
            kept.clear();
            float v = Float.parseFloat(value);
            assertEquals(Math.max(0, Math.min(64, Math.round(v))), AiMedicBase.kitCount(staff, b));
            assertEquals(Math.max(0F, Math.min(1F, v)), AiMedicBase.medicShare(staff));
        }
        acup.gunfeel.ServerSettings.useRemote(new acup.gunfeel.ServerSettings.Snapshot("", Map.of(AiMedicBase.KEY, "false"), Map.of(), ""));
        assertFalse(AiMedicBase.enabled(staff));
        assertFalse(AiMedicBase.careUnderFire(staff));
        acup.gunfeel.ServerSettings.useRemote(new acup.gunfeel.ServerSettings.Snapshot("", Map.of(AiMedicBase.COVER_KEY, "false"), Map.of(), ""));
        assertTrue(AiMedicBase.enabled(staff));
        assertFalse(AiMedicBase.careUnderFire(staff));
    }

    @Test
    void hurtBoundaryAndDeath() {
        Unit u = new Unit(0, 12);
        assertFalse(AiMedicBase.wounded(staff, u.body));
        u.health = Math.nextDown(12F);
        assertTrue(AiMedicBase.wounded(staff, u.body));
        u.alive = false;
        assertFalse(AiMedicBase.wounded(staff, u.body));
    }

    @Test
    void worstThenNearestAndBotBeforeEqualPlayer() {
        Bot b = medic(), a = patient(4, 6), c = patient(3, 8);
        squad(b, a, c);
        assertSame(a.body, pick(b));
        c.unit.health = 6;
        assertSame(c.body, pick(b));
        Unit p = player(3, 6);
        assertSame(c.body, pick(b));
        p.x = 2;
        assertSame(p.body, pick(b));
        p.health = 8;
        assertSame(c.body, pick(b));
    }

    @Test
    void squadAndPlayerTiesKeepTraversalOrder() {
        Bot b = medic(), a = patient(3, 6), c = patient(-3, 6);
        squad(b, a, c);
        var s = AiSquadsBase.squadOf(b);
        s.bots.clear();
        s.bots.addAll(List.of(b, a, c));
        assertSame(a.body, pick(b));
        s.bots.clear();
        s.bots.addAll(List.of(b, c, a));
        assertSame(c.body, pick(b));
        AiSquadsBase.forgetLevel(level);
        Unit first = player(3, 6), second = player(-3, 6);
        assertSame(first.body, pick(b));
        java.util.Collections.swap(players, 0, 1);
        assertSame(second.body, pick(b));
    }

    @Test
    void reachIncludesBoundaryForBotsAndPlayers() {
        Bot b = medic(), a = patient(24, 6);
        squad(b, a);
        assertSame(a.body, pick(b));
        a.unit.x = Math.nextUp(24.0);
        assertNull(pick(b));
        Unit p = player(24, 6);
        assertSame(p.body, pick(b));
        p.x = Math.nextUp(24.0);
        assertNull(pick(b));
    }

    @Test
    void playersFilterModesTeamsHealthAndRecentIntHits() {
        Bot b = medic();
        Unit p = player(2, 6);
        p.creative = true;
        assertNull(pick(b));
        p.creative = false;
        p.spectator = true;
        assertNull(pick(b));
        p.spectator = false;
        p.team = "theirs";
        assertNull(pick(b));
        p.team = "ours";
        p.alive = false;
        assertNull(pick(b));
        p.alive = true;
        p.health = 12;
        assertNull(pick(b));
        p.health = 6;
        p.hit = true;
        p.now = 300;
        p.hitAt = 201;
        assertNull(pick(b));
        p.hitAt = 200;
        assertSame(p.body, pick(b));
        p.now = Integer.MIN_VALUE + 10;
        p.hitAt = Integer.MAX_VALUE - 10;
        assertNull(pick(b));
        p.hit = false;
        assertSame(p.body, pick(b));
    }

    @Test
    void recentHitReadsShortCircuitInEntityOrder() {
        Bot b = medic();
        Unit p = player(25, 6);
        assertNull(pick(b));
        assertTrue(p.hitReads.isEmpty());
        p.x = 2;
        assertSame(p.body, pick(b));
        assertEquals(List.of("lastHurtBy"), p.hitReads);
        p.hitReads.clear();
        p.hit = true;
        p.now = 300;
        p.hitAt = 201;
        assertNull(pick(b));
        assertEquals(List.of("lastHurtBy", "age", "lastHurtTime"), p.hitReads);
    }

    @Test
    void playersGrowWithoutLosingOrder() {
        Bot b = medic();
        for (int i = 0; i < 17; i++) player(2, 20);
        Unit p = player(2, 6), q = player(2, 6);
        assertSame(p.body, pick(b));
        players.remove(p.body);
        assertSame(q.body, pick(b));
    }

    @Test
    void servedExposedDeadAndInactiveBotsAreSkipped() {
        Bot b = medic(), a = patient(3, 6), other = medic();
        squad(b, a, other);
        target(other, a.unit, 0);
        assertTrue(AiMedicBase.patientTaken(staff, level, b, a.unit.id));
        assertNull(pick(b));
        other.role = AiRole.RIFLE;
        assertSame(a.body, pick(b));
        other.role = AiRole.MEDIC;
        other.active = false;
        assertSame(a.body, pick(b));
        other.active = true;
        other.unit.removed = true;
        assertSame(a.body, pick(b));
        other.unit.removed = false;
        other.medic.to = -1;
        a.exposed = true;
        assertNull(pick(b));
        a.exposed = false;
        a.active = false;
        assertNull(pick(b));
        a.active = true;
        a.unit.alive = false;
        assertNull(pick(b));
    }

    @Test
    void servedPlayersAreSkippedToo() {
        Bot b = medic(), other = medic();
        Unit p = player(2, 6);
        target(other, p, 0);
        assertNull(pick(b));
        other.medic.to = -1;
        assertSame(p.body, pick(b));
        target(b, p, 0);
        assertFalse(AiMedicBase.patientTaken(staff, level, b, p.id));
    }

    @Test
    void selfFirstAndTreatAtExactTick() {
        Bot b = medic();
        b.unit.health = 8;
        player(1, 1);
        long t = look(b);
        assertTrue(step(b, t));
        assertEquals(b.body.id(), b.medic.to);
        assertEquals(t, b.medic.treating);
        assertEquals(0, AiMedicBase.runs);
        assertTrue(crouched);
        assertEquals(0, aims);
        assertTrue(step(b, t + 59));
        assertEquals(8, b.body.health());
        assertTrue(step(b, t + 60));
        assertEquals(18, b.body.health());
        assertEquals(1, AiMedicBase.self);
        assertEquals(0, AiMedicBase.treated);
        assertEquals(10, AiMedicBase.healed);
        assertEquals(5, kept.get(b));
        assertEquals(-1, b.medic.to);
        assertEquals(Long.MIN_VALUE, b.medic.treating);
        assertEquals(t + 80, b.medic.next);
    }

    @Test
    void exposedSelfLooksForAnotherAndNoKitsNeverStarts() {
        Bot b = medic();
        b.unit.health = 4;
        b.exposed = true;
        Unit p = player(1, 6);
        assertTrue(step(b, look(b)));
        assertEquals(p.id, b.medic.to);
        assertEquals(1, AiMedicBase.runs);
        b.medic.kits = 0;
        assertFalse(step(b, look(b) + 1));
        assertEquals(-1, b.medic.to);
        assertEquals(Long.MIN_VALUE, b.medic.treating);
    }

    @Test
    void lookPeriodAndNextIncludeExactBoundary() {
        Bot b = medic();
        player(1, 6);
        long t = look(b);
        assertFalse(step(b, t - 1));
        b.medic.next = t + 10;
        assertFalse(step(b, t));
        assertTrue(step(b, t + 10));
        assertEquals(t + 10, b.medic.at);
    }

    @Test
    void roleAndSwitchCancelWithoutCreatingState() {
        Bot b = medic();
        AiMedicBase.on = false;
        assertFalse(step(b, 1000));
        assertNull(b.medic);
        AiMedicBase.on = true;
        target(b, player(1, 6), 0);
        b.medic.treating = 5;
        b.role = AiRole.RIFLE;
        assertFalse(step(b, 1000));
        assertEquals(-1, b.medic.to);
        assertEquals(Long.MIN_VALUE, b.medic.treating);
    }

    @Test
    void freeFailuresCountEachIdleReason() {
        Bot b = medic();
        b.free = false;
        long t = look(b);
        assertFalse(step(b, t));
        assertEquals(1, AiMedicBase.idle[2]);
        b.unit.stance = Stance.PRONE;
        assertFalse(step(b, t));
        assertEquals(1, AiMedicBase.idle[1]);
        b.prone.pin(t);
        assertFalse(step(b, t));
        assertEquals(1, AiMedicBase.idle[0]);
    }

    @Test
    void runTimeoutIsStrictAndUsesElapsedTime() {
        Bot b = medic();
        Unit p = player(8, 6);
        target(b, p, 100);
        assertTrue(step(b, 400));
        assertFalse(step(b, 401));
        assertEquals(1, AiMedicBase.gaveUp);
        assertEquals(501, b.medic.next);
        assertEquals(-1, b.medic.to);
        assertEquals(Long.MIN_VALUE, b.medic.treating);
    }

    @Test
    void inHandIgnoresRunTimeoutAndHeightIsStrict() {
        Bot b = medic();
        Unit p = player(2, 6);
        target(b, p, 0);
        p.y = Math.nextDown(2.0);
        assertTrue(step(b, 1000));
        assertEquals(1000, b.medic.treating);
        target(b, p, 0);
        b.medic.treating = Long.MIN_VALUE;
        p.y = 2;
        assertFalse(step(b, 1000));
    }

    @Test
    void movingPatientRestartsTreatmentAndRunClock() {
        Bot b = medic();
        Unit p = player(2, 6);
        target(b, p, 100);
        assertTrue(step(b, 100));
        p.x = 3.5;
        assertTrue(step(b, 159));
        assertEquals(100, b.medic.treating);
        p.x = Math.nextUp(3.5);
        assertTrue(step(b, 160));
        assertEquals(Long.MIN_VALUE, b.medic.treating);
        assertEquals(160, b.medic.at);
        assertEquals(6, b.medic.kits);
        p.x = 2;
        assertTrue(step(b, 161));
        assertEquals(161, b.medic.treating);
        assertTrue(step(b, 220));
        assertEquals(6, p.health);
        assertTrue(step(b, 221));
        assertEquals(16, p.health);
    }

    @Test
    void reGoalIsStrictCostsPrecedeMovementAndCellFloors() {
        Bot b = medic();
        Unit p = player(8.5, 6);
        p.y = -0.3;
        p.z = -2.2;
        target(b, p, 100);
        assertTrue(step(b, 100));
        assertEquals(PosLong.block(8, -1, -3), b.mover.goal);
        assertEquals(List.of("fire", "cost", "costs", "go"), events.subList(events.size() - 4, events.size()));
        p.x = 8.5;
        p.y = -1;
        p.z = -0.5;
        assertTrue(step(b, 101));
        assertEquals(1, paths);
        p.z = -0.5 + 1e-6;
        assertTrue(step(b, 102));
        assertEquals(2, paths);
    }

    @Test
    void treatmentStopsAndAimsBeforeHealing() {
        Bot b = medic();
        Unit p = player(2, 6);
        target(b, p, 100);
        b.mover.goal = 123;
        assertTrue(step(b, 100));
        assertEquals(1, b.mover.stops);
        assertEquals(new Vec3d(2, p.height * 0.5, 0), aimed);
        events.clear();
        assertTrue(step(b, 160));
        assertEquals("fire", events.get(0));
        assertEquals("crouch", events.get(1));
        assertEquals("aim", events.get(2));
        assertEquals("heal:10.0", events.get(3));
        assertEquals("keep:5", events.get(4));
        assertEquals(16, p.health);
        assertEquals(1, AiMedicBase.treated);
        assertEquals(0, AiMedicBase.self);
        assertEquals(10, AiMedicBase.healed);
        assertEquals(180, b.medic.next);
        assertEquals(p.id, notedTo);
        assertEquals("medic: treated #" + p.id + ", 6 to 16 of 20; 5 kits left", events.get(5));
    }

    @Test
    void healCapsHealthAndCountsActualDelta() {
        Bot b = medic();
        Unit p = player(1, 18);
        target(b, p, 100);
        b.medic.treating = 100;
        events.clear();
        AiMedicBase.useKit(staff, this, b, p.body, 160);
        assertEquals(20, p.health);
        assertEquals(2, AiMedicBase.healed);
        assertEquals(5, kept.get(b));
        assertEquals(List.of("heal:10.0", "keep:5", "medic: treated #" + p.id + ", 18 to 20 of 20; 5 kits left"), events);
        assertEquals(180, b.medic.next);
    }

    @Test
    void cancellationForDeathHealthModesTeamFreeAndFailedGoal() {
        for (int reason = 0; reason < 7; reason++) {
            Bot b = medic();
            Unit p = player(8, 6);
            target(b, p, 100);
            switch (reason) {
                case 0 -> p.alive = false;
                case 1 -> p.health = 12;
                case 2 -> p.creative = true;
                case 3 -> p.spectator = true;
                case 4 -> p.team = "theirs";
                case 5 -> b.free = false;
                case 6 -> {
                    b.mover.failed = true;
                    b.mover.goal = AiMedicBase.patientCell(staff, p.body);
                }
            }
            events.clear();
            assertFalse(step(b, 101), "reason " + reason);
            assertFalse(events.contains("fire"));
            assertEquals(-1, b.medic.to);
            assertEquals(201, b.medic.next);
        }
        assertEquals(7, AiMedicBase.gaveUp);
    }

    @Test
    void cancelledTreatmentDoesNotCountAsFailedRun() {
        Bot b = medic();
        Unit p = player(1, 6);
        target(b, p, 100);
        b.medic.treating = 100;
        b.free = false;
        assertFalse(step(b, 101));
        assertEquals(0, AiMedicBase.gaveUp);
        assertEquals(201, b.medic.next);
        assertEquals(0, AiMedicBase.treated);
    }

    @Test
    void missingOrNonlivingTargetIsForgottenBeforeNextLook() {
        Bot b = medic();
        Unit p = player(1, 6);
        target(b, p, 100);
        b.medic.treating = 100;
        p.living = false;
        assertFalse(step(b, look(b) + 1));
        assertEquals(-1, b.medic.to);
        assertEquals(Long.MIN_VALUE, b.medic.treating);
        b.medic.to = 9999;
        assertFalse(step(b, look(b) + 1));
        assertEquals(-1, b.medic.to);
        assertEquals(0, AiMedicBase.gaveUp);
    }

    @Test
    void coverCancelsRunsStopsMovementAndCountsOnlyLookTicks() {
        Bot b = medic(), mate = patient(3, 6);
        squad(b, mate);
        var foe = mate.memory.seen(500, 20, 0, 0, 100, 100);
        foe.state = TargetMemory.State.VISIBLE;
        target(b, mate.unit, 100);
        b.mover.goal = 123;
        long t = look(b);
        assertFalse(step(b, t));
        assertEquals(1, b.mover.stops);
        assertEquals(1, AiMedicBase.gaveUp);
        assertEquals(1, AiMedicBase.underFire);
        assertEquals(-1, b.medic.to);
        assertEquals(Long.MIN_VALUE, b.medic.treating);
        assertFalse(step(b, t + 1));
        assertEquals(1, AiMedicBase.underFire);
        assertEquals(1, AiMedicBase.gaveUp);
        AiMedicBase.cover = false;
        assertTrue(step(b, t + 10));
        assertEquals(1, AiMedicBase.runs);
    }

    @Test
    void coverCancelsSelfAndTreatmentWithoutFailedRun() {
        Bot b = medic();
        b.unit.health = 6;
        long t = look(b);
        assertTrue(step(b, t));
        b.memory.seen(500, 20, 0, 0, t, 100).state = TargetMemory.State.VISIBLE;
        assertFalse(step(b, t + 1));
        assertEquals(0, AiMedicBase.gaveUp);
        assertEquals(-1, b.medic.to);
        assertEquals(6, b.unit.health);
        assertEquals(0, AiMedicBase.self);
    }

    @Test
    void fightOwnFirstThenLiveSquadAndWaitsNeedStock() {
        Bot b = medic(), mate = patient(3, 6);
        squad(b, mate);
        var theirs = mate.memory.seen(500, 20, 0, 0, 100, 100);
        theirs.state = TargetMemory.State.VISIBLE;
        assertSame(theirs, AiMedicBase.visibleFoe(staff, b));
        var own = b.memory.seen(501, 10, 0, 0, 100, 100);
        own.state = TargetMemory.State.VISIBLE;
        assertSame(own, AiMedicBase.visibleFoe(staff, b));
        own.state = TargetMemory.State.LOST;
        mate.active = false;
        assertNull(AiMedicBase.visibleFoe(staff, b));
        mate.active = true;
        assertTrue(AiMedicBase.awaitsCare(staff, mate));
        assertFalse(AiMedicBase.awaitsCare(staff, b));
        b.medic.kits = 0;
        assertFalse(AiMedicBase.awaitsCare(staff, mate));
        b.medic.kits = 1;
        AiMedicBase.cover = false;
        assertFalse(AiMedicBase.awaitsCare(staff, mate));
    }

    @Test
    void fellShownAndInfoUseCoreState() {
        Bot b = medic();
        AiMedicBase.medicFell(staff, b);
        assertArrayEquals(new int[3], AiMedicBase.down);
        assertEquals("6 kits", AiMedicBase.kitLabel(staff, b));
        AiMedicBase.medicFell(staff, b);
        b.medic.to = 100;
        AiMedicBase.medicFell(staff, b);
        b.medic.treating = 0;
        AiMedicBase.medicFell(staff, b);
        assertArrayEquals(new int[] {1, 1, 1}, AiMedicBase.down);
        assertTrue(AiMedicBase.medicInfo(staff).endsWith("medics down on a run, treating, else: [1, 1, 1]"));
        assertEquals(0.12F, AiMedicBase.medicShare(staff));
    }
}
