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

// A wing/arm can lie outside the vanilla box. Add cached model candidates without widening every world query.
@Mixin(ProjectileUtil.class)
public abstract class MixinProjectileModelQuery {
    @WrapMethod(method="getManyEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;FLnet/minecraft/world/level/ClipContext$Block;Z)Ljava/util/Collection;")
    private static java.util.Collection<net.minecraft.world.phys.EntityHitResult> gunfeel$manyViews(Level level,Entity entity,
            net.minecraft.world.phys.Vec3 from,net.minecraft.world.phys.Vec3 to,AABB area,Predicate<Entity> filter,float margin,
            net.minecraft.world.level.ClipContext.Block blocks,boolean pierce,
            Operation<java.util.Collection<net.minecraft.world.phys.EntityHitResult>> original) {
        if(entity==null)return original.call(level,entity,from,to,area,filter,margin,blocks,pierce);
        var owner=entity instanceof net.minecraft.world.entity.projectile.Projectile p?p.getOwner():entity;
        var previous=ModelHits.beginProjectile(owner,entity instanceof net.minecraft.world.entity.projectile.Projectile?entity.getDeltaMovement().length():0);
        try{return original.call(level,entity,from,to,area,filter,margin,blocks,pierce);}
        finally{ModelHits.endShot(previous);}
    }
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
            "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            "getManyEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;FLnet/minecraft/world/level/ClipContext$Block;Z)Ljava/util/Collection;"}, require = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<Entity> gunfeel$modelCandidates(Level level, Entity except, AABB area, Predicate<Entity> filter) {
        return ModelHits.candidates(level, except, level.getEntities(except, area, filter), area, filter);
    }
}
