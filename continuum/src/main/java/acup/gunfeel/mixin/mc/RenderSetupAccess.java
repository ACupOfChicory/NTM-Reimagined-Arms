package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;

// port/continuum: what 26.1's RenderType.draw reads off its setup, for Prepared261
@Mixin(RenderSetup.class)
public interface RenderSetupAccess {
    @Accessor("layeringTransform")
    LayeringTransform gunfeel$layeringTransform();

    @Accessor("textureTransform")
    TextureTransform gunfeel$textureTransform();
}
