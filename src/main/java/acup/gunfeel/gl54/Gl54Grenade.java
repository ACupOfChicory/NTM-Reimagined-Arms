package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.hbm.particle.SpentCasing;
import com.hbm.particle.SpentCasing.CasingType;

import acup.gunfeel.BulletRules;
import acup.gunfeel.BulletServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// The GL-54's 20 mm high-explosive grenade (design.md, 2026-11-06): a blast of 3 blocks that takes NTM's straight fall-off
// and does not break blocks, but hits the glass in its radius by the mod's glass rules. The owner's numbers are the damage by
// area to a creature near the blast: 18 in the middle, 12 at a block, 6 at two. The mod counts a bullet's explosion at 0.4 of
// the bullet's damage for the area (and 0.6 for a direct hit, ExplosiveDamage), so the bullet's damage is 45; the glass takes
// the fall-off of the whole 45, so it breaks out to 2.3 blocks (hp 10); armed after 3 blocks of flight: nearer it is a dud that hits for 4 and goes out. Leaves are no business of
// its own: the bullet rules (ntmgunfeel-bullets.txt) fly every bullet through them. The speed is the owner's (4.7 blocks a tick), the drop 40 mm NTM's for now.
public final class Gl54Grenade {
    // the blast's reach in blocks; NTM's ExplosionVNT size is half of it (the entity processor doubles it)
    public static final float RADIUS = 3F;
    // the bullet's damage; a direct armed hit does about 31
    public static final float BASE_DAMAGE = 45F;
    // the mod's share of it that the blast does by area (ExplosiveDamage.BLAST)
    public static final float AREA_SHARE = 0.4F;
    // flight before the fuze is armed, blocks
    public static final double ARM_DISTANCE = 3.0;
    public static final float DUD_DAMAGE = 4F;
    // the owner's: the real XM29 20 mm at about 180 m/s, scaled by NTM's own factor for the 40 mm (2.0 blocks a tick against a
    // real M203's 76 m/s); the drop is 40 mm NTM's for now
    public static final float SPEED = 4.7F;
    public static final double GRAVITY = 0.035;
    public static final int LIFE = 200;
    public static final String CASING = "gl54";

    // dev (Gl54Stand): told where a grenade went off or fell as a dud (where, blocks flown, went off, where it was fired from)
    public static volatile java.util.function.Consumer<Object[]> watch;

    private static final Map<Entity, Vec3> STARTS = Collections.synchronizedMap(new WeakHashMap<>());

    private Gl54Grenade() {
    }

    // NTM's blast: the straight fall-off from the middle to the edge (EntityProcessorCrossSmooth.calculateDamage) of the bullet's
    // damage: what the glass takes
    public static float damageAt(double distance) {
        return distance >= RADIUS ? 0F : (float) (BASE_DAMAGE * (1.0 - distance / RADIUS));
    }

    // the damage by area to a creature at this distance from the blast: 18 in the middle, 12 at a block, 6 at two
    public static float areaDamageAt(double distance) {
        return damageAt(distance) * AREA_SHARE;
    }

    public static boolean armed(double flown) {
        return flown >= ARM_DISTANCE;
    }

    private static SpentCasing casing;

    // one case for every kind of grenade (registered once under its name)
    private static synchronized SpentCasing casing() {
        if (casing == null) casing = new SpentCasing(CasingType.STRAIGHT).setColor(SpentCasing.COLOR_CASE_BRASS).setScale(1F, 1F, 1F).register(CASING);
        return casing;
    }

    // the flight and the blast of the HE grenade put on a config; the other kinds fly as HE until stage 3 gives them their own
    public static <T extends BulletConfig> T apply(T config) {
        config.setLife(LIFE)
                .setVel(SPEED)
                .setGrav(GRAVITY)
                .setOnUpdate(Gl54Grenade::update)
                .setOnImpact(Gl54Grenade::impact)
                .setCasing(casing());
        return config;
    }

    // every tick of the flight (server): where it began, from the first tick's old position; the range fuse (Gl54Fuse)
    static void update(Entity entity) {
        if (entity.level().isClientSide()) return;
        Vec3 start = STARTS.get(entity);
        if (start == null) {
            start = new Vec3(entity.xo, entity.yo, entity.zo);
            STARTS.put(entity, start);
            if (entity instanceof EntityBulletBaseMK4 bullet) Gl54Fuse.light(bullet);
        }
        if (entity instanceof EntityBulletBaseMK4 bullet) Gl54Fuse.step(bullet, start);
    }

