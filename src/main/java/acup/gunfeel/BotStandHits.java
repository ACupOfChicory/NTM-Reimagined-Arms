package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The bot stand's shots (BotStand): a grid of rays over the model the client draws, against what the server takes
 * for a hit; and a carbine's shots to kill an FBI agent by the part it hits, in each of its loadouts, with the part's
 * multiplier before the armour (as it was) and after it.
 */
final class BotStandHits {
    /** the grid's step, blocks (2 px), and how far past the agent's box it reaches */
    static final double STEP = 1.0 / 32, MARGIN = 0.4;

    private BotStandHits() {
    }

    /** a ray from {@code from} to {@code to}: what the drawn model shows there ("" for nothing), the part it meets */
    record Ray(Vec3 from, Vec3 to, String drawn, String part) {
    }

    /** where a part's name puts it: the head, a limb or the body */
    static String zone(String part) {
        if (part.isEmpty()) return "";
        // an NTM suit's own model (T-51, HEV, ...): its groups by the piece that draws them (ModelHits.suitZone)
        var suit = ModelHits.suitZone(part);
        if (suit != null) return suit == ModelHits.Zone.HEAD ? "head" : suit == ModelHits.Zone.LIMB ? "limb" : "body";
        if (part.contains("head") || part.contains("hat")) return "head";
        if (part.contains("_arm") || part.contains("_leg")) return "limb";
        return "body";
    }

    /**
     * The rays from the four sides of the agent turned {@code yaw}, every {@link #STEP} across its box and
     * {@link #MARGIN} past it, each with the zone of the drawn box it meets first.
     */
    static List<Ray> grid(List<ModelBox> drawn, AABB box, float yaw) {
        List<Ray> out = new ArrayList<>();
        double a = Math.toRadians(yaw);
        // the agent's forward and right on the ground (Minecraft: yaw 0 faces +z)
        Vec3 fwd = new Vec3(-Math.sin(a), 0, Math.cos(a)), right = new Vec3(-Math.cos(a), 0, -Math.sin(a));
        Vec3 c = box.getCenter();
        // as far as the drawn model reaches (lying, its arms go a block past its box), and the margin past that
        double reach = Math.max(box.getXsize(), box.getZsize()) / 2;
        for (ModelBox b : drawn) for (int i = 0; i < 8; i++) {
            double x = b.x() + (i & 1) * b.ux() + ((i >> 1) & 1) * b.vx() + ((i >> 2) & 1) * b.wx() - c.x;
            double z = b.z() + (i & 1) * b.uz() + ((i >> 1) & 1) * b.vz() + ((i >> 2) & 1) * b.wz() - c.z;
            reach = Math.max(reach, Math.sqrt(x * x + z * z));
        }
        double half = reach + MARGIN, lo = box.minY - MARGIN, hi = box.maxY + MARGIN;
        for (Vec3 way : new Vec3[] { fwd.scale(-1), fwd, right, right.scale(-1) }) {
            Vec3 across = new Vec3(-way.z, 0, way.x);
            for (double s = -half; s <= half + 1e-9; s += STEP) for (double y = lo; y <= hi + 1e-9; y += STEP) {
                Vec3 mid = new Vec3(c.x + across.x * s, y, c.z + across.z * s);
                Vec3 from = mid.subtract(way.scale(3)), to = mid.add(way.scale(3));
                double best = Double.POSITIVE_INFINITY;
                String part = "";
                for (ModelBox b : drawn) {
                    double t = b.clip(from.x, from.y, from.z, to.x, to.y, to.z);
                    if (t >= 0 && t < best) {
                        best = t;
                        part = b.part();
                    }
                }
                out.add(new Ray(from, to, zone(part), part));
            }
        }
        return out;
    }

    /** the grid's shares on the server: hits on the drawn model, past it, the zone; and the plain box's */
    record Grid(int in, int out, double inHit, double outHit, double zoneSame, double boxIn, double boxOut, String wrong, int helmets, int helmetsWrong,
            int suit, int suitWrong) {
    }

