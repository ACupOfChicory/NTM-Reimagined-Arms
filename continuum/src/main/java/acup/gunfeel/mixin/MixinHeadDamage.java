package acup.gunfeel.mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.items.weapon.sedna.BulletImpact;
import com.hbm.util.DamageResistanceHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// port/continuum: main's MixinHeadDamage (NTM NEXT's BulletConfig lambdas) on NTM Continuum, whose standard bullet and
// beam hits are BulletImpact.standardEntityHit and standardBeamHit. The damage of a hit is the model's: head, body or
// limb where the bullet met the model (HeadDamage, ModelHeads), instead of NTM's band above the eyes.
@Mixin(value = BulletImpact.class, remap = false)
public abstract class MixinHeadDamage {
    @Shadow
    private static void attack(ServerLevel level, LivingEntity victim, EntityBulletBaseMK4 bullet, DamageSource source, float amount) {
    }

    @Redirect(method = "standardEntityHit", require = 1, at = @At(value = "INVOKE",
            target = "Lcom/hbm/items/weapon/sedna/BulletImpact;attack(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lcom/hbm/entity/projectile/EntityBulletBaseMK4;Lnet/minecraft/world/damagesource/DamageSource;F)V"))
    private static void gunfeel$head(ServerLevel level, LivingEntity victim, EntityBulletBaseMK4 bullet, DamageSource source, float damage,
            EntityBulletBaseMK4 hitBullet, HitResult impact) {
        attack(level, victim, bullet, source, acup.gunfeel.HeadDamage.damage(bullet, victim, impact, damage));
    }

    @Redirect(method = "standardBeamHit", require = 1, at = @At(value = "INVOKE",
            target = "Lcom/hbm/util/DamageResistanceHandler;hurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;FFF)Z"))
    private static boolean gunfeel$beamZones(ServerLevel level, Entity target, DamageSource source, float damage, float threshold, float piercing,
            EntityBulletBeamBase beam, HitResult impact) {
        float zoned = target instanceof LivingEntity living ? acup.gunfeel.HeadDamage.beamDamage(damage, living, impact) : damage;
        return DamageResistanceHandler.hurt(level, target, source, zoned, threshold, piercing);
    }
}
