package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.tileentity.turret.TileEntityTurretBaseNT;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BeamServer;
import acup.gunfeel.BeamTurrets;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// port: 26.2's MixinBeamTurretSight for NTM 1.12.2 CE, where entityInLOS asks the static Library.isObstructedOpaque(world,
// target, pivot), which every turret shares: only the two beam turrets' call goes to BeamTurrets.
// a beam turret (Tauon, Maxwell) sees its target where its beam gets to it by the bullet rules (BeamTurrets), not along
// the blocks' boxes
@Mixin(value = TileEntityTurretBaseNT.class, remap = false)
public abstract class MixinBeamTurretSight {
    @WrapOperation(method = "entityInLOS", at = @At(value = "INVOKE",
            target = "Lcom/hbm/lib/Library;isObstructedOpaque(Lnet/minecraft/world/World;DDDDDD)Z"))
    private boolean gunfeel$sight(World world, double tx, double ty, double tz, double px, double py, double pz, Operation<Boolean> original) {
        TileEntity self = (TileEntity) (Object) this;
        if (!BeamTurrets.beam(self) || world.isRemote || !BeamServer.on()) return original.call(world, tx, ty, tz, px, py, pz);
        float damage = (Object) self instanceof TurretMaxwellAccess
                ? BeamTurrets.damage(self, ((TurretMaxwellAccess) (Object) self).gunfeel$red(), ((TurretMaxwellAccess) (Object) self).gunfeel$black())
                : BeamTurrets.damage(self, 0, 0);
        return BeamTurrets.blocked(self, new Vec3d(tx, ty, tz), new Vec3d(px, py, pz), damage);
    }
}