    /** on the server thread: each ray as a bullet would test it (ModelHits.clip, the box as its fallback) */
    static Grid server(Mob m, List<Ray> rays) {
        // the mob's box as the game has it: getBoundingBox is the mod's ModelAABB, whose clip is the model's
        AABB model = m.getBoundingBox(), box = new AABB(model.minX, model.minY, model.minZ, model.maxX, model.maxY, model.maxZ);
        int in = 0, out = 0, inHit = 0, outHit = 0, same = 0, boxIn = 0, boxOut = 0, helmets = 0, helmetsWrong = 0, suit = 0, suitWrong = 0;
        var wrong = new java.util.TreeMap<String, Integer>();
        for (Ray r : rays) {
            var hit = ModelHits.clip(m, r.from(), r.to(), box);
            boolean plain = box.clip(r.from(), r.to()).isPresent();
            if (r.drawn().isEmpty()) {
                out++;
                if (hit.isPresent()) outHit++;
                if (plain) boxOut++;
                continue;
            }
            in++;
            if (plain) boxIn++;
            if (hit.isEmpty()) continue;
            inHit++;
            Vec3 p = hit.get();
            String zone = ModelHits.head(m, p).head() ? "head" : ModelHits.limb(m, p) ? "limb" : "body";
            // a ray on a helmet as drawn (a layer's over the head, a suit's own): the head on the server too
            boolean helmet = ModelHits.helmet(true, r.part());
            if (helmet) helmets++;
            if (helmet && !zone.equals("head")) helmetsWrong++;
            // a ray on a suit's own model (its groups, "mesh/<group>"): the part its piece covers
            if (ModelHits.suitZone(r.part()) != null) {
                suit++;
                if (!zone.equals(r.drawn())) suitWrong++;
            }
            if (zone.equals(r.drawn())) same++;
            else {
                wrong.merge(r.drawn() + "→" + zone + " " + r.part(), 1, Integer::sum);
                // a limb's piece taken for the body: the limb zone nearest, how far out
                if (r.drawn().equals("limb") && zone.equals("body")) wrong.merge("  вне " + outside(ModelHits.standLimbZones(m), p), 1, Integer::sum);
            }
        }
        return new Grid(in, out, in == 0 ? 0 : inHit / (double) in, out == 0 ? 0 : outHit / (double) out,
                inHit == 0 ? 0 : same / (double) inHit, in == 0 ? 0 : boxIn / (double) in, out == 0 ? 0 : boxOut / (double) out,
                wrong.toString(), helmets, helmetsWrong, suit, suitWrong);
    }

