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
    public static float beamDamage(float damage,LivingEntity target,HitResult impact) {
        var hit=hit(target,impact);
        return hit.applied()?damage*ModelHeads.multiplier(hit.head(),hit.source().equals("limbs")):damage;
    }
    private static ModelHeads.Hit hit(LivingEntity target,HitResult impact) {
        var hit=ModelHits.head(target,impact.getLocation());
        if(hit.applied() && (!hit.head() || hit.source().equals("height")) && ModelHits.limb(target,impact.getLocation()))
            return new ModelHeads.Hit(true,false,"limbs","проверенная конечность выше высотной догадки головы",1F);
        return hit;
    }
    public static float damage(EntityBulletBaseMK4 bullet,LivingEntity target,HitResult impact,float original) {
        var hit=hit(target,impact);LAST.set(new Last(new WeakReference<>(bullet),target.getUUID(),hit));
        if(!hit.applied()) return original;
        return bullet.damage*ModelHeads.multiplier(hit.head(),hit.source().equals("limbs"));
    }
    public static ModelHeads.Hit last(EntityBulletBaseMK4 bullet,net.minecraft.world.entity.Entity target,net.minecraft.world.phys.Vec3 point) {
        var last=LAST.get();if(last!=null && last.bullet().get()==bullet && last.target().equals(target.getUUID())) return last.hit();
        return ModelHits.head(target,point);
    }
}
