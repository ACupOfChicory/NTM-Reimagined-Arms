package acup.gunfeel.mixin.mc;

import acup.gunfeel.ModelHitState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityModelHitState {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void gunfeel$target(Entity entity, EntityRenderState state, float partial, CallbackInfo ci) {
        ((ModelHitState) state).gunfeel$target(entity instanceof LivingEntity living ? living : null);
    }
}
