package acup.gunfeel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.util.EntityDamageUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.network.PacketDistributor;

/** Контекст живёт только внутри обработчика снаряда, а не вокруг всех взрывов мира. */
public final class ExplosiveDamage {
    static final float CONTACT=.6F, BLAST=.4F;
    public static final class Context {
        final EntityBulletBaseMK4 bullet;
        final HitResult impact;
        boolean exploded;
        final int depth;
        Context(EntityBulletBaseMK4 bullet,HitResult impact){this.bullet=bullet;this.impact=impact;this.depth=DEPTH.get();}
    }
    public static final class Blast {
        final Context context;
        final float base;
        final ExplosionVNT explosion;
        boolean contactPaid;
        Blast(Context context,float base,ExplosionVNT explosion){this.context=context;this.base=base;this.explosion=explosion;}
    }
    public interface Processor {
        void gunfeel$bind(ExplosionVNT explosion);
        Blast gunfeel$blast();
    }
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SUPPRESS=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    private ExplosiveDamage() {}
    public static boolean suppressed(){return SUPPRESS.get();}
    public static Context current(){return CURRENT.get();}
    public static boolean impact(BiConsumer<Object,Object> callback,EntityBulletBaseMK4 bullet,HitResult hit,boolean announce){
        if(bullet.level().isClientSide()){callback.accept(bullet,hit);return false;}
        int previousDepth=DEPTH.get();
        var previous=CURRENT.get();CURRENT.set(new Context(bullet,hit));
        try {
            callback.accept(bullet,hit);
            // Только реально сработавший взрыватель: не оставляем след при отскоке/защите стрелка.
            if(announce && bullet.isRemoved() && ExplosiveAmmo.heavy(bullet.config) && hit instanceof BlockHitResult block
                    && block.getType()==HitResult.Type.BLOCK && bullet.level() instanceof ServerLevel level) {
                Vec3 dir=bullet.getDeltaMovement();
                if(dir.lengthSqr()<1e-12)dir=new Vec3(-block.getDirection().getStepX(),-block.getDirection().getStepY(),-block.getDirection().getStepZ());
                var packet=new ImpactHolePayload(bullet.config.id,block.getBlockPos(),block.getDirection(),block.getLocation(),dir.normalize(),bullet.getId());
                PacketDistributor.sendToPlayersNear(level,null,hit.getLocation().x,hit.getLocation().y,hit.getLocation().z,256,packet);
                return true;
            }
            return false;
        } finally {DEPTH.set(previousDepth);if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static void update(Consumer<Object> callback,EntityBulletBaseMK4 bullet){
        if(bullet.level().isClientSide()){callback.accept(bullet);return;}
        var previous=CURRENT.get();CURRENT.set(new Context(bullet,null));
        int previousDepth=DEPTH.get();
        try{callback.accept(bullet);}finally{DEPTH.set(previousDepth);if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static void enter(){if(CURRENT.get()!=null)DEPTH.set(DEPTH.get()+1);}
    public static void exit(){if(CURRENT.get()!=null)DEPTH.set(Math.max(0,DEPTH.get()-1));}
    public static Blast bind(ExplosionVNT explosion,float base){
        var context=CURRENT.get();
        if(context==null || suppressed() || DEPTH.get()!=context.depth || context.bullet.config==null
                || context.bullet.level()!=explosion.world || context.bullet.getThrower()!=explosion.exploder)return null;
        context.exploded=true;return new Blast(context,base,explosion);
    }
    static float total(float area,float base,float multiplier,boolean direct){return area*BLAST+(direct?base*CONTACT*multiplier:0);}
    public static float damage(Blast blast,Entity target,float original){
        if(blast==null)return original;
        boolean direct=blast.context.impact instanceof EntityHitResult hit && hit.getEntity()==target && !blast.contactPaid;
        float multiplier=1;
        if(direct){
            blast.contactPaid=true;
            if(target instanceof LivingEntity living)multiplier=HeadDamage.contactDamage(1,blast.context.bullet,living,blast.context.impact);
        }
        float base=blast.base*(target==blast.explosion.exploder?.5F:1);
        return total(original,base,multiplier,direct);
    }
    public static boolean attack(LivingEntity target,net.minecraft.world.damagesource.DamageSource source,float damage,
            boolean first,boolean second,double knockback,float threshold,float piercing){
        boolean previous=SUPPRESS.get();SUPPRESS.set(true);
        try{return EntityDamageUtil.attackEntityFromNT(target,source,damage,first,second,knockback,threshold,piercing);}
        finally{SUPPRESS.set(previous);}
    }
    public static float extra(LivingEntity target,float damage){
        var context=CURRENT.get();
        if(suppressed() || context==null || DEPTH.get()!=context.depth || !context.exploded
                || !(context.impact instanceof EntityHitResult hit) || hit.getEntity()!=target)return damage;
        return HeadDamage.contactDamage(damage,context.bullet,target,context.impact);
    }
    public static void finish(Blast blast){
        if(blast==null || blast.contactPaid || !(blast.context.impact instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target) || !target.isAlive())return;
        // Видимая часть крупной модели может находиться за пределами ванильной коробки взрыва.
        blast.contactPaid=true;var bullet=blast.context.bullet;
        float damage=HeadDamage.contactDamage(blast.base*CONTACT,bullet,target,hit);
        attack(target,BulletConfig.getDamage(bullet,bullet.getThrower(),bullet.config.dmgClass),damage,true,true,0,
                bullet.config.armorThresholdNegation,bullet.config.armorPiercingPercent);
    }
}
