package acup.gunfeel.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/** World events the AI listens to, kept out of GunFeelMod. */
public final class AiEvents {
    private AiEvents() {
    }

    /** the senses: every sound the server plays (last, as other mods left it) and every hit an AI mob takes */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayLevelSoundEvent.AtPosition.class, AiEvents::sound);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayLevelSoundEvent.AtEntity.class, AiEvents::sound);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingIncomingDamageEvent.class, e -> {
            if (e.getEntity() instanceof Mob mob && !mob.level().isClientSide()) AiBots.hurt(mob, e.getSource());
        });
    }

    private static void sound(PlayLevelSoundEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getSound() == null) return;
        Vec3 at = e instanceof PlayLevelSoundEvent.AtPosition p ? p.getPosition()
                : e instanceof PlayLevelSoundEvent.AtEntity en ? en.getEntity().position() : null;
        if (at != null) AiBots.sound(level, at.x, at.y, at.z, e.getSound(), e.getSource(), e.getNewVolume());
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
