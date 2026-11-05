package acup.gunfeel;

import java.util.*;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.*;

// Port copy (notes/ports/de-1.21.1.md, «Копии»): the root's HeadSmoke without frame(), the mob review's photo of the
// head zones on 26.2's GPU mask (not ported); the damage checks on the server stay as they are. HeadLabelGeometry's
// two calls are here (the head label editor is not ported), with its cube (ModelDebugRender.CUBE).
/** Проверяет настоящую стандартную обработку NTM, а не копию формулы урона. */
final class HeadSmoke {
    private HeadSmoke() {}
    // the root's check of the head and limb bindings. The root's photo of
    // the baby models (into its notes/head-labels/research) is left out; -PdumpProfiles=<dir> writes every model's parts
    // instead, as HeadProfiles.bind reads them (the pose's boxes turned back by its yaw: the profiles' frame)
    static void bindings(LivingEntity target,java.util.function.BiConsumer<Boolean,String> check) {
        String type=ModelHeads.key(target);
        var boxes=ModelHits.worldBoxes(target);var result=ModelHits.headResolution(target);
        dump(target,type,check);
        check.accept(result.source().equals("dataset") && result.missing()==0,"голова: ручная привязка без потерь, частей="+boxes.size()+", зон="+result.zones().size()+", missing="+result.missing()+", "+result.reason());
        // as the root (X456): every kind LimbPolicy gives limbs, so a kind without a limb label fails
        if(LimbPolicy.eligible(target.getType())) {
            var limbs=ModelHits.limbResolution(target);
            boolean ok=limbs.source().equals("dataset") && limbs.missing()==0;
            check.accept(ok,"конечности: ручная привязка без потерь, зон="+limbs.zones().size()+", missing="+limbs.missing()+", "+limbs.reason()
                    +(ok?"":", части="+boxes.stream().map(ModelBox::part).toList()));
        }
    }
    private record Pose(List<ModelBox> boxes,float yaw) {}
    // ModelHits keeps the captured pose private: read by reflection, as the stand only
    @SuppressWarnings("unchecked")
    private static Pose pose(LivingEntity target) {
        try {
            var f=ModelHits.class.getDeclaredField("POSES");f.setAccessible(true);
            Object at;
            synchronized(ModelHits.class) {
                var levels=(Map<Object,Map<Integer,Object>>)f.get(null);var poses=levels.get(target.level());
                at=poses==null?null:poses.get(target.getId());
            }
            if(at==null) return null;
            var boxes=at.getClass().getDeclaredMethod("boxes");boxes.setAccessible(true);
            var yaw=at.getClass().getDeclaredMethod("yaw");yaw.setAccessible(true);
            return new Pose(List.copyOf((List<ModelBox>)boxes.invoke(at)),(float)yaw.invoke(at));
        } catch(ReflectiveOperationException|RuntimeException e) { return null; }
    }
    // once per type, at the first check: the mob still stands as spawned, not mid-step
    private static final Set<String> DUMPED=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static void dump(LivingEntity target,String type,java.util.function.BiConsumer<Boolean,String> check) {
        // one per stand case: a case's suffixes (minecraft:goat#nolefthorn, MobVariants) draw other parts of one type
        String key=MobSmoke.current().isEmpty()?type:MobSmoke.current();
        String dir=System.getProperty("ntmgunfeel.dumpProfiles","");if(dir.isBlank() || !DUMPED.add(key)) return;
        var pose=pose(target);if(pose==null) { check.accept(false,"снимок частей: нет позы");return; }
        double angle=Math.toRadians(pose.yaw()),cos=Math.cos(angle),sin=Math.sin(angle);
        var parts=new ArrayList<Map<String,Object>>();
        for(var b:pose.boxes()) {
            var c=ModelContours.point(b,.5,.5,.5);
            parts.add(Map.of("name",b.part(),"size",HeadProfiles.size(b),"shape",Long.toHexString(HeadProfiles.shape(b)),
                    "center",new double[]{cos*c.x+sin*c.z,c.y,-sin*c.x+cos*c.z},
                    "origin",new double[]{cos*b.x()+sin*b.z(),b.y(),-sin*b.x()+cos*b.z()},
                    "u",new double[]{cos*b.ux()+sin*b.uz(),b.uy(),-sin*b.ux()+cos*b.uz()},
                    "v",new double[]{cos*b.vx()+sin*b.vz(),b.vy(),-sin*b.vx()+cos*b.vz()},
                    "w",new double[]{cos*b.wx()+sin*b.wz(),b.wy(),-sin*b.wx()+cos*b.wz()}));
        }
        try {
            var file=java.nio.file.Path.of(dir).resolve(key.replace(':','_').replace('#','_')+".json");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file,new com.google.gson.Gson().toJson(Map.of("type",type,"case",key,"yaw",pose.yaw(),"parts",parts)));
        } catch(Exception ex) { check.accept(false,"снимок частей: "+ex); }
    }
    static void run(ServerLevel level,LivingEntity target,java.util.function.BiConsumer<Boolean,String> check) {
        var zones=ModelHits.headZones(target);var boxes=ModelHits.worldBoxes(target);
        String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        var profile=HeadProfiles.get(type);
        check.accept(profile!=null,"голова: найден ручной профиль "+type);
        Vec3 head=null,body=null,limb=null;
        var surface=new ArrayList<MobRayOracle.Triangle>();
        for(var box:boxes) {
            float[] data=triangles(box);
            for(int at=0;at<data.length;at+=9) surface.add(new MobRayOracle.Triangle(point(box,data,at),point(box,data,at+3),point(box,data,at+6)));
        }
        for(var ray:MobRayOracle.grid(surface)) if(ray.hit()) {
            var hit=target.getBoundingBox().clip(ray.from(),ray.to());if(hit.isEmpty()) continue;
            if(ModelHits.head(target,hit.get()).head()) { if(head==null) head=hit.get(); }
            else if(ModelHits.limb(target,hit.get())) { if(limb==null) limb=hit.get(); }
            else if(body==null) body=hit.get();
        }
        check.accept(!zones.isEmpty()==!profile.none(),"голова: наличие зон соответствует ручной разметке"
                +(zones.isEmpty()==!profile.none()?", "+ModelHits.headResolution(target).reason()+", части="+boxes.stream().map(b->b.part()+java.util.Arrays.toString(HeadProfiles.size(b))+"@"+ModelContours.point(b,.5,.5,.5).subtract(target.position())+"#"+Long.toUnsignedString(HeadProfiles.shape(b),16)+"/yaw"+target.getYRot()).toList():""));
        if(!profile.none()) check.accept(head!=null,"голова: найдена видимая точка попадания");
        check.accept(body!=null,"голова: найдена точка тела без бонуса");
        if(body==null || !profile.none() && head==null) return;
        var healthAttribute=target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        double max=healthAttribute==null?20:healthAttribute.getBaseValue();
        boolean invulnerable=target.isInvulnerable();float health=target.getHealth();
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
    static float[] triangles(ModelBox box) { return box.mesh()==0?CUBE:ModelMesh.get(box.mesh()).triangles(); }
    private static Vec3 point(ModelBox box,float[] data,int at) { return ModelContours.point(box,data[at],data[at+1],data[at+2]); }
    private static final float[] CUBE=cube();
    private static float[] cube() {
        float[] data=new float[108]; int at=0;
        for(int axis=0;axis<3;axis++) for(int side=0;side<2;side++) for(int corner:new int[]{0,1,3,0,3,2}) {
            float[] p=new float[3]; p[axis]=side; p[(axis+1)%3]=corner&1; p[(axis+2)%3]=(corner>>1)&1;
            for(float value:p) data[at++]=value;
        }
        return data;
    }
}
