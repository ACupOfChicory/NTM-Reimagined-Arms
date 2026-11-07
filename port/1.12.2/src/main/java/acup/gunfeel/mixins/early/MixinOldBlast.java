package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.water.OldBlast;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import net.minecraft.world.Explosion;

// port only: an NTM CE old bullet's blast in water hurts creatures by 26.2's rule (OldBlast). The game's
// doExplosionA reads its size twice: for the blocks' rays (left as it is) and for the range it gathers creatures in and
// counts their falloff by (× mult). Off for every other blast (OldBlast.wet: 0).
@Mixin(Explosion.class)
public abstract class MixinOldBlast {
    @Unique private float gunfeel$wet;

    @Inject(method = "doExplosionA", at = @At("HEAD"))
    private void gunfeel$oldBlastBegin(CallbackInfo ci) {
        Explosion e = (Explosion) (Object) this;
        net.minecraft.util.math.Vec3d at = e.getPosition();
        gunfeel$wet = OldBlast.wet(e.world, at.x, at.y, at.z, e.size);
    }

    @ModifyExpressionValue(method = "doExplosionA", at = @At(value = "FIELD", target = "Lnet/minecraft/world/Explosion;size:F", ordinal = 1))
    private float gunfeel$oldBlastRange(float size) {
        return gunfeel$wet > 0 ? size * gunfeel$wet : size;
    }

    @WrapOperation(method = "doExplosionA", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getDistance(DDD)D"))
    private double gunfeel$oldBlastDistance(Entity e, double x, double y, double z, Operation<Double> original) {
        return OldBlast.distance(original.call(e, x, y, z), e, gunfeel$wet);
    }

    @WrapOperation(method = "doExplosionA", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"))
    private boolean gunfeel$oldBlastHurt(Entity e, DamageSource source, float amount, Operation<Boolean> original) {
        if (gunfeel$wet <= 0) return original.call(e, source, amount);
        float hurt = OldBlast.damage((Explosion) (Object) this, e, gunfeel$wet);
        return hurt > 0 && original.call(e, source, hurt);
    }

    // a player's push: the game sends it to them (playerKnockbackMap, SPacketExplosion), its motion on the server is not theirs
    @WrapOperation(method = "doExplosionA", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object gunfeel$oldBlastPlayerPush(java.util.Map<Object, Object> map, Object player, Object push, Operation<Object> original) {
        if (gunfeel$wet > 0 && player instanceof Entity && push instanceof net.minecraft.util.math.Vec3d)
            push = OldBlast.push((net.minecraft.util.math.Vec3d) push, (Entity) player, gunfeel$wet);
        return original.call(map, player, push);
    }

    @WrapOperation(method = "doExplosionA", at = @At(value = "INVOKE", target = "Lnet/minecraft/enchantment/EnchantmentProtection;getBlastDamageReduction(Lnet/minecraft/entity/EntityLivingBase;D)D"))
    private double gunfeel$oldBlastPush(EntityLivingBase e, double push, Operation<Double> original) {
        return OldBlast.push(original.call(e, push), e, gunfeel$wet);
    }
}
