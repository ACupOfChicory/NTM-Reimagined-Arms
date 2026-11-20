package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;
import acup.gunfeel.host.WorldView;

/** Side reports on plain bodies, in the registry's order and the level's player order. */
class AiIntelBaseTest {
    private final Object level = new Object();
    private final AiBotsBase.PerLevelBase<Bot> registry = new AiBotsBase.PerLevelBase<>();
    private final List<Person> players = new ArrayList<>();
    private final Map<String, List<AiBotBase>> sides = new LinkedHashMap<>();
    private boolean allAwake;
    private double radius = 16;
    private int radiusCalls, awakeCalls, playerCalls, shortCalls;
    private long now = 1000;

    private final AiIntelBase.Scout scout = new AiIntelBase.Scout() {
        @Override public double radius() { radiusCalls++; return radius; }
        @Override public boolean allAwake() { awakeCalls++; return allAwake; }
    };
    private final EntityView view = new EntityView() {
        @Override public Body byId(int id) { throw new AssertionError("not asked"); }
        @Override public int near(int k, double x0, double y0, double z0, double x1, double y1, double z1, Body[] out) { throw new AssertionError("not asked"); }
        @Override public int players(Body[] out) {
            playerCalls++;
            if (out.length < players.size()) { shortCalls++; return -players.size(); }
            for (int i = 0; i < players.size(); i++) out[i] = players.get(i).body;
            return players.size();
        }
    };

    final class Person {
        final int id;
        final boolean player;
        String team;
        double x, y, z;
        boolean alive = true, removed, spectator, creative;
        float health = 7, maxHealth = 20;
        final Body body;

