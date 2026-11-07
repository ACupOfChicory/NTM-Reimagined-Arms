package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.mob.EntityFBI;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ai.AiBots;
import acup.gunfeel.ai.AiTeams;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// NTM's FBI agent picks the nearest player within 128 blocks as its target every tick, seen or not, and walks
// straight at them. One the mob AI drives knows only what it sees and hears: that step is skipped for it.
// NTM's FBI agent takes no shot of another FBI agent's; an AI mob's shot at an AI mob of another team (AiTeams) it
// does take.
@Mixin(value = EntityFBI.class, remap = false)
public abstract class MixinAiFbi {
    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void gunfeel$aiBot(ServerLevel level, CallbackInfo ci) {
        if (AiBots.get((Entity) (Object) this) != null) ci.cancel();
    }

    @WrapOperation(method = "hurtServer", constant = @Constant(classValue = EntityFBI.class))
    private boolean gunfeel$enemyFbi(Object shooter, Operation<Boolean> original) {
        boolean fbi = original.call(shooter);
        if (fbi && shooter instanceof LivingEntity s && AiBots.get(s) != null && AiTeams.enemy(s, (LivingEntity) (Object) this)) return false;
        return fbi;
    }
}
