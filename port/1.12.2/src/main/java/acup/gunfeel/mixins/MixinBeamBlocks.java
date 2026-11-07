package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BeamServer;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// port: 26.2's MixinBeamBlocks (X578-X580) on NTM 1.12.2 CE's EntityBulletBeamBase: Level.clip -> World.rayTraceBlocks,
// the coin's setPos(Vec3d) -> setPosition(x, y, z) (two of them, one for each kind of target), the beam's own impact.
// NTM's beam through blocks by the bullet rules (BeamServer): its one look at the blocks in performHitscan, and the
// damage each hit on its way gets (less what the blocks before it took)
@Mixin(value = EntityBulletBeamBase.class, remap = false)
public abstract class MixinBeamBlocks implements BeamServer.Holder {
    @Unique
    private BeamServer.Path gunfeel$path;

    @Override
    public BeamServer.Path gunfeel$path() {
        return gunfeel$path;
    }

    @Override
    public void gunfeel$path(BeamServer.Path path) {
        gunfeel$path = path;
    }

    @Inject(method = "performHitscan", at = @At("HEAD"))
    private void gunfeel$begin(CallbackInfo ci) {
        gunfeel$path = null;
    }

    @WrapOperation(method = "performHitscan", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;rayTraceBlocks(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;ZZZ)Lnet/minecraft/util/math/RayTraceResult;"))
    private RayTraceResult gunfeel$blocks(World world, Vec3d from, Vec3d to, boolean liquids, boolean noBox, boolean last,
            Operation<RayTraceResult> original) {
        EntityBulletBeamBase self = (EntityBulletBeamBase) (Object) this;
        if (world.isRemote || !BeamServer.covers(self) || !BeamServer.on()) return original.call(world, from, to, liquids, noBox, last);
        return BeamServer.clip(self, world, from, to);
    }

    // the beam a coin sends on gets x1.25 of what this one has at the coin, not of its whole damage (NTM sets it where
    // the coin is)
    @WrapOperation(method = "performHitscan", at = @At(value = "INVOKE", remap = true,
            target = "Lcom/hbm/entity/projectile/EntityBulletBeamBase;setPosition(DDD)V"))
    private void gunfeel$coin(EntityBulletBeamBase next, double x, double y, double z, Operation<Void> original) {
        original.call(next, x, y, z);
        if (gunfeel$path != null && next != (Object) this) BeamServer.reflected((EntityBulletBeamBase) (Object) this, next, x, y, z);
    }

    // each hit with the damage the beam has there; its own damage back after
    @WrapOperation(method = "performHitscan", at = @At(value = "INVOKE",
            target = "Lcom/hbm/entity/projectile/EntityBulletBeamBase;onImpact(Lnet/minecraft/util/math/RayTraceResult;)V"))
    private void gunfeel$impact(EntityBulletBeamBase self, RayTraceResult mop, Operation<Void> original) {
        if (gunfeel$path == null || mop.hitVec == null) {
            original.call(self, mop);
            return;
        }
        float own = self.damage;
        self.damage = BeamServer.damageAt(self, mop.hitVec.x, mop.hitVec.y, mop.hitVec.z);
        try {
            original.call(self, mop);
        } finally {
            self.damage = own;
        }
    }
}
