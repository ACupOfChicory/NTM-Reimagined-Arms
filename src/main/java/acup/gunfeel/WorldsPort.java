package acup.gunfeel;

import acup.gunfeel.host.WorldView;
import acup.gunfeel.host.Worlds;

/** 26.2's view of a level for core. */
final class WorldsPort implements Worlds.Port {
    @Override
    public WorldView world(Object level) {
        return Wiring.world((net.minecraft.world.level.Level) level);
    }
}
