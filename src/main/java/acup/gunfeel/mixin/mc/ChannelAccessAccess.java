package acup.gunfeel.mixin.mc;

import java.util.Set;
import java.util.concurrent.Executor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.audio.Library;

import net.minecraft.client.sounds.ChannelAccess;

// the sound thread's queue, the library and the live handles (SoundNoWait)
@Mixin(ChannelAccess.class)
public interface ChannelAccessAccess {
    @Accessor("executor")
    Executor gunfeel$executor();

    @Accessor("library")
    Library gunfeel$library();

    @Accessor("channels")
    Set<ChannelAccess.ChannelHandle> gunfeel$channels();
}
