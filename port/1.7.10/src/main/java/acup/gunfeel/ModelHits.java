package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelHits.java at 0762086; changes marked "port:"
// port: the logic is line for line 26.2's; the types are 1.7.10's: World for Level, EntityPlayerMP for ServerPlayer,
// EntityLivingBase for LivingEntity, AxisAlignedBB for AABB. Inside, points are the port's immutable Vec3 (the shared
// ModelBox, HeadZone and ModelContours use it); clipMc and ModelAABB are the entry points in 1.7.10's own Vec3.
// An entity's position is its feet, (posX, posY - yOffset, posZ): the same point on both sides (the local player's
// posY is at its eyes on the client), and the origin RendererLivingEntity draws the model from. 1.7.10 has no Pose:
// pose() packs sneaking and sleeping, the two that change a 1.7.10 model's shape without changing its size.

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

import acup.gunfeel.phys.Vec3;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityDragonPart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

public final class ModelHits {
    public static final int RANGE = 96, MAX_UPDATES = 16, INTERVAL = 2, MAX_SYNC_AGE = 5;
    private record PoseAt(UUID entity, Vec3 position, float yaw, float bodyYaw, int pose, float width, float height, long tick, List<ModelBox> boxes) {}
    private static final Map<World, Map<Integer, PoseAt>> POSES = new WeakHashMap<>();
    private static final Map<EntityPlayerMP, long[]> LIMITS = new WeakHashMap<>();
    private record ViewAt(PoseAt pose,Vec3 rendered,Vec3 velocity,World level) {}
    private static final Map<EntityPlayerMP,Map<Integer,ViewAt>> VIEWS=new WeakHashMap<>();
    private record Contact(Vec3 point,Vec3 origin,double time) {}
    public static final class ShotContext {
        private final EntityPlayerMP player;
        private final double travel;
        private final Map<UUID,List<Contact>> contacts=new HashMap<>();
        private ShotContext(EntityPlayerMP player,double travel){this.player=player;this.travel=travel;}
    }
    private static final ThreadLocal<ShotContext> SHOOTER=new ThreadLocal<>();

    public static ShotContext beginShot(Entity owner) {return beginProjectile(owner,0);}
    public static ShotContext beginProjectile(Entity owner,double travel) {
        var previous=SHOOTER.get();
        if(owner instanceof EntityPlayerMP player)SHOOTER.set(new ShotContext(player,travel));else SHOOTER.remove();
        return previous;
    }
    public static void endShot(ShotContext previous){if(previous==null)SHOOTER.remove();else SHOOTER.set(previous);}

    // port: 26.2's Entity.position(), getId(), getUUID(), level(), getGameTime(), isAlive()
    public static Vec3 position(Entity e) { return new Vec3(e.posX,e.posY-e.yOffset,e.posZ); }
    private static long time(World level) { return level.getTotalWorldTime(); }
    private static int id(Entity e) { return e.getEntityId(); }
    private static UUID uuid(Entity e) { return e.getUniqueID(); }
    private static Entity entity(World level,int id) { return level.getEntityByID(id); }
    // port: 26.2's Pose: crouching and sleeping
    static int pose(Entity e) {
        return (e.isSneaking()?1:0)|(e instanceof EntityPlayer player && player.isPlayerSleeping()?2:0);
    }

