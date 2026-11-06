package acup.gunfeel.mixin.de;

import acup.gunfeel.Config;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// NTM DE's explosion clouds and smoke are Flywheel visuals with order-independent transparency. Flywheel 1.0.7 (inside
// DE) lays them over the frame in OitFramebuffer.composite with the depth written: wherever a cloud has any alpha at
// all, the depth becomes the cloud's nearest. The translucent terrain comes after it, so see-through glass behind even
// the faintest edge of a cloud fails the depth test and is not drawn (the laminate, its pane, stained glass, the
// reinforced glass pane). Here composite keeps the depth as it was, as CrankShaft (26.2's Flywheel) does; the dense
// parts of a cloud still write theirs in renderDepthFromTransmittance, before. The price: see-through glass behind a
// cloud is drawn over it, not under, where the cloud is not dense (as on 26.2). Not with "Fabulous!" graphics: there
// the translucent terrain has a target of its own and is sorted by the depth composite writes, so the glass already
// shows and the cloud's depth keeps the order right. Both backends (indirect, instancing) composite through this class.
// Off with ntmRenderFixes=false.
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer")
public abstract class MixinOitComposite {
    @ModifyArg(method = "composite()V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;depthMask(Z)V"),
            require = 0)
    private boolean gunfeel$keepDepth(boolean write) {
        return write && (!Config.ntmRenderFixes || Minecraft.useShaderTransparency());
    }

    // Flywheel leaves the depth written after composite; the same here
    @Inject(method = "composite()V", at = @At("RETURN"), require = 0)
    private void gunfeel$depthBack(CallbackInfo ci) {
        RenderSystem.depthMask(true);
    }
}
