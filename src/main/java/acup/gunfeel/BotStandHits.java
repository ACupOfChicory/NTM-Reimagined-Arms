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

    /** a ray from {@code from} to {@code to}: what the drawn model shows there ("" for nothing) */
    record Ray(Vec3 from, Vec3 to, String drawn) {
    }

    /** where a part's name puts it: the head, a limb or the body */
    static String zone(String part) {
        if (part.isEmpty()) return "";
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
                out.add(new Ray(from, to, zone(part)));
            }
        }
        return out;
    }

    /** the grid's shares on the server: hits on the drawn model, past it, the zone; and the plain box's */
    record Grid(int in, int out, double inHit, double outHit, double zoneSame, double boxIn, double boxOut, String wrong) {
    }

    /** on the server thread: each ray as a bullet would test it (ModelHits.clip, the box as its fallback) */
    static Grid server(Mob m, List<Ray> rays) {
        // the mob's box as the game has it: getBoundingBox is the mod's ModelAABB, whose clip is the model's
        AABB model = m.getBoundingBox(), box = new AABB(model.minX, model.minY, model.minZ, model.maxX, model.maxY, model.maxZ);
        int in = 0, out = 0, inHit = 0, outHit = 0, same = 0, boxIn = 0, boxOut = 0;
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
            if (zone.equals(r.drawn())) same++;
            else wrong.merge(r.drawn() + "→" + zone, 1, Integer::sum);
        }
        return new Grid(in, out, in == 0 ? 0 : inHit / (double) in, out == 0 ? 0 : outHit / (double) out,
                inHit == 0 ? 0 : same / (double) inHit, in == 0 ? 0 : boxIn / (double) in, out == 0 ? 0 : boxOut / (double) out,
                wrong.toString());
    }

    // ---------------------------------------------------------------- the carbine

    record Loadout(String name, Identifier head, Identifier chest, Identifier legs, Identifier feet) {
    }

    static final Loadout[] LOADOUTS = { new Loadout("без брони", null, null, null, null),
            new Loadout("SECURITY", hbm("security_helmet"), hbm("security_plate"), hbm("security_legs"), hbm("security_boots")),
            new Loadout("GLASS+PAA", Identifier.withDefaultNamespace("glass"), hbm("paa_plate"), hbm("paa_legs"), hbm("paa_boots")) };

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
