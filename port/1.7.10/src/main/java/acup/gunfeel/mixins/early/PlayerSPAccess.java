package acup.gunfeel.mixins.early;

// port: 26.2's mixin/mc/LocalPlayerAccess (e809204): what EntityClientPlayerMP last sent the server (ClickAim sends the
// turn between its ticks).

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.entity.EntityClientPlayerMP;

@Mixin(EntityClientPlayerMP.class)
public interface PlayerSPAccess {
    @Accessor("oldRotationYaw")
    float gunfeel$lastYaw();

    @Accessor("oldRotationYaw")
    void gunfeel$setLastYaw(float yaw);

    @Accessor("oldRotationPitch")
    float gunfeel$lastPitch();

    @Accessor("oldRotationPitch")
    void gunfeel$setLastPitch(float pitch);

    @Accessor("wasOnGround")
    boolean gunfeel$lastOnGround();
}
