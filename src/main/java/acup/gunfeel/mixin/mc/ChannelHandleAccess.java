package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.audio.Channel;

import net.minecraft.client.sounds.ChannelAccess;

// a handle given its channel in the sound thread's turn (SoundNoWait)
@Mixin(ChannelAccess.ChannelHandle.class)
public interface ChannelHandleAccess {
    @Accessor("channel")
    void gunfeel$channel(Channel channel);

    @Accessor("stopped")
    void gunfeel$stopped(boolean stopped);
}
