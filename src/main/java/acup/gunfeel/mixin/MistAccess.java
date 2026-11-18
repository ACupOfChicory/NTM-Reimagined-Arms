package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.hbm.entity.effect.EntityMist;
import com.hbm.inventory.fluid.NTMFluidProperty;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

// What a tick of NTM's mist of a fluid does to one in it (its damage, effects and contamination by the fluid's
// traits): the rule an NTM gas's cloud of this mod applies by its density (ChemClouds).
@Mixin(value = EntityMist.class, remap = false)
public interface MistAccess {
    @Invoker("affect")
    void gunfeel$affect(ServerLevel server, Entity e, NTMFluidProperty prop, double intensity);
}
