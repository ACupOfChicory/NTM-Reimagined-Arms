package acup.gunfeel.mixin;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBullet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BulletServer;
import acup.gunfeel.DebugLog;
import acup.gunfeel.LegacyBullets;
import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import acup.gunfeel.mixin.mc.LivingEntityAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Server: NTM's old bullet (the hunter chopper's, the cyber crab's) through the mod's systems, see LegacyBullets.
// Its tick meets the creatures by their boxes (model boxes, ModelAABB) and then hits the nearest at its middle: here
// the point where the model was met is kept for the damage. A block on its path goes by the bullet rules, and where it
// stops a hole is sent. A "critical" one (the crab's) flies through blocks in NTM and still does.
@Mixin(value = EntityBullet.class, remap = false)
public abstract class MixinLegacyBullet {
    @Shadow public double damage;
    @Shadow public Entity shootingEntity;

    @Shadow public abstract boolean getIsCritical();

    // this tick: where each creature's model was met, the one hit and where, the block it stopped in
    @Unique private final Map<Entity, Vec3> gunfeel$met = new IdentityHashMap<>();
    @Unique private Vec3 gunfeel$point;
    @Unique private BlockHitResult gunfeel$stuck;
    // which of NTM's two hurts this tick's hit carries the zone's change with
    @Unique private LegacyBullets.Hurt gunfeel$hurt = LegacyBullets.Hurt.FIRST;
    // the zone's change the first one worked out, for the second one
    @Unique private float gunfeel$extraNow;
    // the zones' failure is written to the log once
    @Unique private static boolean gunfeel$warned;

    @Inject(method = "tick", at = @At("HEAD"))
    private void gunfeel$begin(CallbackInfo ci) {
        gunfeel$met.clear();
        gunfeel$point = null;
        gunfeel$stuck = null;
        gunfeel$hurt = LegacyBullets.Hurt.FIRST;
        gunfeel$extraNow = 0F;
    }

    // a creature whose model reaches out of its box is met too (as NTM's bullets, MixinThrowableHit)
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<Entity> gunfeel$candidates(Level level, Entity except, AABB area) {
        List<Entity> found = level.getEntities(except, area);
        return level.isClientSide() || !LegacyBullets.on() ? found : ModelHits.candidates(level, except, found, area, e -> true);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;clip(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Ljava/util/Optional;"))
    private Optional<Vec3> gunfeel$clip(AABB box, Vec3 from, Vec3 to) {
        Optional<Vec3> out = box.clip(from, to);
        if (out.isPresent() && box instanceof ModelAABB model) gunfeel$met.put(model.entity(), out.get());
        return out;
    }

    @WrapOperation(method = "tick", at = @At(value = "NEW", target = "(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/EntityHitResult;"))
    private EntityHitResult gunfeel$hit(Entity entity, Operation<EntityHitResult> original) {
        gunfeel$point = gunfeel$met.get(entity);
        gunfeel$stuck = null; // a creature before the block: it hits the creature
        return original.call(entity);
    }

    // NTM hurts with its damage, or (the creature still shielded by its last hit) that plus the last hit: the change
    // the head or a limb makes goes to the one of the two NTM's own damage lands with (LegacyBullets.hurt)
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean gunfeel$hurt(Entity target, ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        float extra = 0F;
        LegacyBullets.Hurt hurt = LegacyBullets.Hurt.FIRST;
        try {
            extra = gunfeel$extra(target);
            // the game's cooldown as LivingEntity.hurtServer has it
            if (target instanceof LivingEntity living) hurt = LegacyBullets.hurt(amount, extra,
                    living.invulnerableTime > 10 && !source.is(DamageTypeTags.BYPASSES_COOLDOWN), ((LivingEntityAccess) living).gunfeel$lastHurt());
        } catch (RuntimeException e) {
            // the zones failed: it hits as in NTM rather than take the server's tick down
            extra = 0F;
            hurt = LegacyBullets.Hurt.FIRST;
            if (!gunfeel$warned) {
                gunfeel$warned = true;
                DebugLog.LOGGER.warn("NTM's old bullet: its head and limb zones failed, it hits as in NTM", e);
            }
        }
        gunfeel$hurt = hurt;
        gunfeel$extraNow = extra;
        return original.call(target, level, source, hurt == LegacyBullets.Hurt.SECOND ? amount : amount + extra);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean gunfeel$hurtAgain(LivingEntity target, ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        if (gunfeel$hurt == LegacyBullets.Hurt.NONE) return false;
        return original.call(target, level, source, amount + gunfeel$extraNow);
    }

    @Unique
    private float gunfeel$extra(Entity target) {
        if (gunfeel$point == null || !LegacyBullets.on()) return 0F;
        float base = (float) damage;
        return LegacyBullets.damage(target, gunfeel$point, base) - base;
    }

    // the bullet rules: a block it breaks and flies on through is not met; the path goes on behind it. NTM's old bullet
    // flies on through the creatures it hits, so a block behind one is met as well
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult gunfeel$blocks(Level level, ClipContext context) {
        BlockHitResult hit = level.clip(context);
        if (level.isClientSide() || getIsCritical() || !LegacyBullets.on()) return hit;
        Entity self = (Entity) (Object) this;
        Vec3 to = context.getTo();
        for (int i = 0; hit.getType() == HitResult.Type.BLOCK; i++) {
            if (i == 8) { // a stack of broken blocks past the limit: it stops in this one
                gunfeel$stuck = hit;
                return hit;
            }
            int got = BulletServer.blockHit(self, shootingEntity, (float) damage, hit);
            if (got == 1) return hit; // broke, and stops there: no block left for a hole
            if (got == 0) {
                gunfeel$stuck = hit;
                return hit;
            }
            Vec3 dir = to.subtract(hit.getLocation());
            if (dir.lengthSqr() < 1e-12) return BlockHitResult.miss(to, hit.getDirection(), BlockPos.containing(to));
            hit = level.clip(new ClipContext(hit.getLocation().add(dir.normalize().scale(1e-3)), to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, self));
        }
        return hit;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gunfeel$hole(CallbackInfo ci) {
        BlockHitResult stuck = gunfeel$stuck;
        gunfeel$stuck = null;
        if (stuck != null) LegacyBullets.stuck((EntityBullet) (Object) this, stuck);
    }
}
