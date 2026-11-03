package acup.gunfeel.mixins.early;

// port: 26.2's mixin/mc/MixinMouseTurn.java (0762086) for 1.7.10. 26.2 cancels MouseHandler.turnPlayer and hands its
// accumulated movement to the menu; 1.7.10 turns the player in EntityRenderer.updateCameraAndRender with setAngles,
// after MouseHelper.mouseXYChange: both calls (smooth camera or not) go to the menu instead, with the raw movement in
// pixels (LWJGL's y is up, 26.2's GLFW y down: -deltaY).
// port: 1.12.2 turns with EntityPlayerSP.turn there; Cleanroom 0.7 calls its InputHooks.onMouseTurn(player, yaw, pitch)
// in its place (it fires its own turn event, then turns): there this one finds nothing (optional) and
// MixinMouseTurnCleanroom (mixins.ntmgunfeel.cleanroom.json, queued by ArmsCore on Cleanroom only) wraps that call.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.RadialMenu;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;

// While the radial ammo menu is open the mouse moves its pointer, not the camera
@Mixin(EntityRenderer.class)
public abstract class MixinMouseTurn {
    @WrapOperation(method = "updateCameraAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/entity/EntityPlayerSP;turn(FF)V"), require = 0)
    private void gunfeel$radial(EntityPlayerSP player, float yaw, float pitch, Operation<Void> turn) {
        if (!RadialMenu.takesTurn()) turn.call(player, yaw, pitch);
    }
}
