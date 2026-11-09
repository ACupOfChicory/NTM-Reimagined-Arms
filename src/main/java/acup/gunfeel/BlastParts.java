package acup.gunfeel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A blast's damage by the parts of the body (MixinWaterBlastProcessor for NTM's EntityProcessorCross and CrossSmooth,
 * MixinBlastPartsStandard for EntityProcessorStandard). NTM takes one distance (to the mob's box, Standard: to its
 * feet) and one visibility (rays from its whole box) for the whole mob. Here each part of the posed model the hits go
 * by (ModelHits) gets its own: the distance from the blast to the part's nearest point, the rays from its own points
 * (blocks cut them), NTM's falloff with them, and its share of the damage by its surface (Cauchy: a convex body's mean
 * shadow over all directions is a quarter of its surface), the shares summing to 1, so a mob standing in the open
 * takes about NTM's damage. The head's and the limbs' multipliers (ModelHeads, as a bullet's) go on their shares,
 * after the armour (HeadDamage): one hurt with the sum and the shares' mean multiplier.
 *
 * <p>A mob with no pose, or one whose parts mark no head and no limb, takes NTM's damage. So does a blast that is not
 * a plain one: a CrossSmooth of another damage class (fire, plasma, electric) and the nuclear ones ({@link #NUCLEAR}).
 */
public final class BlastParts {
    private BlastParts() {
    }

    /** where NTM's nuclear blasts go off: their mobs take NTM's damage (Fat Man's shells, the nuclear grenades and mine) */
    static final Set<String> NUCLEAR = Set.of("com.hbm.items.weapon.sedna.factory.XFactoryCatapult",
            "com.hbm.blocks.bomb.BlockLandmineFat", "com.hbm.entity.mob.glyphid.EntityGlyphidNuclear",
            "com.hbm.items.weapon.grenade.ItemGrenadeFilling.explodeNuclear",
            "com.hbm.items.weapon.grenade.ItemGrenadeFilling.explodeNuclearDemo",
            "com.hbm.items.weapon.grenade.ItemGrenadeFilling.explodeSchrabidium");

    /** blastParts: the server's switch */
    public static boolean enabled() {
        Boolean s = stand;
        return s != null ? s : ReaimTimes.flag("blastParts", true);
    }

    /** the blast stand's (BlastStand) switch over the key, and the parts of the last mob it hurt by its parts */
    static volatile Boolean stand;
    static volatile List<Counted> standParts = List.of();
    /** the stand's cost runs count no parts for it (BlastStand) */
    static volatile boolean standQuiet;

    /** a part as a blast counted it: its distance over the range, the share of its rays clear, NTM's damage */
    record Counted(String part, float multiplier, double share, double scaled, double seen, float raw) {
    }

    /**
     * blastPartsEven: the parts' multipliers divided by their mean over the whole mob (each part's share times its
     * multiplier), so a mob in the open takes about NTM's damage and a head still hurts more than a limb
     */
    static boolean even() {
        return ReaimTimes.flag("blastPartsEven", true);
    }

    /** blastPartRays: the rays from each part (its middle, then points along its length), 1 to 9 */
    static int rays() {
        return Math.clamp(Math.round(ReaimTimes.number("blastPartRays", 3F)), 1, 9);
    }

    /** one processor's run: the mobs' multipliers, whether it is a plain blast (found when first needed) */
    private static final class Run {
        final boolean plain;
        Boolean nuclear;
        final Map<Entity, Float> multiplier = new IdentityHashMap<>();

        Run(boolean plain) {
            this.plain = plain;
        }
    }

    private static final ThreadLocal<ArrayDeque<Run>> RUNS = ThreadLocal.withInitial(ArrayDeque::new);

    /** a processor's process() starts; {@code plain}: its damage class is the explosive one */
    public static void begin(boolean plain) {
        RUNS.get().push(new Run(plain && enabled()));
    }

    public static void end() {
        var runs = RUNS.get();
        if (!runs.isEmpty()) runs.pop();
    }

    /** NTM's damage with a part's distance (scaled by the range), visibility and push */
    @FunctionalInterface
    public interface Damage {
        float of(double scaled, double density, double knockback);
    }

    /** a part as the blast takes it: its box, multiplier and share */
    record Part(ModelBox box, float multiplier, double share) {
    }

    /**
     * The mob's damage from the blast at {@code centre} with its range {@code size}; {@code whole} is NTM's for the
     * whole mob. {@code scale} and {@code push} are the water's (WaterBlast) changes to the distance and the push.
     */
    public static float damage(Entity entity, Level level, Vec3 centre, Vec3[] nodes, float size, float whole, double density,
            Damage calc, DoubleUnaryOperator scale, DoubleUnaryOperator push) {
        try {
            return split(entity, level, centre, nodes, size, whole, density, calc, scale, push);
        } catch (RuntimeException e) {
            // a bad box must not stop NTM's blast for everyone else in it
            return whole;
        }
    }

    private static float split(Entity entity, Level level, Vec3 centre, Vec3[] nodes, float size, float whole, double density,
            Damage calc, DoubleUnaryOperator scale, DoubleUnaryOperator push) {
        var runs = RUNS.get();
        Run run = runs.peek();
        if (run == null || !run.plain || level.isClientSide() || !(entity instanceof LivingEntity living) || size <= 0) return whole;
        List<Part> parts = parts(living);
        if (parts.isEmpty()) return whole;
        if (run.nuclear == null) run.nuclear = nuclear();
        if (run.nuclear) return whole;
        int rays = rays();
        double total = 0, weighted = 0;
        List<Counted> counted = stand != null && !standQuiet ? new ArrayList<>() : null;
        boolean reached = false, limbs = false;
        double mean = 0;
        for (Part p : parts) {
            limbs |= p.multiplier() < 1;
            mean += p.share() * p.multiplier();
            double scaled = scale.applyAsDouble(nearest(p.box(), centre).distanceTo(centre) / size);
            reached |= scaled <= 1;
            if (!(scaled <= 1)) {
                if (counted != null) counted.add(new Counted(p.box().part(), p.multiplier(), p.share(), scaled, 0, 0));
                continue;
            }
            // NTM sees the whole mob from a node (density 1): every part is in sight, no rays
            double seen = density >= 1 ? 1 : visibility(level, p.box(), nodes, entity, rays);
            float raw = Math.max(0F, calc.of(scaled, seen, push.applyAsDouble((1 - scaled) * seen)));
            total += p.share() * raw;
            weighted += p.share() * raw * p.multiplier();
            if (counted != null) counted.add(new Counted(p.box().part(), p.multiplier(), p.share(), scaled, seen, raw));
        }
        if (counted != null) standParts = List.copyOf(counted);
        // at the range's edge NTM's box is in it and no part is: NTM's damage, no part's multiplier
        if (!reached || !Double.isFinite(total) || !Double.isFinite(weighted)) return whole;
        float m = total > 0 ? (float) (weighted / total) : 1F;
        // even: by the whole mob's mean (a mob seen whole takes about NTM's); else a mob with a marked head and no
        // marked limbs (a pig, a chicken) takes no more than NTM's
        if (even() && mean > 0) m = (float) (m / mean);
        else if (!limbs) m = Math.min(m, 1F);
        run.multiplier.put(entity, m);
        return (float) total;
    }

    /** the multiplier the running blast found for {@code entity}'s parts, 1 if none */
    public static float multiplier(Entity entity) {
        Run run = RUNS.get().peek();
        if (run == null) return 1F;
        Float m = run.multiplier.get(entity);
        return m == null ? 1F : m;
    }

    /** the mob's parts as the blast takes them */
    static List<Part> parts(LivingEntity living) {
        if (!ModelHits.enabled()) return List.of();
        var at = ModelHits.standPose(living);
        if (at == null) return List.of();
        // which parts count and their shares, once per pose (in the model's frame); then only put in the world
        List<Part> model;
        if (at.cache().memo instanceof Shape shape) model = shape.parts();
        else at.cache().memo = new Shape(model = shape(at.parts(), living.getBbWidth(), living.getBbHeight()));
        if (model.isEmpty()) return model;
        List<Part> out = new ArrayList<>(model.size());
        for (Part p : model) out.add(new Part(at.world(p.box()), p.multiplier(), p.share()));
        return out;
    }

    /** a pose's parts as the blast takes them, in the model's frame (from the feet, not turned) */
    private record Shape(List<Part> parts) {
    }

    static List<Part> shape(List<ModelHits.StandPart> drawn, double width, double height) {
        // the pose comes from a client: too many boxes, or one far off the mob (at any turn), and the blast is NTM's
        if (drawn.size() > MAX_PARTS) return List.of();
        double r = width / 2 * Math.sqrt(2) + REACH;
        var near = new net.minecraft.world.phys.AABB(-r, -REACH, -r, r, height + REACH, r);
        for (var d : drawn) if (!inside(d.box(), near)) return List.of();
        return of(drawn);
    }

    /** the most boxes a mob's pose may have for the blast to take it by its parts, and how far past its box they may go */
    static final int MAX_PARTS = 48;
    static final double REACH = 1.5;

    /** every corner of {@code b} in {@code near}, and none of them NaN */
    static boolean inside(ModelBox b, net.minecraft.world.phys.AABB near) {
        for (int i = 0; i < 8; i++) {
            double x = b.x() + (i & 1) * b.ux() + ((i >> 1) & 1) * b.vx() + ((i >> 2) & 1) * b.wx();
            double y = b.y() + (i & 1) * b.uy() + ((i >> 1) & 1) * b.vy() + ((i >> 2) & 1) * b.wy();
            double z = b.z() + (i & 1) * b.uz() + ((i >> 1) & 1) * b.vz() + ((i >> 2) & 1) * b.wz();
            if (!(x >= near.minX && x <= near.maxX && y >= near.minY && y <= near.maxY && z >= near.minZ && z <= near.maxZ)) return false;
        }
        return true;
    }

    /**
     * The parts and their shares. Of a box inside another one is counted: a head's or a limb's over an unmarked one,
     * else the one higher in the model (the head under its hat, the upper arm over the forearm's overlap), else the
     * outer one; none if no part is a head or a limb. A mesh's box is taken as its mesh's bounds.
     */
    static List<Part> of(List<ModelHits.StandPart> drawn) {
        if (drawn.isEmpty()) return List.of();
        boolean marked = false;
        List<ModelHits.StandPart> kept = new ArrayList<>(drawn.size());
        for (var a : drawn) {
            boolean dropped = false;
            for (var b : drawn) {
                if (a == b) continue;
                boolean bOuter = b.box().encloses(a.box());
                if ((bOuter || a.box().encloses(b.box())) && before(b, a, bOuter)) {
                    dropped = true;
                    break;
                }
            }
            if (dropped) continue;
            kept.add(a);
            marked |= a.head() || a.limb();
        }
        if (!marked) return List.of();
        double sum = 0;
        double[] area = new double[kept.size()];
        for (int i = 0; i < area.length; i++) sum += area[i] = surface(carrier(kept.get(i).box()));
        if (sum <= 0) return List.of();
        List<Part> out = new ArrayList<>(area.length);
        for (int i = 0; i < area.length; i++) {
            var k = kept.get(i);
            out.add(new Part(carrier(k.box()), ModelHeads.multiplier(k.head(), k.limb()), area[i] / sum));
        }
        return out;
    }

    /** of two boxes one inside the other, {@code b} is kept before {@code a} */
    private static boolean before(ModelHits.StandPart b, ModelHits.StandPart a, boolean bOuter) {
        boolean mb = b.head() || b.limb(), ma = a.head() || a.limb();
        if (mb != ma) return mb;
        if (depth(b) != depth(a)) return depth(b) < depth(a);
        return bOuter;
    }

    /** a mesh's box as the box around its mesh (ModelMesh's bounds in the box's own frame), a cube's as it is */
    static ModelBox carrier(ModelBox b) {
        if (b.mesh() == 0) return b;
        ModelMesh shape = ModelMesh.get(b.mesh());
        double[] k = shape == null ? null : shape.boundsData();
        if (k == null || k.length < 6) return b;
        double x = b.x() + b.ux() * k[0] + b.vx() * k[1] + b.wx() * k[2], y = b.y() + b.uy() * k[0] + b.vy() * k[1] + b.wy() * k[2],
                z = b.z() + b.uz() * k[0] + b.vz() * k[1] + b.wz() * k[2];
        double su = k[3] - k[0], sv = k[4] - k[1], sw = k[5] - k[2];
        return new ModelBox(b.part(), x, y, z, b.ux() * su, b.uy() * su, b.uz() * su, b.vx() * sv, b.vy() * sv, b.vz() * sv,
                b.wx() * sw, b.wy() * sw, b.wz() * sw);
    }

    private static int depth(ModelHits.StandPart p) {
        return p.box().part().split("/").length;
    }

    static double surface(ModelBox b) {
        Vec3 u = new Vec3(b.ux(), b.uy(), b.uz()), v = new Vec3(b.vx(), b.vy(), b.vz()), w = new Vec3(b.wx(), b.wy(), b.wz());
        return 2 * (u.cross(v).length() + v.cross(w).length() + w.cross(u).length());
    }

    /** the box's point nearest {@code p} (its edges taken as square to each other, as a model's cube's are) */
    static Vec3 nearest(ModelBox b, Vec3 p) {
        double[][] e = { { b.ux(), b.uy(), b.uz() }, { b.vx(), b.vy(), b.vz() }, { b.wx(), b.wy(), b.wz() } };
        double x = b.x(), y = b.y(), z = b.z();
        double dx = p.x - b.x(), dy = p.y - b.y(), dz = p.z - b.z();
        for (double[] a : e) {
            double len2 = a[0] * a[0] + a[1] * a[1] + a[2] * a[2];
            if (len2 < 1e-12) continue;
            double t = Math.clamp((dx * a[0] + dy * a[1] + dz * a[2]) / len2, 0, 1);
            x += a[0] * t;
            y += a[1] * t;
            z += a[2] * t;
        }
        return new Vec3(x, y, z);
    }

    /** the part's points the rays start from: its middle, then pairs along its longest edge */
    static List<Vec3> points(ModelBox b, int rays) {
        double lu = Math.sqrt(b.ux() * b.ux() + b.uy() * b.uy() + b.uz() * b.uz());
        double lv = Math.sqrt(b.vx() * b.vx() + b.vy() * b.vy() + b.vz() * b.vz());
        double lw = Math.sqrt(b.wx() * b.wx() + b.wy() * b.wy() + b.wz() * b.wz());
        int axis = lu >= lv && lu >= lw ? 0 : lv >= lw ? 1 : 2;
        List<Vec3> out = new ArrayList<>(rays);
        out.add(ModelContours.point(b, .5, .5, .5));
        for (int i = 1; out.size() < rays; i++) {
            // 0.15 and 0.85 of the length, then halfway in to the middle and so on
            double off = 0.35 / i;
            for (double t : new double[] { .5 - off, .5 + off }) {
                if (out.size() >= rays) break;
                out.add(ModelContours.point(b, axis == 0 ? t : .5, axis == 1 ? t : .5, axis == 2 ? t : .5));
            }
        }
        return out;
    }

    /** the share of the part's rays that reach the blast from its best node, as NTM's getBlockDensity's */
    static double visibility(Level level, ModelBox b, Vec3[] nodes, Entity entity, int rays) {
        // a point inside a block (a big model reaches into a wall) sends no ray: the part's others go by themselves
        List<Vec3> from = new ArrayList<>(rays);
        for (Vec3 p : points(b, rays)) if (!solid(level, p)) from.add(p);
        if (from.isEmpty()) return 0;
        double best = 0;
        for (Vec3 node : nodes) {
            int clear = 0;
            for (Vec3 p : from)
                if (level.clip(new ClipContext(p, node, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType()
                        == HitResult.Type.MISS) clear++;
            best = Math.max(best, clear / (double) from.size());
            if (best >= 1) break;
        }
        return best;
    }

    private static boolean solid(Level level, Vec3 p) {
        var pos = net.minecraft.core.BlockPos.containing(p);
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) return false;
        Vec3 in = p.subtract(pos.getX(), pos.getY(), pos.getZ());
        for (var box : shape.toAabbs()) if (box.contains(in)) return true;
        return false;
    }

    private static boolean nuclear() {
        return StackWalker.getInstance().walk(frames -> frames.limit(48)
                .anyMatch(f -> NUCLEAR.contains(f.getClassName()) || NUCLEAR.contains(f.getClassName() + "." + f.getMethodName())));
    }
}
