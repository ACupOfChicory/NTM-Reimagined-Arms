package acup.gunfeel.mixin.de;

import acup.gunfeel.port.DeReadBounds;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// DE beta.14's own bug, patched until DE fixes it (notes/ports/de-bug-blockreadbounds.md): BlockReadBounds.Client reads
// RenderChunkRegion's minChunkX/minChunkZ through MethodHandles.lookup().unreflectVarHandle, which fails for a private
// field of another class whatever setAccessible did. Its VarHandles stay null, canRead is always true, and a multiblock's
// folded cell near a chunk border makes MultiblockSurface.clientCoreOf read past the 3 x 3 chunks: the client crashes
// building the section (ArrayIndexOutOfBoundsException in RenderChunkRegion.getChunk). Here the same check, through an
// accessor. Once DE reads the corner itself, this gives the answer DE does.
@Mixin(targets = "com.hbm.util.BlockReadBounds$Client")
public abstract class MixinBlockReadBounds {
    @Inject(method = "canRead(Lnet/minecraft/world/level/BlockGetter;III)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void gunfeel$canRead(BlockGetter view, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (view instanceof RenderChunkRegionAccess region)
            cir.setReturnValue(DeReadBounds.inRegion(region.gunfeel$minChunkX(), region.gunfeel$minChunkZ(), x, z));
    }
}
