package acup.gunfeel.mixin.mc;

import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Точные части заменяют общую коробку и красную полосу глаз. Направление взгляда остаётся.
@Mixin(EntityHitboxDebugRenderer.class)
public abstract class MixinModelHitboxDebug {
    @Unique private Entity gunfeel$entity;
    @Unique private GizmoProperties gunfeel$modelOutline;

    @Inject(method = "showHitboxes", at = @At("HEAD"))
    private void gunfeel$target(Entity entity, float partial, boolean server, CallbackInfo ci) {
        gunfeel$entity = entity;
        gunfeel$modelOutline = null;
    }

    @Redirect(method = "showHitboxes", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/gizmos/Gizmos;cuboid(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/gizmos/GizmoStyle;)Lnet/minecraft/gizmos/GizmoProperties;"))
    private GizmoProperties gunfeel$parts(AABB original, GizmoStyle style) {
        if(!acup.gunfeel.ModelHitDebug.defer(gunfeel$entity,original)) return Gizmos.cuboid(original,style);
        return gunfeel$modelOutline=acup.gunfeel.ModelHitDebug.DEFERRED;
    }

    @Redirect(method = "showHitboxes", at = @At(value = "INVOKE", ordinal = 2,
            target = "Lnet/minecraft/gizmos/Gizmos;cuboid(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/gizmos/GizmoStyle;)Lnet/minecraft/gizmos/GizmoProperties;"))
    private GizmoProperties gunfeel$hideEyeStrip(AABB original, GizmoStyle style) {
        return gunfeel$modelOutline != null ? gunfeel$modelOutline : Gizmos.cuboid(original, style);
    }

    @Redirect(method="showHitboxes",at=@At(value="INVOKE",ordinal=3,
            target="Lnet/minecraft/gizmos/Gizmos;cuboid(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/gizmos/GizmoStyle;)Lnet/minecraft/gizmos/GizmoProperties;"))
    private GizmoProperties gunfeel$dragonParts(AABB original,GizmoStyle style) {
        return acup.gunfeel.ModelHits.hasPose(gunfeel$entity)
                ?acup.gunfeel.ModelHitDebug.DEFERRED:Gizmos.cuboid(original,style);
    }

    @Inject(method = "showHitboxes", at = @At("RETURN"))
    private void gunfeel$clear(Entity entity, float partial, boolean server, CallbackInfo ci) {
        gunfeel$entity = null;
        gunfeel$modelOutline = null;
    }
}
