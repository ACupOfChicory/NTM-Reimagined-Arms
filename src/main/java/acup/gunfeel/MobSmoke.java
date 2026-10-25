package acup.gunfeel;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Только runCheckMobs: получает снимки от настоящего рендера и настоящей сети. */
public final class MobSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.checkMobs");
    private static final List<String> DEFAULT_CASES = List.of("minecraft:skeleton", "minecraft:villager", "minecraft:phantom",
            "minecraft:spider", "minecraft:iron_golem", "hbm:entity_undead_soldier", "hbm:entity_radiation_blaze",
            "hbm:entity_mob_mask_man", "hbm:entity_glyphid", "hbm:entity_tesla_crab", "hbm:entity_pigeon", "hbm:entity_cyber_crab");
    private static final List<String> CASES = System.getProperty("ntmgunfeel.mobCases", "").isBlank() ? DEFAULT_CASES
            : java.util.Arrays.stream(System.getProperty("ntmgunfeel.mobCases").split(",")).map(String::trim).toList();
    private record Snapshot(float anchor, List<ModelBox> boxes, String source) {}
    private record Result(boolean ok, String text) {}
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    private static volatile int id = -1;
    private static volatile Snapshot client, server;
    private static volatile boolean pending;
    private static int index=-1, stage, orange, white;
    private static boolean watch;
    private static long since;
    private static BulletConfig beamConfig;

    private MobSmoke() {}
    static boolean enabled() { return ENABLED; }

    static void captured(LivingEntity e, float anchor, List<ModelBox> boxes, String source) {
        if (ENABLED && e.getId()==id) client=new Snapshot(anchor,List.copyOf(boxes),source);
    }
    static void accepted(LivingEntity e, float anchor, List<ModelBox> boxes) {
        if (ENABLED && e.getId()==id) server=new Snapshot(anchor,List.copyOf(boxes),"network");
    }
    public static void debug(Entity e, boolean model) {
        if (ENABLED && watch && e!=null && e.getId()==id) {
            if (model) orange++; else white++;
        }
    }

    static void start() {
        var mc=Minecraft.getInstance();
        mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.ALWAYS_ON);
        pending=true;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var level=mc.getSingleplayerServer().overworld();
                var p=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                p.setGameMode(GameType.CREATIVE);
                p.teleportTo(level,8.5,100,8.5,java.util.Set.of(),0,0,false);
                for(int x=2;x<=15;x++) for(int z=3;z<=26;z++)
                    level.setBlock(new BlockPos(x,99,z),(z<=10?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
                beamConfig=new BulletConfig().setBeam().setDamage(0).setOnBeamImpact(BulletConfig.LAMBDA_STANDARD_BEAM_HIT);
            } catch(Throwable t) { RESULTS.add(new Result(false,"подготовка мира: "+t)); }
            pending=false;
        });
        stage=0;
    }

    static void frame() {
        Result result;
        while((result=RESULTS.poll())!=null) ClientSmoke.check(result.ok(),result.text());
        var mc=Minecraft.getInstance();
        if(mc.player==null || pending) return;
        mc.player.setYRot(stage==4?180:0);
        mc.player.setXRot(0);
        if(stage==0) {
            next();
        } else if(stage==1) {
            if(seconds()>1 && mc.level.getEntity(id)!=null) watch=true;
            if(client!=null && server!=null && seconds()>1) {
                check(true,"захват="+client.source()+", частей="+client.boxes().size()+", сеть принята");
                check(mc.level.getEntity(id) instanceof LivingEntity,"сущность существует у клиента");
                watch=true; orange=white=0;
                stage=2; since=System.nanoTime();
            } else if(seconds()>6) {
                var entity=mc.level.getEntity(id);
                String renderer=entity==null?"нет сущности":mc.getEntityRenderDispatcher().getRenderer(entity).getClass().getName();
                check(false,"нет полной цепочки рендер -> сеть: client="+(client!=null)+", server="+(server!=null)
                        +", оранжевых="+orange+", белых="+white+", renderer="+renderer);
                watch=false;
                stage=0;
            }
        } else if(stage==2 && seconds()>1) {
            watch=false;
            check(orange>0 && white==0,"F3+B: оранжевых кадров="+orange+", белых="+white);
            LivingEntity target=(LivingEntity)mc.level.getEntity(id);
            if(target==null) { check(false,"цель пропала"); stage=0; return; }
            rays(target,client,CASES.get(index)+": клиент, видимый");
            if(CASES.get(index).equals("minecraft:skeleton")) {
                anchor(target,0,1.65,true,"голова скелета");
                anchor(target,.125,.3,true,"нога скелета");
                anchor(target,0,.3,false,"пустота между ногами скелета");
                anchor(target,.56,1.3,false,"пустота рядом со скелетом внутри AABB+0.3");
            }
            serverRays(false);
            stage=3;
        } else if(stage==3) {
            pending=true;
            mc.getSingleplayerServer().execute(() -> {
                var target=(LivingEntity)mc.getSingleplayerServer().overworld().getEntity(id);
                if(target!=null) {
                    target.setPos(target.position().add(.6,0,0));
                    target.setYRot(90); target.yBodyRot=90; target.yHeadRot=90;
                }
                pending=false;
            });
            stage=4; since=System.nanoTime();
        } else if(stage==4 && seconds()>1.2) {
            var target=(LivingEntity)mc.level.getEntity(id);
            if(target!=null) {
                check(!ModelHits.outlines(target).isEmpty(),"поза пережила уход из кадра, сдвиг и поворот");
                rays(target,client,CASES.get(index)+": клиент, вне кадра после движения");
                serverRays(true);
            } else check(false,"цель пропала после движения");
            stage=0;
        }
    }

    private static double seconds() { return (System.nanoTime()-since)/1e9; }
    private static void check(boolean ok,String text) { ClientSmoke.check(ok,CASES.get(index)+": "+text); }
    private static void next() {
        var mc=Minecraft.getInstance();
        int previous=id;
        if(++index>=CASES.size()) { ClientSmoke.finish(); return; }
        id=-1; client=server=null; watch=false; orange=white=0;
        pending=true;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var level=mc.getSingleplayerServer().overworld();
                if(level.getEntity(previous)!=null) level.getEntity(previous).discard();
                var type=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(CASES.get(index)));
                Entity created=type.create(level,EntitySpawnReason.COMMAND);
                if(!(created instanceof LivingEntity target)) throw new IllegalStateException("не живой моб: "+created);
                target.setPos(8.5,100,16.5);
                target.setYRot(180); target.yBodyRot=180; target.yHeadRot=180;
                target.setNoGravity(true); target.setInvulnerable(true);
                if(target instanceof Mob mob) { mob.setNoAi(true); mob.setPersistenceRequired(); }
                id=target.getId();
                level.addFreshEntity(target);
            } catch(Throwable t) { RESULTS.add(new Result(false,CASES.get(index)+": создание: "+t)); }
            pending=false;
        });
        stage=1; since=System.nanoTime();
    }

    private static void serverRays(boolean moved) {
        pending=true;
        String label=CASES.get(index)+": сервер"+(moved?", после движения":"");
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var target=(LivingEntity)Minecraft.getInstance().getSingleplayerServer().overworld().getEntity(id);
                if(target==null || server==null) RESULTS.add(new Result(false,label+": нет цели или сетевой позы"));
                else rays(target,server,label);
            } catch(Throwable t) { RESULTS.add(new Result(false,label+": "+t)); }
            pending=false;
        });
    }

    private static void anchor(LivingEntity target,double x,double y,boolean expected,String label) {
        Vec3 a=target.position().add(x,y,-3),b=a.add(0,0,6);
        boolean actual=ProjectileUtil.getEntityHitResult(target.level(),(Entity)null,a,b,new AABB(a,b).inflate(.001),
                e -> e==target,.3F)!=null;
        check(actual==expected,label+": ожидание="+expected+", попадание="+actual);
    }

    private static void rays(LivingEntity target,Snapshot pose,String label) {
        var triangles=MobRayOracle.surface(target,pose.anchor(),pose.boxes());
        var grid=MobRayOracle.grid(triangles);
        int hits=0,misses=0,mismatch=0,extra=0,gaps=0;
        MobRayOracle.Ray body=null,gap=null,wing=null;
        var model=target.getBoundingBox();
        var raw=new AABB(model.minX,model.minY,model.minZ,model.maxX,model.maxY,model.maxZ);
        for(var ray:grid) {
            boolean actual=ProjectileUtil.getEntityHitResult(target.level(),(Entity)null,ray.from(),ray.to(),
                    new AABB(ray.from(),ray.to()).inflate(.001),e -> e==target,.3F)!=null;
            if(ray.hit()) { hits++; if(body==null) body=ray; } else misses++;
            if(actual!=ray.hit()) mismatch++;
            if(ray.hit() && raw.clip(ray.from(),ray.to()).isEmpty()) { extra++; wing=ray; }
            if(!ray.hit() && raw.inflate(.3).clip(ray.from(),ray.to()).isPresent()) { gaps++; gap=ray; }
        }
        RESULTS.add(new Result(hits>0 && misses>0 && mismatch==0,label+": лучи="+grid.size()+", модель="+hits+
                ", пустота="+misses+", расхождений="+mismatch+", вне AABB="+extra+", пустота внутри AABB+0.3="+gaps));
        if(!target.level().isClientSide()) {
            nativeRay((ServerLevel)target.level(),target,body,true,label+": тело");
            if(gap!=null) nativeRay((ServerLevel)target.level(),target,gap,false,label+": пустота внутри AABB+0.3");
            if(wing!=null) nativeRay((ServerLevel)target.level(),target,wing,true,label+": выступ за AABB");
        }
    }

    private static void nativeRay(ServerLevel level,LivingEntity target,MobRayOracle.Ray ray,boolean expected,String label) {
        if(ray==null) { RESULTS.add(new Result(false,label+": нет контрольного луча")); return; }
        var block=level.clip(new net.minecraft.world.level.ClipContext(ray.from(),ray.to(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,target));
        if(block.getType()!=HitResult.Type.MISS) {
            RESULTS.add(new Result(false,label+": ошибка стенда, луч перекрыт блоком "+block.getBlockPos()+", начало="+ray.from()));
            return;
        }
        var bullet=new ProbeBullet(level,target);
        bullet.setPos(ray.from()); bullet.setThrowableHeading(0,0,1,1,0); bullet.damage=0;
        bullet.tick(); bullet.discard();
        var arrow=new ProbeArrow(level,target);
        arrow.setPos(ray.from()); arrow.setNoGravity(true); arrow.setDeltaMovement(ray.to().subtract(ray.from()));
        arrow.tick(); arrow.discard();
        var beam=new ProbeBeam(level,target);
        beam.setPos(ray.from()); beam.setRotationsFromVector(ray.to().subtract(ray.from()).normalize());
        beam.performHitscanExternal(ray.from().distanceTo(ray.to())); beam.discard();
        RESULTS.add(new Result(bullet.hit==expected && arrow.hit==expected && beam.hit==expected,label+": ожидание="+expected+
                ", NTM-пуля="+bullet.hit+", стрела="+arrow.hit+", NTM-луч="+beam.hit));
    }

    private static final class ProbeBullet extends EntityBulletBaseMK4 {
        private final Entity target;
        private boolean hit;
        ProbeBullet(ServerLevel level,Entity target) { super(level,XFactory9mm.p9_ap,1,0,0,0); this.target=target; }
        @Override protected void onImpact(HitResult result) {
            if(result instanceof EntityHitResult entity && entity.getEntity()==target) hit=true;
            super.onImpact(result);
        }
    }
    private static final class ProbeArrow extends Arrow {
        private final Entity target;
        private boolean hit;
        ProbeArrow(ServerLevel level,Entity target) { super(EntityTypes.ARROW,level); this.target=target; setBaseDamage(0); }
        @Override protected void onHitEntity(EntityHitResult result) {
            if(result.getEntity()==target) hit=true;
            super.onHitEntity(result);
        }
    }
    private static final class ProbeBeam extends EntityBulletBeamBase {
        private final Entity target;
        private boolean hit;
        ProbeBeam(ServerLevel level,Entity target) { super(level,beamConfig,0); this.target=target; }
        @Override protected void onImpact(HitResult result) {
            if(result instanceof EntityHitResult entity && entity.getEntity()==target) hit=true;
            super.onImpact(result);
        }
    }
}
