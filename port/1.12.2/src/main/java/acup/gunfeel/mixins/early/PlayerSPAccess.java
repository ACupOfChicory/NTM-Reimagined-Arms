package acup.gunfeel.mixins.early;

// port: 26.2's mixin/mc/LocalPlayerAccess (e809204): what EntityPlayerSP last sent the server (ClickAim sends the turn
// between its ticks).

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.entity.EntityPlayerSP;

@Mixin(EntityPlayerSP.class)
public interface PlayerSPAccess {
    @Accessor("lastReportedYaw")
    float gunfeel$lastYaw();

    @Accessor("lastReportedYaw")
    void gunfeel$setLastYaw(float yaw);

    @Accessor("lastReportedPitch")
    float gunfeel$lastPitch();

    @Accessor("lastReportedPitch")
    void gunfeel$setLastPitch(float pitch);

    @Accessor("prevOnGround")
    boolean gunfeel$lastOnGround();
}
