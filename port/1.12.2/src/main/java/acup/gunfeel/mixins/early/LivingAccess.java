package acup.gunfeel.mixins.early;

// port: 26.2's mixin/mc/LivingEntityAccess (1fc541c): the last hit a creature took, which shields it from weaker ones
// for a while (NTM's old bullets, MixinLegacyBullet, MixinOldBulletBase)

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.entity.EntityLivingBase;

@Mixin(EntityLivingBase.class)
public interface LivingAccess {
    @Accessor("lastDamage")
    float gunfeel$lastHurt();
}
