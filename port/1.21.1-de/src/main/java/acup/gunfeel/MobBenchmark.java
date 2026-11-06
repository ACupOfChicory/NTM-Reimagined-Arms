package acup.gunfeel;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;

// Port copy (notes/ports/de-1.21.1.md, «F3+B и время кадра»): the root's bench (-PbenchMobs=<label>), its scenes by
// name (-PmobBenchScenes=ntm1,van30,...): n mobs of NTM or vanilla in a grid before the camera, NoAi, in the air;
// with a w (ntm10w) the server walks each to and fro along x, so its model walks and its pose is new every frame.
// Each scene ABBA over the system off/on and F3+B off/on (system off with F3+B: vanilla's boxes), a warm-up before
// each phase. A row per phase: FPS, mean, median, p95 and p99 frame, the render thread's bytes a frame, the GC, and
// the frame's milliseconds on the pose capture, the sync, and F3+B (contour, head zones and their cut, the lines).
// -PmobBenchLimit=<ms>[,<ms>]: the stand fails a scene whose F3+B adds more than that to the mean frame over what
// vanilla's F3+B adds (both passes); the second, if given, for the walking scenes (a new contour every frame).
final class MobBenchmark {
    private record Phase(String scene,int pass,int mode,boolean debug) {}
    static final String[] NTM={"hbm:entity_glyphid","hbm:entity_cyber_crab","hbm:entity_pigeon","hbm:entity_mob_mask_man"};
    static final String[] VANILLA={"minecraft:skeleton","minecraft:zombie","minecraft:sheep","minecraft:slime"};
    private static final List<Phase> PLAN=new ArrayList<>();
    private static final List<String> ROWS=new ArrayList<>();
    // the mean frame ms by scene and phase kind (mode+2*debug), summed over both passes
    private static final Map<String,double[]> MEANS=new LinkedHashMap<>();
    private static final long[] TIMES=new long[200_000];
    private static final com.sun.management.ThreadMXBean THREADS=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static int index=-1,samples;
    private static String scene;
    private static volatile boolean pending;
    private static volatile String failure;
    private static long since,last,measuring,allocationStart,publishStart,gcCountStart,gcTimeStart;
    private static final long[] costs=new long[4];
    // F3+B in the port's ModelHitDebug: the contour, the head zones and the split, the lines; the outlines drawn
    static final int CONTOUR=0,ZONES=1,LINES=2;
    static final long[] debugNanos=new long[3];
    static long debugTargets;
    private static final long[] debugStart=new long[3];
    private static long targetStart;
    private static final double WARM=Double.parseDouble(System.getProperty("ntmgunfeel.benchWarm","2"));
    private static final double MEASURE=Double.parseDouble(System.getProperty("ntmgunfeel.benchSeconds","4"));
    private static final String[] LIMITS=System.getProperty("ntmgunfeel.benchLimit","0").split(",");
    private static final double LIMIT=Double.parseDouble(LIMITS[0]),WALK_LIMIT=Double.parseDouble(LIMITS[LIMITS.length-1]);
    private MobBenchmark() {}

    static void debug(int part,long started) { if(MobBenchMetrics.ENABLED) debugNanos[part]+=System.nanoTime()-started; }

    static int count(String scene) { return Integer.parseInt(scene.substring(3).replace("w","")); }
    static boolean walks(String scene) { return scene.endsWith("w"); }
    // the server's walkers and where each stands; the tick they walk by
    private static volatile List<LivingEntity> walkers=List.of();
    private static volatile double[] homes=new double[0];
    private static boolean listening;
    private static int walkTick;
    static String type(String scene,int i) { String[] types=scene.startsWith("ntm")?NTM:VANILLA; return types[i%types.length]; }

