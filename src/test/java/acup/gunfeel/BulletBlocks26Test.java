package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import acup.gunfeel.host.BulletBlocks;
import acup.gunfeel.host.WorldEdit;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;

class BulletBlocks26Test {
    @Test
    void realBlockStatesKeepOneTokenForAllProperties() throws Exception {
        // Bootstrap needs a production flag and an empty mod list in the unit JVM.
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Class<?> loaderClass = Class.forName("net.neoforged.fml.loading.FMLLoader");
        Field currentField = loaderClass.getDeclaredField("current");
        currentField.setAccessible(true);
        @SuppressWarnings("unchecked") AtomicReference<Object> current = (AtomicReference<Object>) currentField.get(null);
        Object previous = current.get();
        Object loader = unsafe.allocateInstance(loaderClass);
        Field production = loaderClass.getDeclaredField("production");
        production.setAccessible(true);
        production.setBoolean(loader, true);
        Field modList = loaderClass.getDeclaredField("loadingModList");
        modList.setAccessible(true);
        modList.set(loader, net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), Map.of()));
        current.set(loader);
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            BulletServer.Reads reads = new BulletServer.Reads();
            int a = BlockStates.number(Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            int b = BlockStates.number(Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
            int c = BlockStates.number(Blocks.STONE.defaultBlockState());
            assertNotEquals(a, b);
            assertSame(reads.blockToken(a), reads.blockToken(b));
            assertSame(Blocks.OAK_LOG, reads.blockToken(a));
            assertNotSame(reads.blockToken(a), reads.blockToken(c));
            assertSame(BlockStates.state(a), reads.stateObject(a));
        } finally {
            current.set(previous);
        }
    }

    @Test
    void crackIdMatchesTheGamesHashIncludingOverflow() {
        int[] edge = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, -30_000_000, 30_000_000, 2048, -2049};
        for (int x : edge) for (int y : edge) for (int z : edge)
            assertEquals(-1_000_000 - (new BlockPos(x, y, z).hashCode() & 0x3fffffff), BulletServerBase.crackId(x, y, z));
    }

    @Test
    void numberingFailuresCannotBeCaughtAsParserFailures() throws Exception {
        ClassNode c = new ClassNode();
        try (var in = BulletServer.Reads.class.getResourceAsStream("BulletServer$Reads.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(c, 0);
        }
        MethodNode parse = c.methods.stream().filter(m -> m.name.equals("parseState")).findFirst().orElseThrow();
        int conversions = 0;
        for (AbstractInsnNode i : parse.instructions) {
            if (i instanceof MethodInsnNode m && m.owner.equals("acup/gunfeel/BlockStates") && m.name.equals("number")) {
                conversions++;
                int at = parse.instructions.indexOf(i);
                for (TryCatchBlockNode handler : parse.tryCatchBlocks)
                    assertFalse(at >= parse.instructions.indexOf(handler.start) && at < parse.instructions.indexOf(handler.end),
                            "state numbering must be after the old parser catch");
            }
        }
        assertEquals(2, conversions, "cached and newly parsed states are numbered outside the catch");
        assertEquals(1, parse.tryCatchBlocks.size(), "only the old parser catch");
    }

    @Test
    void bulletServerBaseUsesRegisteredBlockPorts() throws Exception {
        Wiring wiring = assertInstanceOf(Wiring.class, CoreServices.root());
        BulletServer.Reads reads = assertInstanceOf(BulletServer.Reads.class, wiring.port(WorldEdit.class));
        assertSame(reads, wiring.port(BulletBlocks.class));
        assertSame(reads, wiring.port(acup.gunfeel.host.ShotEffects.class));
        BulletServerBase base = new BulletServerBase();
        for (String name : new String[] {"edit", "blocks"}) {
            Field stored = BulletServerBase.class.getDeclaredField(name);
            stored.setAccessible(true);
            assertSame(reads, stored.get(base));
        }
    }
}
