package acup.gunfeel.mixins;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// port: copied from 26.2 (mixin/MixinRicochetSound.java, e284339). Server: NTM's standard ricochet
// (BulletConfig.LAMBDA_STANDARD_RICOCHET, lambda$static$0) plays "hbm:weapon.ricochet" at the bullet; off steel ours
// instead (BulletServer.ricochetSound). port: NTM CE plays HBMSoundHandler.ricochet with World.playSound(x, y, z, ...),
// which only a client world plays (on the server NTM CE's ricochet is silent): played to everyone near instead, as
// 26.2's NTM does (its sound, category and volume). Not found in another NTM version: NTM's sound stays.
@Mixin(value = BulletConfig.class, remap = false)
public abstract class MixinRicochetSound {
    @Redirect(method = "lambda$static$0", require = 0, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;playSound(DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FFZ)V"))
    private static void gunfeel$sound(World world, double x, double y, double z, net.minecraft.util.SoundEvent sound,
            net.minecraft.util.SoundCategory category, float volume, float pitch, boolean delay, EntityBulletBaseMK4 bullet,
            RayTraceResult mop) {
        if (acup.gunfeel.BulletServer.ricochetSound(world, mop)) return;
        if (world.isRemote) world.playSound(x, y, z, sound, category, volume, pitch, delay);
        else world.playSound(null, x, y, z, sound, category, volume, pitch);
    }
}
