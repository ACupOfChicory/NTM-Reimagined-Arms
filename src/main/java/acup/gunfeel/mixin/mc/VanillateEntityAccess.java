package acup.gunfeel.mixin.mc;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.lib.visual.AbstractEntityVisual", remap = false)
public interface VanillateEntityAccess {
    @Accessor("entity") Entity gunfeel$entity();
}
