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
import net.minecraft.world.level.Level;
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
public final class BlastParts extends BlastPartsBase {
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
        List<Counted> counted = stand != null && !standQuiet ? new ArrayList<>() : null;
        float[] m = {Float.NaN, 0F};
        float out = split(parts, new Vec3d(centre.x, centre.y, centre.z), size, whole, density, calc, scale, push, new Sight() {
            // NTM sees the whole mob from a node (density 1): no rays, none of this is made; else the game's rays (BlastSight).
            // Made only below density 1, as before (split asks at density NaN too: the rays then get none, and throw)
            Vec3d[] sight;
            acup.gunfeel.host.WorldView world;

            @Override
            public double seen(ModelBox box) {
                if (density < 1 && sight == null) {
                    sight = new Vec3d[nodes.length];
                    for (int i = 0; i < nodes.length; i++) sight[i] = new Vec3d(nodes[i].x, nodes[i].y, nodes[i].z);
                    world = Wiring.world(level);
                }
                return BlastSight.visibility(world, EntityBody.of(entity), box, sight, rays);
            }
        }, counted, m);
        if (counted != null) standParts = List.copyOf(counted);
        if (m[1] != 0F) run.multiplier.put(entity, m[0]);
        return out;
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

    /** every corner of {@code b} in {@code near}, and none of them NaN (BlastPartsBase.inside) */
    static boolean inside(ModelBox b, net.minecraft.world.phys.AABB near) {
        return inside(b, near.minX, near.minY, near.minZ, near.maxX, near.maxY, near.maxZ);
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

    private static int depth(ModelHits.StandPart p) {
        return p.box().part().split("/").length;
    }

    /** the box's point nearest {@code p} (BlastPartsBase.nearest) */
    static Vec3 nearest(ModelBox b, Vec3 p) {
        Vec3d n = nearest(b, p.x, p.y, p.z);
        return new Vec3(n.x(), n.y(), n.z());
    }

    private static boolean nuclear() {
        return StackWalker.getInstance().walk(frames -> frames.limit(48)
                .anyMatch(f -> NUCLEAR.contains(f.getClassName()) || NUCLEAR.contains(f.getClassName() + "." + f.getMethodName())));
    }
}
