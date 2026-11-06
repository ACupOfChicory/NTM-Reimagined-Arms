package acup.gunfeel.water;

import java.util.ArrayDeque;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;

import acup.gunfeel.phys.Vec3;
import acup.gunfeel.water.WaterEvents.Liquid;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

// An NTM explosion at water (the explosives' part, point 4). Under water the blast goes further and harder (water
// hardly compresses), over it weaker: with its centre in water (or on the surface) mobs in water take it out to
// waterBlastRadius × its range, those out of the water half of what they would. Blocks break as NTM breaks them.
// Hooked into EntityProcessorCross.process (MixinWaterBlastProcessor); ExplosionVNT.explode (MixinWaterBlast) shows it
// to the stand (watch).
// port: 26.2's WaterBlast (a1f618b) on NTM 1.7.10 (the same ExplosionVNT and EntityProcessorCross). Not yet here:
// the message to the players near (26.2's WaterBlastPayload, for the plume and the bubble): it comes with the water's
// effects (water 2), its only reader.
public final class WaterBlast {
    private WaterBlast() {
    }

    // a centre this far over the surface still counts as on it: NTM's small blasts sit 0.25 over the point hit
    static final double GAP = 0.3;
    // a blast this far over the surface still shows on it (a plume)
    static final double NEAR = 1.0;
    // how far up the surface is looked for
    static final int DEPTH = 8;
    // a mob out of the water takes this much of the blast
    public static final float ABOVE = 0.5F;

    // where a point stands to liquid: inWater = in it or at most GAP over its surface; above = how far over the
    // surface (0 in it); surface = the surface over it (null: none within DEPTH blocks)
    public record Place(boolean inWater, double above, Vec3 surface, Liquid liquid) {
    }

    // null: no liquid at the point or within NEAR under it. A point on a block's face (a blast where a bullet hit a
    // wall under water) goes by the liquid next to it.
    public static Place place(WaterPath.Fluids w, Vec3 p) {
        Place at = place0(w, p);
        if (at != null) return at;
        final double e = 0.02;
        for (Vec3 d : new Vec3[] {new Vec3(e, 0, 0), new Vec3(-e, 0, 0), new Vec3(0, 0, e), new Vec3(0, 0, -e), new Vec3(0, e, 0), new Vec3(0, -e, 0)}) {
            Vec3 q = p.add(d);
            if (w.at(floor(q.x), floor(q.y), floor(q.z)) == null) continue;
            at = place0(w, q);
            if (at != null) return at;
        }
        return null;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    private static Place place0(WaterPath.Fluids w, Vec3 p) {
        int x = floor(p.x), y = floor(p.y), z = floor(p.z);
        WaterPath.Cell c = w.at(x, y, z);
        int cy = y;
        if (c == null || p.y > y + c.height() + 1e-9) {
            // over the liquid of this cell, or in the air over the one under it
            if (c == null) {
                c = w.at(x, y - 1, z);
                cy = y - 1;
            }
            if (c == null) return null;
        }
        double top = cy + c.height();
        int k = 0;
        // the column up: a full cell with liquid over it goes on
        while (k < DEPTH && c.height() >= 0.999 - 1e-6) {
            WaterPath.Cell up = w.at(x, cy + 1, z);
            if (up == null || up.liquid() != c.liquid()) break;
            c = up;
            cy++;
            top = cy + c.height();
            k++;
        }
        Vec3 surface = k >= DEPTH ? null : new Vec3(p.x, top, p.z);
        double above = Math.max(0, p.y - top);
        if (above > NEAR) return null;
        return new Place(above <= GAP, above, surface, c.liquid());
    }

    public static Place place(World level, Vec3 p) {
        return place(WaterPath.of(level), p);
    }

    // the blast's centre is in water: what the processor does with it
    record Wet(float mult) {
    }

    private static final ThreadLocal<ArrayDeque<Object>> PROCESS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Object DRY = new Object();

    // EntityProcessorCross.process starts (HEAD)
    public static void begin(ExplosionVNT e, World world, double x, double y, double z) {
        Object state = DRY;
        if (!world.isRemote && WaterSettings.ballistics()) {
            Place p = place(world, new Vec3(x, y, z));
            if (p != null && p.inWater() && p.liquid() == Liquid.WATER) state = new Wet(Math.max(1F, WaterSettings.blastRadius()));
        }
        PROCESS.get().push(state);
    }

    // ... and ends (RETURN)
    public static void end() {
        ArrayDeque<Object> s = PROCESS.get();
        if (!s.isEmpty()) s.pop();
    }

    private static Wet wet() {
        Object s = PROCESS.get().peek();
        return s instanceof Wet w ? w : null;
    }

    // the range the processor gathers mobs in and counts the falloff by
    public static float size(float size) {
        Wet w = wet();
        return w == null ? size : size * w.mult;
    }

    public static boolean inWater(Entity e) {
        return e.isInWater();
    }

    // a mob's distance / range: one out of the water goes by the blast's own range
    public static double distance(double scaled, Entity e) {
        Wet w = wet();
        return w == null || inWater(e) ? scaled : scaled * w.mult;
    }

    // its push: one out of the water half
    public static double knockback(double knockback, Entity e) {
        Wet w = wet();
        return w == null || inWater(e) ? knockback : knockback * ABOVE;
    }

    public interface Damage {
        float of(float size, double knockback);
    }

    // its damage, with the processor's size and the push as counted: one out of the water by the blast's own size and
    // push, half of it
    public static float damage(float size, double knockback, Entity e, Damage calc) {
        Wet w = wet();
        if (w == null || inWater(e)) return calc.of(size, knockback);
        return calc.of(size / w.mult, knockback / ABOVE) * ABOVE;
    }

    // the numbers, for the tests: the damage a mob takes from a CrossSmooth blast (fixed × (1 - d / range)) in water
    // and out of it, d = its distance, range = 2 × size
    public static float smooth(float fixed, double d, float size, boolean centreWet, boolean mobWet, float mult) {
        double range = 2 * size * (centreWet ? mult : 1);
        double ds = d / range;
        if (centreWet && !mobWet) ds *= mult;
        if (ds > 1) return 0;
        float dmg = (float) (fixed * (1 - ds));
        return centreWet && !mobWet ? dmg * ABOVE : dmg;
    }

    // the bullet whose impact this is (WaterImpact), for the event's incendiary (water 2)
    static final ThreadLocal<EntityBulletBaseMK4> BULLET = new ThreadLocal<>();

    // the stand's (ExplosiveCheck): every NTM explosion as it starts
    public static volatile java.util.function.Consumer<ExplosionVNT> watch;

    // ExplosionVNT.explode (HEAD), the server
    public static void announce(ExplosionVNT e) {
        java.util.function.Consumer<ExplosionVNT> w = watch;
        if (w != null) w.accept(e);
    }
}
