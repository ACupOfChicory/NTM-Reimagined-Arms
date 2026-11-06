package acup.gunfeel;

import java.util.*;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.*;

// Port copy (notes/ports/de-1.21.1.md, «Копии»): the root's HeadSmoke without frame(), the mob review's photo of the
// head zones on 26.2's GPU mask (not ported); the damage checks on the server stay as they are. HeadLabelGeometry's
// two calls and pick are here (the head label editor is not ported); its cube is the port's ModelDebugRender.CUBE.
/** Проверяет настоящую стандартную обработку NTM, а не копию формулы урона. */
final class HeadSmoke {
    private static final List<Vec3> SIDES=List.of(new Vec3(0,0,1),new Vec3(0,0,-1),new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,-1,0));
    private HeadSmoke() {}
    private static boolean zonedWhole(LivingEntity target,List<ModelBox> boxes) {
        var whole=new HashSet<String>();
        var resolutions=new ArrayList<>(List.of(ModelHits.headResolution(target)));
        if(LimbPolicy.eligible(target.getType())) resolutions.add(ModelHits.limbResolution(target));
        for(var resolution:resolutions) for(var zone:resolution.zones()) if(zone.planes().isEmpty()) whole.add(zone.box().part()+"#"+zone.box().mesh());
        return !boxes.isEmpty() && boxes.stream().allMatch(box->whole.contains(box.part()+"#"+box.mesh()));
    }
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
        // Крупная детская голова или ноги закрывают тело с одной стороны: смотрим ещё с трёх сторон и сверху.
        for(var side:SIDES) for(var ray:MobRayOracle.grid(surface,side)) if(ray.hit()) {
            var hit=target.getBoundingBox().clip(ray.from(),ray.to());if(hit.isEmpty()) continue;
            if(ModelHits.head(target,hit.get()).head()) { if(head==null) head=hit.get(); }
            else if(ModelHits.limb(target,hit.get())) { if(limb==null) limb=hit.get(); }
            else if(body==null) body=hit.get();
        }
        strip(target,surface,check);
        check.accept(!zones.isEmpty()==!profile.none(),"голова: наличие зон соответствует ручной разметке"
                +(zones.isEmpty()==!profile.none()?", "+ModelHits.headResolution(target).reason()+", части="+boxes.stream().map(b->b.part()+java.util.Arrays.toString(HeadProfiles.size(b))+"@"+ModelContours.point(b,.5,.5,.5).subtract(target.position())+"#"+Long.toUnsignedString(HeadProfiles.shape(b),16)+"/yaw"+target.getYRot()).toList():""));
        // Закрытый шалкер прячет голову под панцирем (ванилла): видимой точки головы нет и в игре.
        boolean closed=head==null && target instanceof net.minecraft.world.entity.monster.Shulker shulker && peek(shulker)==0;
        if(closed && !profile.none()) check.accept(true,"голова: шалкер закрыт, голова под панцирем — видимой точки нет, как в игре");
        else if(!profile.none()) check.accept(head!=null,"голова: найдена видимая точка попадания");
        // У страйдера корпус размечен головой, а ноги — конечностями: точки без бонуса у него нет по разметке.
        if(body==null && zonedWhole(target,boxes)) { check.accept(true,"голова: точки тела без бонуса нет по разметке: каждая часть целиком голова или конечность");return; }
        check.accept(body!=null,"голова: найдена точка тела без бонуса");
        if(body==null || !profile.none() && head==null && !closed) return;
        var healthAttribute=target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        double max=healthAttribute==null?20:healthAttribute.getBaseValue();
        boolean invulnerable=target.isInvulnerable();float health=target.getHealth();
        // Старший (Quackos) у NTM бессмертен при любом флаге: isInvulnerableTo всегда true, setHealth ставит максимум.
        if(target instanceof com.hbm.entity.mob.EntityQuackos) { check.accept(true,"голова: урон не проверяется, NTM делает цель бессмертной");return; }
        if(healthAttribute!=null) healthAttribute.setBaseValue(1000);
        target.setInvulnerable(false);
        try {
            var reason=unhurt(target);if(reason!=null) { unhurt(level,target,head,body,limb,check,reason);return; }
            var config=new BulletConfig().setDamage(10).setHeadshot(0);
            config.damageFalloffByPen=false;
            var bullet=new EntityBulletBaseMK4(level,config,1,0,0,0);
            bullet.damage=10;
            var beam=new com.hbm.entity.projectile.EntityBulletBeamBase(level,config,1);beam.damage=10;
            // Дракон ранится только от игрока или взрыва (EnderDragon.hurt), как в игре: стрелок — игрок.
            if(target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) {
                var shooter=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);bullet.setThrower(shooter);beam.thrower=shooter;
            }
            // Множитель проверяется равенством: попадание базовым уроном в голову (конечность) равно попаданию в тело
            // уроном базы ×1,75 (×0,5). Так сами учитываются броня цели (ванильная, DT/DR NTM) и правила вида (дракон:
            // d/4+min(d,1) по телу), которые идут после множителя. Перед каждым выстрелом цель — как перед первым (ready).
            var ready=ready(target);
            java.util.function.BiFunction<Vec3,Float,Float> shot=(at,amount)->{
                ready.run();target.setHealth(1000);float before=target.getHealth();bullet.damage=amount;
                BulletConfig.LAMBDA_STANDARD_ENTITY_HIT.accept(bullet,new EntityHitResult(target,at));bullet.damage=10;
                return before-target.getHealth();
            };
            // Броня с большим DT (ядерный глифид в полной броне — около 14) съедает пулю 10 целиком: база удваивается, пока
            // попадание в тело половиной базы не ранит.
            float base=10;while(base<1000 && shot.apply(body,base*.5F)<=0) base*=2;
            float bodyDamage=shot.apply(body,base);
            check.accept(bodyDamage>0 && !HeadDamage.last(bullet,target,body).head(),"голова: настоящая NTM-пуля по телу, база "+base+", урон="+bodyDamage);
            if(head!=null) {
                float headDamage=shot.apply(head,base);boolean last=HeadDamage.last(bullet,target,head).head();float same=shot.apply(body,base*1.75F);
                check.accept(bodyDamage>0 && Math.abs(headDamage-same)<.01 && last,"голова: единый множитель ×1,75 при конфиге NTM ×0, база "+base+": голова="+headDamage+", тело(×1,75)="+same+", тело="+bodyDamage);
                config.setHeadshot(2);float doubled=shot.apply(head,base);config.setHeadshot(0);
                check.accept(Math.abs(doubled-headDamage)<.01,"голова: конфиг патрона ×2 не меняет единые ×1,75");
            } else if(!closed) {
                check.accept(profile.none() && !HeadDamage.last(bullet,target,body).head(),"голова: ручное отсутствие запрещает хедшот");
            }
            limbPresence(target,limb,check);
            if(limb!=null) {
                float limbDamage=shot.apply(limb,base);float same=shot.apply(body,base*.5F);
                check.accept(bodyDamage>0 && Math.abs(limbDamage-same)<.01,"конечности: настоящая NTM-пуля ×0,5, база "+base+": конечность="+limbDamage+", тело(×0,5)="+same);
            }
            for(var handler:List.of(BulletConfig.LAMBDA_STANDARD_BEAM_HIT,BulletConfig.LAMBDA_BEAM_HIT)) {
                java.util.function.BiFunction<Vec3,Float,Float> ray=(at,amount)->{
                    ready.run();target.setHealth(1000);float before=target.getHealth();beam.damage=amount;
                    handler.accept(beam,new EntityHitResult(target,at));beam.damage=10;
                    return before-target.getHealth();
                };
                float beamBody=ray.apply(body,base);
                if(head!=null) {
                    float beamHead=ray.apply(head,base);float same=ray.apply(body,base*1.75F);
                    check.accept(beamBody>0 && Math.abs(beamHead-same)<.01,"NTM-луч: голова ×1,75, база "+base+": голова="+beamHead+", тело(×1,75)="+same+", тело="+beamBody);
                }
                if(limb!=null) {
                    float beamLimb=ray.apply(limb,base);float same=ray.apply(body,base*.5F);
                    check.accept(beamBody>0 && Math.abs(beamLimb-same)<.01,"NTM-луч: конечность ×0,5, база "+base+": конечность="+beamLimb+", тело(×0,5)="+same);
                }
            }
            beam.discard();
            bullet.discard();
            if(Boolean.getBoolean("ntmgunfeel.checkExplosives"))ExplosiveSmoke.run(level,target,head,body,limb,check);
            NpcShotSmoke.run(level,target,head,body,limb,check);
        } finally {
            if(healthAttribute!=null) healthAttribute.setBaseValue(max);
            target.setHealth(health);target.setInvulnerable(invulnerable);
        }
    }
    // Точки поверхности модели (та же сетка лучей, первая встреча с настоящими гранями частей позы), которые сервер
    // (ModelHits.head) засчитывает головой, хотя ни одна часть, чью грань луч встретил первой, не размечена головой:
    // спина под черепом в коробке головы и подобное. Таких быть не должно. Размечена — по самой коробке
    // (ModelHits.standParts), не по имени. Совпавшие грани (череп и нос, копия слоя) — все первые. Точки частей
    // головы вне зоны — сведения: плоскости режут их нарочно (шея ламы). Конечности (ModelHits.limb) — тоже сведения.
    private static void strip(LivingEntity target,List<MobRayOracle.Triangle> surface,java.util.function.BiConsumer<Boolean,String> check) {
        // Высотная зона NTM (разметка не привязалась) — не геометрия частей: её и считать нечего.
        if(ModelHits.headResolution(target).zones().isEmpty()) return;
        var parts=ModelHits.standParts(target);boolean limbs=parts.stream().anyMatch(ModelHits.StandPart::limb);
        int bodyHits=0,headHits=0,headOut=0,limbHits=0,limbOut=0;var inHead=new TreeMap<String,Integer>();var heads=new TreeMap<String,Integer>();var inLimb=new TreeMap<String,Integer>();
        for(var side:SIDES) for(var ray:MobRayOracle.grid(surface,side)) if(ray.hit()) {
            double epsilon=1e-5/ray.to().subtract(ray.from()).length(),best=Double.POSITIVE_INFINITY;var first=new ArrayList<ModelHits.StandPart>();
            for(var part:parts) {
                var pick=pick(List.of(part.box()),ray.from(),ray.to());if(pick==null || pick.t()>best+epsilon) continue;
                if(pick.t()<best-epsilon) first.clear();
                first.add(part);best=Math.min(best,pick.t());
            }
            if(first.isEmpty()) continue;
            var point=ray.from().add(ray.to().subtract(ray.from()).scale(best));var part=first.getFirst().box().part();
            boolean head=ModelHits.head(target,point).head();if(head) heads.merge(part,1,Integer::sum);
            if(first.stream().anyMatch(ModelHits.StandPart::head)) { headHits++;if(!head) headOut++;continue; }
            boolean limb=!head && limbs && ModelHits.limb(target,point);
            if(first.stream().anyMatch(ModelHits.StandPart::limb)) { limbHits++;if(!limb && !head) limbOut++; }
            else { bodyHits++;if(limb) inLimb.merge(part,1,Integer::sum); }
            if(head) inHead.merge(part,1,Integer::sum);
        }
        int strayHead=inHead.values().stream().mapToInt(Integer::intValue).sum();
        check.accept(strayHead==0,"голова: точек поверхности не головы в зоне головы="+strayHead+" из "+(bodyHits+limbHits)+inHead
                +", точек частей головы вне зоны="+headOut+" из "+headHits+", головой по частям "+heads);
        if(limbs) check.accept(true,"конечности (сведения): точек тела в зоне конечностей="+inLimb.values().stream().mapToInt(Integer::intValue).sum()
                +" из "+bodyHits+inLimb+", точек частей конечностей вне зоны="+limbOut+" из "+limbHits);
    }
    // Цель, которую пуля и луч NTM в игре не ранят: удар дал бы урон 0, а эндермена увёл бы со стенда, пакет — лопнул
    // бы. Множители проверяются там, где их подставляет мод (MixinHeadDamage: damage для пули, beamDamage для луча),
    // без удара.
    private static String unhurt(LivingEntity target) {
        if(target instanceof net.minecraft.world.entity.monster.EnderMan) return "эндермен снарядом не ранится (EnderMan.hurtServer: телепорт), ванилла";
        if(target instanceof net.minecraft.world.entity.decoration.ArmorStand) return "стойка для брони снарядом не ранится и здоровья не теряет, ванилла";
        if(target instanceof com.hbm.entity.mob.EntityGhost) return "призрак NTM неуязвим (isInvulnerableTo всегда true)";
        if(target instanceof com.hbm.entity.mob.EntityPlasticBag) return "пакет NTM от любого удара лопается (hurtServer: discard)";
        return null;
    }
    private static void unhurt(ServerLevel level,LivingEntity target,Vec3 head,Vec3 body,Vec3 limb,java.util.function.BiConsumer<Boolean,String> check,String reason) {
        String why=" (без удара: "+reason+")";
        var bullet=new EntityBulletBaseMK4(level,new BulletConfig().setDamage(10).setHeadshot(0),1,0,0,0);bullet.damage=10;
        try {
            float bodyDamage=HeadDamage.damage(bullet,target,new EntityHitResult(target,body),10);
            check.accept(bodyDamage==10 && !HeadDamage.last(bullet,target,body).head(),"голова: NTM-пуля по телу ×1, урон="+bodyDamage+why);
            if(head!=null) {
                float headDamage=HeadDamage.damage(bullet,target,new EntityHitResult(target,head),10);
                check.accept(Math.abs(headDamage-17.5F)<.01 && HeadDamage.last(bullet,target,head).head(),"голова: единый множитель ×1,75, голова="+headDamage+why);
                check.accept(Math.abs(HeadDamage.beamDamage(10,target,new EntityHitResult(target,head))-17.5F)<.01,"NTM-луч: голова ×1,75"+why);
            }
            limbPresence(target,limb,check);
            if(limb!=null) {
                float limbDamage=HeadDamage.damage(bullet,target,new EntityHitResult(target,limb),10);
                check.accept(Math.abs(limbDamage-5)<.01,"конечности: NTM-пуля ×0,5, урон="+limbDamage+why);
                check.accept(Math.abs(HeadDamage.beamDamage(10,target,new EntityHitResult(target,limb))-5)<.01,"NTM-луч: конечность ×0,5"+why);
            }
            check.accept(Math.abs(HeadDamage.beamDamage(10,target,new EntityHitResult(target,body))-10)<.01,"NTM-луч: тело ×1"+why);
        } finally { bullet.discard(); }
    }
    // HeadLabelGeometry.pick корня (редактор разметки не перенесён): первая встреча луча с гранями коробок.
    record Pick(int box,double t) {}
    static Pick pick(List<ModelBox> boxes,Vec3 from,Vec3 to) {
        Pick best=null;
        for(int i=0;i<boxes.size();i++) {
            var box=boxes.get(i); var data=triangles(box);
            if(box.clip(from.x,from.y,from.z,to.x,to.y,to.z)<0) continue;
            for(int at=0;at<data.length;at+=9) {
                double t=intersection(from,to,point(box,data,at),point(box,data,at+3),point(box,data,at+6));
                if(t>=0 && (best==null || t<best.t())) best=new Pick(i,t);
            }
        }
        return best;
    }
    private static double intersection(Vec3 from,Vec3 to,Vec3 a,Vec3 b,Vec3 c) {
        var d=to.subtract(from); var u=b.subtract(a); var v=c.subtract(a); var p=d.cross(v);
        double determinant=u.dot(p); if(Math.abs(determinant)<1e-12) return -1;
        var s=from.subtract(a); double x=s.dot(p)/determinant; if(x<0 || x>1) return -1;
        var q=s.cross(u); double y=d.dot(q)/determinant;
        double t=v.dot(q)/determinant;
        return y>=0 && x+y<=1 && t>=0 && t<=1?t:-1;
    }
    static float[] triangles(ModelBox box) { return box.mesh()==0?ModelDebugRender.CUBE:ModelMesh.get(box.mesh()).triangles(); }
    private static Vec3 point(ModelBox box,float[] data,int at) { return ModelContours.point(box,data[at],data[at+1],data[at+2]); }
    // Точка конечности ожидается у вида, который не мелкий (LimbPolicy) и у которого конечности не отменены вручную (limbs none).
    private static void limbPresence(LivingEntity target,Vec3 limb,java.util.function.BiConsumer<Boolean,String> check) {
        if(!Boolean.getBoolean("ntmgunfeel.checkLimbs")) return;
        if(!LimbPolicy.eligible(target.getType())) check.accept(limb==null,"конечности: мелкий вид без конечностей");
        else if("manual none".equals(ModelHits.limbResolution(target).reason())) check.accept(limb==null,"конечности: ручное отсутствие (limbs none), точки конечности нет");
        else check.accept(limb!=null,"конечности: найдена точка проверенной области");
    }
    // Перед каждым выстрелом цель — как перед первым: глифид теряет пластину брони от попадания (onDamageDealt, случайно),
    // а от числа пластин зависит его DT; UFO после попадания 5 тиков урона не берёт (hurtCooldown).
    @SuppressWarnings("unchecked")
    private static Runnable ready(LivingEntity target) {
        try {
            if(target instanceof com.hbm.entity.mob.glyphid.EntityGlyphid glyphid) {
                var field=com.hbm.entity.mob.glyphid.EntityGlyphid.class.getDeclaredField("DW_ARMOR");field.setAccessible(true);
                var key=(net.minecraft.network.syncher.EntityDataAccessor<Byte>)field.get(null);byte armor=glyphid.getEntityData().get(key);
                return ()->glyphid.getEntityData().set(key,armor);
            }
            if(target instanceof com.hbm.entity.mob.EntityUFO ufo) return ()->ufo.hurtCooldown=0;
        } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
        return ()->{};
    }
    private static int peek(net.minecraft.world.entity.monster.Shulker shulker) {
        try {
            var field=net.minecraft.world.entity.monster.Shulker.class.getDeclaredField("DATA_PEEK_ID");field.setAccessible(true);
            @SuppressWarnings("unchecked") var key=(net.minecraft.network.syncher.EntityDataAccessor<Byte>)field.get(null);
            return shulker.getEntityData().get(key);
        } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
