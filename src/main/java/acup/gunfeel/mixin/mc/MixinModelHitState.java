package acup.gunfeel.mixin.mc;

import acup.gunfeel.ModelHitState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class MixinModelHitState implements ModelHitState {
    @Unique private LivingEntity gunfeel$target;
    @Unique private EntityModel<?> gunfeel$primary;
    @Unique private Identifier gunfeel$texture;
    public LivingEntity gunfeel$target() { return gunfeel$target; }
    public void gunfeel$target(LivingEntity entity) { gunfeel$target = entity; gunfeel$primary = null; gunfeel$texture = null; }
    public EntityModel<?> gunfeel$primary() { return gunfeel$primary; }
    public Identifier gunfeel$texture() { return gunfeel$texture; }
    public void gunfeel$primary(EntityModel<?> model, Identifier texture) { gunfeel$primary = model; gunfeel$texture = texture; }
}
