package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.MachineMesh;
import net.minecraft.client.renderer.Tessellator;

// Client: what a machine's renderer draws straight with the Tessellator, for the holes on it (MachineMesh). Only while
// a machine is being read; otherwise nothing.
// port: TessellatorDrop, for ArmLayers.
@Mixin(Tessellator.class)
public abstract class MixinTessellator implements acup.gunfeel.TessellatorDrop {
    @Shadow
    private int[] rawBuffer;
    @Shadow
    private int vertexCount;
    @Shadow
    private int drawMode;
    @Shadow
    private boolean hasTexture;
    @Shadow
    private boolean isDrawing;

    @Shadow
    private void reset() {
    }

    @Override
    public boolean gunfeel$drop() {
        if (!isDrawing) return false;
        isDrawing = false;
        reset();
        return true;
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private void gunfeel$draw(CallbackInfoReturnable<Integer> ci) {
        if (MachineMesh.active() && isDrawing) MachineMesh.tess(rawBuffer, vertexCount, drawMode, hasTexture);
    }
}