        Person(int id, boolean player, String team, double x) {
            this.id = id;
            this.player = player;
            this.team = team;
            this.x = x;
            body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
                case "id" -> id;
                case "living" -> true;
                case "player" -> player;
                case "team" -> this.team;
                case "x" -> this.x;
                case "y" -> y;
                case "z" -> z;
                case "alive" -> alive;
                case "removed" -> removed;
                case "spectator" -> spectator;
                case "creative" -> creative;
                case "health" -> health;
                case "maxHealth" -> maxHealth;
                case "level" -> level;
                case "tagged" -> false;
                default -> throw new AssertionError("not asked: " + m.getName());
            });
        }
    }

    static final class Bot extends AiBotBase {
        Bot(Body body) { super(body, SensesTest.P); }
        @Override public AiRole role() { return role; }
        @Override public boolean soldier() { throw new AssertionError(); }
        @Override public boolean onTheMove() { throw new AssertionError(); }
        @Override public boolean inCover() { throw new AssertionError(); }
        @Override public boolean searching() { throw new AssertionError(); }
        @Override public boolean fightsFallen(int target) { throw new AssertionError(); }
        @Override public boolean fallingBack() { throw new AssertionError(); }
        @Override public boolean marksman() { throw new AssertionError(); }
        @Override public boolean perched() { throw new AssertionError(); }
        @Override public boolean atPerch() { throw new AssertionError(); }
        @Override public boolean grenadier() { throw new AssertionError(); }
        @Override public Object serverLevel() { throw new AssertionError(); }
        @Override public WorldView world() { throw new AssertionError(); }
        @Override public AiMoverBase mover() { throw new AssertionError(); }
        @Override public AiGunnerBase gunner() { throw new AssertionError(); }
        @Override public AiProneBase prone() { throw new AssertionError(); }
        @Override public AiStoopBase stoop() { throw new AssertionError(); }
        @Override public AiDashBase dash() { throw new AssertionError(); }
    }

    private Person bot(int id, String team, double x) {
        Person p = new Person(id, false, team, x);
        registry.bots.put(id, new Bot(p.body));
        AiBotsBase.LEVELS.put(level, registry);
        return p;
    }

    private Person player(int id, double x) {
        Person p = new Person(id, true, null, x);
        players.add(p);
        return p;
    }

    private void own(Person... own) {
        List<AiBotBase> list = new ArrayList<>();
        for (Person p : own) list.add(registry.bots.get(p.id));
        sides.put("red", list);
    }

    private long tick() { return AiIntelBase.tickSides(level, view, scout, sides, now); }
    private List<AiIntelBase.Group> groups() { return AiIntelBase.groupsOf(level, "red"); }
    private AiIntelBase.Group group() { assertEquals(1, groups().size()); return groups().get(0); }

    @AfterEach
    void forget() {
        AiIntelBase.forgetLevel(level);
        AiBotsBase.LEVELS.remove(level);
    }

    @Test
    void enemiesAtKnowOnly() {
        own(bot(1, "red", 0));
        Person edge = bot(2, "blue", AiIntelBase.KNOW);
        bot(3, "blue", Math.nextUp(AiIntelBase.KNOW));
        bot(4, "red", 2);
        bot(5, "blue", 3).alive = false;
        bot(6, "blue", 4).removed = true;
        bot(7, null, 5);
        registry.bots.get(2).role = AiRole.BASE;
        tick();
        assertArrayEquals(new int[] {2}, group().ids());
        assertSame(edge.body, group().members().get(0));
        assertEquals(1, group().known().kinds[AiRole.BASE.ordinal()]);
        assertEquals(2, radiusCalls);
        assertEquals(1, awakeCalls);
        assertFalse(group().known().awake);
    }

    @Test
    void playersExcludeSpectatorsCreativeAndDead() {
        own(bot(1, "red", 0));
        player(2, 1).spectator = true;
        player(3, 2).creative = true;
        player(4, 3).alive = false;
        Person live = player(5, 4);
        player(6, 5).team = "red";
        player(7, Math.nextUp(AiIntelBase.KNOW));
        tick();
        assertArrayEquals(new int[] {5}, group().ids());
        assertSame(live.body, group().members().get(0));
        assertEquals(1, group().known().players());
        assertTrue(group().known().awake);
        assertEquals(7f / 20f, group().known().health);
    }

    @Test
    void spectatorDoesNotWakeBot() {
        own(bot(1, "red", 0));
        bot(2, "blue", 100);
        player(3, 100).spectator = true;
        tick();
        assertArrayEquals(new int[] {2}, group().ids());
        assertFalse(group().known().awake);
    }

    @Test
    void playerAtRadiusWakesEvenInCreative() {
        own(bot(1, "red", 0));
        bot(2, "blue", 100);
        player(3, 100 + radius).creative = true;
        tick();
        assertTrue(group().known().awake);
        players.get(0).x = Math.nextUp(100 + radius);
        now += AiIntelBase.REPORT;
        tick();
        assertFalse(group().known().awake);
        allAwake = true;
        now += AiIntelBase.REPORT;
        tick();
        assertTrue(group().known().awake);
    }

    @Test
    void healthAndLatestKnownSeen() {
        Person a = bot(1, "red", 0), b = bot(2, "red", 1), c = bot(4, "red", 2);
        own(a, b, c);
        Person foe = bot(3, "blue", 4);
        registry.bots.get(1).memory.seen(3, 4, 0, 0, 970, 0);
        registry.bots.get(2).memory.seen(3, 4, 0, 0, 990, 0);
        registry.bots.get(2).memory.contact(3).state = TargetMemory.State.LOST;
        registry.bots.get(4).memory.seen(3, 4, 0, 0, 999, 100).seenTick = 999;
        tick();
        assertEquals(990, group().known().seen);
        assertEquals(7f / 20f, group().known().health);
        foe.maxHealth = 0;
        now += AiIntelBase.REPORT;
        tick();
        assertEquals(0, group().known().health);
        foe.maxHealth = -1;
        now += AiIntelBase.REPORT;
        tick();
        assertEquals(0, group().known().health);
    }

    @Test
    void dueReportsLostAndForgottenSides() {
        own(bot(1, "red", 0));
        bot(2, "blue", 3);
        AiIntelBase.lostTo(level, "red", 2, 999);
        assertEquals(1000 + AiIntelBase.REPORT, tick());
        assertEquals(999, group().known().lost);
        assertEquals(1, AiIntelBase.reportsOf(level, "red"));
        List<AiIntelBase.Group> first = groups();
        long reports = AiIntelBase.reports;
        now++;
        assertEquals(1000 + AiIntelBase.REPORT, tick());
        assertSame(first, groups());
        assertEquals(reports, AiIntelBase.reports);
        AiIntelBase.lostTo(level, "red", 2, now);
        assertEquals(now, group().known().lost);
        now = 1000 + AiIntelBase.REPORT;
        assertEquals(now + AiIntelBase.REPORT, tick());
        assertEquals(2, AiIntelBase.reportsOf(level, "red"));
        sides.clear();
        assertEquals(Long.MAX_VALUE, tick());
        assertTrue(groups().isEmpty());
        assertEquals(0, AiIntelBase.reportsOf(level, "red"));
        own(new Person(1, false, "red", 0));
        tick();
        assertEquals(1, AiIntelBase.reportsOf(level, "red"));
        AiIntelBase.forgetLevel(level);
        assertTrue(groups().isEmpty());
        assertEquals(0, AiIntelBase.reportsOf(level, "red"));
    }

    @Test
    void playerBufferGrowsAndKeepsOrder() {
        own(bot(1, "red", 0));
        // Bigger than any other test's player buffer, so this call must ask again.
        for (int i = 0; i < 100; i++) player(1000 - i, i * 0.01);
        tick();
        assertEquals(1, shortCalls);
        assertEquals(2, playerCalls);
        assertEquals(100, group().members().size());
        for (int i = 0; i < 100; i++) assertSame(players.get(99 - i).body, group().members().get(i));
    }

    @Test
    void groupAliveReadsBothFlagsAndHelpers() {
        own(bot(1, "red", 0));
        Person foe = bot(2, "blue", 3);
        tick();
        AiIntelBase.Group g = group();
        assertTrue(g.alive());
        assertTrue(g.has(2));
        assertFalse(g.has(1));
        assertTrue(g.any(new int[] {1, 2}));
        assertFalse(g.any(null));
        assertFalse(g.any(new int[] {1}));
        assertEquals(2, g.lead());
        assertEquals(g.known().x, g.x());
        assertEquals(g.known().y, g.y());
        assertEquals(g.known().z, g.z());
        assertEquals(g.known().r, g.r());
        foe.removed = true;
        assertFalse(g.alive());
        foe.removed = false;
        foe.alive = false;
        assertFalse(g.alive());
        assertFalse(new AiIntelBase.Group(List.of(), g.known()).alive());
    }

    @Test
    void emptySideDoesNotAskScoutOrPlayers() {
        sides.put("red", List.of());
        assertEquals(now + AiIntelBase.REPORT, tick());
        assertTrue(groups().isEmpty());
        assertEquals(0, playerCalls);
        assertEquals(0, radiusCalls);
        assertEquals(1, AiIntelBase.reportsOf(level, "red"));
    }
}
