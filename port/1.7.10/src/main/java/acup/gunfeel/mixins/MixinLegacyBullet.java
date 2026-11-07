package acup.gunfeel.mixins;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBullet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BulletServer;
import acup.gunfeel.LegacyBullets;
import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

// port: 26.2's MixinLegacyBullet (X547). Server: NTM's old bullet (the hunter chopper's, the cyber crab's) through the
// mod's systems, see LegacyBullets. Its tick meets the creatures by their boxes (model boxes, ModelAABB) and then hits
// the nearest at its middle: here the point where the model was met is kept for the damage. A block on its path goes by
// the bullet rules, and where it stops a hole is sent. A "critical" one (the crab's) flies through blocks in NTM and
// still does.
// port: NTM 1.7.10's EntityBullet.onUpdate asks World.func_147447_a for blocks (26.2: Level.clip), meets creatures by
// getEntitiesWithinAABBExcludingEntity, the field boundingBox expanded by 0.3 and calculateIntercept, takes the nearest
// as new MovingObjectPosition(entity), and hurts with attackEntityFrom (26.2: hurtServer). Where it stopped, its next
// tick breaks vanilla glass and panes in that block itself: a block the rules broke is air by then.
@Mixin(value = EntityBullet.class, remap = false)
public abstract class MixinLegacyBullet {
    @Shadow public double damage;
    @Shadow public Entity shootingEntity;

    @Shadow public abstract boolean getIsCritical();

    // this tick: where each creature's model was met, the one hit and where, the block it stopped in
    @Unique private final Map<Entity, Vec3> gunfeel$met = new IdentityHashMap<Entity, Vec3>();
    @Unique private Vec3 gunfeel$point;
    @Unique private MovingObjectPosition gunfeel$stuck;

    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$begin(CallbackInfo ci) {
        gunfeel$met.clear();
        gunfeel$point = null;
        gunfeel$stuck = null;
    }

    // a creature whose model reaches out of its box is met too (as NTM's bullets, MixinThrowableClip)
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gunfeel$candidates(World world, Entity except, AxisAlignedBB area, Operation<List> original) {
        List found = original.call(world, except, area);
        return world.isRemote || !LegacyBullets.on() ? found : ModelHits.candidates(world, except, (List<Entity>) found, area, e -> true);
    }

    // a creature's box is its model's (ModelAABB): expand keeps it one. The bullet's own box (its swept area) stays
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.GETFIELD, remap = true,
            target = "Lnet/minecraft/entity/Entity;boundingBox:Lnet/minecraft/util/AxisAlignedBB;"))
    private AxisAlignedBB gunfeel$box(Entity entity, Operation<AxisAlignedBB> original) {
        if (entity == (Object) this || entity.worldObj.isRemote || !LegacyBullets.on()) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/util/AxisAlignedBB;calculateIntercept(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;)Lnet/minecraft/util/MovingObjectPosition;"))
    private MovingObjectPosition gunfeel$clip(AxisAlignedBB box, Vec3 from, Vec3 to, Operation<MovingObjectPosition> original) {
        MovingObjectPosition out = original.call(box, from, to);
        if (out != null && out.hitVec != null && box instanceof ModelAABB) gunfeel$met.put(((ModelAABB) box).entity(), out.hitVec);
        return out;
    }

    // a constructor by its descriptor, as 26.2 and the 1.12.2 port: 1.7.10's class names need no remapping
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "NEW", remap = false,
            target = "(Lnet/minecraft/entity/Entity;)Lnet/minecraft/util/MovingObjectPosition;"))
    private MovingObjectPosition gunfeel$hit(Entity entity, Operation<MovingObjectPosition> original) {
        gunfeel$point = gunfeel$met.get(entity);
        gunfeel$stuck = null; // a creature before the block: it hits the creature
        return original.call(entity);
    }

    // NTM hurts with its damage, or (the creature still shielded by its last hit) that plus the last hit: the change
    // the head or a limb makes is added to either
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/entity/Entity;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"))
    private boolean gunfeel$hurt(Entity target, DamageSource source, float amount, Operation<Boolean> original) {
        return original.call(target, source, amount + gunfeel$extra(target));
    }

    @Unique
    private float gunfeel$extra(Entity target) {
        if (gunfeel$point == null || target.worldObj.isRemote || !LegacyBullets.on()) return 0F;
        float base = (float) damage;
        return LegacyBullets.damage(target, gunfeel$point, base) - base;
    }

    // the bullet rules: a block it breaks and flies on through is not met; the path goes on behind it (the same walk,
    // from just past the broken block). The block is asked before NTM picks a creature: when a creature stands before
    // it within this tick's step, NTM hits the creature instead and its bullet flies its whole step on through the
    // block (as 26.2): the block is worn by then and gets no hole
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;func_147447_a(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;ZZZ)Lnet/minecraft/util/MovingObjectPosition;"))
    private MovingObjectPosition gunfeel$blocks(World world, Vec3 from, Vec3 to, boolean liquids, boolean noBox, boolean last,
            Operation<MovingObjectPosition> original) {
        MovingObjectPosition hit = original.call(world, from, to, liquids, noBox, last);
        if (world.isRemote || getIsCritical() || !LegacyBullets.on()) return hit;
        Entity self = (Entity) (Object) this;
        for (int i = 0; hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && hit.hitVec != null; i++) {
            if (i == 8) { // a stack of broken blocks past the limit: it stops in this one
                gunfeel$stuck = hit;
                return hit;
            }
            int got = BulletServer.blockHit(self, shootingEntity, (float) damage, hit.blockX, hit.blockY, hit.blockZ, hit.hitVec.xCoord,
                    hit.hitVec.yCoord, hit.hitVec.zCoord);
            if (got == 1) return hit; // broke, and stops there: no block left for a hole
            if (got == 0) {
                gunfeel$stuck = hit;
                return hit;
            }
            double dx = to.xCoord - hit.hitVec.xCoord, dy = to.yCoord - hit.hitVec.yCoord, dz = to.zCoord - hit.hitVec.zCoord;
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l < 1e-6) return null;
            Vec3 past = Vec3.createVectorHelper(hit.hitVec.xCoord + dx / l * 1e-3, hit.hitVec.yCoord + dy / l * 1e-3, hit.hitVec.zCoord + dz / l * 1e-3);
            hit = original.call(world, past, to, liquids, noBox, last);
        }
        return hit;
    }

    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$hole(CallbackInfo ci) {
        MovingObjectPosition stuck = gunfeel$stuck;
        gunfeel$stuck = null;
        gunfeel$met.clear();
        if (stuck != null) LegacyBullets.stuck((EntityBullet) (Object) this, stuck);
    }
}
