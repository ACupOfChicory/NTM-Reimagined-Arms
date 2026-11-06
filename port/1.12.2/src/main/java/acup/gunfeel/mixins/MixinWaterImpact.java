package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterImpact;
import net.minecraft.util.math.RayTraceResult;

// 26.2's MixinWaterImpact. Server: NTM's impact of a bullet in water (WaterImpact): b75_inc's small blast, no setting
// alight under water
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinWaterImpact {
    @WrapMethod(method = "onImpact")
    private void gunfeel$water(RayTraceResult hit, Operation<Void> original) {
        WaterImpact.impact((EntityBulletBaseMK4) (Object) this, hit, () -> original.call(hit));
    }
}
