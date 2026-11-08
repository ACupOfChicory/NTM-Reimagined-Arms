package acup.gunfeel.mixin.mc;

import acup.gunfeel.ai.bench.PathCount;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// the mob AI's stand (acup.gunfeel.ai.bench.AiBench): how many mobs a changed block asks whether to look for a new
// path, and how many go looking; counted only while the stand measures
@Mixin(PathNavigation.class)
public abstract class MixinPathCount {
    @Inject(method = "shouldRecomputePath", at = @At("HEAD"))
    private void gunfeel$asked(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
        if (PathCount.on) PathCount.asked++;
    }

    @Inject(method = "recomputePath", at = @At("HEAD"))
    private void gunfeel$recomputed(CallbackInfo ci) {
        if (PathCount.on) PathCount.recomputed++;
    }
}