    private static ViewAt view(Entity target) {
        var context=SHOOTER.get();EntityPlayerMP player=context==null?null:context.player;
        var views=player==null?null:VIEWS.get(player);
        var view=views==null?null:views.get(id(target));
        return view!=null && player.worldObj==target.worldObj && view.level()==target.worldObj && compatible(target,view.pose())
                && time(target.worldObj)-view.pose().tick()<=MAX_SYNC_AGE?view:null;
    }
    private static PoseAt pose(Entity target,Map<Integer,PoseAt> poses) {
        var view=view(target);
        return view==null?(poses==null?null:poses.get(id(target))):view.pose();
    }
    private static Vec3 origin(Entity target) {
        var view=view(target);
        // Отсчитываем движение от времени снимка, а не от порядка обхода сущностей.
        return view==null?position(target):view.rendered().add(view.velocity().scale(time(target.worldObj)-view.pose().tick()));
    }
    private static Vec3 impactOrigin(Entity target,Vec3 point){
        var contact=contact(target,point);
        return contact==null?origin(target):contact.origin();
    }
    public static double contactTime(Entity target,Vec3 point){
        var contact=contact(target,point);return contact==null?Double.NaN:contact.time();
    }
    private static Contact contact(Entity target,Vec3 point){
        var parent=ModelPartEntities.parent(target);if(parent!=null)target=parent;
        var context=SHOOTER.get();var contacts=context==null?null:context.contacts.get(uuid(target));
        if(contacts!=null)for(int i=contacts.size()-1;i>=0;i--)if(contacts.get(i).point().distanceToSqr(point)<1e-10)return contacts.get(i);
        return null;
    }
    private static double angle(EntityLivingBase target,PoseAt pose) {
        return view(target)!=null?0:turnAngle(target.worldObj.isRemote,target.renderYawOffset,pose.yaw(),pose.bodyYaw());
    }

    private ModelHits() {}

    // port: no compat Feature switch in the port; the server's flag as in 26.2
    public static boolean enabled() {
        return (!MobBenchMetrics.ENABLED || MobBenchMetrics.mode!=0) && ReaimTimes.flag("modelHitboxes", true);
    }

    public static synchronized void receive(EntityPlayerMP observer, ModelPosePayload p) {
        ModelHitLog.count("server", "packet_received");
        if (!enabled()) {
            ModelHitLog.event("server", "disabled", observer, () -> "packet_entity=" + p.entity());
            return;
        }
        World level=observer.worldObj;
        long tick = time(level);
        long[] rate = LIMITS.computeIfAbsent(observer, k -> new long[] {tick / INTERVAL, 0});
        if (rate[0] != tick / INTERVAL) { rate[0] = tick / INTERVAL; rate[1] = 0; }
        if (++rate[1] > MAX_UPDATES * 2) {
            ModelHitLog.event("server", "rate_rejected", observer, () -> "packets=" + rate[1] + " tick=" + tick);
            return;
        }
        Entity target = entity(level,p.entity());
        if (!(target instanceof EntityLivingBase living) || !target.isEntityAlive() || !uuid(target).equals(p.uuid())
                || observer.getDistanceSqToEntity(target) > RANGE * RANGE || !Double.isFinite(p.x()) || !Double.isFinite(p.y())
                || !Double.isFinite(p.z()) || !Float.isFinite(p.yaw()) || position(target).distanceToSqr(new Vec3(p.x(), p.y(), p.z())) > 16
                || p.boxes().isEmpty() || p.boxes().size() > ModelPosePayload.MAX_BOXES) {
            ModelHitLog.event("server", "target_rejected", target, () -> "observer=" + id(observer)
                    + " packet_entity=" + p.entity() + " packet_uuid=" + p.uuid()
                    + " target_uuid=" + (target == null ? null : uuid(target))
                    + " alive=" + (target != null && target.isEntityAlive())
                    + " distance2=" + (target == null ? -1 : observer.getDistanceSqToEntity(target))
                    + " packet_pos=" + p.x() + "," + p.y() + "," + p.z()
                    + " target_pos=" + (target == null ? null : position(target)) + " yaw=" + p.yaw() + " cubes=" + p.boxes().size());
            return;
        }
        double radius = radius(target);
        if (p.boxes().stream().anyMatch(b -> !b.valid(radius))) {
            ModelHitLog.event("server", "geometry_rejected", target, () -> "radius=" + radius + " invalid_cube="
                    + p.boxes().stream().filter(b -> !b.valid(radius)).findFirst().orElse(null));
            return;
        }
        var filtered=PlaneHitPolicy.filter(living,AvatarHitPolicy.filter(living,p.boxes()));
        if(filtered.isEmpty()) return;
        put(level, living, p.yaw(), filtered);
        var saved=POSES.get(level).get(id(target));
        var views=VIEWS.computeIfAbsent(observer,k->new HashMap<>());
        var previous=views.get(id(target));var rendered=new Vec3(p.x(),p.y(),p.z());
        var velocity=new Vec3(target.motionX,target.motionY,target.motionZ);
        if(previous!=null && compatible(target,previous.pose())) {
            long elapsed=tick-previous.pose().tick();
            velocity=elapsed>0 && elapsed<=MAX_SYNC_AGE?rendered.subtract(previous.rendered()).scale(1.0/elapsed):previous.velocity();
        }
        views.put(id(target),new ViewAt(saved,rendered,velocity,level));
        // port: no MobSmoke stand in the port
        ModelHitLog.event("server", "accepted", living, () -> "observer=" + id(observer) + " cubes=" + p.boxes().size()
                + " tick=" + tick + " rendered=" + p.x() + "," + p.y() + "," + p.z()
                + " authority=" + position(living) + " first_cube=" + p.boxes().getFirst());
    }

