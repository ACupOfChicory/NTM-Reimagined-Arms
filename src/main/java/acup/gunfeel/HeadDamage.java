package acup.gunfeel;

import java.lang.ref.WeakReference;
import java.util.UUID;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.HitResult;

/** Сохраняет фактическое решение для лога, в том числе после смертельного удара. */
public final class HeadDamage {
    private record Last(WeakReference<EntityBulletBaseMK4> bullet,UUID target,ModelHeads.Hit hit) {}
    private static final ThreadLocal<Last> LAST=new ThreadLocal<>();
    private HeadDamage() {}
    public static float damage(EntityBulletBaseMK4 bullet,LivingEntity target,HitResult impact,float original) {
        var hit=ModelHits.head(target,impact.getLocation());LAST.set(new Last(new WeakReference<>(bullet),target.getUUID(),hit));
        if(!hit.applied() || bullet.config==null || bullet.config.headshotMult<=1) return original;
        return bullet.damage*(hit.head()?bullet.config.headshotMult:1);
    }
    public static ModelHeads.Hit last(EntityBulletBaseMK4 bullet,net.minecraft.world.entity.Entity target,net.minecraft.world.phys.Vec3 point) {
        var last=LAST.get();if(last!=null && last.bullet().get()==bullet && last.target().equals(target.getUUID())) return last.hit();
        return ModelHits.head(target,point);
    }
}
