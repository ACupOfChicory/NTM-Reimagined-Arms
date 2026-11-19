package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import acup.gunfeel.BodyOwner;
import acup.gunfeel.EntityBody;
import acup.gunfeel.host.Body;
import net.minecraft.world.entity.Entity;

// An entity's one Body for core (EntityBody), kept on the entity for its whole life: holding the Body is holding the
// entity, as a reference to it was. Made the first time it is asked for, on the server's thread (the AI's).
@Mixin(Entity.class)
public abstract class MixinEntityBody implements BodyOwner {
    @Unique private Body gunfeel$body;

    @Override
    public Body gunfeel$body() {
        Body b = gunfeel$body;
        if (b == null) gunfeel$body = b = new EntityBody((Entity) (Object) this);
        return b;
    }
}
