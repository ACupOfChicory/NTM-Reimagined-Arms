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
    // the range stored on a stack, blocks; NaN when there is none
    public static float get(ItemStack stack) {
        if (Gl54Items.RANGE == null) return Float.NaN;
        Float r = stack.get(Gl54Items.RANGE.get());
        return r == null ? Float.NaN : r;
    }

    public static void set(ItemStack stack, OptionalDouble range) {
        if (range.isPresent()) stack.set(Gl54Items.RANGE.get(), (float) range.getAsDouble());
        else stack.remove(Gl54Items.RANGE.get());
    }

    // the gun's second key went down (server): measures if the gun is aimed, does nothing otherwise
    static void onKey(ItemStack stack, ItemGunBaseNT.LambdaContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null || player.level().isClientSide() || !Gl54Server.enabled() || !ItemGunBaseNT.getIsAiming(stack)) return;
        set(stack, measure(player.level(), player));
    }

    // the horizontal distance from the eye to the first block or creature on the look ray; empty if there is none in reach.
    // Blocks the grenade flies through (leaves, by the bullet rules) are not hits; glass is.
    public static OptionalDouble measure(Level level, LivingEntity shooter) {
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
        for (Entity e : level.getEntities(shooter, box, x -> x.isPickable() && x.isAlive() && !x.isSpectator())) {
            var hit = e.getBoundingBox().inflate(e.getPickRadius()).clip(eye, stop);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceTo(eye);
            if (d < best) {
                best = d;
                where = hit.get();
            }
        }
        if (where == null) return OptionalDouble.empty();
        return OptionalDouble.of(horizontal(eye, where));
    }

    public static double horizontal(Vec3 from, Vec3 to) {
        return Math.hypot(to.x - from.x, to.z - from.z);
    }
}
