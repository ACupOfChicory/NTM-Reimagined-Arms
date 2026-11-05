package acup.gunfeel.mixin.de;

import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The lower corner chunk of the 3 x 3 chunks a section is built from: what DE's BlockReadBounds means to read and
// cannot (MixinBlockReadBounds)
@Mixin(RenderChunkRegion.class)
public interface RenderChunkRegionAccess {
    @Accessor("minChunkX")
    int gunfeel$minChunkX();

    @Accessor("minChunkZ")
    int gunfeel$minChunkZ();
}
