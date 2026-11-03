package acup.gunfeel.mixins.early;

// port: new for 1.12.2. Cleanroom 0.7 turns the player in EntityRenderer.updateCameraAndRender with its
// InputHooks.onMouseTurn(player, yaw, pitch) instead of EntityPlayerSP.turn: the same as MixinMouseTurn there. Only
// queued on Cleanroom (ArmsCore): Forge's Mixin fails an optional wrap of a call it does not find. The call goes on
// through the wrap, so the mod names no Cleanroom class.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;

@Mixin(EntityRenderer.class)
public abstract class MixinMouseTurnCleanroom {
    @WrapOperation(method = "updateCameraAndRender", at = @At(value = "INVOKE",
            target = "Lcom/cleanroommc/client/input/InputHooks;onMouseTurn(Lnet/minecraft/client/entity/EntityPlayerSP;FF)V", remap = false))
    private void gunfeel$radialCleanroom(EntityPlayerSP player, float yaw, float pitch, Operation<Void> turn) {
        if (!acup.gunfeel.RadialMenu.takesTurn()) turn.call(player, yaw, pitch);
    }
}
