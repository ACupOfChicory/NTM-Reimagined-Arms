package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;

// what a trapdoor is made of: whether a hand opens it (AiDoors: an AI mob opens a hatch on its way as a player would)
@Mixin(TrapDoorBlock.class)
public interface TrapDoorAccess {
    @Invoker("getType")
    BlockSetType gunfeel$type();
}
