package acup.gunfeel;

import java.util.*;
import java.util.function.BiConsumer;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.*;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

/** Полный обработчик onImpact NTM и реальные изменения здоровья в отдельном тестовом мире. */
final class ExplosiveSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("ntmgunfeel.checkExplosives");
    private static final Set<UUID> DONE=new HashSet<>();
    private static final Map<Integer,Integer> HOLES=new HashMap<>();
    private static volatile long waiting;
    private static boolean reported;
    private ExplosiveSmoke() {}
    private static final class Probe extends EntityBulletBaseMK4 implements CollisionProbe {
        Probe(ServerLevel level,BulletConfig config){super(level,config,1,0,0,0);damage=10;tickCount=20;setDeltaMovement(0,0,1);}
        void impact(HitResult hit){onImpact(hit);}
    }
    private static float measure(ServerLevel level,LivingEntity target,BulletConfig config,Vec3 point,boolean original) throws Exception {
        return measure(level,target,config,new EntityHitResult(target,point),original);
    }
    @SuppressWarnings("unchecked")
    private static float measure(ServerLevel level,LivingEntity target,BulletConfig config,HitResult hit,boolean original) throws Exception {
        target.setHealth(1000);target.invulnerableTime=0;
        var bullet=new Probe(level,config);bullet.setPos(hit.getLocation().subtract(0,0,.1));
        var field=ExplosiveDamage.class.getDeclaredField("SUPPRESS");field.setAccessible(true);
        var guard=(ThreadLocal<Boolean>)field.get(null);boolean previous=guard.get();
        if(original)guard.set(true);
        try{bullet.impact(hit);return 1000-target.getHealth();}
        finally{guard.set(previous);bullet.discard();target.setDeltaMovement(Vec3.ZERO);}
    }
    static void run(ServerLevel level,LivingEntity target,Vec3 head,Vec3 body,Vec3 limb,BiConsumer<Boolean,String> check) {
        if(!ENABLED || !DONE.add(target.getUUID()))return;
        waiting=System.nanoTime();
        check.accept(head!=null && limb!=null,"взрывные: доступны проверенные голова и конечность");
        if(head==null || limb==null)return;
        var armor=target.getAttribute(Attributes.ARMOR);double armorBefore=armor==null?0:armor.getBaseValue();
        var observer=level.getServer().getPlayerList().getPlayers().getFirst();
        var observerMode=observer.gameMode.getGameModeForPlayer();
        // Наблюдателя не должно унести серией контрольных взрывов за дальность отслеживания цели.
        observer.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        if(armor!=null)armor.setBaseValue(0);
        var configs=new BulletConfig[]{XFactory10ga.g10_explosive,XFactory50.bmg50_he,
                XFactory40mm.g40_he,XFactory40mm.g40_heat,XFactory40mm.g40_demo,
                XFactoryRocket.rocket_rpzb[0],XFactoryRocket.rocket_rpzb[1],XFactoryRocket.rocket_rpzb[2]};
        try {
            for(var config:configs) {
                String name=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(config.ammoItem.get()).toString();
                float extra=name.endsWith("_heat")?30:0;
                Vec3[] points={body,head,limb};float[] multipliers={1,1.75F,.5F};
                for(int i=0;i<3;i++) {
                    float old=measure(level,target,config,points[i],true);
                    float actual=measure(level,target,config,points[i],false);
                    float expected=(old-extra)*.4F+(6+extra)*multipliers[i];
                    check.accept(old>0 && Math.abs(actual-expected)<.15F,
                            "взрывные "+name+" "+(i==0?"тело":i==1?"голова":"конечность")+": исходный="+old+", новый="+actual+", ожидается="+expected);
                }
                check.accept(ExplosiveDamage.current()==null,"взрывные: контекст очищен после "+name);
            }
            for(Vec3 point:new Vec3[]{body,head,limb}) {
                var hit=new BlockHitResult(point,Direction.UP,BlockPos.containing(point),false);
                float old=measure(level,target,XFactory10ga.g10_explosive,hit,true);
                float actual=measure(level,target,XFactory10ga.g10_explosive,hit,false);
                check.accept(old>0 && Math.abs(actual-old*.4F)<.15F,
                        "непрямой взрыв: только 40% радиального урона без зон, исходный="+old+", новый="+actual);
            }
            target.setHealth(1000);target.invulnerableTime=0;
            var plain=new ExplosionVNT(level,body.x,body.y,body.z,3,null);
            plain.setEntityProcessor(new EntityProcessorCrossSmooth(.5,10));
            plain.setPlayerProcessor(new com.hbm.explosion.vanillant.standard.PlayerProcessorStandard());plain.explode();
            check.accept(Math.abs(1000-target.getHealth()-10)<.15,"обычный взрыв мира не ослаблен: "+(1000-target.getHealth()));
            target.setDeltaMovement(Vec3.ZERO);
            // Стойкая стена: дырка должна сохраниться после взрыва. Пакеты проходят через настоящий сервер/клиент.
            var pos=target.blockPosition().offset(12,1,0);
            for(int x=-2;x<=2;x++)for(int y=-1;y<=2;y++)level.setBlock(pos.offset(x,y,0),Blocks.BEDROCK.defaultBlockState(),3);
            int offset=0;
            for(var config:new BulletConfig[]{XFactory40mm.g40_he,XFactoryRocket.rocket_rpzb[0]}) {
                var block=pos.offset(offset++,0,0);var point=Vec3.atCenterOf(block).add(0,0,-.501);
                var bullet=new Probe(level,config);bullet.setPos(point.add(0,0,-.1));
                bullet.impact(new BlockHitResult(point,Direction.NORTH,block,false));
                check.accept(bullet.isRemoved(),"медленный снаряд взорвался на стене: "+config.id);
            }
            waiting=System.nanoTime();
        } catch(Throwable e){check.accept(false,"взрывные: исключение "+e);waiting=System.nanoTime();}
        finally{if(armor!=null)armor.setBaseValue(armorBefore);target.setHealth(1000);target.setDeltaMovement(Vec3.ZERO);observer.setGameMode(observerMode);}
    }
    static void hole(BulletConfig config,float size) {
        if(!ENABLED || !ExplosiveAmmo.heavy(config))return;
        HOLES.merge(config.id,1,Integer::sum);
        ClientSmoke.check(size>=Config.scorchSize*1.2F-.001 && size<=Config.scorchSize*1.8F+.001,
                "серверный след ракеты/40мм создан, размер ×1,5 с обычным разбросом: "+size+", config="+config.id);
    }
    static boolean finish() {
        if(!ENABLED)return true;
        if(!reported && HOLES.size()<2 && waiting!=0 && System.nanoTime()-waiting<5_000_000_000L)return false;
        if(!reported){reported=true;ClientSmoke.check(HOLES.size()==2 && HOLES.values().stream().allMatch(n->n==1),"созданы по одному серверному следу 40мм и ракеты без дублей: "+HOLES);}
        return true;
    }
}
