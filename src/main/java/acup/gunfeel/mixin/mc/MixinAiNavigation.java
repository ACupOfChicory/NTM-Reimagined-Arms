package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.ai.AiNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

// A block that changes on a mob's path makes the game search that mob's path again, on the server thread
// (ServerLevel.sendBlockUpdated asks every navigating mob). An AI mob follows its own paths, searched off the thread
// and checked against the tactical map: its navigation says no.
@Mixin(PathNavigation.class)
public abstract class MixinAiNavigation implements AiNavigation {
    @Unique
    private boolean gunfeel$aiOwned;

    @Override
    public void gunfeel$setAiOwned(boolean owned) {
        gunfeel$aiOwned = owned;
    }

    @Override
    public boolean gunfeel$aiOwned() {
        return gunfeel$aiOwned;
    }

    @Inject(method = "shouldRecomputePath", at = @At("HEAD"), cancellable = true)
    private void gunfeel$keepAiPath(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (gunfeel$aiOwned) cir.setReturnValue(false);
    }
}
