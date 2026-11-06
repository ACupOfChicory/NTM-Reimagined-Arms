package acup.gunfeel.water;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// What a bullet does at a liquid, as WaterPath finds it: the same on the server and the client. The client hands each
// event to its listeners (splashes, bubbles, sounds); the server hands them to no one.
public final class WaterEvents {
    private WaterEvents() {
    }

    public enum Type {
        // came into the liquid from the air
        ENTER,
        // came out of it into the air (fired from under water upwards, through a thin stream)
        EXIT,
        // skipped off the surface (a shallow angle on still or sloped water): goes on in `dir`
        SKIP,
        // the liquid stopped it (f under waterStopFraction; lava, a flame: where it met it)
        STOP,
        // hit the block under the liquid it was in (shallow water): a block hit with the slowed f
        BOTTOM
    }

    public enum Liquid {
        WATER, LAVA, OTHER
    }

    // the water where it happened: still (no flow), sloped (flowing), falling (FALLING, a stream), shallow (the
    // column is under 0.55 blocks deep). Falling counts first, then shallow.
    public enum Kind {
        STILL, SLOPE, FALLING, SHALLOW
    }

    // at = the point; face = the side of the liquid's cell it went through (UP: the surface; for STOP the side it
    // moved towards; BOTTOM: the block's face that was hit); normal = that face's normal out of the liquid; f = the
    // speed fraction there (after a skip: the f it goes on with); dir = the bullet's direction there (unit; for SKIP:
    // the new one); flow = FluidState.getFlow of the cell; depth = the liquid column at that spot, surface to bottom;
    // under = how far the point is under the surface; pos = the cell
    public record Event(Type type, Vec3 at, Direction face, Vec3 normal, double f, WaterClass cls, Kind kind, Liquid liquid,
            Vec3 flow, double depth, double under, Vec3 dir, net.minecraft.core.BlockPos pos, @Nullable Entity bullet) {
    }

    public interface Listener {
        void on(Level level, Event event);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    // client listeners (effects, sounds); added once at start-up
    public static void listen(Listener l) {
        LISTENERS.add(l);
    }

    public static void fire(Level level, Event event) {
        if (!level.isClientSide()) return;
        for (Listener l : LISTENERS) {
            try {
                l.on(level, event);
            } catch (RuntimeException e) {
                acup.gunfeel.GunFeel.LOGGER.warn("water event listener failed on {}", event.type(), e);
            }
        }
    }

    public static void fire(Level level, List<Event> events) {
        if (!level.isClientSide() || LISTENERS.isEmpty()) return;
        for (Event e : events) fire(level, e);
    }
}
