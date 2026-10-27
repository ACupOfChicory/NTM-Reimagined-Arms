package acup.gunfeel;

import java.util.*;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.*;

/** Проверяет настоящую стандартную обработку NTM, а не копию формулы урона. */
final class HeadSmoke {
    private static final Set<Integer> PHOTOS=new HashSet<>();
    private static final Map<Integer,Integer> PHOTO_FRAMES=new HashMap<>();
    private HeadSmoke() {}
    static void bindings(LivingEntity target,java.util.function.BiConsumer<Boolean,String> check) {
        String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        var boxes=ModelHits.worldBoxes(target);var result=ModelHits.headResolution(target);
        check.accept(result.source().equals("dataset") && result.missing()==0,"голова: ручная привязка без потерь, частей="+boxes.size()+", зон="+result.zones().size()+", missing="+result.missing());
    }
    static void frame() {
        if(!Boolean.getBoolean("ntmgunfeel.checkHeadBindings") || !MobSmoke.reviewReady() || !(MobSmoke.target() instanceof LivingEntity target) || PHOTOS.contains(target.getId())) return;
        int frames=PHOTO_FRAMES.merge(target.getId(),1,Integer::sum);
        if(frames==1) {
            var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();int id=target.getId();
            server.execute(()->{
                var entity=server.overworld().getEntity(id);if(entity==null) return;
                var boxes=ModelHits.worldBoxes(entity);if(boxes.isEmpty()) return;
                double x0=Double.POSITIVE_INFINITY,y0=x0,z0=x0,x1=-x0,y1=x1,z1=x1;
                for(var b:boxes) for(int i=0;i<8;i++) {
                    var p=ModelContours.point(b,i&1,i>>1&1,i>>2&1);
                    x0=Math.min(x0,p.x);y0=Math.min(y0,p.y);z0=Math.min(z0,p.z);x1=Math.max(x1,p.x);y1=Math.max(y1,p.y);z1=Math.max(z1,p.z);
                }
                double distance=Math.min(85,Math.max(4,Math.max(x1-x0,Math.max(y1-y0,z1-z0))*2.5));
                var player=server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(server.overworld(),(x0+x1)/2-distance*.5,(y0+y1)/2-player.getEyeHeight(),(z0+z1)/2-distance*.8660254,Set.of(),-30,0,false);
                player.setDeltaMovement(Vec3.ZERO);
            });
        }
        if(frames<15) return;
        PHOTOS.add(target.getId());
        bindings(target,(ok,text)->ClientSmoke.check(ok,MobSmoke.current()+": клиент: "+text));
        String id=MobSmoke.current();boolean expected=!HeadProfiles.get(id).none();
        net.minecraft.client.Screenshot.takeScreenshot(net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget(),image->{
            try(image) {
                long cyan=Arrays.stream(image.getPixels()).filter(pixel->(pixel>>16&255)<90 && (pixel>>8&255)>230 && (pixel&255)>230).count();
                ClientSmoke.check(expected?cyan>5:cyan==0,id+": GPU зоны головы, пикселей="+cyan+", ожидается="+expected);
                image.writeToFile(java.nio.file.Path.of("../notes/head-labels/research/native-head-"+id.replace(':','_')+".png"));
            } catch(Exception e) { ClientSmoke.check(false,id+": снимок зон головы: "+e); }
        });
    }
    static void run(ServerLevel level,LivingEntity target,java.util.function.BiConsumer<Boolean,String> check) {
        var zones=ModelHits.headZones(target);var boxes=ModelHits.worldBoxes(target);
        String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        var profile=HeadProfiles.get(type);
        check.accept(profile!=null,"голова: найден ручной профиль "+type);
        Vec3 head=null,body=null;
        var surface=new ArrayList<MobRayOracle.Triangle>();
        for(var box:boxes) {
            float[] data=HeadLabelGeometry.triangles(box);
            for(int at=0;at<data.length;at+=9) surface.add(new MobRayOracle.Triangle(HeadLabelGeometry.point(box,data,at),HeadLabelGeometry.point(box,data,at+3),HeadLabelGeometry.point(box,data,at+6)));
        }
        for(var ray:MobRayOracle.grid(surface)) if(ray.hit()) {
            var hit=target.getBoundingBox().clip(ray.from(),ray.to());if(hit.isEmpty()) continue;
            if(ModelHits.head(target,hit.get()).head()) { if(head==null) head=hit.get(); } else if(body==null) body=hit.get();
        }
        check.accept(!zones.isEmpty()==!profile.none(),"голова: наличие зон соответствует ручной разметке");
        if(!profile.none()) check.accept(head!=null,"голова: найдена видимая точка попадания");
        check.accept(body!=null,"голова: найдена точка тела без бонуса");
        if(body==null || !profile.none() && head==null) return;
        var healthAttribute=target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        double max=healthAttribute==null?20:healthAttribute.getBaseValue();
        boolean invulnerable=target.isInvulnerable();float health=target.getHealth();
        if(healthAttribute!=null) healthAttribute.setBaseValue(1000);
        target.setInvulnerable(false);
        try {
            var config=new BulletConfig().setDamage(10).setHeadshot(2);
            config.damageFalloffByPen=false;
            var bullet=new EntityBulletBaseMK4(level,config,1,0,0,0);
            bullet.damage=10;
            target.setHealth(1000);float before=target.getHealth();
            BulletConfig.LAMBDA_STANDARD_ENTITY_HIT.accept(bullet,new EntityHitResult(target,body));
            float bodyDamage=before-target.getHealth();
            check.accept(bodyDamage>0 && !HeadDamage.last(bullet,target,body).head(),"голова: настоящая NTM-пуля по телу, урон="+bodyDamage);
            if(head!=null) {
                target.setHealth(1000);before=target.getHealth();
                BulletConfig.LAMBDA_STANDARD_ENTITY_HIT.accept(bullet,new EntityHitResult(target,head));
                float headDamage=before-target.getHealth();
                check.accept(Math.abs(headDamage-bodyDamage*2)<.01 && HeadDamage.last(bullet,target,head).head(),"голова: множитель применён один раз, тело="+bodyDamage+", голова="+headDamage);
            } else {
                check.accept(profile.none() && !HeadDamage.last(bullet,target,body).head(),"голова: ручное отсутствие запрещает хедшот");
            }
            bullet.discard();
        } finally {
            if(healthAttribute!=null) healthAttribute.setBaseValue(max);
            target.setHealth(health);target.setInvulnerable(invulnerable);
        }
    }
}
