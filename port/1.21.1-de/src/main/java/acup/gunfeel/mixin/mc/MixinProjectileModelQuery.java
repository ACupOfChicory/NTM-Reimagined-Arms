package acup.gunfeel.mixin.mc;

import java.util.List;
import java.util.function.Predicate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import acup.gunfeel.ModelHits;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Port copy (notes/ports/de-1.21.1.md, «Копии»): 1.21.1 has no ProjectileUtil.getManyEntityHitResult (26.2's piercing
// query); the two getEntityHitResult are as in 26.2.
// A wing/arm can lie outside the vanilla box. Add cached model candidates without widening every world query.
@Mixin(ProjectileUtil.class)
public abstract class MixinProjectileModelQuery {
    @WrapMethod(method="getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;")
    private static net.minecraft.world.phys.EntityHitResult gunfeel$pickView(Entity entity,
            net.minecraft.world.phys.Vec3 from,net.minecraft.world.phys.Vec3 to,AABB area,Predicate<Entity> filter,double distance,
            Operation<net.minecraft.world.phys.EntityHitResult> original) {
        var owner=entity instanceof net.minecraft.world.entity.projectile.Projectile p?p.getOwner():entity;
        var previous=ModelHits.beginProjectile(owner,entity instanceof net.minecraft.world.entity.projectile.Projectile?entity.getDeltaMovement().length():0);
        try{return original.call(entity,from,to,area,filter,distance);}
        finally{ModelHits.endShot(previous);}
    }
    @WrapMethod(method="getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;")
    private static net.minecraft.world.phys.EntityHitResult gunfeel$view(Level level,Entity entity,
            net.minecraft.world.phys.Vec3 from,net.minecraft.world.phys.Vec3 to,AABB area,Predicate<Entity> filter,float margin,
            Operation<net.minecraft.world.phys.EntityHitResult> original) {
        if(entity==null)return original.call(level,entity,from,to,area,filter,margin);
        var owner=entity instanceof net.minecraft.world.entity.projectile.Projectile p?p.getOwner():entity;
        var previous=ModelHits.beginProjectile(owner,entity instanceof net.minecraft.world.entity.projectile.Projectile?entity.getDeltaMovement().length():0);
        try{return original.call(level,entity,from,to,area,filter,margin);}
        finally{ModelHits.endShot(previous);}
    }
    @Redirect(method = {
            "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;"}, require = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<Entity> gunfeel$modelCandidates(Level level, Entity except, AABB area, Predicate<Entity> filter) {
        return ModelHits.candidates(level, except, level.getEntities(except, area, filter), area, filter);
    }
}
