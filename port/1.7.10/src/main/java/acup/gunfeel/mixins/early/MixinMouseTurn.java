package acup.gunfeel.mixins.early;

// port: 26.2's mixin/mc/MixinMouseTurn.java (0762086) for 1.7.10. 26.2 cancels MouseHandler.turnPlayer and hands its
// accumulated movement to the menu; 1.7.10 turns the player in EntityRenderer.updateCameraAndRender with setAngles,
// after MouseHelper.mouseXYChange: both calls (smooth camera or not) go to the menu instead, with the raw movement in
// pixels (LWJGL's y is up, 26.2's GLFW y down: -deltaY).

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import acup.gunfeel.RadialMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.renderer.EntityRenderer;

// While the radial ammo menu is open the mouse moves its pointer, not the camera
@Mixin(EntityRenderer.class)
public abstract class MixinMouseTurn {
    @Redirect(method = "updateCameraAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/entity/EntityClientPlayerMP;setAngles(FF)V"))
    private void gunfeel$radial(EntityClientPlayerMP player, float yaw, float pitch) {
        if (!RadialMenu.isOpen()) {
            player.setAngles(yaw, pitch);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        RadialMenu.move(mc.mouseHelper.deltaX, -mc.mouseHelper.deltaY);
    }
}
