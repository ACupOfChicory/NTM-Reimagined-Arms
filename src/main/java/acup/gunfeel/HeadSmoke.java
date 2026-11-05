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
    private static final List<Vec3> SIDES=List.of(new Vec3(0,0,1),new Vec3(0,0,-1),new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,-1,0));
    private HeadSmoke() {}
    private static boolean zonedWhole(LivingEntity target,List<ModelBox> boxes) {
        var whole=new HashSet<String>();
        var resolutions=new ArrayList<>(List.of(ModelHits.headResolution(target)));
        if(LimbPolicy.eligible(target.getType())) resolutions.add(ModelHits.limbResolution(target));
        for(var resolution:resolutions) for(var zone:resolution.zones()) if(zone.planes().isEmpty()) whole.add(zone.box().part()+"#"+zone.box().mesh());
        return !boxes.isEmpty() && boxes.stream().allMatch(box->whole.contains(box.part()+"#"+box.mesh()));
    }
    static void bindings(LivingEntity target,java.util.function.BiConsumer<Boolean,String> check) {
        String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        var boxes=ModelHits.worldBoxes(target);var result=ModelHits.headResolution(target);
        if(target.isBaby()) try {
            var parts=boxes.stream().map(b->Map.of("part",b.part(),"size",HeadProfiles.size(b),
                    "shape",Long.toUnsignedString(HeadProfiles.shape(b),16),"frame",b.numbers())).toList();
            java.nio.file.Files.writeString(java.nio.file.Path.of("../notes/head-labels/research/baby-binding-"+type.replace(':','_')+".json"),
                    new com.google.gson.Gson().toJson(parts));
        } catch(Exception ex) { check.accept(false,"снимок детской модели: "+ex); }
        check.accept(result.source().equals("dataset") && result.missing()==0,"голова: ручная привязка без потерь, частей="+boxes.size()+", зон="+result.zones().size()+", missing="+result.missing());
        if(LimbPolicy.eligible(target.getType())) {
            var limbs=ModelHits.limbResolution(target);
            boolean ok=limbs.source().equals("dataset") && limbs.missing()==0;
            check.accept(ok,"конечности: ручная привязка без потерь, зон="+limbs.zones().size()+", missing="+limbs.missing()+", "+limbs.reason()
                    +(ok?"":", части="+boxes.stream().map(ModelBox::part).toList()));
        }
        if(Boolean.getBoolean("ntmgunfeel.dumpParts")) try {
            var parts=ModelHits.profileParts(target).stream().map(p->{var r=p.ref();return Map.of("name",r.name(),"size",r.size(),
                    "shape",Long.toUnsignedString(r.shape(),16),"center",new double[]{r.center().x,r.center().y,r.center().z},
                    "vertices",p.vertices().stream().map(v->new double[]{v.x,v.y,v.z}).toList());}).toList();
            java.nio.file.Files.writeString(java.nio.file.Path.of("../notes/head-labels/research/parts-"+MobSmoke.current().replace(':','_').replace('#','-')+".json"),
                    new com.google.gson.Gson().toJson(parts));
        } catch(Exception ex) { check.accept(false,"снимок частей: "+ex); }
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
        String id=MobSmoke.current();var profile=HeadProfiles.get(ModelHeads.key(target));boolean expected=profile==null || !profile.none();
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
        Vec3 head=null,body=null,limb=null;
        var surface=new ArrayList<MobRayOracle.Triangle>();
        for(var box:boxes) {
            float[] data=HeadLabelGeometry.triangles(box);
            for(int at=0;at<data.length;at+=9) surface.add(new MobRayOracle.Triangle(HeadLabelGeometry.point(box,data,at),HeadLabelGeometry.point(box,data,at+3),HeadLabelGeometry.point(box,data,at+6)));
        }
        // Крупная детская голова или ноги закрывают тело с одной стороны: смотрим ещё с трёх сторон и сверху.
        for(var side:SIDES) for(var ray:MobRayOracle.grid(surface,side)) if(ray.hit()) {
            var hit=target.getBoundingBox().clip(ray.from(),ray.to());if(hit.isEmpty()) continue;
            if(ModelHits.head(target,hit.get()).head()) { if(head==null) head=hit.get(); }
            else if(ModelHits.limb(target,hit.get())) { if(limb==null) limb=hit.get(); }
            else if(body==null) body=hit.get();
        }
        check.accept(!zones.isEmpty()==!profile.none(),"голова: наличие зон соответствует ручной разметке"
                +(zones.isEmpty()==!profile.none()?", "+ModelHits.headResolution(target).reason()+", части="+boxes.stream().map(b->b.part()+java.util.Arrays.toString(HeadProfiles.size(b))+"@"+ModelContours.point(b,.5,.5,.5).subtract(target.position())+"#"+Long.toUnsignedString(HeadProfiles.shape(b),16)+"/yaw"+target.getYRot()).toList():""));
        if(!profile.none()) check.accept(head!=null,"голова: найдена видимая точка попадания");
        // У страйдера корпус размечен головой, а ноги — конечностями: точки без бонуса у него нет по разметке.
        if(body==null && zonedWhole(target,boxes)) { check.accept(true,"голова: точки тела без бонуса нет по разметке: каждая часть целиком голова или конечность");return; }
        check.accept(body!=null,"голова: найдена точка тела без бонуса");
        if(body==null || !profile.none() && head==null) return;
        var healthAttribute=target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        double max=healthAttribute==null?20:healthAttribute.getBaseValue();
        boolean invulnerable=target.isInvulnerable();float health=target.getHealth();
        // Старший (Quackos) у NTM бессмертен при любом флаге: isInvulnerableTo всегда true, setHealth ставит максимум.
        if(target instanceof com.hbm.entity.mob.EntityQuackos) { check.accept(true,"голова: урон не проверяется, NTM делает цель бессмертной");return; }
        if(healthAttribute!=null) healthAttribute.setBaseValue(1000);
        target.setInvulnerable(false);
        try {
            var config=new BulletConfig().setDamage(10).setHeadshot(0);
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
                check.accept(Math.abs(headDamage-bodyDamage*1.75F)<.01 && HeadDamage.last(bullet,target,head).head(),"голова: единый множитель ×1,75 при конфиге NTM ×0, тело="+bodyDamage+", голова="+headDamage);
                config.setHeadshot(2);target.setHealth(1000);before=target.getHealth();
                BulletConfig.LAMBDA_STANDARD_ENTITY_HIT.accept(bullet,new EntityHitResult(target,head));
                check.accept(Math.abs(before-target.getHealth()-headDamage)<.01,"голова: конфиг патрона ×2 не меняет единые ×1,75");
            } else {
                check.accept(profile.none() && !HeadDamage.last(bullet,target,body).head(),"голова: ручное отсутствие запрещает хедшот");
            }
            // Мелкий вид (LimbPolicy) штрафа за конечности не получает: точки конечности у него быть не должно.
            if(Boolean.getBoolean("ntmgunfeel.checkLimbs")) {
                if(LimbPolicy.eligible(target.getType())) check.accept(limb!=null,"конечности: найдена точка проверенной области");
                else check.accept(limb==null,"конечности: мелкий вид без конечностей");
            }
            if(limb!=null) {
                target.setHealth(1000);before=target.getHealth();
                BulletConfig.LAMBDA_STANDARD_ENTITY_HIT.accept(bullet,new EntityHitResult(target,limb));
                check.accept(Math.abs(before-target.getHealth()-bodyDamage*.5F)<.01,"конечности: настоящая NTM-пуля ×0,5, урон="+(before-target.getHealth()));
            }
            var beam=new com.hbm.entity.projectile.EntityBulletBeamBase(level,config,1);beam.damage=10;
            for(var handler:List.of(BulletConfig.LAMBDA_STANDARD_BEAM_HIT,BulletConfig.LAMBDA_BEAM_HIT)) {
                target.setHealth(1000);before=target.getHealth();handler.accept(beam,new EntityHitResult(target,body));float beamBody=before-target.getHealth();
                if(head!=null) {
                    target.setHealth(1000);before=target.getHealth();handler.accept(beam,new EntityHitResult(target,head));
                    check.accept(beamBody>0 && Math.abs(before-target.getHealth()-beamBody*1.75F)<.01,"NTM-луч: голова ×1,75, тело="+beamBody+", голова="+(before-target.getHealth()));
                }
                if(limb!=null) {
                    target.setHealth(1000);before=target.getHealth();handler.accept(beam,new EntityHitResult(target,limb));
                    check.accept(beamBody>0 && Math.abs(before-target.getHealth()-beamBody*.5F)<.01,"NTM-луч: конечность ×0,5, урон="+(before-target.getHealth()));
                }
            }
            beam.discard();
            bullet.discard();
            if(Boolean.getBoolean("ntmgunfeel.checkExplosives"))ExplosiveSmoke.run(level,target,head,body,limb,check);
        } finally {
            if(healthAttribute!=null) healthAttribute.setBaseValue(max);
            target.setHealth(health);target.setInvulnerable(invulnerable);
        }
    }
}
