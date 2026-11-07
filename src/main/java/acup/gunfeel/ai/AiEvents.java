package acup.gunfeel.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/** World events the AI listens to, kept out of GunFeelMod. */
public final class AiEvents {
    private AiEvents() {
    }

    public static void chunk(LevelAccessor l, ChunkAccess chunk, boolean loaded) {
        if (!(l instanceof ServerLevel level) || !(chunk instanceof LevelChunk) || !TacticalMap.active) return;
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        Runnable r = () -> {
            TacticalMap map = TacticalMap.peek(level);
            if (map == null) return;
            if (loaded) map.chunkLoaded(cx, cz);
            else map.chunkUnloaded(cx, cz);
        };
        if (level.getServer().isSameThread()) r.run();
        else level.getServer().execute(r);
    }
}
