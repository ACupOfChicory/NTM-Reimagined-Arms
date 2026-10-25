package acup.gunfeel.mixin;

import com.hbm.render.loader.HFRWavefrontObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.hbm.client.render.flywheel.GlyphidVisual", remap = false)
public interface GlyphidGeometryAccess {
    @Accessor("MESH") static HFRWavefrontObject gunfeel$mesh() { throw new AssertionError(); }
    @Accessor("PART_IDS") static int[] gunfeel$ids() { throw new AssertionError(); }
    @Accessor("ARMOR_BIT") static int[] gunfeel$armor() { throw new AssertionError(); }
}
