package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.LivingEntity;

// whether a creature jumps (Prone)
@Mixin(LivingEntity.class)
public interface LivingEntityAccess {
    // the jump key held (a player) or a jump asked for (a mob); lying down is not allowed then (Prone, as TaCZ)
    @Accessor("jumping")
    boolean gunfeel$jumping();
}
