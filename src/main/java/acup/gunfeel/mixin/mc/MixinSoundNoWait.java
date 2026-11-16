package acup.gunfeel.mixin.mc;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Library;

import acup.gunfeel.SoundNoWait;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;

// A sound started while the sound thread is busy gets its handle at once, the channel in the sound thread's turn
// (SoundNoWait). Should the game change, nothing here applies and the frame waits as before.
@Mixin(SoundEngine.class)
public abstract class MixinSoundNoWait {
    @WrapOperation(method = "play", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/ChannelAccess;createHandle(Lcom/mojang/blaze3d/audio/Library$Pool;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChannelAccess.ChannelHandle> gunfeel$handle(ChannelAccess access, Library.Pool pool,
            Operation<CompletableFuture<ChannelAccess.ChannelHandle>> original) {
        SoundNoWait.applied = true;
        SoundNoWait.playAt = System.nanoTime();
        CompletableFuture<ChannelAccess.ChannelHandle> handle = SoundNoWait.handle(access, pool);
        return handle != null ? handle : original.call(access, pool);
    }

    // the stand only: the time to the channel's play (SoundNoWait.timing)
    @WrapOperation(method = "play", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V"))
    private void gunfeel$timed(ChannelAccess.ChannelHandle handle, java.util.function.Consumer<com.mojang.blaze3d.audio.Channel> play,
            Operation<Void> original) {
        original.call(handle, SoundNoWait.timed(play));
    }

    @Inject(method = "stopAll", require = 0, at = @At("HEAD"))
    private void gunfeel$stopAll(CallbackInfo ci) {
        SoundNoWait.stopAll();
    }
}
