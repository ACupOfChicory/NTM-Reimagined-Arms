package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import acup.gunfeel.BulletRuleText.Effect;
import acup.gunfeel.BulletRuleText.Particle;
import acup.gunfeel.BulletRuleText.Sound;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.BulletBlocks;
import acup.gunfeel.host.ShotEffects;
import acup.gunfeel.host.WorldView;

class BulletEffectsTest {
    private static final Object LEVEL = "level", POS = "pos";
    private static final boolean[] BOOL = {false, true};

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static final class Fake implements ShotEffects {
        final List<List<Object>> calls = new ArrayList<>();
        boolean player, living, alive, client, air;
        int failAt = -1;
        Effect entity, kill, hit;
        void call(Object... args) {
            calls.add(Arrays.asList(args));
            if (calls.size() == failAt) throw new IllegalStateException("port failed");
        }
        final GameRandom random = proxy(GameRandom.class, (p, m, a) -> {
            call(m.getName());
            return switch (m.getName()) {
                case "nextFloat" -> .371F;
                case "nextLong" -> 982734651L;
                default -> throw new AssertionError(m);
            };
        });
        final Body body = proxy(Body.class, (p, m, a) -> {
            call("body." + m.getName());
            return switch (m.getName()) {
                case "player" -> player;
                case "living" -> living;
                case "alive" -> alive;
                case "x" -> 7.25;
                case "y" -> -3.75;
                case "z" -> 9.5;
                case "bbHeight" -> 1.73F;
                default -> throw new AssertionError(m);
            };
        });
        final WorldView world = proxy(WorldView.class, (p, m, a) -> {
            call("world." + m.getName(), a == null ? List.of() : Arrays.asList(a));
            return switch (m.getName()) {
                case "random" -> random;
                case "client" -> client;
                case "state" -> 3;
                case "air" -> air;
                default -> throw new AssertionError(m);
            };
        });
        final BulletBlocks blocks = proxy(BulletBlocks.class, (p, m, a) -> {
            call(m.getName(), Arrays.asList(a));
            return switch (m.getName()) {
                case "ruleState" -> 4;
                case "stateObject" -> "machine";
                case "material" -> "steel";
                default -> throw new AssertionError(m);
            };
        });
        Effect entity(String kind, Body b) {
            assertSame(body, b);
            call("entity", kind);
            return kind.equals("entity") ? entity : kill;
        }
        Effect hit(int state) { call("hit", state); return hit; }
        @Override public Object particle(String name, int state) { call("particle", name, state); return name.equals("bad") ? null : name; }
        @Override public void particles(Object level, Object part, double x, double y, double z, int count) { call("particles", level, part, x, y, z, count); }
        @Override public Object sound(String id) { call("sound", id); return id.equals("bad id") ? null : id; }
        @Override public void playAll(Object level, Object sound, double x, double y, double z, float volume, float pitch) { call("all", level, sound, x, y, z, volume, pitch); }
        @Override public void playTo(Body player, Object sound, double x, double y, double z, float volume, float pitch, long seed) {
            assertSame(body, player);
            call("to", sound, x, y, z, volume, pitch, seed);
        }
        @Override public Object ricochetSteel() { call("steel"); return "ricochet"; }
    }

    // The pre-port algorithms, with game calls replaced by the same observable fakes.
    private record Old(Fake f) {
        void play(Body owner, boolean at, Effect fx) {
            if (fx.empty()) return;
            double x, y, z;
            if (at) { x = 1.25; y = 2.5; z = -6.75; }
            else { x = f.body.x(); y = f.body.y() + f.body.bbHeight() / 2; z = f.body.z(); }
            for (Particle part : fx.particles()) {
                Object o = f.particle(part.id(), -1);
                if (o != null) f.particles(LEVEL, o, x, y, z, part.count());
            }
            Sound s = fx.sound();
            if (s == null) return;
            Object sound = f.sound(s.id());
            if (sound == null) return;
            float pitch = s.pitch() * (0.95F + f.world.random().nextFloat() * 0.1F);
            if (fx.toAll()) f.playAll(LEVEL, sound, x, y, z, s.volume(), pitch);
            else if (owner != null && owner.player()) f.playTo(owner, sound, x, y, z, s.volume(), pitch, f.world.random().nextLong());
        }

        void entityHit(boolean server, Body owner, boolean at, boolean wasAlive) {
            if (!server || !wasAlive) return;
            boolean dead = f.body.living() && !f.body.alive();
            play(owner, at, f.entity("entity", f.body));
            if (dead) play(owner, at, f.entity("kill", f.body));
        }

        boolean ricochetSound(boolean block) {
            if (!block || f.world.client()) return false;
            int state = f.world.state(1, 2, 3);
            if (f.world.air(state)) return false;
            int rule = f.blocks.ruleState(LEVEL, POS, state);
            Sound s = f.hit(rule).sound();
            boolean steel = Ricochet.steelSound(s == null ? null : s.id());
            if (TestLog.server) TestLog.s(TestLog.shot(), "ricochet", "block", f.blocks.stateObject(rule), "pos", POS,
                    "material", f.blocks.material(LEVEL, POS), "result", "bounced", "sound", steel ? "ours" : "ntm");
            if (!steel) return false;
            f.playAll(LEVEL, f.ricochetSteel(), 1.25, 2.5, -6.75, 1.0F, 0.9F + f.world.random().nextFloat() * 0.2F);
            return true;
        }
    }

