package acup.gunfeel.mixin.mc;

import acup.gunfeel.ModelHitDebug;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Gizmos.class)
public abstract class MixinLegacyHitboxGizmos {
    @Inject(method="cuboid(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/gizmos/GizmoStyle;)Lnet/minecraft/gizmos/GizmoProperties;",
            at=@At("HEAD"),cancellable=true)
    private static void gunfeel$replaceLegacy(AABB box,GizmoStyle style,CallbackInfoReturnable<GizmoProperties> ci) {
        if(ModelHitDebug.replaceLegacyBox(box,style)) ci.setReturnValue(ModelHitDebug.DEFERRED);
    }
}
