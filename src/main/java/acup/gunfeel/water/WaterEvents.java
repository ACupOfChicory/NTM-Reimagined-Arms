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

    // every tick of a bullet whose path went through liquid (WaterBullet.inWater), on the client: the whole path, for
    // bubbles along it; after the tick's events
    public interface TickListener {
        void tick(Level level, Entity bullet, WaterPath.Result path);
    }

    // an NTM explosion whose centre is in a liquid or within a block over its surface, on the client (the server sends
    // WaterBlastPayload to the players near; in single player the integrated server sends it to this client too).
    // at = the centre; size = NTM's ExplosionVNT size (the blast's range in blocks is about twice that); incendiary =
    // the charge sets alight in air (phosphorus, napalm, b75_inc): under water it gives a flash and no fire; inWater =
    // the centre is in the liquid (else it is just above the surface); surface = the point of the surface over the
    // centre (null when no surface is within 8 blocks over it); liquid = the liquid at the centre or under it
    public record Blast(Vec3 at, float size, boolean incendiary, boolean inWater, @Nullable Vec3 surface, Liquid liquid) {
    }

    public interface BlastListener {
        void blast(Level level, Blast blast);
    }

    private static final List<BlastListener> BLASTS = new CopyOnWriteArrayList<>();

    public static void listenBlasts(BlastListener l) {
        BLASTS.add(l);
    }

    public static void blast(Level level, Blast b) {
        if (!level.isClientSide()) return;
        for (BlastListener l : BLASTS) {
            try {
                l.blast(level, b);
            } catch (RuntimeException e) {
                acup.gunfeel.GunFeel.LOGGER.warn("water blast listener failed", e);
            }
        }
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<TickListener> TICKS = new CopyOnWriteArrayList<>();

    // NTM's own bubbles (4 vanilla BUBBLE a tick while the bullet is in water): the effects turn them off to draw
    // their own trail instead. Read by MixinThrowableHit on the client.
    public static volatile boolean ntmBubbles = true;

    public static void listenTicks(TickListener l) {
        TICKS.add(l);
    }

    public static void tick(Level level, Entity bullet, WaterPath.Result path) {
        if (!level.isClientSide()) return;
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
