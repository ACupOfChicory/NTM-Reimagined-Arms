package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.ai.TacticalMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

// Every block a loaded chunk changes, NTM's explosions included: the AI's tactical map marks what it copied of the
// section stale. Leaves at once while no mob uses the AI.
@Mixin(LevelChunk.class)
public abstract class MixinAiBlockChange {
    @Shadow @Final private Level level;

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void gunfeel$aiBlockChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> ci) {
        if (TacticalMap.active && level instanceof ServerLevel server) TacticalMap.blockChanging(server, pos, state);
        if (acup.gunfeel.ai.AiTerrain.active && level instanceof ServerLevel server) acup.gunfeel.ai.AiTerrain.blockChanging(server, pos);
    }
}