    // blocks flown from where it was fired to `at`; 0 before the first tick is over
    static double flown(EntityBulletBaseMK4 bullet, Vec3 at) {
        Vec3 start = STARTS.get(bullet);
        return start == null ? 0.0 : start.distanceTo(at);
    }

    static void impact(EntityBulletBaseMK4 bullet, HitResult hit) {
        if (!(bullet.level() instanceof ServerLevel level)) return;
        double flown = flown(bullet, hit.getLocation());
        Vec3 start = STARTS.remove(bullet);
        var watching = watch;
        if (watching != null) watching.accept(new Object[] {hit.getLocation(), flown, Gl54Server.enabled() && armed(flown), start});
        if (!Gl54Server.enabled() || !armed(flown)) {
            // a dud: a hit on a creature is NTM's own hit with 4, on a block it falls
            if (hit instanceof EntityHitResult) bullet.damage = DUD_DAMAGE;
            else bullet.discard();
            return;
        }
        Lego.standardExplode(bullet, hit, RADIUS / 2F);
        breakGlass(level, bullet, hit.getLocation());
        bullet.discard();
    }

    // the glass in the blast's radius that the blast can reach takes the damage it would do there; the glass breaks by
    // the mod's rule for it (hp 10: cracks, then gone), NTM's explosion itself breaks no block
    static void breakGlass(ServerLevel level, EntityBulletBaseMK4 bullet, Vec3 centre) {
        int r = (int) Math.ceil(RADIUS);
        BlockPos middle = BlockPos.containing(centre);
        for (BlockPos pos : BlockPos.betweenClosed(middle.offset(-r, -r, -r), middle.offset(r, r, r))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !glass(state)) continue;
            Vec3 target = Vec3.atCenterOf(pos);
            // the nearest point of its box is the glass's distance
            double d = distanceToBlock(centre, pos);
            float damage = damageAt(d);
            if (damage <= 0F || !reaches(level, centre, pos, target)) continue;
            BlockHitResult hit = new BlockHitResult(target, Direction.UP, pos.immutable(), false);
            BulletServer.blockHit(bullet, bullet.getOwner(), damage, hit);
        }
    }

    static double distanceToBlock(Vec3 p, BlockPos pos) {
        double dx = Math.max(Math.max(pos.getX() - p.x, 0), p.x - (pos.getX() + 1));
        double dy = Math.max(Math.max(pos.getY() - p.y, 0), p.y - (pos.getY() + 1));
        double dz = Math.max(Math.max(pos.getZ() - p.z, 0), p.z - (pos.getZ() + 1));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // nothing else solid between the blast and the glass (the blast stands in front of it, or in the air next to it)
    static boolean reaches(ServerLevel level, Vec3 centre, BlockPos pos, Vec3 target) {
        BlockHitResult first = level.clip(new ClipContext(centre, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return first.getType() == HitResult.Type.MISS || first.getBlockPos().equals(pos);
    }

    // what counts as glass here: a block with a bullet break rule that is glass (a tag of any mod's, or glass in its id)
    static boolean glass(BlockState state) {
        BulletRules.Break rule = BulletRules.breakRule(state);
        if (rule == null || rule.mode() == 0) return false;
        return state.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("c", "glass_blocks")))
                || state.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath("c", "glass_panes")))
                || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("glass");
    }

    // for tests: the blocks of a cube that a blast of this radius at `centre` can touch
    static List<BlockPos> candidates(Vec3 centre) {
        List<BlockPos> out = new ArrayList<>();
        int r = (int) Math.ceil(RADIUS);
        BlockPos middle = BlockPos.containing(centre);
        for (BlockPos pos : BlockPos.betweenClosed(middle.offset(-r, -r, -r), middle.offset(r, r, r)))
            if (distanceToBlock(centre, pos) < RADIUS) out.add(pos.immutable());
        return out;
    }

    @Nullable
    static Vec3 startOf(Entity e) {
        return STARTS.get(e);
    }
}
