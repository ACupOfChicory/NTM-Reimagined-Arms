package acup.gunfeel.mixins.early;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.CameraRecoil;
import acup.gunfeel.Config;
import acup.gunfeel.ModelHitClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {
    // The camera's roll shake (26.2: ViewportEvent.ComputeCameraAngles, roll + CameraRecoil.roll): a turn about the view
    // axis before the camera's pitch and yaw, where the hurt shake turns it too. Forge 1.7.10 has no camera event.
    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void gunfeel$roll(float partial, CallbackInfo ci) {
        if (Config.enabled && acup.gunfeel.compat.Feature.RECOIL.on() && CameraRecoil.roll != 0F) GL11.glRotatef(CameraRecoil.roll, 0F, 0F, 1F);
    }

    // The world's pass, the shader pack's shadow pass in it: a pose is taken only while the world is drawn
    // (ModelHitClient.world). Not required: without it every draw is taken, as before.
    @Inject(method = "renderWorld(FJ)V", at = @At("HEAD"), require = 0)
    private void gunfeel$worldStart(float partial, long finish, CallbackInfo ci) {
        ModelHitClient.world(true);
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("RETURN"), require = 0)
    private void gunfeel$worldEnd(float partial, long finish, CallbackInfo ci) {
        ModelHitClient.world(false);
    }

    // The bullet holes on translucent blocks (NTM's laminate, stained glass, ice) again, right after the translucent
    // terrain (Tracers.drawLateHoles), so the block's color does not lie over them. Not required: without it they are
    // drawn at RenderWorldLastEvent.
    @WrapOperation(method = "renderWorldPass", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderGlobal;renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I"))
    private int gunfeel$lateHoles(RenderGlobal global, BlockRenderLayer layer, double partial, int pass, Entity viewer, Operation<Integer> original) {
        int drawn = original.call(global, layer, partial, pass, viewer);
        if (layer == BlockRenderLayer.TRANSLUCENT) acup.gunfeel.Tracers.drawLateHoles();
        return drawn;
    }
}
