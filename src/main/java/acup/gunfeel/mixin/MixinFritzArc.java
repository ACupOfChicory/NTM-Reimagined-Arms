package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.blockentity.turret.BlockEntityTurretBaseNT;
import com.hbm.blockentity.turret.BlockEntityTurretFritz;

import acup.gunfeel.ammo.FlamerArc;
import acup.gunfeel.ammo.FlamerGravity;
import net.minecraft.world.phys.Vec3;

// Server: the Fritz turret's flame falls (FlamerGravity), so it lifts its barrel for the arc to end on the target. NTM's
// alignTurret turns it straight at the target (turnTowards); the yaw stays NTM's, the target too (no lead, as NTM's)
@Mixin(value = BlockEntityTurretBaseNT.class, remap = false)
public abstract class MixinFritzArc {
    @Shadow
    public Vec3 tPos;

    @Inject(method = "alignTurret", at = @At("HEAD"), cancellable = true)
    private void gunfeel$arc(CallbackInfo ci) {
        BlockEntityTurretBaseNT self = (BlockEntityTurretBaseNT) (Object) this;
        if (!(self instanceof BlockEntityTurretFritz) || tPos == null || !FlamerGravity.fritz()) return;
        Vec3 d = tPos.subtract(self.getTurretPos());
        double pitch = FlamerArc.pitch(Math.sqrt(d.x * d.x + d.z * d.z), d.y, FlamerArc.SPEED, FlamerArc.FALL, self.getBarrelLength());
        if (!Double.isFinite(pitch)) return;
        self.turnTowardsAngle(pitch, -Math.atan2(d.x, d.z));
        ci.cancel();
    }
}
