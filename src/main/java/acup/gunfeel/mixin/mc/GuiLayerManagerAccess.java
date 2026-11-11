package acup.gunfeel.mixin.mc;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.neoforged.neoforge.client.gui.GuiLayerManager;

@Mixin(GuiLayerManager.class)
public interface GuiLayerManagerAccess {
    @Accessor("layers")
    List<GuiLayerManager.NamedLayer> gunfeel$layers();
}
