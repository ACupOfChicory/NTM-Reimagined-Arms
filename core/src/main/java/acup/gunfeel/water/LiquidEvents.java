package acup.gunfeel.water;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.Vec3d;

// What a bullet does at a liquid, as LiquidPath finds it, the same on the server and the client, in core's terms:
// points as Vec3d, the face as its index (LiquidPath.DOWN .. EAST, Minecraft's 3D data value), the cell as numbers,
// the bullet as the version's own object. The version's adapter turns a Hit into its own event and hands it to the
// effects (26.2: WaterEvents, which takes these kinds as they are).
public class LiquidEvents {
    protected LiquidEvents() {
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
    // moved towards; BOTTOM: the block's face that was hit), as LiquidPath's face index; normal = that face's normal out
    // of the liquid; f = the speed fraction there (after a skip: the f it goes on with); dir = the bullet's direction
    // there (unit; for SKIP: the new one); flow = the cell's flow; depth = the liquid column at that spot, surface to
    // bottom; under = how far the point is under the surface; x, y, z = the cell; bullet = the version's bullet
    public record Hit(Type type, Vec3d at, int face, Vec3d normal, double f, WaterClass cls, Kind kind, Liquid liquid,
            Vec3d flow, double depth, double under, Vec3d dir, int x, int y, int z, @Nullable Object bullet) {
    }
}
