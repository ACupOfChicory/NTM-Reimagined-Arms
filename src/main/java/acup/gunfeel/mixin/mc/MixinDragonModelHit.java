package acup.gunfeel.mixin.mc;

import acup.gunfeel.ModelHitState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.monster.dragon.EnderDragonModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Дракон использует EntityRenderer напрямую, минуя LivingEntityRenderer.
@Mixin(EnderDragonRenderer.class)
public abstract class MixinDragonModelHit {
    @Shadow @Final private EnderDragonModel model;
    @Inject(method="submit(Lnet/minecraft/client/renderer/entity/state/EnderDragonRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",at=@At("HEAD"))
    private void gunfeel$primary(EnderDragonRenderState state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera,CallbackInfo ci) {
        ((ModelHitState)state).gunfeel$primary(camera.initialized?model:null,Identifier.withDefaultNamespace("textures/entity/enderdragon/dragon.png"));
    }
}