    public static double radius(Entity target) {
        // Also bounds candidate lookup: no accepted cube extends beyond this distance from the entity origin.
        return Math.min(256, Math.max(16, Math.max(target.width, target.height) * 4));
    }

    public static synchronized void put(World level, EntityLivingBase target, float yaw, List<ModelBox> boxes) {
        putAt(level, target, yaw, boxes, time(level));
    }

    // Явная отметка захвата также позволяет воспроизвести пропуск кадров в серверном стенде.
    static synchronized void putAt(World level, EntityLivingBase target, float yaw, List<ModelBox> boxes, long tick) {
        Map<Integer, PoseAt> poses = POSES.computeIfAbsent(level, k -> new HashMap<>());
        poses.put(id(target), new PoseAt(uuid(target), position(target), yaw, target.renderYawOffset, pose(target),
                target.width, target.height, tick, List.copyOf(boxes)));
    }

    // Пропуск рендера не меняет модель сущности. Следим за корнем раз в игровой тик,
    // не запуская рендерер или анимацию, и удаляем только действительно несовместимые позы.
    public static synchronized void tick(World level) {
        Map<Integer, PoseAt> poses = POSES.get(level);
        if (poses == null) return;
        if (!enabled()) { clear(level); return; }
        var it = poses.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            PoseAt p = entry.getValue();
            Entity target = entity(level,entry.getKey());
            if (!compatible(target, p)) {
                it.remove();
            } else if (!position(target).equals(p.position())) {
                entry.setValue(new PoseAt(p.entity(), position(target), p.yaw(), p.bodyYaw(), p.pose(),
                        p.width(), p.height(), p.tick(), p.boxes()));
            }
        }
        for(var observer:VIEWS.entrySet()) {
            observer.getValue().entrySet().removeIf(entry->{
                var v=entry.getValue();
                if(v.level()!=level)return false;
                var target=entity(level,entry.getKey());
                if(observer.getKey().worldObj!=level || !compatible(target,v.pose())
                        || time(level)-v.pose().tick()>MAX_SYNC_AGE)return true;
                var p=v.pose();
                entry.setValue(new ViewAt(new PoseAt(p.entity(),position(target),p.yaw(),p.bodyYaw(),p.pose(),
                        p.width(),p.height(),p.tick(),p.boxes()),v.rendered(),v.velocity(),level));
                return false;
            });
        }
    }

    public static synchronized void clear(World level) {
        POSES.remove(level);
        VIEWS.values().forEach(views->views.values().removeIf(v->v.level()==level));
    }

    // port: getBoundingBox().inflate(r).intersects(area) -> boundingBox.expand(r, r, r).intersectsWith(area); the dragon's
    // parts are its dragonPartArray
    public static synchronized List<Entity> candidates(World level, Entity except, List<Entity> original, AxisAlignedBB area,
            java.util.function.Predicate<Entity> filter) {
        Map<Integer, PoseAt> poses = POSES.get(level);
        if (poses == null || !enabled()) return original;
        List<Entity> out = new ArrayList<>(original);
        for (var entry : poses.entrySet()) {
            Entity target = entity(level,entry.getKey());
            if(!compatible(target,entry.getValue()) || target==except) continue;
            double r=radius(target);
            if(!target.boundingBox.expand(r,r,r).intersectsWith(area)) continue;
            if(target instanceof EntityDragon dragon)
                for(var part:dragon.dragonPartArray) if(part!=except && filter.test(part) && !out.contains(part)) out.add(part);
            for(var part:ModelPartEntities.parts(target))
                if(part!=null && part!=except && filter.test(part) && !out.contains(part)) out.add(part);
            if(filter.test(target) && !out.contains(target)) out.add(target);
        }
        return out;
    }

    // No data, teleport, changed size/posture: keep the original hitbox. An available model missed by the
    // ray is a real miss, never replaced with a hit on the enclosing entity AABB.
    private static boolean usable(Entity target, PoseAt p) {
        return enabled() && compatible(target, p);
    }

    private static boolean compatible(Entity target, PoseAt p) {
        return target != null && target.isEntityAlive() && p != null && p.entity().equals(uuid(target))
                && position(target).distanceToSqr(p.position()) <= 16 && p.pose() == pose(target)
                && p.width() == target.width && p.height() == target.height;
    }

    // F3+B получает ровно ту же позу и те же проверки актуальности, что пересечение луча.
    public static synchronized boolean hasPose(Entity target) {
        if(!(target instanceof EntityLivingBase)) return false;
        var poses=POSES.get(target.worldObj);
        return poses!=null && usable(target,poses.get(id(target)));
    }
    public static synchronized ModelHeads.Hit head(Entity target,Vec3 point) {
        if(!enabled()) return new ModelHeads.Hit(false,false,"disabled","точные хитбоксы отключены",0);
        var parent=ModelPartEntities.parent(target);Entity root=parent==null?target:parent;
        if(!(root instanceof EntityLivingBase living) || point==null) return new ModelHeads.Hit(true,false,"unknown","нет живой цели или точки",0);
        String type=ModelHeads.key(root);
        if(ModelHeads.noHead(type)) return new ModelHeads.Hit(true,false,"dataset","размечено без головы",1);
        var poses=POSES.get(root.worldObj);var pose=pose(root,poses);
        if(!usable(root,pose)) return heightHead(living,point);
        var resolved=ModelHeads.resolve(type,pose.boxes(),pose.yaw());
        if(resolved.source().equals("height")) return heightHead(living,point);
        double angle=angle(living,pose);
        boolean head=HeadZone.contains(resolved.zones(),unturn(point.subtract(impactOrigin(root,point)),angle));
        return new ModelHeads.Hit(true,head,resolved.source(),resolved.reason(),resolved.confidence());
    }
    // port: NTM 1.7.10's own rule (BulletConfig LAMBDA_STANDARD_ENTITY_HIT): head = height - getEyeHeight()
    private static ModelHeads.Hit heightHead(EntityLivingBase living,Vec3 point) {
        double margin=living.height-living.getEyeHeight();
        return new ModelHeads.Hit(true,living.isEntityAlive() && point.y>impactOrigin(living,point).y+living.height-margin*2,
                "height","высотная зона NTM с прежним допуском",0F);
    }
    public static synchronized boolean limb(Entity target,Vec3 point) {
        if(!enabled() || point==null) return false;
        var parent=ModelPartEntities.parent(target);Entity root=parent==null?target:parent;
        if(!(root instanceof EntityLivingBase living) || !LimbPolicy.eligible(living)) return false;
        var poses=POSES.get(root.worldObj);var pose=pose(root,poses);
        if(!usable(root,pose)) return false;
        var zones=ModelHeads.limbs(ModelHeads.key(root),pose.boxes(),pose.yaw()).zones();
        double angle=angle(living,pose);
        return HeadZone.contains(zones,unturn(point.subtract(impactOrigin(root,point)),angle));
    }
    public static synchronized List<HeadZone> headZones(Entity target) {
        var poses=POSES.get(target.worldObj);var pose=poses==null?null:poses.get(id(target));
        if(!usable(target,pose) || !(target instanceof EntityLivingBase living)) return List.of();
        String type=ModelHeads.key(target);
        var resolved=ModelHeads.resolve(type,pose.boxes(),pose.yaw());
        double angle=turnAngle(target.worldObj.isRemote,living.renderYawOffset,pose.yaw(),pose.bodyYaw());
        var out=new ArrayList<HeadZone>();
        double cos=Math.cos(-angle),sin=Math.sin(-angle);
        for(var zone:resolved.zones()) {
            var box=zone.box();var origin=unturn(new Vec3(box.x(),box.y(),box.z()),cos,sin).add(position(target));
            var u=unturn(new Vec3(box.ux(),box.uy(),box.uz()),cos,sin);var v=unturn(new Vec3(box.vx(),box.vy(),box.vz()),cos,sin);var w=unturn(new Vec3(box.wx(),box.wy(),box.wz()),cos,sin);
            var world=new ModelBox(box.part(),origin.x,origin.y,origin.z,u.x,u.y,u.z,v.x,v.y,v.z,w.x,w.y,w.z,box.mesh());
            out.add(HeadZone.local(world,zone.planes().stream().map(p->HeadZone.localPlane(box,p)).toList()));
        }
        return List.copyOf(out);
    }
    public static synchronized ModelHeads.Resolution headResolution(Entity target) {
        var poses=POSES.get(target.worldObj);var pose=poses==null?null:poses.get(id(target));
        if(!usable(target,pose)) return new ModelHeads.Resolution(List.of(),"unknown","нет совместимой позы модели",0,0);
        String type=ModelHeads.key(target);
        return ModelHeads.resolve(type,pose.boxes(),pose.yaw());
    }
    public static synchronized List<Vec3[]> outlines(Entity target) {
        return ModelContours.edges(worldBoxes(target));
    }

    public static synchronized List<ModelBox> worldBoxes(Entity target) {
        Map<Integer, PoseAt> poses = POSES.get(target.worldObj);
        PoseAt p = poses == null ? null : poses.get(id(target));
        if (!usable(target, p) || !(target instanceof EntityLivingBase living)) return List.of();
        double angle = turnAngle(target.worldObj.isRemote, living.renderYawOffset, p.yaw(), p.bodyYaw());
        List<ModelBox> out = new ArrayList<>(p.boxes().size());
        double cos=Math.cos(-angle),sin=Math.sin(-angle);
        for (ModelBox box : p.boxes()) {
            if(!target.worldObj.isRemote && !ModelWool.visible(target,box)) continue;
            var origin=unturn(new Vec3(box.x(),box.y(),box.z()),cos,sin).add(position(target));
            var u=unturn(new Vec3(box.ux(),box.uy(),box.uz()),cos,sin);
            var v=unturn(new Vec3(box.vx(),box.vy(),box.vz()),cos,sin);
            var w=unturn(new Vec3(box.wx(),box.wy(),box.wz()),cos,sin);
            out.add(new ModelBox(box.part(),origin.x,origin.y,origin.z,u.x,u.y,u.z,v.x,v.y,v.z,w.x,w.y,w.z,box.mesh()));
        }
        return out;
    }

    // port: 26.2's AABB.contains(Vec3) and AABB.clip(Vec3, Vec3) on the fallback box, through 1.7.10's own
    private static Optional<Vec3> fallback(AxisAlignedBB box,Vec3 from,Vec3 to) {
        if(from.x>=box.minX && from.x<=box.maxX && from.y>=box.minY && from.y<=box.maxY && from.z>=box.minZ && from.z<=box.maxZ)
            return Optional.of(from);
        MovingObjectPosition hit=box.calculateIntercept(mc(from),mc(to));
        return hit==null || hit.hitVec==null?Optional.empty():Optional.of(of(hit.hitVec));
    }
    static net.minecraft.util.Vec3 mc(Vec3 v) { return net.minecraft.util.Vec3.createVectorHelper(v.x,v.y,v.z); }
    static Vec3 of(net.minecraft.util.Vec3 v) { return new Vec3(v.xCoord,v.yCoord,v.zCoord); }

    // The 1.7.10 form of clip for the bullets: the first point of the segment on the target's posed model, or null
    // for a miss. Without a usable pose it is the fallback box's own intercept (or from when from is inside it).
    public static net.minecraft.util.Vec3 clipMc(Entity target,net.minecraft.util.Vec3 from,net.minecraft.util.Vec3 to,AxisAlignedBB fallback) {
        var hit=clip(target,of(from),of(to),fallback);
        return hit.isPresent()?mc(hit.get()):null;
    }

    public static synchronized Optional<Vec3> clip(Entity target, Vec3 from, Vec3 to, AxisAlignedBB fallback) {
        var dragonPart=target instanceof EntityDragonPart part && part.entityDragonObj instanceof EntityDragon?part:null;
        var parent=ModelPartEntities.parent(target);
        Entity root=dragonPart!=null?(Entity)dragonPart.entityDragonObj:parent!=null?parent:target;
        Map<Integer, PoseAt> poses = POSES.get(target.worldObj);
        PoseAt p = root==null ? null : pose(root,poses);
        boolean fresh = usable(root, p);
        if (!fresh || !(root instanceof EntityLivingBase living)) {
            if (!from.equals(to)) ModelHitLog.event(target.worldObj.isRemote ? "client" : "server", "clip_fallback", target,
                    () -> "enabled=" + enabled() + " has_pose=" + (p != null) + " age=" + (p == null ? -1 : time(target.worldObj) - p.tick())
                            + " uuid_matches=" + (p != null && p.entity().equals(uuid(target)))
                            + " stored_pose=" + (p == null ? null : p.pose()) + " current_pose=" + pose(target)
                            + " stored_size=" + (p == null ? "none" : p.width() + "," + p.height())
                            + " current_size=" + target.width + "," + target.height
                            + " displacement2=" + (p == null ? -1 : position(target).distanceToSqr(p.position()))
                            + " from=" + from + " to=" + to);
            return fallback(fallback,from,to);
        }
        Vec3 segmentFrom=from,segmentTo=to;
        if(parent!=null && dragonPart==null) {
            // Не расширяем область части на 0.3 вместе с AABB снаряда.
            // Часть сохраняет свой урон, но её невидимая коробка сама по себе
            // больше не является поверхностью попадания.
            var shift=origin(root).subtract(position(root));
            AxisAlignedBB region=target.boundingBox.getOffsetBoundingBox(shift.x,shift.y,shift.z);
            double sx=region.maxX-region.minX,sy=region.maxY-region.minY,sz=region.maxZ-region.minZ;
            if(sx<=0 || sy<=0 || sz<=0) return Optional.empty();
            var carrier=new ModelBox("part_region",region.minX,region.minY,region.minZ,
                    sx,0,0,0,sy,0,0,0,sz);
            double[] span=carrier.carrierSpan(from,to);
            if(span==null) return Optional.empty();
            segmentFrom=from.lerp(to,span[0]); segmentTo=from.lerp(to,span[1]);
        }
        double angle = angle(living,p);
        Vec3 rootOrigin=origin(root);
        var context=SHOOTER.get();var view=view(root);
        double duration=context==null || context.travel<=0 || from.equals(to)?0:Math.min(1,from.distanceTo(to)/context.travel);
        Vec3 motion=view==null?Vec3.ZERO:view.velocity().scale(duration);
        double length=from.distanceTo(to);
        double first=length<=1e-12?0:from.distanceTo(segmentFrom)/length,last=length<=1e-12?0:from.distanceTo(segmentTo)/length;
        Vec3 a = unturn(segmentFrom.subtract(rootOrigin.add(motion.scale(first))), angle),
                b = unturn(segmentTo.subtract(rootOrigin.add(motion.scale(last))), angle);
        double nearest = Double.POSITIVE_INFINITY;
        String part = "";
        for (ModelBox box : p.boxes()) {
            if(!target.worldObj.isRemote && !ModelWool.visible(root,box)) continue;
            if(dragonPart!=null) {
                var center=unturn(ModelContours.point(box,.5,.5,.5),-angle).add(position(root));
                if(!DragonModelParts.belongs((EntityDragon)root,dragonPart,box,center)) continue;
            }
            double t = box.clip(a.x, a.y, a.z, b.x, b.y, b.z);
            if (t >= 0 && t < nearest) { nearest = t; part = box.part(); }
        }
        if (!from.equals(to)) {
            final double at = nearest;
            final String name = part;
            ModelHitLog.event(target.worldObj.isRemote ? "client" : "server", "clip_model", target,
                    () -> "result=" + (Double.isFinite(at) ? "hit" : "miss") + " part=" + name + " t=" + at
                            + " cubes=" + p.boxes().size() + " age=" + (time(target.worldObj) - p.tick())
                            + " shooter=" + (SHOOTER.get()==null?"none":id(SHOOTER.get().player))
                            + " origin=" + rootOrigin + " authority=" + position(root)
                            + " from=" + from + " to=" + to);
        }
        // port: TestLog.at(level, ...) -> the side's TestLog.c / TestLog.s
        if (!from.equals(to) && (target.worldObj.isRemote ? TestLog.client : TestLog.server)) {
            Object[] kv={"target", id(target), "part", part, "result", Double.isFinite(nearest) ? "hit" : "miss", "age", time(target.worldObj) - p.tick()};
            if(target.worldObj.isRemote) TestLog.c(-1, "model_hit", kv); else TestLog.s(-1, "model_hit", kv);
        }
        if(!Double.isFinite(nearest))return Optional.empty();
        Vec3 point=segmentFrom.lerp(segmentTo,nearest);
        if(context!=null){
            double time=first+(last-first)*nearest;
            var contacts=context.contacts.computeIfAbsent(uuid(root),k->new ArrayList<>());
            if(contacts.size()<32)contacts.add(new Contact(point,rootOrigin.add(motion.scale(time)),duration*time));
        }
        return Optional.of(point);
    }

    // Клиент сохраняет исходную интерполяцию и добавляет лишь движение корня после захвата.
    // Сервер доворачивает от фактического угла полученной по сети геометрии.
    // port: MathHelper.wrapAngleTo180_float is 26.2's Mth.wrapDegrees(float)
    static double turnAngle(boolean client, float currentYaw, float capturedYaw, float anchorYaw) {
        return Math.toRadians(MathHelper.wrapAngleTo180_float(currentYaw - (client ? anchorYaw : capturedYaw)));
    }

    private static Vec3 unturn(Vec3 p, double angle) {
        return unturn(p, Math.cos(angle), Math.sin(angle));
    }
    // Для многих частей одной позы cos и sin считаются один раз.
    private static Vec3 unturn(Vec3 p, double cos, double sin) {
        return new Vec3(cos * p.x + sin * p.z, p.y, -sin * p.x + cos * p.z);
    }
}
