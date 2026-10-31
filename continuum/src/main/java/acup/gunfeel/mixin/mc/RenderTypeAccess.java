package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

// port/continuum: the render type's setup, for Prepared261 (26.2 has RenderType.prepare())
@Mixin(RenderType.class)
public interface RenderTypeAccess {
    @Accessor("state")
    RenderSetup gunfeel$state();
}
