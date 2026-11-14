package acup.gunfeel;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;

// Одинаковая сцена, ABBA, прогрев каждого режима; пробы попаданий и обзор
// отключены. Сохраняет сырые фазы, распределение кадра, аллокации и трафик.
// Сцены (-PmobBenchScenes): номера 0–3 из SCENES или имена (ntm|van)N[w] —
// N мобов NTM или ванили сеткой перед камерой; с w сервер водит каждого
// туда-обратно, поза новая каждый кадр.
// -PmobBenchLimit=<мс>[,<мс ходьбы>]: сцена падает, если наш F3+B добавляет
// к среднему кадру больше предела сверх ванильного F3+B (оба прохода ABBA).
// -PmobBenchFps=<n>: предел FPS (по умолчанию 260, то есть без предела).
// -PcontourAge: на фазах «система вкл, F3+B вкл» ContourAge считает точки
// контура старше правила; сцена падает, если такие есть. Время кадра в этом
// прогоне не мерить.
final class MobBenchmark {
    private record Phase(String scene,int pass,int mode,boolean debug) {}
    private static final String[] SCENES={"корабль_в_кадре","корабль_за_камерой","смешанные_64","корабли_8"};
    private static final String[] NTM={"hbm:entity_glyphid","hbm:entity_cyber_crab","hbm:entity_pigeon","hbm:entity_mob_mask_man"};
    private static final String[] VANILLA={"minecraft:skeleton","minecraft:zombie","minecraft:sheep","minecraft:slime"};
    private static final String[] TYPES={"minecraft:skeleton","minecraft:zombie","minecraft:sheep","minecraft:slime",
            "hbm:entity_glyphid","hbm:entity_cyber_crab","hbm:entity_pigeon","hbm:entity_mob_mask_man"};
    private static final List<Phase> PLAN=new ArrayList<>();
    private static final List<String> ROWS=new ArrayList<>();
    private static final long[] TIMES=new long[100_000];
    private static final com.sun.management.OperatingSystemMXBean SYSTEM=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    private static final com.sun.management.ThreadMXBean THREADS=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static int index=-1,samples;
    private static String scene;
    // средний кадр по сцене и виду фазы (mode+2*debug), сумма двух проходов
    private static final Map<String,double[]> MEANS=new LinkedHashMap<>();
    // счёт ContourAge по сцене: точек, на модели кадра, на прошлой или моложе порога, старше
    private static final Map<String,long[]> AGES=new LinkedHashMap<>();
    private static final double[] LIMIT=limit(System.getProperty("ntmgunfeel.benchLimit",""));
    // ходоки сервера и их места; тик, по которому они ходят
    private static volatile List<LivingEntity> walkers=List.of();
    private static volatile double[] homes=new double[0];
    private static boolean listening;
    private static int walkTick;
    private static volatile boolean pending;
    private static volatile String failure;
    private static long since,last,measuring,allocationStart,bytesStart,packetsStart;
    private static long publishStart,shapeStart,cpuStart;
    private static final long[] costs=new long[4];
    private static final double WARM=2;
    private static final double MEASURE=4;
    private MobBenchmark() {}
    private static double[] limit(String text) {
        if(text.isBlank()) return new double[]{0,0};
        var parts=text.split(",");
        double still=Double.parseDouble(parts[0]);
        return new double[]{still,parts.length>1?Double.parseDouble(parts[1]):still};
    }
    private static boolean numbered(String scene) { return scene.matches("[0-3]"); }
    static String label(String scene) { return numbered(scene)?SCENES[Integer.parseInt(scene)]:scene; }
    static int count(String scene) {
        if(!numbered(scene)) return Integer.parseInt(scene.substring(3).replace("w",""));
        int n=Integer.parseInt(scene);
        return n<2?1:n==2?64:8;
    }
    static boolean walks(String scene) { return scene.endsWith("w"); }
    static void start() {
        var mc=Minecraft.getInstance();
        mc.options.framerateLimit().set(Integer.getInteger("ntmgunfeel.benchFps",260)); mc.options.enableVsync().set(false);
        mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        mc.options.pauseOnLostFocus=false; mc.options.fov().set(70);
        for(String s:System.getProperty("ntmgunfeel.benchScenes","0,1,2,3").split(",")) {
            if(!numbered(s) && !s.matches("(ntm|van)[1-9][0-9]*w?")) { failure="сцена "+s; return; }
            for(int pass=0;pass<2;pass++) for(int j=0;j<4;j++) {
                int v=pass==0?j:3-j;
                PLAN.add(new Phase(s,pass,v%2,v>=2));
            }
        }
        ROWS.add("сцена\tповтор\tсистема\tF3B\tкадров\tсекунд\tFPS\tсредний_мс\tмедиана_мс\tp95_мс\tp99_мс\tаллокаций_байт_кадр\tзахват_мс\tсеть_мс\tконтур_мс\tрисование_мс\tбайт_с\tпакетов_с\tсущностей_клиент\tпубликаций_кадр\tобводок_кадр\tпроцессор_ядер");
        org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info("[mobbench] окно={}x{} расстояние={} FPS={} vsync={} камера=70",
                mc.gameRenderer.mainRenderTarget().width,mc.gameRenderer.mainRenderTarget().height,
                mc.options.renderDistance().get(),mc.options.framerateLimit().get(),mc.options.enableVsync().get());
        next();
    }
    private static void next() {
        if(++index==PLAN.size()) {
            MobBenchMetrics.mode=-1;
            try { Files.write(Path.of(System.getProperty("ntmgunfeel.benchFile")),ROWS); }
            catch(Exception e) { ClientSmoke.check(false,"запись замера: "+e); }
            ClientSmoke.check(true,"замер завершён, фаз="+PLAN.size());
            report(); ClientSmoke.finish(); return;
        }
        var phase=PLAN.get(index);
        MobBenchMetrics.mode=phase.mode();
        var mc=Minecraft.getInstance();
        mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES,
                phase.debug()?DebugScreenEntryStatus.ALWAYS_ON:DebugScreenEntryStatus.NEVER);
        if(!phase.scene().equals(scene)) {
            scene=phase.scene(); pending=true;
            String placed=scene;
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var server=mc.getSingleplayerServer(); var level=server.overworld();
                    level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false,server);
                    level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWNER_BLOCKS_WORK,false,server);
                    level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false,server);
                    level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false,server);
                    var player=server.getPlayerList().getPlayers().getFirst();
                    MobStageCleanup.clear(level,player); player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().flying=true; player.onUpdateAbilities();
                    int count=count(placed),columns=Math.min(count,6);
                    boolean named=!numbered(placed); int n=named?-1:Integer.parseInt(placed);
                    var walking=new ArrayList<LivingEntity>(); var at=new double[count];
                    if(!listening) {
                        listening=true;
                        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre.class,e -> walk());
                    }
                    for(int i=0;i<count;i++) {
                        String type=named?(placed.startsWith("ntm")?NTM:VANILLA)[i%4]:n==2?TYPES[i%TYPES.length]:"hbm:entity_ufo";
                        var created=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(type)).create(level,EntitySpawnReason.COMMAND);
                        if(!(created instanceof LivingEntity living)) throw new IllegalStateException(type);
                        double x=named?(i%columns-(columns-1)/2.0)*3:n<2?0:n==2?(i%8-3.5)*2.4:(i%4-1.5)*24;
                        double z=named?10+i/columns*3:n<2?10:n==2?i/8*2.4:i/4*30+8;
                        living.setPos(x,100,z); living.setNoGravity(true); living.setInvulnerable(true);
                        if(named) { living.setYRot(180); living.setYBodyRot(180); living.setYHeadRot(180); }
                        if(living instanceof net.minecraft.world.entity.monster.cubemob.Slime slime) slime.setSize(1,true);
                        if(living instanceof Mob mob) { mob.setNoAi(true); mob.setPersistenceRequired(); }
                        level.addFreshEntity(living);
                        if(walks(placed)) { at[walking.size()]=x; walking.add(living); }
                    }
                    homes=at; walkers=List.copyOf(walking);
                    if(named) player.teleportTo(level,0,101,count>6?-8:2,Set.of(),0,count>6?15:10,false);
                    else player.teleportTo(level,0,101,n==2?-18:n==3?-40:-16,Set.of(),n==1?180:0,0,false);
                } catch(Throwable t) { failure=t.toString(); }
                since=System.nanoTime(); pending=false;
            });
        } else since=System.nanoTime();
        last=measuring=0; samples=0;
        org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info("[mobbench] фаза {}/{} {} система={} F3B={} повтор={}",
                index+1,PLAN.size(),label(phase.scene()),phase.mode(),phase.debug(),phase.pass());
    }
    // поток сервера: каждый ходок на 0,6 в обе стороны от своего места, не больше 0,19 за тик (ванильная анимация
    // ходьбы идёт на клиенте от смены позиции)
    private static void walk() {
        var list=walkers; var at=homes; walkTick++;
        for(int i=0;i<list.size();i++) {
            var mob=list.get(i);
            if(!mob.isRemoved()) mob.setPos(at[i]+.6*Math.sin(walkTick*.32),mob.getY(),mob.getZ());
        }
    }
    private static void report() {
        for(var e:MEANS.entrySet()) {
            double[] m=e.getValue();
            // виды: 0 выкл, 1 вкл, 2 выкл с F3+B (ванильные рамки), 3 вкл с F3+B
            double vanilla=(m[2]-m[0])/2,ours=(m[3]-m[1])/2,limit=LIMIT[walks(e.getKey())?1:0];
            String text=String.format(Locale.ROOT,"%s: F3+B ванили +%.3f мс, наш +%.3f мс к кадру",label(e.getKey()),vanilla,ours);
            ClientSmoke.check(limit<=0 || ours-vanilla<=limit,limit>0?text+String.format(Locale.ROOT," (предел +%.2f мс к ванили)",limit):text);
        }
        for(var e:AGES.entrySet()) {
            long[] a=e.getValue();
            ClientSmoke.check(a[0]>0 && a[3]==0,String.format(Locale.ROOT,
                    "%s: возраст контура (модель прошлого кадра или моложе %.1f мс): точек %d, на модели кадра %d, на прошлой или моложе порога %d, старше %d",
                    label(e.getKey()),ModelContours.LAG/1e6,a[0],a[1],a[2],a[3]));
        }
    }
    static void frame() {
        if(pending) return;
        if(failure!=null) { ClientSmoke.check(false,"замер: "+failure); ClientSmoke.finish(); return; }
        long now=System.nanoTime();
        if(measuring==0) {
            double warm=index==0?Math.max(WARM,6):WARM;
            if((now-since)/1e9<warm) return;
            measuring=last=now; samples=0;
            allocationStart=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
            bytesStart=MobBenchMetrics.bytes.sum(); packetsStart=MobBenchMetrics.packets.sum();
            publishStart=MobBenchMetrics.published; shapeStart=MobBenchMetrics.shapes; cpuStart=SYSTEM.getProcessCpuTime();
            System.arraycopy(MobBenchMetrics.nanos,0,costs,0,4); ContourAge.reset(); return;
        }
        if(samples<TIMES.length) TIMES[samples++]=now-last;
        last=now;
        if((now-measuring)/1e9<MEASURE) return;
        double seconds=(now-measuring)/1e9;
        long allocation=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocationStart;
        long[] sorted=Arrays.copyOf(TIMES,samples); Arrays.sort(sorted);
        var p=PLAN.get(index);
        String row=String.format(Locale.ROOT,"%s\t%d\t%d\t%s\t%d\t%.4f\t%.2f\t%.5f\t%.5f\t%.5f\t%.5f\t%.0f\t%.5f\t%.5f\t%.5f\t%.5f\t%.0f\t%.2f",
                label(p.scene()),p.pass(),p.mode(),p.debug(),samples,seconds,samples/seconds,seconds*1000/samples,
                sorted[samples/2]/1e6,sorted[(int)((samples-1)*.95)]/1e6,sorted[(int)((samples-1)*.99)]/1e6,
                allocation/(double)samples,(MobBenchMetrics.nanos[0]-costs[0])/1e6/samples,
                (MobBenchMetrics.nanos[1]-costs[1])/1e6/samples,(MobBenchMetrics.nanos[2]-costs[2])/1e6/samples,
                (MobBenchMetrics.nanos[3]-costs[3])/1e6/samples,(MobBenchMetrics.bytes.sum()-bytesStart)/seconds,
                (MobBenchMetrics.packets.sum()-packetsStart)/seconds);
        int alive=0;
        for(var entity:Minecraft.getInstance().level.entitiesForRendering())
            if(entity instanceof LivingEntity && !(entity instanceof net.minecraft.world.entity.player.Player)) alive++;
        int expected=count(p.scene());
        if(alive!=expected) { failure="Ожидалось сущностей "+expected+", клиент видит "+alive; return; }
        // процессор всего процесса (клиент, сервер, фоновые сборки) — в среднем ядер за фазу
        row+=String.format(Locale.ROOT,"\t%d\t%.2f\t%.2f\t%.2f",alive,(MobBenchMetrics.published-publishStart)/(double)samples,
                (MobBenchMetrics.shapes-shapeStart)/(double)samples,(SYSTEM.getProcessCpuTime()-cpuStart)/1e9/seconds);
        MEANS.computeIfAbsent(p.scene(),k -> new double[4])[p.mode()+(p.debug()?2:0)]+=seconds*1000/samples;
        if(ContourAge.ENABLED && p.mode()==1 && p.debug()) {
            long[] a=AGES.computeIfAbsent(p.scene(),k -> new long[4]);
            a[0]+=ContourAge.points; a[1]+=ContourAge.now; a[2]+=ContourAge.earlier; a[3]+=ContourAge.older;
        }
        ROWS.add(row); org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info("[mobbench] {}",row);
        try { Files.write(Path.of(System.getProperty("ntmgunfeel.benchFile")),ROWS); }
        catch(Exception e) { throw new IllegalStateException(e); }
        next();
    }
}
