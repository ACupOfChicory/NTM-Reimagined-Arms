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

/** The game's typed entry points and live reads for the bullet context. */
public final class ExplosiveDamage extends ExplosiveDamageBase {
    public interface Processor {
        void gunfeel$bind(ExplosionVNT explosion);
        Blast gunfeel$blast();
        /** its damage class is the explosive one (not fire, plasma, electric) */
        boolean gunfeel$explosive();
    }
    private ExplosiveDamage() {}
    public static Entity bulletNow(){return (Entity)ExplosiveDamageBase.bulletNow();}
    public static Entity struckNow(){return (Entity)ExplosiveDamageBase.struckNow();}
    public static boolean impact(BiConsumer<Object,Object> callback,EntityBulletBaseMK4 bullet,HitResult hit,boolean announce){
        return ExplosiveDamageBase.impact(callback,bullet,hit,announce);
    }
    public static void update(Consumer<Object> callback,EntityBulletBaseMK4 bullet){ExplosiveDamageBase.update(callback,bullet);}
    public static Blast bind(ExplosionVNT explosion,float base){return ExplosiveDamageBase.bind(explosion,base);}
    public static EntityBulletBaseMK4 bulletOf(Blast blast){return (EntityBulletBaseMK4)ExplosiveDamageBase.bulletOf(blast);}
    public static boolean struck(Blast blast,Entity target){return ExplosiveDamageBase.struck(blast,target);}
    public static boolean rocketDirect(Blast blast,Entity target){return ExplosiveDamageBase.rocketDirect(blast,target);}
    public static float damage(Blast blast,Entity target,float original){return ExplosiveDamageBase.damage(blast,target,original);}
    public static boolean attack(LivingEntity target,net.minecraft.world.damagesource.DamageSource source,float damage,
            boolean first,boolean second,double knockback,float threshold,float piercing){
        return ExplosiveDamageBase.attack(target,source,damage,first,second,knockback,threshold,piercing);
    }

    /** The version's live access to bullets, impacts and explosions. */
    static final class Reads implements acup.gunfeel.host.ExplosiveShots {
        @Override public boolean client(Object bullet){return ((EntityBulletBaseMK4)bullet).level().isClientSide();}
        @Override public Object config(Object bullet){return ((EntityBulletBaseMK4)bullet).config;}
        @Override public Object thrower(Object bullet){return ((EntityBulletBaseMK4)bullet).getThrower();}
        @Override public Object level(Object bullet){return ((EntityBulletBaseMK4)bullet).level();}
        @Override public Object world(Object explosion){return ((ExplosionVNT)explosion).world;}
        @Override public Object exploder(Object explosion){return ((ExplosionVNT)explosion).exploder;}
        @Override public Object struck(Object impact){return impact instanceof EntityHitResult hit?hit.getEntity():null;}
        @Override public boolean heavy(Object config){return ExplosiveAmmo.heavy((BulletConfig)config);}
        @Override public boolean rocket(Object config){return ExplosiveAmmo.rocket((BulletConfig)config);}
        @Override public boolean removed(Object bullet){return ((EntityBulletBaseMK4)bullet).isRemoved();}
        @Override public boolean hole(Object raw,Object hit){
            var bullet=(EntityBulletBaseMK4)raw;
            if(hit instanceof BlockHitResult block
                    && !acup.gunfeel.water.WaterTick.surfaceHit(bullet,(HitResult)hit)
                    && block.getType()==HitResult.Type.BLOCK && bullet.level() instanceof ServerLevel level) {
                Vec3 dir=bullet.getDeltaMovement();
                if(dir.lengthSqr()<1e-12)dir=new Vec3(-block.getDirection().getStepX(),-block.getDirection().getStepY(),-block.getDirection().getStepZ());
                var packet=new ImpactHolePayload(bullet.config.id,block.getBlockPos(),block.getDirection(),block.getLocation(),dir.normalize(),bullet.getId());
                PacketDistributor.sendToPlayersNear(level,null,((HitResult)hit).getLocation().x,((HitResult)hit).getLocation().y,((HitResult)hit).getLocation().z,256,packet);
                return true;
            }
            return false;
        }
        @Override public boolean living(Object target){return target instanceof LivingEntity;}
        @Override public boolean alive(Object target){return ((LivingEntity)target).isAlive();}
        @Override public boolean attack(Object target,Object source,float damage,boolean first,boolean second,double knockback,float threshold,float piercing){
            return EntityDamageUtil.attackEntityFromNT((LivingEntity)target,(net.minecraft.world.damagesource.DamageSource)source,
                    damage,first,second,knockback,threshold,piercing);
        }
        @Override public void contact(Object raw,Object target,float damage){
            var bullet=(EntityBulletBaseMK4)raw;
            ExplosiveDamage.attack((LivingEntity)target,BulletConfig.getDamage(bullet,bullet.getThrower(),bullet.config.dmgClass),damage,true,true,0,
                    bullet.config.armorThresholdNegation,bullet.config.armorPiercingPercent);
        }
    }
}
