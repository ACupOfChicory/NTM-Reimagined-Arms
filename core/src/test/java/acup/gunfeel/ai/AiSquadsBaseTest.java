package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.WorldView;

// squads off the game: soldiers of fake bodies where the test puts them, the staff faked, a level any object
class AiSquadsBaseTest {
    private final Object level = new Object();
    private long time = 1000;
    private final WorldView world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class}, (p, m, a) -> {
        if (m.getName().equals("gameTime")) return time;
        throw new AssertionError("not asked: " + m.getName());
    });
    private final List<Bot> supplied = new ArrayList<>();
    private boolean leash;

    private final AiSquadsBase.Staff<Bot> staff = new AiSquadsBase.Staff<>() {
        @Override
        public AiSquadsBase.Squad<Bot> squad() {
            return new AiSquadsBase.Squad<>();
        }

        @Override
        public void supply(List<Bot> bots, long now) {
            supplied.addAll(bots);
        }

        @Override
        public boolean leashed(Bot bot, int target, long tick) {
            assertEquals(time, tick);
            return leash;
        }

        @Override
        public boolean presses(Bot bot, long tick) {
            return false;
        }
    };

    /** a soldier where it is put, of a team ("" none), a rifleman unless its role is set; searching if told */
    final class Bot extends AiBotBase {
        double x, y, z;
        final int id;
        @Nullable String team;
        AiRole as = AiRole.RIFLE;
        boolean soldier = true, searching;
        final AiMoverBase mover;
        final AiProneBase prone;

        Bot(int id, @Nullable String team, double x, double z) {
            super(body(id), SensesTest.P);
            this.id = id;
            this.team = team;
            this.x = x;
            this.z = z;
            mover = new AiMoverBase(body) {
            };
            prone = new AiProneBase(body, () -> true) {
            };
            bodies.put(id, this);
        }

        @Override
        public AiRole role() {
            return as;
        }

        @Override
        public boolean soldier() {
            return soldier;
        }

        @Override
        public boolean onTheMove() {
            return false;
        }

        @Override
        public boolean inCover() {
            return false;
        }

        @Override
        public boolean searching() {
            return searching;
        }

        @Override
        public boolean fightsFallen(int target) {
            return false;
        }

        @Override
        public boolean fallingBack() {
            return false;
        }

        @Override
        public boolean marksman() {
            return as == AiRole.MARKSMAN;
        }

        @Override
        public boolean perched() {
            return false;
        }

        @Override
        public boolean atPerch() {
            return false;
        }

        @Override
        public boolean grenadier() {
            return as == AiRole.GRENADIER;
        }

        @Override
        public @Nullable Object serverLevel() {
            return level;
        }

        @Override
        public WorldView world() {
            return world;
        }

        @Override
        public AiMoverBase mover() {
            return mover;
        }

        @Override
        public AiGunnerBase gunner() {
            throw new AssertionError("the gun not asked");
        }

        @Override
        public AiProneBase prone() {
            return prone;
        }

        @Override
        public AiStoopBase stoop() {
            throw new AssertionError();
        }

        @Override
        public AiDashBase dash() {
            throw new AssertionError();
        }
    }

    private final java.util.Map<Integer, Bot> bodies = new java.util.HashMap<>();

    /** the body of the bot of this id, read from it as it is now */
    private Body body(int id) {
        return (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
            Bot b = bodies.get(id);
            return switch (m.getName()) {
                case "id" -> id;
                case "x" -> b.x;
                case "y" -> b.y;
                case "z" -> b.z;
                case "team" -> b.team;
                case "alive" -> true;
                case "removed" -> false;
                case "stance" -> Stance.STAND;
                case "hashCode" -> id;
                case "equals" -> p == a[0];
                default -> throw new AssertionError("the body not asked: " + m.getName());
            };
        });
    }

    private final AiBotsBase.PerLevelBase<Bot> registry = new AiBotsBase.PerLevelBase<>();

    private Bot bot(int id, @Nullable String team, double x, double z) {
        Bot b = new Bot(id, team, x, z);
        registry.bots.put(id, b);
        AiBotsBase.LEVELS.put(level, registry);
        return b;
    }

    @AfterEach
    void forget() {
        AiSquadsBase.forgetLevel(level);
        AiBotsBase.LEVELS.remove(level);
    }

    private void make() {
        AiSquadsBase.make(staff, level, world, registry.bots.values());
    }

    @Test
    void nearOnesOfASide() {
        Bot a = bot(1, "red", 0, 0), b = bot(2, "red", 10, 0), c = bot(3, "red", 25, 0), far = bot(4, "red", 60, 0), blue = bot(5, "blue", 5, 0);
        make();
        // chained: c is within NEAR of b, not of a
        List<AiSquadsBase.Squad<?>> squads = AiSquadsBase.squadsOf(level);
        assertEquals(1, squads.size(), "the lone red and the lone blue are no squad");
        assertEquals(java.util.Set.of(a, b, c), java.util.Set.copyOf(squads.get(0).bots));
        assertNull(AiSquadsBase.squadOf(far));
        assertNull(AiSquadsBase.squadOf(blue));
        assertSame(squads.get(0), AiSquadsBase.squadOf(b));
        // its supplies looked at once, as it was made
        assertEquals(3, supplied.size());
        // not again before EVERY ticks
        far.x = 20;
        time += AiSquadsBase.EVERY - 1;
        make();
        assertNull(AiSquadsBase.squadOf(far));
        time += 1;
        make();
        assertSame(AiSquadsBase.squadOf(a), AiSquadsBase.squadOf(far));
    }

    @Test
    void atMostMax() {
        for (int i = 0; i < 8; i++) bot(i + 1, null, i, 0);
        make();
        List<AiSquadsBase.Squad<?>> squads = AiSquadsBase.squadsOf(level);
        assertEquals(2, squads.size());
        assertEquals(AiSquadsBase.MAX, squads.get(0).bots.size());
        assertEquals(2, squads.get(1).bots.size());
        assertEquals("", AiSquadsBase.teamOf(squads.get(0).bots.get(0)));
    }

    @Test
    void aBaseOfFireFurther() {
        Bot a = bot(1, "red", 0, 0), mg = bot(2, "red", 25, 0);
        mg.as = AiRole.BASE;
        make();
        AiSquadsBase.Squad<Bot> s = AiSquadsBase.squadOf(a);
        assertSame(s, AiSquadsBase.squadOf(mg), "within BASE_REACH times NEAR");
        assertTrue(s.based);
        assertTrue(AiSquadsBase.baseOfFire(mg));
        assertFalse(AiSquadsBase.baseOfFire(a));
        assertSame(mg, AiSquadsBase.holderOf(a, AiRole.BASE));
        assertSame(a, AiSquadsBase.holderOf(mg, AiRole.RIFLE));
        assertNull(AiSquadsBase.holderOf(a, AiRole.MEDIC));
        assertEquals("rifle 1, base 1", AiSquadsBase.rolesOf(registry.bots.values()));
    }

    @Test
    void theFlankerOffTheLine() {
        // the target seen north of the squad's west end; the one most off the line from it to the squad's middle goes
        // round: the west one (its cosine 0.981, the east one's 0.983)
        Bot a = bot(1, "red", 0, 0), b = bot(2, "red", 6, 0), c = bot(3, "red", 12, 0);
        for (Bot x : List.of(a, b, c)) {
            TargetMemory.Contact t = x.memory.seen(99, 0, 0, -30, time, 100);
            // seen whole (not a glimpse): known where it is
            t.state = TargetMemory.State.VISIBLE;
            t.x = 0;
            t.z = -30;
            x.memory.focusId = 99;
            assertEquals(99, t.id);
        }
        make();
        AiSquadsBase.Squad<Bot> s = AiSquadsBase.squadOf(a);
        assertEquals(99, s.target);
        assertEquals(1, s.flanker);
        assertTrue(AiSquadsBase.flanksTarget(staff, s.flanker == 1 ? a : c, 99));
        leash = true;
        assertFalse(AiSquadsBase.flanksTarget(staff, s.flanker == 1 ? a : c, 99), "on a leash");
    }

    @Test
    void movesOnlyCovered() {
        Bot a = bot(1, "red", 0, 0);
        bot(2, "red", 5, 0);
        // alone: free to go
        Bot alone = bot(3, "blue", 100, 0);
        make();
        assertTrue(AiSquadsBase.mayMoveNow(alone));
        // none of the rest in cover ready to fire
        assertFalse(AiSquadsBase.mayMoveNow(a));
    }

    @Test
    void twoHuntAtMost() {
        Bot a = bot(1, "red", 0, 0), b = bot(2, "red", 10, 0), c = bot(3, "red", 20, 0), far = bot(4, "red", 100, 0);
        for (Bot x : List.of(b, c, far)) {
            x.memory.focusId = 99;
            x.searching = true;
        }
        assertFalse(AiSquadsBase.mayHuntNow(staff, a, 99), "two of its side within HUNT_NEAR at it");
        c.searching = false;
        assertTrue(AiSquadsBase.mayHuntNow(staff, a, 99), "one; the far one counts for nothing");
        AiSquadsBase.offTeams.add("red");
        try {
            c.searching = true;
            assertTrue(AiSquadsBase.mayHuntNow(staff, a, 99), "a team fighting as before");
        } finally {
            AiSquadsBase.offTeams.remove("red");
        }
        leash = true;
        assertFalse(AiSquadsBase.mayHuntNow(staff, a, 99), "on a leash");
    }

    @Test
    void distanceAsTheGame() {
        Bot a = bot(1, null, 1.5, -2.25), b = bot(2, null, -0.75, 3);
        a.y = 64.2;
        b.y = 63;
        double dx = 1.5 - -0.75, dy = 64.2 - 63, dz = -2.25 - 3;
        assertEquals(dx * dx + dy * dy + dz * dz, AiSquadsBase.distanceSqr(a, b));
    }
}
