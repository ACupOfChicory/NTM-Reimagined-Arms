package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;

// the move vector the input works out from its keys (RubberClient swaps it with the keys)
@Mixin(ClientInput.class)
public interface ClientInputAccess {
    @Accessor("moveVector")
    Vec2 gunfeel$moveVector();

    @Accessor("moveVector")
    void gunfeel$setMoveVector(Vec2 v);
}
