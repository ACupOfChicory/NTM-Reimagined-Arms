package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

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

    private static MethodNode method(Class<?> type, String name) throws Exception {
        return code(type).methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static int callIndex(MethodNode m, String owner, String name) {
        for (var i : m.instructions)
            if (i instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name))
                return m.instructions.indexOf(i);
        fail("missing call " + owner + "." + name);
        return -1;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode i) {
        do { i = i.getNext(); } while (i != null && i.getOpcode() < 0);
        return i;
    }

    /** The rejected guard value must return without entering the adapter. */
    private static void rejects(AbstractInsnNode value, boolean rejected, int returnCode) {
        var branch = assertInstanceOf(JumpInsnNode.class, nextCode(value));
        assertTrue(branch.getOpcode() == Opcodes.IFEQ || branch.getOpcode() == Opcodes.IFNE);
        boolean taken = branch.getOpcode() == Opcodes.IFNE ? rejected : !rejected;
        AbstractInsnNode exit = taken ? nextCode(branch.label) : nextCode(branch);
        if (returnCode == Opcodes.IRETURN) {
            assertEquals(Opcodes.ICONST_0, exit.getOpcode());
            exit = nextCode(exit);
        }
        assertEquals(returnCode, exit.getOpcode(), "guard must return before Worlds.of and body reads");
    }

    @Test void facadeRejectsClientNonServerAndAlreadyDeadBeforeWorldLookup() throws Exception {
        MethodNode ricochet = method(BulletServer.class, "ricochetSound");
        int client = callIndex(ricochet, "net/minecraft/world/level/Level", "isClientSide");
        int ricochetWorld = callIndex(ricochet, "acup/gunfeel/host/Worlds", "of");
        assertTrue(client < ricochetWorld);
        rejects(ricochet.instructions.get(client), true, Opcodes.IRETURN);

        MethodNode entity = method(BulletServer.class, "entityHit");
        int world = callIndex(entity, "acup/gunfeel/host/Worlds", "of");
        int server = -1, alive = -1;
        for (var i : entity.instructions) {
            if (i instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                    && type.desc.equals("net/minecraft/server/level/ServerLevel")) server = entity.instructions.indexOf(i);
            if (i instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == 3
                    && entity.instructions.indexOf(i) < world) alive = entity.instructions.indexOf(i);
        }
        assertTrue(server >= 0 && server < world, "ServerLevel guard before Worlds.of");
        assertTrue(alive > server && alive < world, "wasAlive guard before Worlds.of");
        rejects(entity.instructions.get(server), false, Opcodes.RETURN);
        rejects(entity.instructions.get(alive), false, Opcodes.RETURN);
        assertTrue(world < callIndex(entity, "acup/gunfeel/EntityBody", "of"));
        assertEquals(0, calls(code(BulletServer.class), "entityHit", "com/hbm/entity/projectile/EntityBulletBaseMK4", "getOwner"));
    }

    @Test void ruleListenerInitHasNoGenerationLookupOrMonitor() throws Exception {
        assertEquals(1, calls(code(BulletServer.class), "entityHit", "acup/gunfeel/BulletRules", "init"));
        assertEquals(0, calls(code(BulletServer.class), "entityHit", "acup/gunfeel/BulletRules", "generation"));
        assertEquals(1, calls(code(BulletServer.Reads.class), "ruleState", "acup/gunfeel/BulletRules", "init"));
        assertEquals(0, calls(code(BulletServer.Reads.class), "ruleState", "acup/gunfeel/BulletRules", "generation"));
        MethodNode entity = method(BulletServer.class, "entityHit");
        for (MethodNode entry : List.of(entity, method(BulletServer.Reads.class, "ruleState")))
            for (var i : entry.instructions)
                if (i instanceof MethodInsnNode call) assertNotEquals("generation", call.name);
        int init = callIndex(entity, "acup/gunfeel/BulletRules", "init");
        assertTrue(callIndex(entity, "acup/gunfeel/host/Worlds", "of") < init);
        assertTrue(init < callIndex(entity, "acup/gunfeel/BulletEffectsBase", "entityHit"));
        MethodNode empty = method(BulletRules.class, "init");
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, empty.access);
        assertEquals(List.of(Opcodes.RETURN), java.util.stream.StreamSupport.stream(empty.instructions.spliterator(), false)
                .filter(i -> i.getOpcode() >= 0).map(AbstractInsnNode::getOpcode).toList());
    }

    @Test void ownerAdapterReadsTheProjectileOnceThenWrapsIt() throws Exception {
        ClassNode reads = code(BulletServer.Reads.class);
        assertEquals(1, calls(reads, "owner", "com/hbm/entity/projectile/EntityBulletBaseMK4", "getOwner"));
        assertEquals(1, calls(reads, "owner", "acup/gunfeel/BulletServer$Reads", "body"));
        MethodNode owner = method(BulletServer.Reads.class, "owner");
        assertTrue(callIndex(owner, "com/hbm/entity/projectile/EntityBulletBaseMK4", "getOwner")
                < callIndex(owner, "acup/gunfeel/BulletServer$Reads", "body"));
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
        var play = copy.getMethod("play", Object.class, WorldView.class, Object.class, Body.class, boolean.class,
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
        assertThrows(UnsupportedOperationException.class, () -> e.owner("bullet"));
        assertThrows(UnsupportedOperationException.class, () -> e.particle("smoke", -1));
        assertThrows(UnsupportedOperationException.class, () -> e.particles("level", "particle", 1, 2, 3, 4));
        assertThrows(UnsupportedOperationException.class, () -> e.sound("sound"));
        assertThrows(UnsupportedOperationException.class, () -> e.playAll("level", "sound", 1, 2, 3, 1F, 1F));
        assertThrows(UnsupportedOperationException.class, () -> e.playTo(null, "sound", 1, 2, 3, 1F, 1F, 1L));
        assertThrows(UnsupportedOperationException.class, e::ricochetSteel);
        assertTrue(ShotEffects.class.isAssignableFrom(BulletServer.Reads.class));
    }
}
