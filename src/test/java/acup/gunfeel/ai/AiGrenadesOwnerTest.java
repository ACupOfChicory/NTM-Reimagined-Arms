package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class AiGrenadesOwnerTest {
    private static long calls(String type, String method, String owner, String name) throws Exception {
        ClassNode node = new ClassNode();
        try (var in = AiGrenadesOwnerTest.class.getClassLoader().getResourceAsStream(type + ".class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        long count = 0;
        for (var m : node.methods) if (m.name.equals(method))
            for (var instruction : m.instructions)
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) count++;
        return count;
    }

    @Test void ownerReadsKeepTheOriginalCallSites() throws Exception {
        assertEquals(1, calls("acup/gunfeel/EntityBody", "owner", "net/minecraft/world/entity/projectile/Projectile", "getOwner"));
        assertEquals(1, calls("acup/gunfeel/ai/AiGrenadesBase", "oldest", "acup/gunfeel/host/Body", "owner"));
        for (var site : Map.of("dangerNow", 2, "ownSide", 1).entrySet())
            assertEquals(site.getValue().longValue(), calls("acup/gunfeel/ai/AiGrenades", site.getKey(),
                    "net/minecraft/world/entity/projectile/Projectile", "getOwner"));
    }

    @Test void launchHasOneSpawnAndTheReplayHasNone() throws Exception {
        assertEquals(1, calls("acup/gunfeel/ai/AiGrenades", "launch", "net/minecraft/server/level/ServerLevel", "addFreshEntity"));
        assertEquals(0, calls("acup/gunfeel/ai/AiGrenadesOld", "replayToss", "net/minecraft/server/level/ServerLevel", "addFreshEntity"));
    }
}
