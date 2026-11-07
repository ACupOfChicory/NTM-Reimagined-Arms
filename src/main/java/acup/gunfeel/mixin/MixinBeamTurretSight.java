package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.blockentity.turret.BlockEntityTurretBaseNT;

import acup.gunfeel.BeamServer;
import acup.gunfeel.BeamTurrets;
import net.minecraft.world.phys.Vec3;

// a beam turret (Tauon, Maxwell) sees its target where its beam gets to it by the bullet rules (BeamTurrets), not along
// the blocks' boxes. NTM's isObstructedOpaque(target, pivot) in entityInLOS
@Mixin(value = BlockEntityTurretBaseNT.class, remap = false)
public abstract class MixinBeamTurretSight {
    @Inject(method = "isObstructedOpaque", at = @At("HEAD"), cancellable = true)
    private void gunfeel$sight(Vec3 target, Vec3 pivot, CallbackInfoReturnable<Boolean> cir) {
        BlockEntityTurretBaseNT self = (BlockEntityTurretBaseNT) (Object) this;
        if (!BeamTurrets.beam(self) || self.getLevel() == null || self.getLevel().isClientSide() || !BeamServer.on()) return;
        float damage = (Object) self instanceof TurretMaxwellAccess m ? BeamTurrets.damage(self, m.gunfeel$red(), m.gunfeel$black())
                : BeamTurrets.damage(self, 0, 0);
        cir.setReturnValue(BeamTurrets.blocked(self, target, pivot, damage));
    }
}
