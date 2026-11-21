package acup.gunfeel.ammo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** The adapter keeps the rear's random source and the old setter order. */
class DuplexReadsTest {
    private static MethodNode method(Class<?> type, String name) throws Exception {
        ClassNode c = new ClassNode();
        try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            new ClassReader(in).accept(c, 0);
        }
        return c.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> instructions(MethodNode m) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode i : m.instructions) {
            if (i.getOpcode() < 0) continue;
            String s = "" + i.getOpcode();
            if (i instanceof VarInsnNode v) s += ":" + v.var;
            if (i instanceof TypeInsnNode t) s += ":" + t.desc;
            if (i instanceof MethodInsnNode c) s += ":" + c.owner + "." + c.name + c.desc;
            if (i instanceof FieldInsnNode f) s += ":" + f.name + ":" + f.desc;
            out.add(s);
        }
        return out;
    }

    @Test void gaussianUsesTheArgumentBulletsRandomDirectly() throws Exception {
        assertEquals(List.of("25:1", "192:com/hbm/entity/projectile/EntityBulletBaseMK4",
                "182:com/hbm/entity/projectile/EntityBulletBaseMK4.getRandom()Lnet/minecraft/util/RandomSource;",
                "185:net/minecraft/util/RandomSource.nextGaussian()D", "175"), instructions(method(Duplex.Reads.class, "gaussian")));
    }

    @Test void writeKeepsSettersFieldsAndArgumentsInOrder() throws Exception {
        List<String> expected = new ArrayList<>(List.of("25:1", "192:com/hbm/entity/projectile/EntityBulletBaseMK4", "58:16"));
        String owner = "182:com/hbm/entity/projectile/EntityBulletBaseMK4.";
        add(expected, owner + "setDeltaMovement(DDD)V", "24:2", "24:4", "24:6");
        add(expected, owner + "setYRot(F)V", "23:8");
        add(expected, "181:yRotO:F", "23:8");
        add(expected, owner + "setXRot(F)V", "23:9");
        add(expected, "181:xRotO:F", "23:9");
        add(expected, owner + "setPos(DDD)V", "24:10", "24:12", "24:14");
        add(expected, "181:xo:D", "24:10");
        add(expected, "181:yo:D", "24:12");
        add(expected, "181:zo:D", "24:14");
        add(expected, "181:xOld:D", "24:10");
        add(expected, "181:yOld:D", "24:12");
        add(expected, "181:zOld:D", "24:14");
        expected.add("177");
        assertEquals(expected, instructions(method(Duplex.Reads.class, "write")));
    }

    private static void add(List<String> out, String operation, String... args) {
        out.add("25:16");
        out.addAll(List.of(args));
        out.add(operation);
    }

    @Test void facadeDelegatesToCoreAndOwnsNoFront() throws Exception {
        assertEquals(List.of("184:acup/gunfeel/ammo/DuplexBase.shotStart()V", "177"), instructions(method(Duplex.class, "shotStart")));
        assertEquals(List.of("25:0", "184:acup/gunfeel/ammo/DuplexBase.place(Ljava/lang/Object;)V", "177"), instructions(method(Duplex.class, "place")));
        assertFalse(java.util.Arrays.stream(Duplex.class.getDeclaredFields()).anyMatch(f -> f.getType() == ThreadLocal.class));
    }

    @Test void classificationChecksTypeBeforeConfig() throws Exception {
        MethodNode m = method(Duplex.Reads.class, "duplex");
        List<String> ops = instructions(m);
        int type = ops.indexOf("193:com/hbm/entity/projectile/EntityBulletBaseMK4");
        int config = ops.indexOf("180:config:Lcom/hbm/items/weapon/sedna/BulletConfig;");
        assertTrue(type >= 0 && config > type);
        AbstractInsnNode check = m.instructions.getFirst();
        while (check.getOpcode() != Opcodes.INSTANCEOF) check = check.getNext();
        AbstractInsnNode branch = check.getNext();
        while (branch.getOpcode() < 0) branch = branch.getNext();
        assertEquals(Opcodes.IFEQ, branch.getOpcode());
        assertTrue(ops.contains("184:acup/gunfeel/ammo/Duplex.is(Lcom/hbm/items/weapon/sedna/BulletConfig;)Z"));
        assertFalse(new Duplex.Reads().duplex(new Object()));
    }

    @Test
    void wiringRegistersTheReads() {
        var wiring = (acup.gunfeel.Wiring) acup.gunfeel.CoreServices.root();
        assertNotNull(wiring);
        assertTrue(wiring.port(acup.gunfeel.host.DuplexShots.class) instanceof Duplex.Reads);
    }
}
