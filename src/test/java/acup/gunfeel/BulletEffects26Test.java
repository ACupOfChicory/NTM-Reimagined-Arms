package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.BulletBlocks;
import acup.gunfeel.host.ShotEffects;
import acup.gunfeel.host.WorldView;

public class BulletEffects26Test {
    private static int lookups;
    private static WorldView supplied;

    /** Count every lookup in the copied core, including cache hits. */
    public static WorldView lookup(Object level) { lookups++; return supplied; }

    private static ClassNode code(Class<?> type) throws Exception {
        ClassNode node = new ClassNode();
        try (var in = type.getResourceAsStream(type.getName().substring(type.getName().lastIndexOf('.') + 1) + ".class")) {
            assertNotNull(in); new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static int calls(ClassNode type, String method, String owner, String name) {
        int count = 0;
        for (MethodNode m : type.methods) if (m.name.equals(method))
            for (var i : m.instructions) if (i instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) count++;
        return count;
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    @Test void oneWorldLookupAtEachFacadeEntryAndNoneForRepeatedSounds() throws Exception {
        ClassNode facade = code(BulletServer.class);
        assertEquals(1, calls(facade, "entityHit", "acup/gunfeel/host/Worlds", "of"));
        assertEquals(1, calls(facade, "ricochetSound", "acup/gunfeel/host/Worlds", "of"));
        ClassNode core = code(BulletEffectsBase.class);
        // Replace lookups with a counter, so even a cached Worlds.of is observable.
        for (MethodNode m : core.methods) for (var i : m.instructions)
            if (i instanceof MethodInsnNode call && call.owner.equals("acup/gunfeel/host/Worlds") && call.name.equals("of")) {
                call.owner = "acup/gunfeel/BulletEffects26Test"; call.name = "lookup";
            }
        ClassWriter writer = new ClassWriter(0); core.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> copy = new ClassLoader(BulletEffectsBase.class.getClassLoader()) {
            Class<?> copy() { return defineClass(BulletEffectsBase.class.getName(), bytes, 0, bytes.length); }
        }.copy();
        ShotEffects effects = fake(ShotEffects.class, (p, m, a) -> switch (m.getName()) {
            case "sound" -> "resolved";
            case "playAll" -> null;
            default -> throw new AssertionError(m);
        });
        GameRandom random = fake(GameRandom.class, (p, m, a) -> {
            assertEquals("nextFloat", m.getName()); return .25F;
        });
        supplied = fake(WorldView.class, (p, m, a) -> {
            assertEquals("random", m.getName()); return random;
        });
        Body target = fake(Body.class, (p, m, a) -> { throw new AssertionError(m); });
        var constructor = copy.getDeclaredConstructor(ShotEffects.class, BulletBlocks.class);
        constructor.setAccessible(true);
        Object instance = constructor.newInstance(effects, BulletBlocks.MISSING);
        var play = copy.getMethod("play", Object.class, WorldView.class, Body.class, Body.class, boolean.class,
                double.class, double.class, double.class, BulletRuleText.Effect.class);
        lookups = 0;
        for (int i = 0; i < 2; i++) play.invoke(instance, "level", supplied, null, target, true, 1., 2., 3.,
                new BulletRuleText.Effect(List.of(), new BulletRuleText.Sound("sound", 1F, 1F), true, 16F));
        assertEquals(0, lookups, "entity and kill must reuse the entry's WorldView");
    }

    @Test void randomAdapterCallsTheOriginalLongDrawAndLevelGenerator() throws Exception {
        ClassNode random = code(Class.forName("acup.gunfeel.GameRandoms$1"));
        assertEquals(1, calls(random, "nextLong", "net/minecraft/util/RandomSource", "nextLong"));
        ClassNode world = code(LevelWorld.class);
        assertEquals(1, calls(world, "random", "net/minecraft/world/level/Level", "getRandom"));
        assertEquals(1, calls(world, "random", "acup/gunfeel/GameRandoms", "of"));
    }

    @Test void missingEffectsThrowForEveryOperation() {
        ShotEffects e = ShotEffects.MISSING;
        assertThrows(UnsupportedOperationException.class, () -> e.particle("smoke", -1));
        assertThrows(UnsupportedOperationException.class, () -> e.particles("level", "particle", 1, 2, 3, 4));
        assertThrows(UnsupportedOperationException.class, () -> e.sound("sound"));
        assertThrows(UnsupportedOperationException.class, () -> e.playAll("level", "sound", 1, 2, 3, 1F, 1F));
        assertThrows(UnsupportedOperationException.class, () -> e.playTo(null, "sound", 1, 2, 3, 1F, 1F, 1L));
        assertThrows(UnsupportedOperationException.class, e::ricochetSteel);
        assertTrue(ShotEffects.class.isAssignableFrom(BulletServer.Reads.class));
    }
}
