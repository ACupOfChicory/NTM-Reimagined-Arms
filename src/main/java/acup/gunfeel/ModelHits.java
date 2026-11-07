package acup.gunfeel;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ModelHits {
    public static final int RANGE = 96, MAX_UPDATES = 16, INTERVAL = 2, MAX_SYNC_AGE = 5;
    private record PoseAt(UUID entity, Vec3 position, float yaw, float bodyYaw, float yRot, int age, Pose pose, float width, float height, long tick, List<ModelBox> boxes, List<Object> gear) {}
    private static final Map<Level, Map<Integer, PoseAt>> POSES = new WeakHashMap<>();
    private static final Map<ServerPlayer, long[]> LIMITS = new WeakHashMap<>();
    private record ViewAt(PoseAt pose,Vec3 rendered,Vec3 velocity,Level level) {}
    private static final Map<ServerPlayer,Map<Integer,ViewAt>> VIEWS=new WeakHashMap<>();
    private record Contact(Vec3 point,Vec3 origin,double time) {}
    public static final class ShotContext {
        private final ServerPlayer player;
        private final double travel;
        private final Map<UUID,List<Contact>> contacts=new HashMap<>();
        private ShotContext(ServerPlayer player,double travel){this.player=player;this.travel=travel;}
    }
    private static final ThreadLocal<ShotContext> SHOOTER=new ThreadLocal<>();

    public static ShotContext beginShot(Entity owner) {return beginProjectile(owner,0);}
    public static ShotContext beginProjectile(Entity owner,double travel) {
        var previous=SHOOTER.get();
        if(owner instanceof ServerPlayer player)SHOOTER.set(new ShotContext(player,travel));else SHOOTER.remove();
        return previous;
    }
    public static void endShot(ShotContext previous){if(previous==null)SHOOTER.remove();else SHOOTER.set(previous);}

    private static ViewAt view(Entity target) {
        var context=SHOOTER.get();ServerPlayer player=context==null?null:context.player;
        var views=player==null?null:VIEWS.get(player);
        var view=views==null?null:views.get(target.getId());
        return view!=null && player.level()==target.level() && view.level()==target.level() && compatible(target,view.pose())
                && target.level().getGameTime()-view.pose().tick()<=MAX_SYNC_AGE?view:null;
    }
    private static PoseAt pose(Entity target,Map<Integer,PoseAt> poses) {
        var view=view(target);
        return view==null?(poses==null?null:poses.get(target.getId())):view.pose();
    }
    private static Vec3 origin(Entity target) {
        var view=view(target);
        // Отсчитываем движение от времени снимка, а не от порядка обхода сущностей.
        return view==null?target.position():view.rendered().add(view.velocity().scale(target.level().getGameTime()-view.pose().tick()));
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
        var context=SHOOTER.get();var contacts=context==null?null:context.contacts.get(target.getUUID());
        if(contacts!=null)for(int i=contacts.size()-1;i>=0;i--)if(contacts.get(i).point().distanceToSqr(point)<1e-10)return contacts.get(i);
        return null;
    }
    private static double angle(LivingEntity target,PoseAt pose) {
        return view(target)!=null?0:turnAngle(target,pose);
    }

    private ModelHits() {}

    // What the target wears, on its hitbox (WornGear): on unless the server turns modelHitboxesWorn off. Armor: an
    // equippable for its slot, vanilla's (HumanoidArmorLayer) or a mod's that draws its own model (NTM's suits); not a
    // glider (the elytra) or a block. On the head a skull or another block as CustomHeadLayer draws it.
    static WornGear.Worn worn(Entity target) {
        if(!(target instanceof LivingEntity living) || !ReaimTimes.flag("modelHitboxesWorn",true)) return WornGear.Worn.NOTHING;
        return new WornGear.Worn(wornHead(living),armor(living,net.minecraft.world.entity.EquipmentSlot.CHEST),
                armor(living,net.minecraft.world.entity.EquipmentSlot.LEGS),armor(living,net.minecraft.world.entity.EquipmentSlot.FEET));
    }
    private static boolean armor(LivingEntity living,net.minecraft.world.entity.EquipmentSlot slot) {
        var stack=living.getItemBySlot(slot);
        if(stack.isEmpty() || stack.has(net.minecraft.core.component.DataComponents.GLIDER)
                || stack.getItem() instanceof net.minecraft.world.item.BlockItem) return false;
        var equippable=stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        return equippable!=null && equippable.slot()==slot;
    }
    private static WornGear.Head wornHead(LivingEntity living) {
        var stack=living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
        if(stack.isEmpty()) return WornGear.Head.NONE;
        if(armor(living,net.minecraft.world.entity.EquipmentSlot.HEAD)) return WornGear.Head.ARMOR;
        if(stack.getItem() instanceof net.minecraft.world.item.BlockItem block)
            return !(block.getBlock() instanceof net.minecraft.world.level.block.AbstractSkullBlock skull)?WornGear.Head.BLOCK
                    :skull.getType()==net.minecraft.world.level.block.SkullBlock.Types.DRAGON?WornGear.Head.DRAGON:WornGear.Head.SKULL;
        return WornGear.Head.NONE;
    }
    private static List<ModelBox> wornBoxes(Entity target,PoseAt p) {
        return WornGear.boxes(p.boxes(),worn(target));
    }
    // zones ModelHeads found on the bare model, grown with what is worn
    private static List<HeadZone> wornZones(Entity target,PoseAt p,List<HeadZone> zones) {
        var worn=worn(target);
        return worn.any()?WornGear.zones(zones,worn,WornGear.humanoid(p.boxes())):zones;
    }

    public static boolean enabled() {
        return (!MobBenchMetrics.ENABLED || MobBenchMetrics.mode!=0)
                && acup.gunfeel.compat.Feature.MODEL_HITBOXES.on() && ReaimTimes.flag("modelHitboxes", true);
    }

    public static synchronized void receive(ServerPlayer observer, ModelPosePayload p) {
        ModelHitLog.count("server", "packet_received");
        if (!enabled()) {
            ModelHitLog.event("server", "disabled", observer, () -> "packet_entity=" + p.entity());
            return;
        }
        long tick = observer.level().getGameTime();
        long[] rate = LIMITS.computeIfAbsent(observer, k -> new long[] {tick / INTERVAL, 0});
        if (rate[0] != tick / INTERVAL) { rate[0] = tick / INTERVAL; rate[1] = 0; }
        if (++rate[1] > MAX_UPDATES * 2) {
            ModelHitLog.event("server", "rate_rejected", observer, () -> "packets=" + rate[1] + " tick=" + tick);
            return;
        }
        Entity target = observer.level().getEntity(p.entity());
        if (!(target instanceof LivingEntity living) || !target.isAlive() || !target.getUUID().equals(p.uuid())
                || observer.distanceToSqr(target) > RANGE * RANGE || !Double.isFinite(p.x()) || !Double.isFinite(p.y())
                || !Double.isFinite(p.z()) || !Float.isFinite(p.yaw()) || target.position().distanceToSqr(new Vec3(p.x(), p.y(), p.z())) > 16
                || p.boxes().isEmpty() || p.boxes().size() > ModelPosePayload.MAX_BOXES) {
            ModelHitLog.event("server", "target_rejected", target, () -> "observer=" + observer.getId()
                    + " packet_entity=" + p.entity() + " packet_uuid=" + p.uuid()
                    + " target_uuid=" + (target == null ? null : target.getUUID())
                    + " alive=" + (target != null && target.isAlive())
                    + " distance2=" + (target == null ? -1 : observer.distanceToSqr(target))
                    + " packet_pos=" + p.x() + "," + p.y() + "," + p.z()
                    + " target_pos=" + (target == null ? null : target.position()) + " yaw=" + p.yaw() + " cubes=" + p.boxes().size());
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
        put(observer.level(), living, p.yaw(), filtered);
        var saved=POSES.get(observer.level()).get(target.getId());
        var views=VIEWS.computeIfAbsent(observer,k->new HashMap<>());
        var previous=views.get(target.getId());var rendered=new Vec3(p.x(),p.y(),p.z());
        var velocity=target.getDeltaMovement();
        if(previous!=null && compatible(target,previous.pose())) {
            long elapsed=tick-previous.pose().tick();
            velocity=elapsed>0 && elapsed<=MAX_SYNC_AGE?rendered.subtract(previous.rendered()).scale(1.0/elapsed):previous.velocity();
        }
        views.put(target.getId(),new ViewAt(saved,rendered,velocity,observer.level()));
        MobSmoke.accepted(living, p.yaw(), filtered.stream().filter(b -> ModelWool.visible(living,b)).toList());
        ModelHitLog.event("server", "accepted", living, () -> "observer=" + observer.getId() + " cubes=" + p.boxes().size()
                + " tick=" + tick + " rendered=" + p.x() + "," + p.y() + "," + p.z()
                + " authority=" + living.position() + " first_cube=" + p.boxes().getFirst());
    }

    public static double radius(Entity target) {
        // Also bounds candidate lookup: no accepted cube extends beyond this distance from the entity origin.
        return Math.min(256, Math.max(16, Math.max(target.getBbWidth(), target.getBbHeight()) * 4));
    }

    public static synchronized void put(Level level, LivingEntity target, float yaw, List<ModelBox> boxes) {
        putAt(level, target, yaw, boxes, level.getGameTime());
    }

    // Явная отметка захвата также позволяет воспроизвести пропуск кадров в серверном стенде.
    static synchronized void putAt(Level level, LivingEntity target, float yaw, List<ModelBox> boxes, long tick) {
        Map<Integer, PoseAt> poses = POSES.computeIfAbsent(level, k -> new HashMap<>());
        poses.put(target.getId(), new PoseAt(target.getUUID(), target.position(), yaw, target.yBodyRot, target.getYRot(), target.tickCount, target.getPose(),
                target.getBbWidth(), target.getBbHeight(), tick, List.copyOf(boxes), gear(target)));
    }

    // Пропуск рендера не меняет модель сущности. Следим за корнем раз в игровой тик,
    // не запуская рендерер или анимацию, и удаляем только действительно несовместимые позы.
    public static synchronized void tick(Level level) {
        Map<Integer, PoseAt> poses = POSES.get(level);
        if (poses == null) return;
        if (!enabled()) { clear(level); return; }
        var it = poses.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            PoseAt p = entry.getValue();
            Entity target = level.getEntity(entry.getKey());
            if (!compatible(target, p)) {
                it.remove();
            } else if (!target.position().equals(p.position())) {
                entry.setValue(new PoseAt(p.entity(), target.position(), p.yaw(), p.bodyYaw(), p.yRot(), p.age(), p.pose(),
                        p.width(), p.height(), p.tick(), p.boxes(), p.gear()));
            }
        }
        for(var observer:VIEWS.entrySet()) {
            observer.getValue().entrySet().removeIf(entry->{
                var v=entry.getValue();
                if(v.level()!=level)return false;
                var target=level.getEntity(entry.getKey());
                if(observer.getKey().level()!=level || !compatible(target,v.pose())
                        || level.getGameTime()-v.pose().tick()>MAX_SYNC_AGE)return true;
                var p=v.pose();
                entry.setValue(new ViewAt(new PoseAt(p.entity(),target.position(),p.yaw(),p.bodyYaw(),p.yRot(),p.age(),p.pose(),
                        p.width(),p.height(),p.tick(),p.boxes(),p.gear()),v.rendered(),v.velocity(),level));
                return false;
            });
        }
    }

    public static synchronized void clear(Level level) {
        POSES.remove(level);
        VIEWS.values().forEach(views->views.values().removeIf(v->v.level()==level));
    }

    public static synchronized List<Entity> candidates(Level level, Entity except, List<Entity> original, AABB area,
            java.util.function.Predicate<Entity> filter) {
        Map<Integer, PoseAt> poses = POSES.get(level);
        if (poses == null || !enabled()) return original;
        List<Entity> out = new ArrayList<>(original);
        for (var entry : poses.entrySet()) {
            Entity target = level.getEntity(entry.getKey());
            if(!compatible(target,entry.getValue()) || target==except || !target.getBoundingBox().inflate(radius(target)).intersects(area)) continue;
            if(target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon)
                for(var part:dragon.getSubEntities()) if(part!=except && filter.test(part) && !out.contains(part)) out.add(part);
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
        return target != null && target.isAlive() && p != null && p.entity().equals(target.getUUID())
                && target.position().distanceToSqr(p.position()) <= 16 && p.pose() == target.getPose()
                && p.width() == target.getBbWidth() && p.height() == target.getBbHeight() && p.gear().equals(gear(target));
    }

    // what it wore when the pose was caught: the armor layers (a wolf's or horse's body armor and a saddle too) are in
    // the boxes, so a pose outlives no change of them
    private static List<Object> gear(Entity target) {
        if(!(target instanceof LivingEntity living)) return List.of();
        return List.of(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem(),
                living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem(),
                living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).getItem(),
                living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).getItem(),
                living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.BODY).getItem(),
                living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.SADDLE).getItem());
    }

    // F3+B получает ровно ту же позу и те же проверки актуальности, что пересечение луча.
    public static synchronized boolean hasPose(Entity target) {
        if(!(target instanceof LivingEntity)) return false;
        var poses=POSES.get(target.level());
        return poses!=null && usable(target,poses.get(target.getId()));
    }
    public static synchronized ModelHeads.Hit head(Entity target,Vec3 point) {
        if(!enabled()) return new ModelHeads.Hit(false,false,"disabled","точные хитбоксы отключены",0);
        var parent=ModelPartEntities.parent(target);Entity root=parent==null?target:parent;
        if(!(root instanceof LivingEntity living) || point==null) return new ModelHeads.Hit(true,false,"unknown","нет живой цели или точки",0);
        String type=ModelHeads.key(root);
        if(ModelHeads.noHead(type)) return new ModelHeads.Hit(true,false,"dataset","размечено без головы",1);
        var poses=POSES.get(root.level());var pose=pose(root,poses);
        if(!usable(root,pose)) return heightHead(living,point);
        var resolved=ModelHeads.resolve(type,pose.boxes(),pose.yaw());
        if(resolved.source().equals("height")) return heightHead(living,point);
        double angle=angle(living,pose);
        boolean head=HeadZone.contains(wornZones(root,pose,resolved.zones()),unturn(point.subtract(impactOrigin(root,point)),angle));
        return new ModelHeads.Hit(true,head,resolved.source(),resolved.reason(),resolved.confidence());
    }
    private static ModelHeads.Hit heightHead(LivingEntity living,Vec3 point) {
        double margin=living.getBbHeight()-living.getEyeHeight();
        return new ModelHeads.Hit(true,living.isAlive() && point.y>impactOrigin(living,point).y+living.getBbHeight()-margin*2,
                "height","высотная зона NTM с прежним допуском",0F);
    }
    static synchronized boolean limb(Entity target,Vec3 point) {
        if(!enabled() || point==null) return false;
        var parent=ModelPartEntities.parent(target);Entity root=parent==null?target:parent;
        if(!(root instanceof LivingEntity living) || !LimbPolicy.eligible(root.getType())) return false;
        var poses=POSES.get(root.level());var pose=pose(root,poses);
        if(!usable(root,pose)) return false;
        var zones=wornZones(root,pose,ModelHeads.limbs(ModelHeads.key(root),pose.boxes(),pose.yaw()).zones());
        double angle=angle(living,pose);
        return HeadZone.contains(zones,unturn(point.subtract(impactOrigin(root,point)),angle));
    }
    public static synchronized List<HeadZone> headZones(Entity target) {
        var poses=POSES.get(target.level());var pose=poses==null?null:poses.get(target.getId());
        if(!usable(target,pose) || !(target instanceof LivingEntity living)) return List.of();
        String type=ModelHeads.key(target);
        var resolved=ModelHeads.resolve(type,pose.boxes(),pose.yaw());
        double angle=turnAngle(living,pose);
        var out=new ArrayList<HeadZone>();
        for(var zone:wornZones(target,pose,resolved.zones())) out.add(world(zone,angle,target.position()));
        return List.copyOf(out);
    }
    // Зона в мир: коробка, плоскости и кубы (pieces) — одним поворотом на angle и сдвигом в position.
    static HeadZone world(HeadZone zone,double angle,Vec3 position) {
        double cos=Math.cos(-angle),sin=Math.sin(-angle);var box=zone.box();
        return HeadZone.local(world(box,cos,sin,position),zone.planes().stream().map(p->HeadZone.localPlane(box,p)).toList(),zone.pieces().stream().map(b->world(b,cos,sin,position)).toList());
    }
    private static ModelBox world(ModelBox box,double cos,double sin,Vec3 position) {
        var origin=unturn(new Vec3(box.x(),box.y(),box.z()),cos,sin).add(position);
        var u=unturn(new Vec3(box.ux(),box.uy(),box.uz()),cos,sin);var v=unturn(new Vec3(box.vx(),box.vy(),box.vz()),cos,sin);var w=unturn(new Vec3(box.wx(),box.wy(),box.wz()),cos,sin);
        return new ModelBox(box.part(),origin.x,origin.y,origin.z,u.x,u.y,u.z,v.x,v.y,v.z,w.x,w.y,w.z,box.mesh());
    }
    // Стенд: части позы в мире (без надетого, как worldBoxes) и размечена ли каждая головой и конечностью — сама
    // коробка зоны или куб коробки вокруг кубов части (HeadZone.pieces). По самой коробке, не по имени: у части
    // одного имени размечены бывают не все коробки (camel_husk: root/body/head трижды, зоны у двух).
    record StandPart(ModelBox box,boolean head,boolean limb) {}
    static synchronized List<StandPart> standParts(Entity target) {
        var poses=POSES.get(target.level());var pose=poses==null?null:poses.get(target.getId());
        if(!usable(target,pose) || !(target instanceof LivingEntity living)) return List.of();
        String type=ModelHeads.key(target);
        var heads=ModelHeads.resolve(type,pose.boxes(),pose.yaw()).zones();
        var limbs=LimbPolicy.eligible(target.getType())?ModelHeads.limbs(type,pose.boxes(),pose.yaw()).zones():List.<HeadZone>of();
        double angle=turnAngle(living,pose);
        double cos=Math.cos(-angle),sin=Math.sin(-angle);
        var out=new ArrayList<StandPart>();
        for(var box:pose.boxes()) {
            if(!target.level().isClientSide() && !ModelWool.visible(target,box)) continue;
            out.add(new StandPart(world(box,cos,sin,target.position()),zoned(heads,box),zoned(limbs,box)));
        }
        return List.copyOf(out);
    }
    private static boolean zoned(List<HeadZone> zones,ModelBox box) {
        for(var zone:zones) if(zone.box().equals(box) || zone.pieces().contains(box)) return true;
        return false;
    }
    public static synchronized ModelHeads.Resolution headResolution(Entity target) {
        var poses=POSES.get(target.level());var pose=poses==null?null:poses.get(target.getId());
        if(!usable(target,pose)) return new ModelHeads.Resolution(List.of(),"unknown","нет совместимой позы модели",0,0);
        String type=ModelHeads.key(target);
        return ModelHeads.resolve(type,pose.boxes(),pose.yaw());
    }
    public static synchronized List<Vec3[]> outlines(Entity target) {
        return ModelContours.edges(worldBoxes(target));
    }

    // Стенд: привязка конечностей по той же позе, что у headResolution.
    static synchronized ModelHeads.Resolution limbResolution(Entity target) {
        var poses=POSES.get(target.level());var pose=poses==null?null:poses.get(target.getId());
        if(!usable(target,pose)) return new ModelHeads.Resolution(List.of(),"unknown","нет совместимой позы модели",0,0);
        return ModelHeads.limbs(ModelHeads.key(target),pose.boxes(),pose.yaw());
    }
    // Стенд: части позы в кадре, где HeadProfiles.bind сравнивает их с разметкой (центр повёрнут на yaw позы),
    // и вершины сетки части в том же кадре.
    record ProfilePart(HeadProfiles.Ref ref,List<Vec3> vertices) {}
    static synchronized List<ProfilePart> profileParts(Entity target) {
        var poses=POSES.get(target.level());var pose=poses==null?null:poses.get(target.getId());
        if(!usable(target,pose)) return List.of();
        double a=Math.toRadians(pose.yaw()),cos=Math.cos(a),sin=Math.sin(a);
        java.util.function.UnaryOperator<Vec3> turn=c->new Vec3(cos*c.x+sin*c.z,c.y,-sin*c.x+cos*c.z);
        return pose.boxes().stream().map(b->{
            var data=HeadLabelGeometry.triangles(b);var vertices=new java.util.LinkedHashSet<Vec3>();
            for(int at=0;at<data.length;at+=3) {
                var v=turn.apply(ModelContours.point(b,data[at],data[at+1],data[at+2]));
                vertices.add(new Vec3(Math.round(v.x*1e4)/1e4,Math.round(v.y*1e4)/1e4,Math.round(v.z*1e4)/1e4));
            }
            return new ProfilePart(new HeadProfiles.Ref(b.part(),HeadProfiles.size(b),HeadProfiles.shape(b),turn.apply(ModelContours.point(b,.5,.5,.5)),-1),List.copyOf(vertices));
        }).toList();
    }
    public static synchronized List<ModelBox> worldBoxes(Entity target) {
        Map<Integer, PoseAt> poses = POSES.get(target.level());
        PoseAt p = poses == null ? null : poses.get(target.getId());
        if (!usable(target, p) || !(target instanceof LivingEntity living)) return List.of();
        double angle = turnAngle(living, p);
        List<ModelBox> out = new ArrayList<>(p.boxes().size());
        double cos=Math.cos(-angle),sin=Math.sin(-angle);
        for (ModelBox box : wornBoxes(target,p)) {
            if(!target.level().isClientSide() && !ModelWool.visible(target,box)) continue;
            out.add(world(box,cos,sin,target.position()));
        }
        return out;
    }

    // Пары концов двенадцати рёбер обычного ModelPart-куба.
    private static final float[] BOX_EDGES = boxEdges();
    private static float[] boxEdges() {
        float[] edges = new float[72];
        int at = 0;
        for (int i = 0; i < 8; i++) for (int axis = 1; axis <= 4; axis <<= 1) if ((i & axis) == 0)
            for (int point : new int[] {i, i | axis}) {
                edges[at++] = (point & 1) == 0 ? 0 : 1;
                edges[at++] = (point & 2) == 0 ? 0 : 1;
                edges[at++] = (point & 4) == 0 ? 0 : 1;
            }
        return edges;
    }

    public static synchronized Optional<Vec3> clip(Entity target, Vec3 from, Vec3 to, AABB fallback) {
        var dragonPart=target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragonPart part?part:null;
        var parent=ModelPartEntities.parent(target);
        Entity root=dragonPart!=null?dragonPart.parentMob:parent!=null?parent:target;
        Map<Integer, PoseAt> poses = POSES.get(target.level());
        PoseAt p = root==null ? null : pose(root,poses);
        boolean fresh = usable(root, p);
        if (!fresh || !(root instanceof LivingEntity living)) {
            if (!from.equals(to)) ModelHitLog.event(target.level().isClientSide() ? "client" : "server", "clip_fallback", target,
                    () -> "enabled=" + enabled() + " has_pose=" + (p != null) + " age=" + (p == null ? -1 : target.level().getGameTime() - p.tick())
                            + " uuid_matches=" + (p != null && p.entity().equals(target.getUUID()))
                            + " stored_pose=" + (p == null ? null : p.pose()) + " current_pose=" + target.getPose()
                            + " stored_size=" + (p == null ? "none" : p.width() + "," + p.height())
                            + " current_size=" + target.getBbWidth() + "," + target.getBbHeight()
                            + " displacement2=" + (p == null ? -1 : target.position().distanceToSqr(p.position()))
                            + " from=" + from + " to=" + to);
            return fallback.contains(from) ? Optional.of(from) : fallback.clip(from, to);
        }
        Vec3 segmentFrom=from,segmentTo=to;
        if(parent!=null && dragonPart==null) {
            // Не расширяем область части на 0.3 вместе с AABB снаряда.
            // Часть сохраняет свой урон, но её невидимая коробка сама по себе
            // больше не является поверхностью попадания.
            AABB region=target.getBoundingBox().move(origin(root).subtract(root.position()));
            if(region.getXsize()<=0 || region.getYsize()<=0 || region.getZsize()<=0) return Optional.empty();
            var carrier=new ModelBox("part_region",region.minX,region.minY,region.minZ,
                    region.getXsize(),0,0,0,region.getYsize(),0,0,0,region.getZsize());
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
            if(!target.level().isClientSide() && !ModelWool.visible(root,box)) continue;
            if(dragonPart!=null) {
                var center=unturn(ModelContours.point(box,.5,.5,.5),-angle).add(root.position());
                if(!DragonModelParts.belongs(dragonPart.parentMob,dragonPart,box,center)) continue;
            }
            double t = box.clip(a.x, a.y, a.z, b.x, b.y, b.z);
            if (t >= 0 && t < nearest) { nearest = t; part = box.part(); }
        }
        if (!from.equals(to)) {
            final double at = nearest;
            final String name = part;
            ModelHitLog.event(target.level().isClientSide() ? "client" : "server", "clip_model", target,
                    () -> "result=" + (Double.isFinite(at) ? "hit" : "miss") + " part=" + name + " t=" + at
                            + " cubes=" + p.boxes().size() + " age=" + (target.level().getGameTime() - p.tick())
                            + " shooter=" + (SHOOTER.get()==null?"none":SHOOTER.get().player.getId())
                            + " origin=" + rootOrigin + " authority=" + root.position()
                            + " from=" + from + " to=" + to);
        }
        if (TestLog.on(target.level()) && !from.equals(to)) TestLog.at(target.level(), -1, "model_hit", "target", target.getId(),
                "part", part, "result", Double.isFinite(nearest) ? "hit" : "miss", "age", target.level().getGameTime() - p.tick());
        if(!Double.isFinite(nearest))return Optional.empty();
        Vec3 point=segmentFrom.lerp(segmentTo,nearest);
        if(context!=null){
            double time=first+(last-first)*nearest;
            var contacts=context.contacts.computeIfAbsent(root.getUUID(),k->new ArrayList<>());
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
    static double turnAngle(LivingEntity target, PoseAt p) {
        return turnAngle(target, p.yaw(), p.bodyYaw(), p.yRot(), p.age());
    }
    // yaw: the turn the pose was drawn at (the client's body; the drawn yRot for YAW, YAW_BACK, takenYaw); bodyYaw, yRot,
    // age: this side's when the pose came in.
    // age is the mob's tickCount, which RenderUFO spins by; the client's game time jumps with the server's time packets.
    static double turnAngle(LivingEntity target, float yaw, float bodyYaw, float yRot, int age) {
        return turnAngle(TURNS.get(target.getClass()), target.level().isClientSide(), target.yBodyRot, target.getYRot(),
                target.tickCount, yaw, bodyYaw, yRot, age);
    }
    static double turnAngle(Turn turn, boolean client, float body, float yRotNow, long now, float yaw, float bodyYaw, float yRot, long tick) {
        return switch(turn) {
            case BODY -> turnAngle(client, body, yaw, bodyYaw);
            // taken at the drawn yRot: turned by yRot alone, as the body is (the client's body is not sent)
            case YAW -> turnAngle(client, yRotNow, yaw, yRot);
            case YAW_BACK -> -turnAngle(client, yRotNow, yaw, yRot);
            // the worm's bodies on the client and the server go each their own way: by yRot alone
            case WORM -> -Math.toRadians(Mth.wrapDegrees(yRotNow - yRot));
            case FIXED -> 0;
            case SPIN -> -Math.toRadians(Mth.wrapDegrees((float)(5 * Math.floorMod(now - tick, 72L))));
        };
    }

    // The angle a client takes a pose at: its body, or for a mob drawn by its yRot (YAW, YAW_BACK) the yRot its renderer
    // turned it by. The client's body is not sent, and a mob that stands keeps it up to 50 degrees off its yRot
    // (LivingEntity.tickHeadTurn): a pose taken at that body would be turned on the server by the gap as well.
    public static float takenYaw(LivingEntity e, float body, float partial) {
        Turn t = TURNS.get(e.getClass());
        return t == Turn.YAW || t == Turn.YAW_BACK ? Mth.rotLerp(partial, e.yRotO, e.getYRot()) : body;
    }

    // Клиент сохраняет исходную интерполяцию и добавляет лишь движение корня после захвата.
    // Сервер доворачивает от фактического угла полученной по сети геометрии.
    static double turnAngle(boolean client, float currentYaw, float capturedYaw, float anchorYaw) {
        return Math.toRadians(Mth.wrapDegrees(currentYaw - (client ? anchorYaw : capturedYaw)));
    }

    private static Vec3 unturn(Vec3 p, double angle) {
        return unturn(p, Math.cos(angle), Math.sin(angle));
    }
    // Для многих частей одной позы cos и sin считаются один раз.
    private static Vec3 unturn(Vec3 p, double cos, double sin) {
        return new Vec3(cos * p.x + sin * p.z, p.y, -sin * p.x + cos * p.z);
    }
}