    static void start() {
        var mc=Minecraft.getInstance();
        mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false);
        mc.options.pauseOnLostFocus=false; mc.options.fov().set(70);
        for(String s:System.getProperty("ntmgunfeel.benchScenes","ntm1,ntm10,ntm30,van1,van10,van30").split(",")) {
            if(!s.matches("(ntm|van)[0-9]+w?")) { failure="сцена "+s; return; }
            for(int pass=0;pass<2;pass++) for(int j=0;j<4;j++) {
                int v=pass==0?j:3-j;
                PLAN.add(new Phase(s,pass,v%2,v>=2));
            }
        }
        ROWS.add("сцена\tповтор\tсистема\tF3B\tкадров\tсекунд\tFPS\tсредний_мс\tмедиана_мс\tp95_мс\tp99_мс\tаллокаций_байт_кадр"
                +"\tGC_за_фазу\tGC_мс\tзахват_мс\tсеть_мс\tконтур_мс\tзоны_мс\tлинии_мс\tсущностей_клиент\tпубликаций_кадр\tобводок_кадр");
        org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info("[mobbench] окно={}x{} расстояние={} FPS={} vsync={} камера=70 flywheel={}",
                mc.getMainRenderTarget().width,mc.getMainRenderTarget().height,
                mc.options.renderDistance().get(),mc.options.framerateLimit().get(),mc.options.enableVsync().get(),
                dev.engine_room.flywheel.api.backend.BackendManager.currentBackend());
        next();
    }

    private static void next() {
        if(++index==PLAN.size()) {
            MobBenchMetrics.mode=-1;
            write();
            ClientSmoke.check(true,"замер завершён, фаз="+PLAN.size());
            for(var e:MEANS.entrySet()) {
                double[] m=e.getValue();
                // kinds: 0 off, 1 on, 2 off with F3+B (vanilla's boxes), 3 on with F3+B
                double vanilla=(m[2]-m[0])/2,ours=(m[3]-m[1])/2;
                String text=String.format(Locale.ROOT,"%s: F3+B ванили +%.3f мс, наш +%.3f мс к кадру",e.getKey(),vanilla,ours);
                double limit=e.getKey().endsWith("w")?WALK_LIMIT:LIMIT;
                ClientSmoke.check(limit<=0 || ours-vanilla<=limit,limit>0?text+String.format(Locale.ROOT," (предел +%.2f мс к ванили)",limit):text);
            }
            ClientSmoke.finish(); return;
        }
        var phase=PLAN.get(index);
        MobBenchMetrics.mode=phase.mode();
        var mc=Minecraft.getInstance();
        mc.getEntityRenderDispatcher().setRenderHitBoxes(phase.debug());
        if(!phase.scene().equals(scene)) {
            scene=phase.scene(); pending=true;
            String placed=scene;
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var server=mc.getSingleplayerServer(); var level=server.overworld();
                    level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
                    level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
                    var player=server.getPlayerList().getPlayers().getFirst();
                    MobStageCleanup.clear(level,player); player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().flying=true; player.onUpdateAbilities();
                    int count=count(placed),columns=Math.min(count,6);
                    var walking=new ArrayList<LivingEntity>(); var at=new double[count];
                    if(!listening) {
                        listening=true;
                        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre.class,e -> walk());
                    }
                    for(int i=0;i<count;i++) {
                        String type=type(placed,i);
                        var created=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(type)).create(level);
                        if(!(created instanceof LivingEntity living)) throw new IllegalStateException(type);
                        double x=(i%columns-(columns-1)/2.0)*3,z=10+i/columns*3;
                        living.setPos(x,100,z); living.setNoGravity(true); living.setInvulnerable(true);
                        living.setYRot(180); living.setYBodyRot(180); living.setYHeadRot(180);
                        if(living instanceof net.minecraft.world.entity.monster.Slime slime) slime.setSize(1,true);
                        if(living instanceof Mob mob) { mob.setNoAi(true); mob.setPersistenceRequired(); }
                        level.addFreshEntity(living);
                        if(walks(placed)) { at[walking.size()]=x; walking.add(living); }
                    }
                    homes=at; walkers=List.copyOf(walking);
                    player.teleportTo(level,0,101,count>6?-8:2,Set.of(),0,count>6?15:10);
                } catch(Throwable t) { failure=t.toString(); }
                since=System.nanoTime(); pending=false;
            });
        } else since=System.nanoTime();
        last=measuring=0; samples=0;
        org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info("[mobbench] фаза {}/{} {} система={} F3B={} повтор={}",
                index+1,PLAN.size(),phase.scene(),phase.mode(),phase.debug(),phase.pass());
    }

    // server thread: each walker 0.6 to either side of its place, 0.19 a tick at most (vanilla's walk animation runs
    // on the client from the position's change)
    private static void walk() {
        var list=walkers; var at=homes; walkTick++;
        for(int i=0;i<list.size();i++) {
            var mob=list.get(i);
            if(!mob.isRemoved()) mob.setPos(at[i]+.6*Math.sin(walkTick*.32),mob.getY(),mob.getZ());
        }
    }

    private static long gcCount() { long n=0; for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) n+=Math.max(0,gc.getCollectionCount()); return n; }
    private static long gcTime() { long n=0; for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) n+=Math.max(0,gc.getCollectionTime()); return n; }

    static void frame() {
        if(pending) return;
        if(failure!=null) { ClientSmoke.check(false,"замер: "+failure); ClientSmoke.finish(); return; }
        long now=System.nanoTime();
        if(measuring==0) {
            double warm=index==0?Math.max(WARM,6):WARM;
            if((now-since)/1e9<warm) return;
            measuring=last=now; samples=0;
            allocationStart=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
            publishStart=MobBenchMetrics.published;
            gcCountStart=gcCount(); gcTimeStart=gcTime();
            System.arraycopy(MobBenchMetrics.nanos,0,costs,0,4);
            System.arraycopy(debugNanos,0,debugStart,0,3); targetStart=debugTargets;
            return;
        }
        if(samples<TIMES.length) TIMES[samples++]=now-last;
        last=now;
        if((now-measuring)/1e9<MEASURE) return;
        double seconds=(now-measuring)/1e9;
        long allocation=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocationStart;
        long[] sorted=Arrays.copyOf(TIMES,samples); Arrays.sort(sorted);
        var p=PLAN.get(index);
        double mean=seconds*1000/samples;
        String row=String.format(Locale.ROOT,"%s\t%d\t%d\t%s\t%d\t%.4f\t%.2f\t%.5f\t%.5f\t%.5f\t%.5f\t%.0f\t%d\t%d\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f",
                p.scene(),p.pass(),p.mode(),p.debug(),samples,seconds,samples/seconds,mean,
                sorted[samples/2]/1e6,sorted[(int)((samples-1)*.95)]/1e6,sorted[(int)((samples-1)*.99)]/1e6,
                allocation/(double)samples,gcCount()-gcCountStart,gcTime()-gcTimeStart,
                (MobBenchMetrics.nanos[0]-costs[0])/1e6/samples,(MobBenchMetrics.nanos[1]-costs[1])/1e6/samples,
                (debugNanos[CONTOUR]-debugStart[CONTOUR])/1e6/samples,(debugNanos[ZONES]-debugStart[ZONES])/1e6/samples,
                (debugNanos[LINES]-debugStart[LINES])/1e6/samples);
        int alive=0;
        for(var entity:Minecraft.getInstance().level.entitiesForRendering())
            if(entity instanceof LivingEntity && !(entity instanceof net.minecraft.world.entity.player.Player)) alive++;
        int expected=count(p.scene());
        if(alive!=expected) { failure="Ожидалось сущностей "+expected+", клиент видит "+alive; return; }
        row+=String.format(Locale.ROOT,"\t%d\t%.2f\t%.2f",alive,(MobBenchMetrics.published-publishStart)/(double)samples,
                (debugTargets-targetStart)/(double)samples);
        ROWS.add(row); org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info("[mobbench] {}",row);
        MEANS.computeIfAbsent(p.scene(),k -> new double[4])[p.mode()+(p.debug()?2:0)]+=mean;
        write();
        next();
    }

    private static void write() {
        try { Files.write(Path.of(System.getProperty("ntmgunfeel.benchFile")),ROWS); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
