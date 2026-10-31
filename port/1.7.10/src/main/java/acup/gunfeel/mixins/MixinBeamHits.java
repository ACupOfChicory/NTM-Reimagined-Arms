package acup.gunfeel.mixins;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

// The mobs' hitboxes from their models for NTM's beams (26.2: the hitscan part of the hitboxes' mixins): while
// performHitscan runs on the server, rays are checked against the pose the shooter sees, the hit loop's
// entity.boundingBox is the model's box (ModelAABB) and mobs whose model sticks out of the vanilla box are candidates.
@Mixin(value = EntityBulletBeamBase.class, remap = false)
public abstract class MixinBeamHits {
    @Unique
    private ModelHits.ShotContext gunfeel$beamPrevious;
    @Unique
    private boolean gunfeel$beamOn;

    @Inject(method = "performHitscan", at = @At("HEAD"))
    private void gunfeel$beginBeam(CallbackInfo ci) {
        EntityBulletBeamBase self = (EntityBulletBeamBase) (Object) this;
        if (self.worldObj.isRemote) return;
        gunfeel$beamPrevious = ModelHits.beginShot(self.getThrower());
        gunfeel$beamOn = true;
    }

    @Inject(method = "performHitscan", at = @At("RETURN"))
    private void gunfeel$endBeam(CallbackInfo ci) {
        if (!gunfeel$beamOn) return;
        gunfeel$beamOn = false;
        ModelHits.endShot(gunfeel$beamPrevious);
        gunfeel$beamPrevious = null;
    }

    @WrapOperation(method = "performHitscan", at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.GETFIELD, remap = true,
            target = "Lnet/minecraft/entity/Entity;boundingBox:Lnet/minecraft/util/AxisAlignedBB;"))
    private AxisAlignedBB gunfeel$beamBox(Entity entity, Operation<AxisAlignedBB> original) {
        if (entity == (Object) this || entity.worldObj.isRemote) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "performHitscan", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gunfeel$beamCandidates(World world, Entity except, AxisAlignedBB area, Operation<List> original) {
        List list = original.call(world, except, area);
        if (world.isRemote) return list;
        return ModelHits.candidates(world, except, (List<Entity>) list, area, e -> true);
    }
}
