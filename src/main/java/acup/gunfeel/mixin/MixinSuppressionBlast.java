package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityThrowableNT;
import com.hbm.explosion.vanillant.ExplosionVNT;

import acup.gunfeel.SuppressionServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// Server: an NTM blast (grenades, explosive rounds, rockets, machines, bombs) is told to the players near it, for the
// suppression scale (SuppressionServer); the one who threw or fired it is left out
@Mixin(value = ExplosionVNT.class, remap = false)
public abstract class MixinSuppressionBlast {
    @Inject(method = "explode", at = @At("HEAD"), require = 1)
    private void gunfeel$suppression(CallbackInfo ci) {
        ExplosionVNT e = (ExplosionVNT) (Object) this;
        if (!(e.world instanceof ServerLevel level)) return;
        LivingEntity by = e.getIndirectSourceEntity();
        if (by == null && e.exploder instanceof EntityThrowableNT t) by = t.getThrower();
        SuppressionServer.blast(level, new Vec3(e.posX, e.posY, e.posZ), e.size, by);
    }
}
