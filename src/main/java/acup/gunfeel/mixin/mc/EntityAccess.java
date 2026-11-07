package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

// the step an entity made over its last tick, as the server counts it at the start of each tick (Entity.computeSpeed):
// for a player, where its client's move packets took it. ServerPlayer.getKnownSpeed is the last packet's step instead,
// and a packet that only turns the view (one a frame while a fire key is held, ClickAim) makes it zero (ProneFire).
@Mixin(Entity.class)
public interface EntityAccess {
    @Accessor("lastKnownSpeed")
    Vec3 gunfeel$lastKnownSpeed();
}
