package acup.gunfeel;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.model.EntityModel;
import net.minecraft.resources.Identifier;

public interface ModelHitState {
    LivingEntity gunfeel$target();
    void gunfeel$target(LivingEntity entity);
    EntityModel<?> gunfeel$primary();
    Identifier gunfeel$texture();
    void gunfeel$primary(EntityModel<?> model, Identifier texture);
}