    /** a part's box in the agent's frame, px: across, up, along its facing; at its centre */
    record Size(String part, double across, double up, double along) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s %.2f×%.2f×%.2f", part, across, up, along);
        }
    }

    /** the boxes' sizes in px (the agent turned {@code yaw}): the parts shot and every layer drawn over the body */
    static List<Size> sizes(List<ModelBox> boxes, float yaw) {
        double a = Math.toRadians(yaw);
        Vec3 fwd = new Vec3(-Math.sin(a), 0, Math.cos(a)), right = new Vec3(-Math.cos(a), 0, -Math.sin(a)), up = new Vec3(0, 1, 0);
        List<Size> out = new ArrayList<>();
        for (ModelBox b : boxes) {
            if (!b.part().contains("/draws/") && java.util.Arrays.stream(PARTS).noneMatch(p -> p[1].equals(b.part()))) continue;
            out.add(new Size(b.part(), extent(b, right) * 16, extent(b, up) * 16, extent(b, fwd) * 16));
        }
        return out;
    }

    /**
     * Each piece of armour against the part under it (the same name without the layer's, in the same order): how far
     * its centre is from the part's, px, and the largest angle between their edges, degrees. Armour posed as the part
     * shows 0 and 0.
     */
    static List<String> fit(List<ModelBox> boxes) {
        List<String> out = new ArrayList<>();
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (ModelBox armour : boxes) {
            int at = armour.part().indexOf("/armor/draws/");
            if (at < 0) continue;
            String part = armour.part().substring(0, at);
            int index = seen.merge(part, 1, Integer::sum) - 1;
            ModelBox bare = null;
            int n = 0;
            for (ModelBox b : boxes) if (b.part().equals(part) && n++ == index) bare = b;
            if (bare == null) continue;
            double angle = Math.max(angle(bare.ux(), bare.uy(), bare.uz(), armour.ux(), armour.uy(), armour.uz()),
                    Math.max(angle(bare.vx(), bare.vy(), bare.vz(), armour.vx(), armour.vy(), armour.vz()),
                            angle(bare.wx(), bare.wy(), bare.wz(), armour.wx(), armour.wy(), armour.wz())));
            out.add(String.format(Locale.ROOT, "%s#%d %.2f px %.1f°", part, index, centreOf(bare).distanceTo(centreOf(armour)) * 16, angle));
        }
        return out;
    }

    private static Vec3 centreOf(ModelBox b) {
        return new Vec3(b.x() + (b.ux() + b.vx() + b.wx()) / 2, b.y() + (b.uy() + b.vy() + b.wy()) / 2, b.z() + (b.uz() + b.vz() + b.wz()) / 2);
    }

    private static double angle(double ax, double ay, double az, double bx, double by, double bz) {
        double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < 1e-9 || lb < 1e-9) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, Math.abs(ax * bx + ay * by + az * bz) / la / lb))));
    }

    private static double extent(ModelBox b, Vec3 axis) {
        return Math.abs(b.ux() * axis.x + b.uy() * axis.y + b.uz() * axis.z) + Math.abs(b.vx() * axis.x + b.vy() * axis.y + b.vz() * axis.z)
                + Math.abs(b.wx() * axis.x + b.wy() * axis.y + b.wz() * axis.z);
    }

    /** a shot that meets the helmet alone: from the front, half a pixel past the head (over its top, past its side) */
    record Edge(String name, Vec3 from, Vec3 to) {
    }

    static List<Edge> helmetEdges(List<ModelBox> boxes, float yaw) {
        ModelBox head = null;
        for (ModelBox b : boxes) if (b.part().equals("root/head")) head = b;
        if (head == null) return List.of();
        double a = Math.toRadians(yaw);
        Vec3 fwd = new Vec3(-Math.sin(a), 0, Math.cos(a)), right = new Vec3(-Math.cos(a), 0, -Math.sin(a));
        Vec3 c = new Vec3(head.x() + (head.ux() + head.vx() + head.wx()) / 2, head.y() + (head.uy() + head.vy() + head.wy()) / 2,
                head.z() + (head.uz() + head.vz() + head.wz()) / 2);
        double px = 1.0 / 16, top = extent(head, new Vec3(0, 1, 0)) / 2, side = extent(head, right) / 2;
        Vec3 over = c.add(0, top + px / 2, 0), past = c.add(right.scale(side + px / 2));
        return List.of(new Edge("над головой", over.add(fwd.scale(2)), over.subtract(fwd.scale(2))),
                new Edge("сбоку головы", past.add(fwd.scale(2)), past.subtract(fwd.scale(2))));
    }

    /** the zone nearest the point: its part, its size and how far out the point is along each edge, px (0.01 steps) */
    static String outside(List<HeadZone> zones, Vec3 p) {
        String best = "нет зон";
        double least = Double.POSITIVE_INFINITY;
        for (HeadZone z : zones) {
            ModelBox b = z.box();
            Vec3 u = new Vec3(b.ux(), b.uy(), b.uz()), v = new Vec3(b.vx(), b.vy(), b.vz()), w = new Vec3(b.wx(), b.wy(), b.wz());
            Vec3 d = p.subtract(b.x(), b.y(), b.z());
            double det = u.dot(v.cross(w));
            if (Math.abs(det) < 1e-12) continue;
            double[] f = { d.dot(v.cross(w)) / det, u.dot(d.cross(w)) / det, u.dot(v.cross(d)) / det };
            double[] len = { u.length() * 16, v.length() * 16, w.length() * 16 };
            double[] out = new double[3];
            double sum = 0;
            for (int i = 0; i < 3; i++) {
                out[i] = Math.max(0, Math.max(-f[i], f[i] - 1)) * len[i];
                sum += out[i] * out[i];
            }
            if (sum < least) {
                least = sum;
                best = String.format(Locale.ROOT, "%s %.1f×%.1f×%.1f: %.2f/%.2f/%.2f", b.part(), len[0], len[1], len[2],
                        Math.round(out[0] * 100) / 100.0, Math.round(out[1] * 100) / 100.0, Math.round(out[2] * 100) / 100.0);
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- the carbine

    record Loadout(String name, Identifier head, Identifier chest, Identifier legs, Identifier feet) {
    }

    static final Loadout[] LOADOUTS = { new Loadout("без брони", null, null, null, null),
            new Loadout("SECURITY", hbm("security_helmet"), hbm("security_plate"), hbm("security_legs"), hbm("security_boots")),
            new Loadout("GLASS+PAA", Identifier.withDefaultNamespace("glass"), hbm("paa_plate"), hbm("paa_legs"), hbm("paa_boots")),
            // a suit of its own model: the helmet a mesh standing further off the head than a layer's (modelHitboxesHelmet)
            new Loadout("T51", hbm("t51_helmet"), hbm("t51_plate"), hbm("t51_legs"), hbm("t51_boots")), suit("HEV", "hev"), suit("AJR", "ajr"),
            suit("NCRPA", "ncrpa"), suit("BJ", "bj"), suit("RPA", "rpa"), suit("CMB", "cmb"), suit("DNS", "dns"), suit("FAU", "fau"),
            suit("TRENCH", "trenchmaster"), suit("EUPH", "euphemium"), suit("STEAM", "steamsuit"), suit("ENV", "envsuit"),
            suit("BISMUTH", "bismuth"), suit("TAURUN", "taurun"), suit("DIESEL", "dieselsuit"), suit("SCHRAB", "schrabidium") };

    /** a full NTM set by its items' name */
    private static Loadout suit(String name, String set) {
        return new Loadout(name, hbm(set + "_helmet"), hbm(set + "_plate"), hbm(set + "_legs"), hbm(set + "_boots"));
    }

    /** the parts shot, by their names in the agent's boxes; the body first: the others are measured against it */
    static final String[][] PARTS = { { "тело", "root/body" }, { "голова", "root/head" }, { "плечо", "root/right_arm" },
            { "предплечье", "root/right_arm/forearm" }, { "бедро", "root/right_leg" }, { "голень", "root/right_leg/shin" } };

    private static Identifier hbm(String path) {
        return Identifier.fromNamespaceAndPath("hbm", path);
    }

    static void wear(Mob m, Loadout l) {
        m.setItemSlot(EquipmentSlot.HEAD, stack(l.head()));
        m.setItemSlot(EquipmentSlot.CHEST, stack(l.chest()));
        m.setItemSlot(EquipmentSlot.LEGS, stack(l.legs()));
        m.setItemSlot(EquipmentSlot.FEET, stack(l.feet()));
    }

    private static ItemStack stack(Identifier id) {
        if (id == null) return ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR) throw new IllegalStateException("no item " + id);
        return new ItemStack(item);
    }

    /** the carbine's round and its damage as NTM fires it from a new gun (Lego.calcDamage × the round's) */
    record Round(BulletConfig config, float damage) {
    }

    static Round carbine() {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(hbm("gun_carbine")));
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        var receiver = gun.getConfig(stack, 0).getReceivers(stack)[0];
        BulletConfig config = (BulletConfig) receiver.getMagazine(stack).getType(stack, null);
        return new Round(config, receiver.getBaseDamage(stack) * config.damageMult);
    }

    /** the part's box centre in the world, from the server's boxes */
    static Vec3 centre(List<ModelBox> boxes, String part) {
        for (ModelBox b : boxes)
            if (b.part().equals(part)) return new Vec3(b.x() + (b.ux() + b.vx() + b.wx()) / 2, b.y() + (b.uy() + b.vy() + b.wy()) / 2,
                    b.z() + (b.uz() + b.vz() + b.wz()) / 2);
        return null;
    }

    /** one shot of the round at {@code at}: the health it takes from an agent at 1000 */
    static float shot(ServerLevel level, LivingEntity target, Round round, Vec3 at) {
        var bullet = new EntityBulletBaseMK4(level, round.config(), round.damage(), 0, 0, 0);
        bullet.damage = round.damage();
        try {
            target.setHealth(1000);
            target.invulnerableTime = 0;
            target.hurtTime = 0;
            BulletConfig.LAMBDA_STANDARD_ENTITY_HIT.accept(bullet, new EntityHitResult(target, at));
            return 1000 - target.getHealth();
        } finally {
            bullet.discard();
        }
    }

    /** NTM's threshold and resistance of the target against the round (DamageResistanceHandler.getDTDR), "DT/DR" */
    static String dtdr(LivingEntity target, Round round) {
        var bullet = new EntityBulletBaseMK4((ServerLevel) target.level(), round.config(), round.damage(), 0, 0, 0);
        try {
            var source = BulletConfig.getDamage(bullet, null, round.config().dmgClass);
            float[] v = com.hbm.util.DamageResistanceHandler.getDTDR(target, source, round.damage(), round.config().armorThresholdNegation,
                    round.config().armorPiercingPercent);
            return String.format(Locale.ROOT, "%.2f/%.0f%%", v[0], v[1] * 100);
        } finally {
            bullet.discard();
        }
    }

    /** the agent's own health, before the stand raises it */
    static double maxHealth(LivingEntity target) {
        var a = target.getAttribute(Attributes.MAX_HEALTH);
        return a == null ? 20 : a.getBaseValue();
    }
}
