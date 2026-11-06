package acup.gunfeel.water;

import java.util.ArrayDeque;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;

import acup.gunfeel.water.WaterEvents.Liquid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

// An NTM explosion at water (the explosives' part, point 4). Under water the blast goes further and harder (water
// hardly compresses), over it weaker: with its centre in water (or on the surface) mobs in water take it out to
// waterBlastRadius × its range, those out of the water half of what they would. Blocks break as NTM breaks them.
// Hooked into EntityProcessorCross.process (MixinWaterBlastProcessor); ExplosionVNT.explode tells the players near
// (WaterBlastPayload → WaterEvents.blast) for the plume and the bubble.
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
    public record Place(boolean inWater, double above, @Nullable Vec3 surface, Liquid liquid) {
    }

    // null: no liquid at the point or within NEAR under it. A point on a block's face (a blast where a bullet hit a
    // wall under water) goes by the liquid next to it.
    public static @Nullable Place place(WaterPath.Fluids w, Vec3 p) {
        Place at = place0(w, p);
        if (at != null) return at;
        final double e = 0.02;
        for (Vec3 d : new Vec3[] {new Vec3(e, 0, 0), new Vec3(-e, 0, 0), new Vec3(0, 0, e), new Vec3(0, 0, -e), new Vec3(0, e, 0), new Vec3(0, -e, 0)}) {
            Vec3 q = p.add(d);
            if (w.at(Mth.floor(q.x), Mth.floor(q.y), Mth.floor(q.z)) == null) continue;
            at = place0(w, q);
            if (at != null) return at;
        }
        return null;
    }

    private static @Nullable Place place0(WaterPath.Fluids w, Vec3 p) {
        int x = Mth.floor(p.x), y = Mth.floor(p.y), z = Mth.floor(p.z);
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

    public static @Nullable Place place(Level level, Vec3 p) {
        return place(WaterPath.of(level), p);
    }

    // the blast's centre is in water: what the processor does with it
    record Wet(float mult) {
    }

    private static final ThreadLocal<ArrayDeque<Object>> PROCESS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Object DRY = new Object();

    // EntityProcessorCross.process starts (HEAD)
    public static void begin(ExplosionVNT e, Level world, double x, double y, double z) {
        Object state = DRY;
        if (!world.isClientSide() && WaterSettings.ballistics()) {
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

    private static @Nullable Wet wet() {
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

    // the bullet whose impact this is (WaterImpact), for the event's incendiary
    static final ThreadLocal<@Nullable EntityBulletBaseMK4> BULLET = new ThreadLocal<>();

    // the stand's (ExplosiveCheck): every NTM explosion as it starts
    public static volatile java.util.function.@Nullable Consumer<ExplosionVNT> watch;

    // ExplosionVNT.explode (HEAD), the server: tell the players near when it is at water
    public static void announce(ExplosionVNT e) {
        var w = watch;
        if (w != null) w.accept(e);
        if (!(e.world instanceof ServerLevel level)) return;
        Vec3 at = new Vec3(e.posX, e.posY, e.posZ);
        Place p = place(level, at);
        if (p == null) return;
        EntityBulletBaseMK4 b = BULLET.get();
        boolean incendiary = b != null && WaterClass.incendiary(b.config);
        var packet = new WaterBlastPayload(at, e.size, incendiary, p.inWater(), p.surface(), p.liquid());
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, 128 + e.size * 2, packet);
    }
}
