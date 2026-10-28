package acup.gunfeel.mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import acup.gunfeel.ExplosiveDamage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value=EntityBulletBaseMK4.class,remap=false)
public abstract class MixinExplosiveBullet {
    @Unique private boolean gunfeel$impactSent;
    @Redirect(method="onImpact",at=@At(value="INVOKE",target="Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V",ordinal=0),require=1)
    private void gunfeel$impact(BiConsumer<Object,Object> callback,Object bullet,Object hit){
        gunfeel$impactSent |= ExplosiveDamage.impact(callback,(EntityBulletBaseMK4)bullet,(HitResult)hit,!gunfeel$impactSent);
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"),require=1)
    private void gunfeel$update(Consumer<Object> callback,Object bullet){ExplosiveDamage.update(callback,(EntityBulletBaseMK4)bullet);}
}
