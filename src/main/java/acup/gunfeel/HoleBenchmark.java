package acup.gunfeel;

import java.nio.file.*;
import java.util.*;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory10ga;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

// Настоящий Heretic стреляет штатным обработчиком NTM каждые три тика.
// Отдельные выключения эффектов нужны для поиска причины, не для выпуска.
final class HoleBenchmark {
    private record Phase(String surface,int mode,int repeat) {}
    private static final String[] MODES={"обычный","разрывной","без_дырок","без_частиц_взрыва","малый_след","разрывной_старый_путь"};
    private static final List<Phase> PLAN=new ArrayList<>();
    private static final List<String> ROWS=new ArrayList<>();
    private static final long[] TIMES=new long[200_000],COSTS=new long[17];
    private static final int SHELLS=Integer.getInteger("ntmgunfeel.holeBenchShells",20);
    private static final boolean LOADED=Boolean.getBoolean("ntmgunfeel.holeBenchLoaded");
    private static final String[] ORIGIN=System.getProperty("ntmgunfeel.holeBenchOrigin","0,100,0").split(",");
    private static final int OX=Integer.parseInt(ORIGIN[0]),OY=Integer.parseInt(ORIGIN[1]),OZ=Integer.parseInt(ORIGIN[2]);
    private static final com.sun.management.ThreadMXBean THREADS=(com.sun.management.ThreadMXBean)
            java.lang.management.ManagementFactory.getThreadMXBean();
    private static int index=-1,samples,maxPending;
    private static volatile int fired,ticks;
    private static volatile boolean pending,shooting;
    private static volatile String failure;
    private static volatile long readyAt,lastShot;
    private static long started,last,allocated,projectiles,explosions;
    private static float scorch;
    private static volatile ItemStack gunStack;
    private static volatile boolean opened,closed;
    private HoleBenchmark() {}
    static void start() {
        var mc=Minecraft.getInstance();
        mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false);
        mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        mc.options.pauseOnLostFocus=false; mc.options.fov().set(70);
        mc.debugEntries.setStatus(net.minecraft.client.gui.components.debug.DebugScreenEntries.ENTITY_HITBOXES,
                net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.NEVER);
        scorch=Config.scorchSize;
        if(Boolean.getBoolean("ntmgunfeel.holeBenchRealism")) {
            Config.holeMax=2500; Config.holeMaxMachines=750; Config.holeLife=150; Config.quality="realism";
        }
        int repeats=Integer.getInteger("ntmgunfeel.holeBenchRepeats",1);
        for(String surface:System.getProperty("ntmgunfeel.holeBenchSurfaces","stone,door").split(",")) {
            if(!HoleBenchMetrics.CHECK_MOTION) { PLAN.add(new Phase(surface,System.getProperty("ntmgunfeel.holeBenchModes","").contains("5")?5:0,-1)); PLAN.add(new Phase(surface,1,-1)); }
            var selected=Arrays.stream(System.getProperty("ntmgunfeel.holeBenchModes","0,1,2,3,4").split(",")).mapToInt(Integer::parseInt).toArray();
            for(int r=0;r<repeats;r++) for(int j=0;j<selected.length;j++) PLAN.add(new Phase(surface,selected[r%2==0?j:selected.length-1-j],r));
        }
        ROWS.add("поверхность\tрежим\tповтор\tвыстрелов\tснарядов\tвзрывов_частиц\tкадров\tсекунд\tсредний_мс\tp95_мс\tp99_мс\tmax_мс\tаллокаций_байт_кадр\tдырки\tочередь_max\tдырка_max_мс\tсоздание_мс\tрисование_мс\tзапекание_мс\tследование_мс\tпроверки_мс\tчастицы_tick_мс\tчастицы_extract_мс\tчастицы_submit_мс\tFlywheel_frame_мс\tFlywheel_draw_мс");
        next();
    }
    private static BlockPos at(int x,int y,int z) { return new BlockPos(OX+x,OY+y-100,OZ+z); }
    static void applyMode() {
        if(index<0 || index>=PLAN.size()) return;
        int mode=PLAN.get(index).mode();
        Config.bulletHoles=mode!=2; Config.scorchSize=mode==4?Config.holeSize:scorch;
    }
    static boolean wantPixelProbe() {
        return HoleBenchMetrics.CHECK_MOTION && index>=0 && PLAN.get(index).repeat()>=0 && fired>=SHELLS
                && Tracers.pendingHoles()==0 && Minecraft.getInstance().gameRenderer.mainCamera().position().z>OZ+7;
    }
    private static void next() {
        shooting=false;
        if(++index==PLAN.size()) { ClientSmoke.check(true,"замер дырок завершён, фаз="+PLAN.size()); ClientSmoke.finish(); return; }
        var phase=PLAN.get(index); var mc=Minecraft.getInstance();
        HoleBenchMetrics.phaseLegacy=phase.mode()==5;
        HoleBenchWarmup.reset(index==0);
        Config.bulletHoles=phase.mode()!=2;
        Config.scorchSize=phase.mode()==4?Config.holeSize:scorch;
        HoleBenchMetrics.omitBlast=phase.mode()==3;
        Tracers.clear(); mc.particleEngine.clearParticles();
        pending=true; started=last=0; fired=ticks=samples=maxPending=0; lastShot=0; opened=closed=false;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var server=mc.getSingleplayerServer(); var level=server.overworld();
                level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false,server);
                level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false,server);
                level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false,server);
                var player=server.getPlayerList().getPlayers().getFirst();
                if(!LOADED) MobStageCleanup.clear(level,player);
                else for(var entity:level.getAllEntities()) if(entity instanceof net.minecraft.world.entity.item.ItemEntity) entity.discard();
                player.setGameMode(GameType.CREATIVE);
                player.teleportTo(level,OX+.5,OY,OZ+.5,Set.of(),0,0,false);
                com.hbm.blocks.multiblock.BlockMultiblockCore.withoutTeardown(() -> {
                    for(int x=-6;x<=6;x++) for(int z=-2;z<=22;z++) for(int y=99;y<=111;y++)
                        level.setBlock(at(x,y,z),(y==99?Blocks.BEDROCK:Blocks.AIR).defaultBlockState(),3);
                });
                if(!phase.surface().equals("stone")) {
                    var item=BuiltInRegistries.ITEM.getValue(Identifier.parse(phase.surface().equals("blast")?"hbm:sliding_blast_door":"hbm:secure_access_door"));
                    var placement=new ItemStack(item); player.setItemInHand(InteractionHand.MAIN_HAND,placement);
                    var target=at(0,99,12);
                    var result=placement.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,
                            new BlockHitResult(new net.minecraft.world.phys.Vec3(OX+.5,OY,OZ+12.5),Direction.UP,target,false)));
                    if(level.getBlockEntity(target.above())==null) throw new IllegalStateException("Дверь не установлена: "+result);
                } else {
                    for(int x=-6;x<=6;x++) for(int y=100;y<=106;y++) level.setBlock(at(x,y,12),Blocks.STONE.defaultBlockState(),3);
                }
                ItemStack stack=new ItemStack(com.hbm.items.ModItems.GUN_AUTOSHOTGUN_HERETIC.get());
                var gun=(ItemGunBaseNT)stack.getItem(); var receiver=gun.getConfig(stack,0).getReceivers(stack)[0];
                var magazine=receiver.getMagazine(stack);
                magazine.setType(stack,phase.mode()==0?XFactory10ga.g10:XFactory10ga.g10_explosive); magazine.setAmount(stack,250);
                ItemGunBaseNT.setIsDrawn(stack,true); ItemGunBaseNT.setIsEquipped(stack,true);
                ItemGunBaseNT.setState(stack,0,ItemGunBaseNT.GunState.IDLE); ItemGunBaseNT.setTimer(stack,0,0);
                player.getInventory().setSelectedSlot(0); player.setItemInHand(InteractionHand.MAIN_HAND,stack);
                gunStack=stack;
                player.getRandom().setSeed(71294);
                Rng.seed(71294);
                readyAt=System.nanoTime();
            } catch(Throwable t) { failure=t.toString(); }
            pending=false;
        });
        GunFeel.LOGGER.info("[holebench] фаза {}/{} {} {} повтор={}",index+1,PLAN.size(),phase.surface(),MODES[phase.mode()],phase.repeat());
    }
    static void tick() {
        if(shooting && fired>=SHELLS && HoleBenchMetrics.CHECK_MOTION && PLAN.get(index).repeat()>=0) {
            double age=(System.nanoTime()-lastShot)/1e9;
            if(!opened && age>.5 && HoleVisualProbe.finished || opened && !closed && age>8) {
                var level=Minecraft.getInstance().getSingleplayerServer().overworld();
                if(level.getBlockEntity(at(0,100,12)) instanceof com.hbm.blockentity.BlockEntityDoorGeneric door) door.tryToggle(null);
                if(!opened) opened=true; else closed=true;
            }
        }
        if(!shooting || fired>=SHELLS || ++ticks%3!=0) return;
        try {
            var player=Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayers().getFirst();
            player.getInventory().setSelectedSlot(0);
            if(player.getMainHandItem()!=gunStack) player.setItemInHand(InteractionHand.MAIN_HAND,gunStack);
            player.setYRot(0); player.setXRot(0);
            var stack=player.getMainHandItem(); var gun=(ItemGunBaseNT)stack.getItem(); var config=gun.getConfig(stack,0);
            config.getReceivers(stack)[0].getOnFire(stack).accept(stack,new ItemGunBaseNT.LambdaContext(config,player,player.getInventory(),0));
            fired++; lastShot=System.nanoTime();
            if(HoleBenchMetrics.CHECK_MOTION && fired==SHELLS && PLAN.get(index).repeat()>=0)
                player.teleportTo(player.level(),OX+.5,OY,OZ+8.5,Set.of(),0,0,false);
        } catch(Throwable t) { failure="стрельба: "+t; shooting=false; }
    }
    static void frame() {
        if(pending) return;
        if(HoleBenchMetrics.failure!=null) failure=HoleBenchMetrics.failure;
        if(failure!=null) { ClientSmoke.check(false,failure); ClientSmoke.finish(); return; }
        long now=System.nanoTime();
        var mc=Minecraft.getInstance(); mc.player.getInventory().setSelectedSlot(0); mc.player.setYRot(0); mc.player.setXRot(0);
        if(started==0) {
            if(LOADED) { if(!HoleBenchWarmup.ready(now,readyAt)) return; }
            else if(now-readyAt<3_000_000_000L) return;
            started=last=now; allocated=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
            GunFeel.LOGGER.info("[holebench] условия окно={}x{} цель={}x{} качество={} лимит_дверей={} жизнь={} след={} оружие={}",
                    mc.getWindow().getWidth(),mc.getWindow().getHeight(),mc.gameRenderer.mainRenderTarget().width,
                    mc.gameRenderer.mainRenderTarget().height,Config.quality,Config.holeMaxMachines,Config.holeLife,Config.scorchSize,
                    BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()));
            System.arraycopy(HoleBenchMetrics.nanos,0,COSTS,0,17);
            HoleBenchMetrics.maxHole=0; projectiles=HoleBenchMetrics.projectiles; explosions=HoleBenchMetrics.explosions;
            HoleBenchMetrics.poseChecks=HoleBenchMetrics.poseUpdates=0;
            shooting=true; return;
        }
        if(samples<TIMES.length) TIMES[samples++]=now-last;
        last=now; maxPending=Math.max(maxPending,Tracers.pendingHoles());
        if(now-started>120_000_000_000L) { failure="таймаут фазы, выстрелов="+fired+", очередь="+Tracers.pendingHoles(); return; }
        boolean motion=HoleBenchMetrics.CHECK_MOTION && PLAN.get(index).repeat()>=0;
        if(fired<SHELLS || now-lastShot<(motion?16_000_000_000L:3_000_000_000L) || Tracers.pendingHoles()>0) return;
        shooting=false;
        var sorted=Arrays.copyOf(TIMES,samples); Arrays.sort(sorted); var phase=PLAN.get(index);
        ClientSmoke.check(HoleBenchMetrics.projectiles-projectiles==SHELLS*10L,"штатная очередь Heretic: выстрелов="+fired+
                ", снарядов="+(HoleBenchMetrics.projectiles-projectiles));
        ClientSmoke.check(phase.mode()==2?Tracers.holeCount()==0:Tracers.holeCount()>0,"число следов соответствует режиму: "+Tracers.holeCount());
        String row=String.format(Locale.ROOT,"%s\t%s\t%d\t%d\t%d\t%d\t%d\t%.4f\t%.5f\t%.5f\t%.5f\t%.5f\t%d\t%d\t%d\t%.5f",
                phase.surface(),MODES[phase.mode()],phase.repeat(),fired,HoleBenchMetrics.projectiles-projectiles,HoleBenchMetrics.explosions-explosions,
                samples,(now-started)/1e9,(now-started)/1e6/samples,sorted[(int)((samples-1)*.95)]/1e6,
                sorted[(int)((samples-1)*.99)]/1e6,sorted[samples-1]/1e6,
                (THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated)/samples,
                Tracers.holeCount(),maxPending,HoleBenchMetrics.maxHole/1e6);
        for(int part:new int[]{Perf.HOLE_MAKE,Perf.HOLE_DRAW,Perf.HOLE_BAKE,Perf.FOLLOW,Perf.CHECKS,12,13,14,15,16})
            row+=String.format(Locale.ROOT,"\t%.5f",(HoleBenchMetrics.nanos[part]-COSTS[part])/1e6/samples);
        ROWS.add(row); GunFeel.LOGGER.info("[holebench] {}",row);
        if(motion) {
            ClientSmoke.check(opened && closed,"движение двери: запрошены открытие и закрытие");
            ClientSmoke.check(HoleBenchMetrics.poseChecks>0 && HoleBenchMetrics.poseUpdates>0,"проверки пропуска="+
                    HoleBenchMetrics.poseChecks+", обновления движущихся следов="+HoleBenchMetrics.poseUpdates);
            ClientSmoke.check(HoleVisualProbe.finished,"GPU-сравнение прежнего и нового рисования завершено");
        }
        try { Files.write(Path.of(System.getProperty("ntmgunfeel.holeBenchFile")),ROWS); }
        catch(Exception e) { throw new IllegalStateException(e); }
        next();
    }
}
