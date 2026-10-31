package acup.gunfeel.mixins;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// port: copied from 26.2 (mixin/MixinRicochetSound.java, e284339). Server: NTM's standard ricochet
// (BulletConfig.LAMBDA_STANDARD_RICOCHET, lambda$static$0) plays "hbm:weapon.ricochet" at the bullet; off steel ours
// instead (BulletServer.ricochetSound). Not found in another NTM version: NTM's sound stays.
@Mixin(value = BulletConfig.class, remap = false)
public abstract class MixinRicochetSound {
    @Redirect(method = "lambda$static$0", require = 0, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;playSoundAtEntity(Lnet/minecraft/entity/Entity;Ljava/lang/String;FF)V"))
    private static void gunfeel$sound(World world, Entity entity, String sound, float volume, float pitch, EntityBulletBaseMK4 bullet,
            MovingObjectPosition mop) {
        if (acup.gunfeel.BulletServer.ricochetSound(world, mop)) return;
        world.playSoundAtEntity(entity, sound, volume, pitch);
    }
}
