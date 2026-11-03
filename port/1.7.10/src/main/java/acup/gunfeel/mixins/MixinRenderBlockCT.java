package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.render.block.RenderBlockCT;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.world.IBlockAccess;

// The cracks on NTM's connected-texture blocks (its glass: ash, boron, lead, reinforced...). RenderGlobal draws a
// block's cracks by drawing the block once more with the crack's icon set on the RenderBlocks it hands over, in a blend
// that doubles what is under it. NTM's RenderBlockCT draws with its own RenderBlocksCT and never sees that icon: the
// glass itself came out once more, doubled, and the block looked lit up while it had cracks (a bullet's wear,
// BulletServer.wear, or a player breaking it). With an icon set: a plain cube in that icon, as vanilla's glass.
@Mixin(value = RenderBlockCT.class, remap = false)
public abstract class MixinRenderBlockCT {
    @Inject(method = "renderWorldBlock", at = @At("HEAD"), cancellable = true, require = 1)
    private void gunfeel$cracks(IBlockAccess world, int x, int y, int z, Block block, int modelId, RenderBlocks renderer,
            CallbackInfoReturnable<Boolean> cir) {
        if (renderer != null && renderer.hasOverrideBlockTexture()) cir.setReturnValue(renderer.renderStandardBlock(block, x, y, z));
    }
}
