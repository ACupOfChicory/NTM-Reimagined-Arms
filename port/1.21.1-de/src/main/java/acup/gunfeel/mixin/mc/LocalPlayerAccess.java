package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.player.LocalPlayer;

// what LocalPlayer last sent the server (ClickAim sends the turn between its ticks). 1.21.1 sends no horizontal
// collision with the turn and keeps no lastHorizontalCollision.
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccess {
    @Accessor("yRotLast")
    float gunfeel$yRotLast();

    @Accessor("yRotLast")
    void gunfeel$setYRotLast(float yRot);

    @Accessor("xRotLast")
    float gunfeel$xRotLast();

    @Accessor("xRotLast")
    void gunfeel$setXRotLast(float xRot);

    @Accessor("lastOnGround")
    boolean gunfeel$lastOnGround();
}
