package acup.gunfeel.water;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// 26.2's WaterEvents (water, the ballistics' part): what a bullet does at a liquid, as WaterPath finds it, the same on
// the server and the client. The client hands each event to its listeners (the effects, not in this port yet); the
// server hands them to no one. port: NTM CE moves its bullets only on the server, so the client's events come from the
// tracer, not from the bullet.
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

    // the water where it happened: still (no flow), sloped (flowing), falling (a falling stream), shallow (the column
    // is under 0.55 blocks deep). Falling counts first, then shallow.
    public enum Kind {
        STILL, SLOPE, FALLING, SHALLOW
    }

    // at = the point; face = the side of the liquid's cell it went through (UP: the surface; for STOP the side it
    // moved towards; BOTTOM: the block's face that was hit); normal = that face's normal out of the liquid; f = the
    // speed fraction there (after a skip: the f it goes on with); dir = the bullet's direction there (unit; for SKIP:
    // the new one); flow = the liquid's flow in the cell; depth = the liquid column at that spot, surface to bottom;
    // under = how far the point is under the surface; pos = the cell
    public record Event(Type type, Vec3d at, EnumFacing face, Vec3d normal, double f, WaterClass cls, Kind kind, Liquid liquid,
            Vec3d flow, double depth, double under, Vec3d dir, BlockPos pos, Entity bullet) {
    }

    public interface Listener {
        void on(World world, Event event);
    }

    // every tick of a bullet whose path went through liquid, on the client: the whole path, after the tick's events
    public interface TickListener {
        void tick(World world, Entity bullet, WaterPath.Result path);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<TickListener> TICKS = new CopyOnWriteArrayList<>();

    public static void listenTicks(TickListener l) {
        TICKS.add(l);
    }

    public static void tick(World world, Entity bullet, WaterPath.Result path) {
        if (!world.isRemote) return;
        for (TickListener l : TICKS) {
            try {
                l.tick(world, bullet, path);
            } catch (RuntimeException e) {
                acup.gunfeel.GunFeel.LOGGER.warn("water tick listener failed", e);
            }
        }
    }

    // client listeners (effects, sounds); added once at start-up
    public static void listen(Listener l) {
        LISTENERS.add(l);
    }

    public static void fire(World world, Event event) {
        if (!world.isRemote) return;
        for (Listener l : LISTENERS) {
            try {
                l.on(world, event);
            } catch (RuntimeException e) {
                acup.gunfeel.GunFeel.LOGGER.warn("water event listener failed on {}", event.type(), e);
            }
        }
    }

    public static void fire(World world, List<Event> events) {
        if (!world.isRemote || LISTENERS.isEmpty()) return;
        for (Event e : events) fire(world, e);
    }
}
