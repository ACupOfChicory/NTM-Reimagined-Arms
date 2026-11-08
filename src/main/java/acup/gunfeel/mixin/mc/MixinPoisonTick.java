package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

// Ground the mustard poisoned (GasPoison): a plant's random tick there kills it instead of growing it, and grass does not
// spread from it. Every other block's random tick is left as it is.
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class MixinPoisonTick {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void gunfeel$poisoned(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (acup.gunfeel.gas.GasPoison.randomTick((BlockState) (Object) this, level, pos)) ci.cancel();
    }
}
