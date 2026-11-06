package acup.gunfeel.water;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import acup.gunfeel.phys.Direction;
import net.minecraft.entity.Entity;
import acup.gunfeel.phys.Vec3;
import net.minecraft.world.World;

// What a bullet does at a liquid, as WaterPath finds it: the same on the server and the client.
// port: 26.2's WaterEvents (df28728) with World for Level, the port's BlockPos and Direction. The client's events come
// from the tracers only: NTM 1.7.10's bullet does not tick on the client (EntityThrowableInterp). The client hands each
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
            Vec3 flow, double depth, double under, Vec3 dir, acup.gunfeel.phys.BlockPos pos, Entity bullet) {
    }

    public interface Listener {
        void on(World level, Event event);
    }

    // every tick of a bullet whose path went through liquid (WaterBullet.inWater), on the client: the whole path, for
    // bubbles along it; after the tick's events
    public interface TickListener {
        void tick(World level, Entity bullet, WaterPath.Result path);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<TickListener> TICKS = new CopyOnWriteArrayList<>();

    // NTM's own bubbles (4 vanilla BUBBLE a tick while the bullet is in water): the effects turn them off to draw
    // their own trail instead. Read by MixinThrowableHit on the client.
    public static volatile boolean ntmBubbles = true;

    public static void listenTicks(TickListener l) {
        TICKS.add(l);
    }

    public static void tick(World level, Entity bullet, WaterPath.Result path) {
        if (!level.isRemote) return;
        for (TickListener l : TICKS) {
            try {
                l.tick(level, bullet, path);
            } catch (RuntimeException e) {
                acup.gunfeel.GunFeel.LOGGER.warn("water tick listener failed", e);
            }
        }
    }

    // client listeners (effects, sounds); added once at start-up
    public static void listen(Listener l) {
        LISTENERS.add(l);
    }

    public static void fire(World level, Event event) {
        if (!level.isRemote) return;
        for (Listener l : LISTENERS) {
            try {
                l.on(level, event);
            } catch (RuntimeException e) {
                acup.gunfeel.GunFeel.LOGGER.warn("water event listener failed on {}", event.type(), e);
            }
        }
    }

    public static void fire(World level, List<Event> events) {
        if (!level.isRemote || LISTENERS.isEmpty()) return;
        for (Event e : events) fire(level, e);
    }
}
