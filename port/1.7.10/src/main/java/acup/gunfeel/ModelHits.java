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
    private record PoseAt(UUID entity, Vec3 position, float yaw, float bodyYaw, float yRot, int age, int pose, float width, float height, long tick, List<ModelBox> boxes, List<Object> gear) {}
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
        return view(target)!=null?0:turnAngle(target,pose);
    }

    private ModelHits() {}

    // port: no compat Feature switch in the port; the server's flag as in 26.2
    public static boolean enabled() {
        return (!MobBenchMetrics.ENABLED || MobBenchMetrics.mode!=0)
                && acup.gunfeel.compat.Feature.MODEL_HITBOXES.on() && ReaimTimes.flag("modelHitboxes", true);
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
        // port: no UUID check (26.2: uuid(target).equals(p.uuid())). 1.7.10 does not send a mob's UUID to the clients,
        // a client's one is its own random one; the entity id (not reused while the server runs) and the position say
        // which mob it is
        if (!(target instanceof EntityLivingBase living) || !target.isEntityAlive()
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
        poses.put(id(target), new PoseAt(uuid(target), position(target), yaw, target.renderYawOffset, target.rotationYaw, target.ticksExisted, pose(target),
                target.width, target.height, tick, List.copyOf(boxes), gear(target)));
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
                entry.setValue(new PoseAt(p.entity(), position(target), p.yaw(), p.bodyYaw(), p.yRot(), p.age(), p.pose(),
                        p.width(), p.height(), p.tick(), p.boxes(), p.gear()));
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
                entry.setValue(new ViewAt(new PoseAt(p.entity(),position(target),p.yaw(),p.bodyYaw(),p.yRot(),p.age(),p.pose(),
                        p.width(),p.height(),p.tick(),p.boxes(),p.gear()),v.rendered(),v.velocity(),level));
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
                && p.width() == target.width && p.height() == target.height && p.gear().equals(gear(target));
    }

    // what it wore when the pose was caught: the armor passes are in the boxes, so a pose outlives no change of them.
    // port: 1.7.10's getEquipmentInSlot 4 is the helmet, down to 1 the boots; a skull's kind is the stack's damage
    private static List<Object> gear(Entity target) {
        if(!(target instanceof EntityLivingBase living)) return List.of();
        var gear=new java.util.ArrayList<Object>(8);
        for(int slot=4;slot>=1;slot--) {
            var stack=living.getEquipmentInSlot(slot);
            gear.add(stack==null?"":stack.getItem());
            gear.add(stack==null || !stack.getHasSubtypes()?0:stack.getItemDamage()); // wear on armor is damage too, and changes nothing drawn
        }
        return gear;
    }

    // What the target wears, on its hitbox (WornGear): on unless the server turns modelHitboxesWorn off. port: 1.7.10's
    // RenderBiped and RenderPlayer draw an ItemArmor for its slot as a render pass (MixinRendererLivingEntity takes it),
    // and on the head a skull 1.0625 times larger or a block (a pumpkin).
    static WornGear.Worn worn(Entity target) {
        if(!(target instanceof EntityLivingBase living) || !ReaimTimes.flag("modelHitboxesWorn",true)) return WornGear.Worn.NOTHING;
        return new WornGear.Worn(wornHead(living),armor(living,1),armor(living,2),armor(living,3));
    }
    // armorType: 0 the helmet, 1 the chestplate, 2 the leggings, 3 the boots (ItemArmor's)
    private static boolean armor(EntityLivingBase living,int armorType) {
        var stack=living.getEquipmentInSlot(4-armorType);
        if(stack==null) return false;
        var item=stack.getItem();
        if(item instanceof net.minecraft.item.ItemArmor armor) return armor.armorType==armorType; // vanilla's, NTM's suits (ArmorFSB)
        // another mod's armor that is not an ItemArmor names its slot through Forge's hook; not a block or a skull (Forge
        // takes a pumpkin and a skull as valid on the head too)
        return !(item instanceof net.minecraft.item.ItemBlock) && item!=net.minecraft.init.Items.skull
                && item.isValidArmor(stack,armorType,living);
    }
    private static WornGear.Head wornHead(EntityLivingBase living) {
        var stack=living.getEquipmentInSlot(4);
        if(stack==null) return WornGear.Head.NONE;
        if(armor(living,0)) return WornGear.Head.ARMOR;
        if(stack.getItem()==net.minecraft.init.Items.skull) return WornGear.Head.OLD_SKULL;
        return stack.getItem() instanceof net.minecraft.item.ItemBlock?WornGear.Head.BLOCK:WornGear.Head.NONE;
    }
    private static List<ModelBox> wornBoxes(Entity target,PoseAt p) {
        return WornGear.boxes(p.boxes(),worn(target));
    }
    // zones ModelHeads found on the bare model, grown with what is worn
    private static List<HeadZone> wornZones(Entity target,PoseAt p,List<HeadZone> zones) {
        var worn=worn(target);
        return worn.any()?WornGear.zones(zones,worn,WornGear.humanoid(p.boxes())):zones;
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
        boolean head=HeadZone.contains(wornZones(root,pose,resolved.zones()),unturn(point.subtract(impactOrigin(root,point)),angle));
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
        var zones=wornZones(root,pose,ModelHeads.limbs(ModelHeads.key(root),pose.boxes(),pose.yaw()).zones());
        double angle=angle(living,pose);
        return HeadZone.contains(zones,unturn(point.subtract(impactOrigin(root,point)),angle));
    }
    public static synchronized List<HeadZone> headZones(Entity target) {
        var poses=POSES.get(target.worldObj);var pose=poses==null?null:poses.get(id(target));
        if(!usable(target,pose) || !(target instanceof EntityLivingBase living)) return List.of();
        String type=ModelHeads.key(target);
        var resolved=ModelHeads.resolve(type,pose.boxes(),pose.yaw());
        double angle=turnAngle(living,pose);
        var out=new ArrayList<HeadZone>();
        for(var zone:wornZones(target,pose,resolved.zones())) out.add(world(zone,angle,position(target)));
        return List.copyOf(out);
    }
    // a zone to the world: its box, planes and cubes (pieces) turned by angle and moved to position
    static HeadZone world(HeadZone zone,double angle,Vec3 position) {
        double cos=Math.cos(-angle),sin=Math.sin(-angle);var box=zone.box();
        return HeadZone.local(world(box,cos,sin,position),zone.planes().stream().map(p->HeadZone.localPlane(box,p)).toList(),zone.pieces().stream().map(b->world(b,cos,sin,position)).toList());
    }
    private static ModelBox world(ModelBox box,double cos,double sin,Vec3 position) {
        var origin=unturn(new Vec3(box.x(),box.y(),box.z()),cos,sin).add(position);
        var u=unturn(new Vec3(box.ux(),box.uy(),box.uz()),cos,sin);var v=unturn(new Vec3(box.vx(),box.vy(),box.vz()),cos,sin);var w=unturn(new Vec3(box.wx(),box.wy(),box.wz()),cos,sin);
        return new ModelBox(box.part(),origin.x,origin.y,origin.z,u.x,u.y,u.z,v.x,v.y,v.z,w.x,w.y,w.z,box.mesh());
    }
    // the stand's (RenderMobsScenario): the pose kept for the entity now (null: none), the tick it came in, its boxes about
    // the origin, and how far its model has turned since, by the mob's rule or by the body's alone
    static synchronized Object standPose(Entity target) {
        var poses=POSES.get(target.worldObj);return poses==null?null:poses.get(id(target));
    }
    static long standTick(Object pose) { return ((PoseAt)pose).tick(); }
    static float standYaw(Object pose) { return ((PoseAt)pose).yaw(); }
    static float standYRot(Object pose) { return ((PoseAt)pose).yRot(); }
    static List<ModelBox> standBoxes(Object pose) { return ((PoseAt)pose).boxes(); }
    static double standAngle(EntityLivingBase target, Object pose, boolean bodyRule) {
        var p=(PoseAt)pose;
        return bodyRule?turnAngle(target.worldObj.isRemote,target.renderYawOffset,p.yaw(),p.bodyYaw()):turnAngle(target,p);
    }
    // the pose as taken (MobsScenario's dump): its boxes about the origin and its yaw; null without one
    static synchronized List<ModelBox> poseBoxes(Entity target) {
        var poses=POSES.get(target.worldObj);var pose=poses==null?null:poses.get(id(target));
        return usable(target,pose)?pose.boxes():null;
    }
    static synchronized float poseYaw(Entity target) {
        var poses=POSES.get(target.worldObj);var pose=poses==null?null:poses.get(id(target));
        return pose==null?0F:pose.yaw();
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
        double angle = turnAngle(living, p);
        List<ModelBox> out = new ArrayList<>(p.boxes().size());
        double cos=Math.cos(-angle),sin=Math.sin(-angle);
        for (ModelBox box : wornBoxes(target,p)) {
            if(!target.worldObj.isRemote && !ModelWool.visible(target,box)) continue;
            out.add(world(box,cos,sin,position(target)));
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
        for (ModelBox box : wornBoxes(root,p)) {
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

    // How a mob's renderer turns it. Vanilla's turn a mob by its body, and so do most of NTM's (glyphids, Mask Man, the
    // crabs); five do not. The hunter chopper is drawn by its yRot, the usual way round; its body follows yRot only while
    // it moves, so a chopper that aims from where it hangs turns its model and not its body. The plastic bag is drawn by
    // yRot the other way round; its body follows yRot only in water (Squid.aiStep). NTM's worm draws each piece by its
    // yRot the other way round, and the body does not follow (the head sets yRot from its motion every tick). The FBI
    // drone is drawn at a fixed angle of its id, the UFO spins 5 degrees a tick, whatever their body does.
    // port: NTM 1.7.10's renderers of these (RenderHunterChopper, RenderPlasticBag, RenderWormHead/Body, RenderUFO,
    // RenderDrone) are Render, not RendererLivingEntity: ModelHitClient.beginRender takes their poses. The siege craft
    // and tunneler have renderers too, but X5863 neither registers nor makes them: no rule.
    // port: a mob drawn by its yRot (YAW, YAW_BACK) is taken at the yRot its renderer turned it by (takenYaw), not at its
    // body, and turned by how far yRot has gone since. 1.7.10 does not send the body: the client works its own out and
    // keeps it up to 50 degrees off yRot while the mob stands (EntityLivingBase.func_110146_f), so 26.2's yRot "the
    // client's body plus how far the server's yRot stood off the server's body" was off by that much (the rendermobs
    // stand: 48 degrees for a hanging chopper). yRot itself comes from the server, the chopper's and the dry bag's.
    enum Turn { BODY, YAW, YAW_BACK, WORM, FIXED, SPIN }
    private static final ClassValue<Turn> TURNS=new ClassValue<>() {
        @Override protected Turn computeValue(Class<?> type) { return turn(type); }
    };
    static Turn turn(Class<?> type) {
        if(com.hbm.entity.mob.botprime.EntityWormBaseNT.class.isAssignableFrom(type)) return Turn.WORM;
        if(com.hbm.entity.mob.EntityPlasticBag.class.isAssignableFrom(type)) return Turn.YAW_BACK;
        if(com.hbm.entity.mob.EntityFBIDrone.class.isAssignableFrom(type)) return Turn.FIXED;
        if(com.hbm.entity.mob.EntityUFO.class.isAssignableFrom(type)) return Turn.SPIN;
        if(com.hbm.entity.mob.EntityHunterChopper.class.isAssignableFrom(type)) return Turn.YAW;
        return Turn.BODY;
    }
    // How far the drawn model has turned since the pose was caught, in the body's terms.
    static double turnAngle(EntityLivingBase target, PoseAt p) {
        return turnAngle(target, p.yaw(), p.bodyYaw(), p.yRot(), p.age());
    }
    // yaw: the turn the pose was drawn at (the client's body; port: the drawn yRot for YAW, YAW_BACK, takenYaw); bodyYaw,
    // yRot, age: this side's when the pose came in.
    // age is the mob's tickCount, which RenderUFO spins by; the client's game time jumps with the server's time packets.
    // port: renderYawOffset, rotationYaw, ticksExisted are 26.2's yBodyRot, getYRot(), tickCount
    static double turnAngle(EntityLivingBase target, float yaw, float bodyYaw, float yRot, int age) {
        return turnAngle(TURNS.get(target.getClass()), target.worldObj.isRemote, target.renderYawOffset, target.rotationYaw,
                target.ticksExisted, yaw, bodyYaw, yRot, age);
    }
    static double turnAngle(Turn turn, boolean client, float body, float yRotNow, long now, float yaw, float bodyYaw, float yRot, long tick) {
        return switch(turn) {
            case BODY -> turnAngle(client, body, yaw, bodyYaw);
            case YAW -> turnAngle(client, yRotNow, yaw, yRot);
            case YAW_BACK -> -turnAngle(client, yRotNow, yaw, yRot);
            // the worm's bodies on the client and the server go each their own way: by yRot alone
            case WORM -> -Math.toRadians(MathHelper.wrapAngleTo180_float(yRotNow - yRot));
            case FIXED -> 0;
            case SPIN -> -Math.toRadians(MathHelper.wrapAngleTo180_float((float)(5 * Math.floorMod(now - tick, 72L))));
        };
    }

    // port: the angle a pose is taken at (ModelHitClient): the body's, as rotateCorpse turns the model by; a mob drawn by
    // its yRot, the yRot as its renderer interpolates it (RenderHunterChopper, RenderPlasticBag: prev + (now - prev) * partial)
    static float takenYaw(EntityLivingBase e, float body, float partial) {
        Turn t = TURNS.get(e.getClass());
        return t == Turn.YAW || t == Turn.YAW_BACK ? e.prevRotationYaw + (e.rotationYaw - e.prevRotationYaw) * partial : body;
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
