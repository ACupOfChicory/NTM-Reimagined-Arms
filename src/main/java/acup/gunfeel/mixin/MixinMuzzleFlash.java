package acup.gunfeel.mixin;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.item.weapon.sedna.MuzzleFlash;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.water.WaterFx;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

// No muzzle flash under water: the shot makes a gas bubble there instead (WaterFx.fired).
@Mixin(value = MuzzleFlash.class, remap = false)
public abstract class MixinMuzzleFlash {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lcom/mojang/blaze3d/vertex/PoseStack;JIDI)V", at = @At("HEAD"),
            cancellable = true)
    private void gunfeel$underWater(SubmitNodeCollector collector, PoseStack pose, long time, int a, double b, int c, CallbackInfo ci) {
        if (!WaterFx.on()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = mc.gameRenderer.mainCamera();
        if (acup.gunfeel.Arms.measuring) return;
        Vector3f p;
        if (acup.gunfeel.GunFeel.current != null) {
            // the gun in this player's hands (first person): the same point Tracers.muzzle takes, in world axes
            p = camera.rotation().transform(acup.gunfeel.Tracers.view(pose));
        } else {
            // a gun in the world (another player, third person): the pose is the camera's offset in world axes
            p = pose.last().pose().transformPosition(0F, 0F, 0F, new Vector3f());
        }
        Vec3 at = camera.position().add(p.x, p.y, p.z);
        if (WaterFx.wetAt(mc.level, at)) {
            WaterFx.flashHidden++;
            ci.cancel();
        }
    }
}
