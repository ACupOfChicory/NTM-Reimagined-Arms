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
final class MobBenchmark {
    private record Phase(int scene,int pass,int mode,boolean debug) {}
    private static final String[] SCENES={"корабль_в_кадре","корабль_за_камерой","смешанные_64","корабли_8"};
    private static final String[] TYPES={"minecraft:skeleton","minecraft:zombie","minecraft:sheep","minecraft:slime",
            "hbm:entity_glyphid","hbm:entity_cyber_crab","hbm:entity_pigeon","hbm:entity_mob_mask_man"};
    private static final List<Phase> PLAN=new ArrayList<>();
    private static final List<String> ROWS=new ArrayList<>();
    private static final long[] TIMES=new long[100_000];
    private static final com.sun.management.ThreadMXBean THREADS=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static int index=-1,scene=-1,samples;
    private static volatile boolean pending;
    private static volatile String failure;
    private static long since,last,measuring,allocationStart,bytesStart,packetsStart;
    private static long publishStart,shapeStart;
    private static final long[] costs=new long[4];
    private static final double WARM=Double.parseDouble(System.getProperty("ntmgunfeel.benchWarm","2"));
    private static final double MEASURE=Double.parseDouble(System.getProperty("ntmgunfeel.benchSeconds","4"));
    private MobBenchmark() {}
    static void start() {
        var mc=Minecraft.getInstance();
        mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false);
        mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        mc.options.pauseOnLostFocus=false; mc.options.fov().set(70);
        Set<String> selected=Set.of(System.getProperty("ntmgunfeel.benchScenes","0,1,2,3").split(","));
        for(int s=0;s<SCENES.length;s++) if(selected.contains(Integer.toString(s))) for(int pass=0;pass<2;pass++) for(int j=0;j<4;j++) {
            int v=pass==0?j:3-j;
            PLAN.add(new Phase(s,pass,v%2,v>=2));
        }
        ROWS.add("сцена\tповтор\tсистема\tF3B\tкадров\tсекунд\tFPS\tсредний_мс\tмедиана_мс\tp95_мс\tp99_мс\tаллокаций_байт_кадр\tзахват_мс\tсеть_мс\tконтур_мс\tрисование_мс\tбайт_с\tпакетов_с\tсущностей_клиент\tпубликаций_кадр\tобводок_кадр");
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
            ClientSmoke.check(true,"замер завершён, фаз="+PLAN.size()); ClientSmoke.finish(); return;
        }
        var phase=PLAN.get(index);
        MobBenchMetrics.mode=phase.mode();
        var mc=Minecraft.getInstance();
        mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES,
                phase.debug()?DebugScreenEntryStatus.ALWAYS_ON:DebugScreenEntryStatus.NEVER);
        if(scene!=phase.scene()) {
            scene=phase.scene(); pending=true;
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
                    int count=scene<2?1:scene==2?64:8;
                    for(int i=0;i<count;i++) {
                        String type=scene==2?TYPES[i%TYPES.length]:"hbm:entity_ufo";
                        var created=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(type)).create(level,EntitySpawnReason.COMMAND);
                        if(!(created instanceof LivingEntity living)) throw new IllegalStateException(type);
                        double x=scene<2?0:scene==2?(i%8-3.5)*2.4:(i%4-1.5)*24;
                        double z=scene<2?10:scene==2?i/8*2.4:i/4*30+8;
                        living.setPos(x,100,z); living.setNoGravity(true); living.setInvulnerable(true);
                        if(living instanceof net.minecraft.world.entity.monster.cubemob.Slime slime) slime.setSize(1,true);
                        if(living instanceof Mob mob) { mob.setNoAi(true); mob.setPersistenceRequired(); }
                        level.addFreshEntity(living);
                    }
                    double z=scene==2?-18:scene==3?-40:-16;
                    player.teleportTo(level,0,101,z,Set.of(),scene==1?180:0,0,false);
                } catch(Throwable t) { failure=t.toString(); }
                since=System.nanoTime(); pending=false;
            });
        } else since=System.nanoTime();
        last=measuring=0; samples=0;
        org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info("[mobbench] фаза {}/{} {} система={} F3B={} повтор={}",
                index+1,PLAN.size(),SCENES[phase.scene()],phase.mode(),phase.debug(),phase.pass());
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
            publishStart=MobBenchMetrics.published; shapeStart=MobBenchMetrics.shapes;
            System.arraycopy(MobBenchMetrics.nanos,0,costs,0,4); return;
        }
        if(samples<TIMES.length) TIMES[samples++]=now-last;
        last=now;
        if((now-measuring)/1e9<MEASURE) return;
        double seconds=(now-measuring)/1e9;
        long allocation=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocationStart;
        long[] sorted=Arrays.copyOf(TIMES,samples); Arrays.sort(sorted);
        var p=PLAN.get(index);
        String row=String.format(Locale.ROOT,"%s\t%d\t%d\t%s\t%d\t%.4f\t%.2f\t%.5f\t%.5f\t%.5f\t%.5f\t%.0f\t%.5f\t%.5f\t%.5f\t%.5f\t%.0f\t%.2f",
                SCENES[p.scene()],p.pass(),p.mode(),p.debug(),samples,seconds,samples/seconds,seconds*1000/samples,
                sorted[samples/2]/1e6,sorted[(int)((samples-1)*.95)]/1e6,sorted[(int)((samples-1)*.99)]/1e6,
                allocation/(double)samples,(MobBenchMetrics.nanos[0]-costs[0])/1e6/samples,
                (MobBenchMetrics.nanos[1]-costs[1])/1e6/samples,(MobBenchMetrics.nanos[2]-costs[2])/1e6/samples,
                (MobBenchMetrics.nanos[3]-costs[3])/1e6/samples,(MobBenchMetrics.bytes.sum()-bytesStart)/seconds,
                (MobBenchMetrics.packets.sum()-packetsStart)/seconds);
        int alive=0;
        for(var entity:Minecraft.getInstance().level.entitiesForRendering())
            if(entity instanceof LivingEntity && !(entity instanceof net.minecraft.world.entity.player.Player)) alive++;
        int expected=p.scene()<2?1:p.scene()==2?64:8;
        if(alive!=expected) { failure="Ожидалось сущностей "+expected+", клиент видит "+alive; return; }
        row+=String.format(Locale.ROOT,"\t%d\t%.2f\t%.2f",alive,(MobBenchMetrics.published-publishStart)/(double)samples,
                (MobBenchMetrics.shapes-shapeStart)/(double)samples);
        ROWS.add(row); org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info("[mobbench] {}",row);
        try { Files.write(Path.of(System.getProperty("ntmgunfeel.benchFile")),ROWS); }
        catch(Exception e) { throw new IllegalStateException(e); }
        next();
    }
}
