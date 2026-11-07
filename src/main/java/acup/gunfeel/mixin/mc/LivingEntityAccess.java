package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.LivingEntity;

// the last hit a creature took, which shields it from weaker ones for a while (NTM's old bullet, MixinLegacyBullet)
@Mixin(LivingEntity.class)
public interface LivingEntityAccess {
    @Accessor("lastHurt")
    float gunfeel$lastHurt();
}
