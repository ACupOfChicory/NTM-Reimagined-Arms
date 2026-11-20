package acup.gunfeel;

import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * What the mod keeps of a level, forgotten when the level goes (a dimension unloaded, the world left to the menu):
 * the place for it outside GunFeelMod's own listener. A value kept by a level, or by a player, that holds the level
 * keeps the whole world in memory, a weak key never letting it go.
 */
@EventBusSubscriber(modid = "ntmgunfeel")
public final class LevelUnload {
    private LevelUnload() {
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload e) {
        // the hit boxes' poses and the players' views of them on that level (a view holds its level)
        if (e.getLevel() instanceof Level level) {
            ModelHits.clear(level);
            // WorldView holds its level, so a weak key alone cannot release it
            acup.gunfeel.host.Worlds.forget(level);
        }
    }
}
