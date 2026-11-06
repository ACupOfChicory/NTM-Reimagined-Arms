package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterImpact;
import net.minecraft.util.MovingObjectPosition;

// Server: NTM's impact of a bullet in water (WaterImpact): b75_inc's small blast, no setting alight under water.
// port: 26.2's MixinWaterImpact (7e2382c) on NTM 1.7.10's EntityBulletBaseMK4.onImpact (NTM's own method, not remapped)
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinWaterImpact {
    @WrapMethod(method = "onImpact")
    private void gunfeel$water(MovingObjectPosition hit, Operation<Void> original) {
        WaterImpact.impact((EntityBulletBaseMK4) (Object) this, hit, () -> original.call(hit));
    }
}
