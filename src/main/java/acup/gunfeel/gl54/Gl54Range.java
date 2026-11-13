package acup.gunfeel.gl54;

import java.util.OptionalDouble;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.BulletRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// The GL-54's laser rangefinder (design.md, 2026-11-07): the gun's second key while the gun is aimed measures the horizontal
// distance to what the grenade would hit along the look direction, and the number stays on the gun (a data component, set on
// the server, synced) until the next measurement. A grenade then bursts after flying that far (Gl54Fuse), and the sight shows
// the range and the holdover mark (ReflexDraw). A try that hits nothing clears the number.
public final class Gl54Range {
    // how far the ray reaches, blocks
    public static final double REACH = 200.0;
    // the measure is off by up to this much either way (uniform), as a rangefinder's is
    public static final double NOISE = 0.5;
    // the burst's shift from the range, blocks: the most either way, and where it starts: past cover (a block hit), at a creature
    public static final int OFFSET_MAX = 3, OFFSET_BLOCK = 1, OFFSET_ENTITY = 0;
    // dev (Gl54ReflexStand): off, the measure is exact
    public static volatile boolean noiseOn = true;

    // the point found, in the world; null when there is none (a gun measured before the point was kept has a range only)
    public static Vec3 target(ItemStack stack) {
        if (Gl54Items.TARGET == null) return null;
        return stack.get(Gl54Items.TARGET.get());
    }

    // the range stored on a stack, blocks; NaN when there is none
    public static float get(ItemStack stack) {
        if (Gl54Items.RANGE == null) return Float.NaN;
        Float r = stack.get(Gl54Items.RANGE.get());
        return r == null ? Float.NaN : r;
    }

    // the burst's shift on a stack, blocks (0 with none)
    public static int offset(ItemStack stack) {
        if (Gl54Items.OFFSET == null) return 0;
        Integer o = stack.get(Gl54Items.OFFSET.get());
        return o == null ? 0 : o;
    }

    // the range as it is (the stand's), the shift back at 0; none clears both
    public static void set(ItemStack stack, OptionalDouble range) {
        set(stack, range, 0);
    }

    public static void set(ItemStack stack, OptionalDouble range, int offset) {
        stack.remove(Gl54Items.TARGET.get());
        if (range.isPresent()) {
            stack.set(Gl54Items.RANGE.get(), (float) range.getAsDouble());
            stack.set(Gl54Items.OFFSET.get(), clamp(offset));
        } else {
            stack.remove(Gl54Items.RANGE.get());
            stack.remove(Gl54Items.OFFSET.get());
        }
    }

    // the point found (the rangefinder's, noise in), its horizontal distance from the eye kept as the range, and the shift
    public static void setTarget(ItemStack stack, Vec3 point, double range, int offset) {
        stack.set(Gl54Items.TARGET.get(), point);
        stack.set(Gl54Items.RANGE.get(), (float) range);
        stack.set(Gl54Items.OFFSET.get(), clamp(offset));
    }

    public static int clamp(int offset) {
        return Math.max(-OFFSET_MAX, Math.min(OFFSET_MAX, offset));
    }

    // where the shift starts for what the ray hit
    public static int defaultOffset(boolean entity) {
        return entity ? OFFSET_ENTITY : OFFSET_BLOCK;
    }

    // the measure with its noise: the true distance and up to NOISE more or less, never under 0
    public static double noisy(double distance, double unit) {
        return Math.max(0.0, distance + (unit * 2.0 - 1.0) * NOISE);
    }

    // the wheel while aimed (server): shifts the burst of the stored range by `delta`, within the limit; nothing without a range
    public static void shift(net.minecraft.server.level.ServerPlayer player, int delta) {
        ItemStack stack = player.getMainHandItem();
        if (Gl54Items.GUN == null || stack.getItem() != Gl54Items.GUN.get() || !Gl54Server.enabled() || !ItemGunBaseNT.getIsAiming(stack)) return;
        if (Float.isNaN(get(stack))) return;
        stack.set(Gl54Items.OFFSET.get(), clamp(offset(stack) + Integer.signum(delta)));
    }

    // the gun's second key went down (server): measures if the gun is aimed, does nothing otherwise
    static void onKey(ItemStack stack, ItemGunBaseNT.LambdaContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null || player.level().isClientSide() || !Gl54Server.enabled() || !ItemGunBaseNT.getIsAiming(stack)) return;
        Hit hit = measure(player.level(), player);
        // thick smoke on the ray (gl54.rangeSmoke; Fable №2, 2.5): the laser ends where it goes in, the range stays as it was
        if (hit != null && smoked(player.level(), player.getEyePosition(), hit.point())) return;
        if (hit == null) {
            set(stack, OptionalDouble.empty());
            return;
        }
        // the noise is along the ray: the point is found a little nearer or farther than it is
        Vec3 eye = player.getEyePosition();
        double along = hit.point().distanceTo(eye);
        double shift = noiseOn ? noisy(along, player.getRandom().nextDouble()) - along : 0.0;
        Vec3 point = hit.point().add(player.getLookAngle().normalize().scale(shift));
        setTarget(stack, point, horizontal(eye, point), defaultOffset(hit.entity()));
    }

    // what the ray found: the point, and whether it was a creature
    public record Hit(Vec3 point, boolean entity) {
    }

    // the first block or creature on the look ray from the eye; null if there is none in reach.
    // Blocks the grenade flies through (leaves, by the bullet rules) are not hits; glass is.
    public static Hit measure(Level level, LivingEntity shooter) {
        Vec3 eye = shooter.getEyePosition(), look = shooter.getLookAngle();
        Vec3 end = eye.add(look.scale(REACH));
        Vec3 blockAt = null;
        Vec3 from = eye;
        for (int i = 0; i < 64; i++) {
            BlockHitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
            if (hit.getType() == HitResult.Type.MISS) break;
            BlockPos pos = hit.getBlockPos();
            if (BulletRules.through(level.getBlockState(pos))) {
                // on from just inside the block that lets the grenade through
                from = hit.getLocation().add(look.scale(0.01));
                continue;
            }
            blockAt = hit.getLocation();
            break;
        }
        double limit = blockAt == null ? REACH : blockAt.distanceTo(eye);
        Vec3 stop = eye.add(look.scale(limit));
        AABB box = new AABB(eye, stop).inflate(1.0);
        double best = Double.MAX_VALUE;
        Vec3 where = blockAt;
        boolean entity = false;
        for (Entity e : level.getEntities(shooter, box, x -> x.isPickable() && x.isAlive() && !x.isSpectator())) {
            var hit = e.getBoundingBox().inflate(e.getPickRadius()).clip(eye, stop);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceTo(eye);
            if (d < best) {
                best = d;
                where = hit.get();
                entity = true;
            }
        }
        if (where == null) return null;
        return new Hit(where, entity);
    }

    // the smoke's optical depth past which the laser comes back from the cloud, not from what is behind it (e^-3: 5 % through)
    public static final float SMOKE_DEPTH = 3F;

    // the ray from the eye to the point found goes through thick smoke (gl54.rangeSmoke on; off: the laser sees through)
    public static boolean smoked(Level level, Vec3 eye, Vec3 point) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server) || !acup.gunfeel.ReaimTimes.flag("gl54.rangeSmoke", true)) return false;
        var clouds = acup.gunfeel.smoke.SmokeClouds.get(server);
        return clouds != null && !clouds.isEmpty() && clouds.opticalDepth(eye, point) > SMOKE_DEPTH;
    }

    public static double horizontal(Vec3 from, Vec3 to) {
        return Math.hypot(to.x - from.x, to.z - from.z);
    }
}