    private static final class Pair {
        final Fake a = new Fake(), b = new Fake();
        final Old old = new Old(a);
        final BulletEffectsBase core = new BulletEffectsBase(b, b.blocks) {
            @Override Effect entityEffect(String kind, Body target) { return b.entity(kind, target); }
            @Override Effect hitEffect(int state) { return b.hit(state); }
        };
        void effects(Effect entity, Effect kill) { a.entity = b.entity = entity; a.kill = b.kill = kill; }
        void compare(Supplier<Object> before, Supplier<Object> after) {
            assertEquals(capture(before), capture(after));
            assertEquals(a.calls, b.calls, "ordered calls, arguments and draw counts");
        }
        void play(boolean owner, boolean at, Effect fx) {
            compare(() -> { old.play(owner ? a.body : null, at, fx); return null; },
                    () -> { core.play(LEVEL, b.world, owner ? b.body : null, b.body, at, 1.25, 2.5, -6.75, fx); return null; });
        }
        void entity(boolean server, boolean owner, boolean at, boolean wasAlive) {
            compare(() -> { old.entityHit(server, owner ? a.body : null, at, wasAlive); return null; },
                    () -> { core.entityHit(LEVEL, b.world, server, owner ? b.body : null, b.body, at, 1.25, 2.5, -6.75, wasAlive); return null; });
        }
        void ricochet(boolean block) {
            compare(() -> old.ricochetSound(block), () -> core.ricochetSound(LEVEL, b.world, block, POS, 1, 2, 3, 1.25, 2.5, -6.75));
        }
    }

    private record Result(Object value, Class<?> failure, String log) {}
    private static Result capture(Supplier<Object> action) {
        try {
            Field out = TestLog.class.getDeclaredField("out"); out.setAccessible(true);
            Object saved = out.get(null);
            StringWriter log = new StringWriter();
            out.set(null, new BufferedWriter(log));
            try {
                Object value = action.get();
                return new Result(value, null, log.toString().replaceAll("(?m)^\\d+\\t", ""));
            } catch (IllegalStateException ex) {
                return new Result(null, ex.getClass(), log.toString().replaceAll("(?m)^\\d+\\t", ""));
            } finally { out.set(null, saved); }
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private static Effect fx(int particles, int sound, boolean all) {
        return new Effect(particles == 0 ? List.of() : List.of(new Particle("smoke", 3), new Particle("bad", 7), new Particle("flame", 2)),
                sound == 0 ? null : new Sound(sound == 1 ? "bad id" : "valid", .73F, 1.19F), all, 16F);
    }

    @Test void playMatchesEveryBranchAndParticleOrder() {
        for (int particles = 0; particles < 2; particles++) for (int sound = 0; sound < 3; sound++)
            for (boolean all : BOOL) for (boolean owner : BOOL) for (boolean player : BOOL) for (boolean at : BOOL) {
                Pair p = new Pair(); p.a.player = p.b.player = player;
                p.play(owner, at, fx(particles, sound, all));
            }
    }

    @Test void pitchIsDrawnWithoutAnyRecipient() {
        Pair p = new Pair(); p.play(false, true, fx(0, 2, false));
        assertEquals(List.of(List.of("sound", "valid"), List.of("world.random", List.of()), List.of("nextFloat")), p.b.calls);
    }

    @Test void entityAndKillMatchForLivingDeadAndNonLivingTargets() {
        for (boolean server : BOOL) for (boolean wasAlive : BOOL) for (boolean living : BOOL) for (boolean alive : BOOL)
            for (boolean owner : BOOL) for (boolean player : BOOL) for (boolean at : BOOL)
                for (int fx = 0; fx < 6; fx++) for (int kill = 0; kill < 6; kill++) {
                    Pair p = new Pair();
                    p.a.living = p.b.living = living; p.a.alive = p.b.alive = alive;
                    p.a.player = p.b.player = player;
                    p.effects(fx(fx / 3, fx % 3, false), fx(kill / 3, kill % 3, true));
                    p.entity(server, owner, at, wasAlive);
                }
    }

    @Test void ricochetMatchesClientMissAirSteelAndDiagnostic() {
        boolean saved = TestLog.server;
        try {
            for (boolean logging : BOOL) for (boolean block : BOOL) for (boolean client : BOOL) for (boolean air : BOOL)
                for (String sound : new String[] {null, "stone", "ntmgunfeel:bullet.hit.steel", "ntmgunfeel:bullet.hit.metal.2"}) {
                    TestLog.server = logging;
                    Pair p = new Pair(); p.a.client = p.b.client = client; p.a.air = p.b.air = air;
                    p.a.hit = p.b.hit = new Effect(List.of(), sound == null ? null : new Sound(sound, 1F, 1F), true, 16F);
                    p.ricochet(block);
                }
        } finally { TestLog.server = saved; }
    }

    @Test void failuresPropagateAtEveryEffectAndReadWithoutExtraCalls() {
        for (int entry = 0; entry < 3; entry++) {
            Pair baseline = failurePair(); runFailureEntry(baseline, entry);
            for (int fail = 1; fail <= baseline.a.calls.size(); fail++) {
                Pair p = failurePair(); p.a.failAt = p.b.failAt = fail;
                runFailureEntry(p, entry);
            }
        }
    }

    private static Pair failurePair() {
        Pair p = new Pair(); p.a.player = p.b.player = true; p.a.living = p.b.living = true;
        p.effects(fx(1, 2, false), fx(1, 2, true));
        p.a.hit = p.b.hit = new Effect(List.of(), new Sound("ntmgunfeel:bullet.hit.steel", 1F, 1F), true, 16F);
        return p;
    }
    private static void runFailureEntry(Pair p, int entry) {
        switch (entry) {
            case 0 -> p.play(true, false, fx(1, 2, false));
            case 1 -> p.entity(true, true, false, true);
            default -> p.ricochet(true);
        }
    }
}
