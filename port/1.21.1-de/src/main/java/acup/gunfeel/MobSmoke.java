package acup.gunfeel;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Только runCheckMobs: получает снимки от настоящего рендера и настоящей сети. */
// Копия порта (notes/ports/de-1.21.1.md, «Копии»): без режима обзора (MobReview, HeadLabels в skip.txt)
// и без проверки пикселей обводки (ModelDebugRender на порте заглушка). F3+B включается setRenderHitBoxes 1.21.1,
// Slime и стрижка овцы по 1.21.1.
public final class MobSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.checkMobs");
    private static volatile List<String> CASES = List.of();
    private record Snapshot(float anchor, float body, float yRot, int age, List<ModelBox> boxes, String source) {}
    private record Result(boolean ok, String text) {}
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    private static volatile int id = -1;
    private static volatile Snapshot client, server;
    private static volatile boolean pending;
    private static int index=-1, stage, orange, white, sameFrame, oldFrame;
    private static boolean watch;
    private static final boolean CONTOUR_SHOTS=Boolean.getBoolean("ntmgunfeel.contourShots");
    // -PcheckTurn: a pose kept from before a turn, on the server, against the pose the player sees after it
    private static final boolean TURN=Boolean.getBoolean("ntmgunfeel.checkTurn");
    private static Object kept;
    // the target's poses as the client published them, with the tick and partial tick of the frame: a spin's stand
    // finds the client's frame of the kept and the fresh pose, which the server does not get
    private record Phase(List<ModelBox> boxes, double tick) {}
    private static final java.util.concurrent.ConcurrentLinkedDeque<Phase> PHASES=new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static int photographed=-1;
    private static long since;
    private static long contourBuilds;
    private static int gpuChecks,gpuMismatch,geometryChecks,geometryMismatch;
    static void gpuGeometry(LivingEntity entity,boolean matches) {
        if(!enabled() || entity.getId()!=id) return;
        geometryChecks++;
        if(!matches && geometryMismatch++==0) RESULTS.add(new Result(false,current()+": кэш геометрии отличается от пересчёта текущей матрицей"));
    }
    static void gpuPose(LivingEntity entity,boolean matches) {
        if(!ENABLED || entity.getId()!=id) return;
        gpuChecks++;
        if(!matches && gpuMismatch++==0) RESULTS.add(new Result(false,current()+": CPU-матрица отличается от настоящей GPU-матрицы"));
    }
    private static BulletConfig beamConfig;
    private static MobRayOracle.Ray woolRay;
    private static final java.util.Set<Integer> PROBES=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static int probeTracers;
    static void tracer(int bullet) {
        if(ENABLED && PROBES.contains(bullet)) {
            probeTracers++;
            ClientSmoke.check(false,"Контрольная пуля создала видимый трассер: "+bullet);
        }
    }

    private MobSmoke() {}
    static boolean enabled() { return ENABLED; }
    static void debugProbe(boolean ok,String text) { RESULTS.add(new Result(ok,"GPU обводка: "+text)); }
    static void contourShot() {
        if(!ENABLED || !CONTOUR_SHOTS || stage!=2 || seconds()<.3 || photographed==index) return;
        photographed=index; String name=current().replace(':','_');
        acup.gunfeel.port.Mc.screenshot(Minecraft.getInstance().getMainRenderTarget(),image -> {
            try(image) { image.writeToFile(java.nio.file.Path.of("../notes/accurate-hitboxes/contour-"+name+".png")); }
            catch(java.io.IOException ex) { debugProbe(false,"сохранение контура: "+ex); }
        });
    }
    // режима обзора на порте нет
    static boolean review() { return false; }
    // a trace (-Ptrace): the partial tick is 0 (MixinTracePartialTick) and the target's pose stays (hold), so two runs
    // write the same parts
    public static boolean frozen() { return ENABLED && Trace.ON && !review(); }
    static List<String> cases() { return CASES; }
    static int index() { return index; }
    static boolean busy() { return pending; }
    static String current() { return index<0 || index>=CASES.size()?"":CASES.get(index); }
    static boolean hasPose() { return client!=null && server!=null; }
    static Entity target() { var mc=Minecraft.getInstance(); return mc.level==null?null:mc.level.getEntity(id); }
    static String status() {
        return "модель: "+(client==null?"нет":client.boxes().size()+" частей")
                +" · сервер: "+(server==null?"нет позы":"поза принята");
    }
    static String evidence() {
        var mc=Minecraft.getInstance();
        var target=target();
        return "ID="+current()+"\n"+status()+"\nposition="+(target==null?"нет сущности":target.position())
                +"\nrenderer="+(target==null?"нет":mc.getEntityRenderDispatcher().getRenderer(target).getClass().getName())
                +"\ncamera="+mc.gameRenderer.getMainCamera().getPosition()+"\nclient="+client+"\nserver="+server+"\n";
    }

    static void captured(LivingEntity e, float anchor, List<ModelBox> boxes, String source) {
        OutlineDrift.captured(e, boxes);
        if (ENABLED && e.getId()==id) {
            client=new Snapshot(anchor,e.yBodyRot,e.getYRot(),e.tickCount,List.copyOf(boxes),source);
            if(TURN) {
                double tick=e.tickCount+Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
                PHASES.addLast(new Phase(client.boxes(),tick));
                // the spin comes round in 72 ticks: older frames would match a pose a turn later
                while(!PHASES.isEmpty() && PHASES.peekFirst().tick()<tick-60) PHASES.pollFirst();
            }
        }
    }
    static void accepted(LivingEntity e, float anchor, List<ModelBox> boxes) {
        if (ENABLED && e.getId()==id) server=new Snapshot(anchor,e.yBodyRot,e.getYRot(),e.tickCount,List.copyOf(boxes),"network");
    }
    public static void debug(Entity e, boolean model) {
        if (ENABLED && watch && e!=null && e.getId()==id) {
            if (model) orange++; else white++;
            if(model && e instanceof LivingEntity living && client!=null && !client.source().startsWith("ntm_")) {
                if(ModelHitClient.currentFrame(living)) sameFrame++; else oldFrame++;
            }
        }
    }

    static void start() {
        var mc=Minecraft.getInstance();
        mc.getEntityRenderDispatcher().setRenderHitBoxes(true);
        pending=true;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var level=mc.getSingleplayerServer().overworld();
                CASES=System.getProperty("ntmgunfeel.mobCases", "").isBlank()?
                        MobCatalog.discover():
                        java.util.Arrays.stream(System.getProperty("ntmgunfeel.mobCases").split(",")).map(String::trim).toList();
                var namespaces=System.getProperty("ntmgunfeel.mobNamespaces", "");
                if(!namespaces.isBlank()) {
                    var selected=java.util.Set.of(namespaces.split(","));
                    CASES=CASES.stream().filter(key -> selected.contains(key.substring(0,key.indexOf(':')))).toList();
                }
                java.nio.file.Path catalog=java.nio.file.Path.of(System.getProperty("ntmgunfeel.checkClient")).resolveSibling("catalog.txt");
                java.nio.file.Files.createDirectories(catalog.getParent());
                java.nio.file.Files.write(catalog,CASES);
                RESULTS.add(new Result(!CASES.isEmpty(),"Реестр живых сущностей: "+CASES.size()+", все пространства имён"));
                var p=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                p.setGameMode(GameType.CREATIVE);
                p.teleportTo(level,8.5,100,8.5,java.util.Set.of(),0,0);
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
        while((result=RESULTS.poll())!=null) {
            ClientSmoke.check(result.ok(),result.text());
        }
        var mc=Minecraft.getInstance();
        if(frozen() && mc.level!=null && mc.level.getEntity(id) instanceof LivingEntity still) hold(still);
        if(mc.player==null || pending) return;
        if(stage==22) { if(ExplosiveSmoke.finish())ClientSmoke.finish(); return; }
        mc.player.setYRot(stage==4?180:0);
        mc.player.setXRot(0);
        if(CONTOUR_SHOTS && stage!=4 && target() instanceof LivingEntity lookingAt) {
            mc.options.fov().set(30);
            double distance=Math.hypot(lookingAt.getX()-mc.player.getX(),lookingAt.getZ()-mc.player.getZ());
            mc.player.setXRot((float)Math.toDegrees(Math.atan2(mc.player.getEyeY()-lookingAt.getY()-lookingAt.getBbHeight()/2,distance)));
            if(current().equals("minecraft:fox") && (stage==1 || stage==2)) {
                lookingAt.yBodyRot=lookingAt.yBodyRotO=90;
                lookingAt.yHeadRot=lookingAt.yHeadRotO=90;
                lookingAt.setYRot(90); lookingAt.yRotO=90;
            }
        }
        if(stage==5 && seconds()<=1 && mc.level.getEntity(id) instanceof LivingEntity rotating) {
            rotating.yBodyRotO=rotating.yBodyRot;
            rotating.yBodyRot=(float)(seconds()*240);
            rotating.setYRot(rotating.yBodyRot); rotating.yHeadRot=rotating.yBodyRot;
        }
        if(stage==0) {
            turned=false;
            next();
        } else if(stage==1) {
            if(seconds()>1 && mc.level.getEntity(id)!=null) watch=true;
            if(client!=null && server!=null && seconds()>1) {
                check(true,"захват="+client.source()+", частей="+client.boxes().size()+", сеть принята");
                check(mc.level.getEntity(id) instanceof LivingEntity,"сущность существует у клиента");
                if(current().equals("minecraft:sheep")) check(hasWool(client),"захвачен отдельный слой шерсти");
                if(current().equals("minecraft:slime")) {
                    check(hasShell(client),"захвачена внешняя оболочка слайма");
                    checkSlimeScale(client,1,"клиент");
                }
                if(current().equals("minecraft:magma_cube")) check(!hasShell(client),"к магмовому кубу не добавлена оболочка слайма");
                if(current().equals("minecraft:bee") || current().equals("minecraft:bat")) {
                    long flat=client.boxes().stream().filter(box -> box.mesh()!=0).count();
                    check(flat>0,"плоские части из настоящей модели="+flat);
                    check(client.boxes().stream().filter(box -> box.mesh()!=0).allMatch(box -> ModelMesh.get(box.mesh()).edges().length>=24),
                            "у плоских частей есть внешняя обводка");
                }
                if(current().equals("minecraft:breeze"))
                    check(client.boxes().stream().anyMatch(box -> box.part().contains("wind_bottom"))
                            && client.boxes().stream().anyMatch(box -> box.part().contains("wind_top")),"нижняя и верхняя части вихря присутствуют");
                if(current().equals("minecraft:goat")) {
                    check(client.boxes().stream().noneMatch(PlaneHitPolicy::flat),"у взрослой козы нет плоских поверхностей попадания");
                    check(EntityType.GOAT.getDimensions().height()==PlaneHitPolicy.LARGE_SIZE,"порог размера совпадает со взрослой козой");
                }
                if(Boolean.getBoolean("ntmgunfeel.checkAvatarLayers")) {
                    check(SkinLayerGeometry.enabled(),"3D Skin Layers: необязательный API доступен");
                    check(client.boxes().stream().anyMatch(b->b.part().endsWith("/skin3d")),"клиент: захвачены настоящие объёмные слои скина");
                    check(server.boxes().stream().anyMatch(b->b.part().endsWith("/skin3d")),"сервер: получены объёмные слои скина");
                    check(SkinLayerGeometry.captures>0 && SkinLayerGeometry.bakes<=6,"3D Skin Layers: кэш геометрии, captures="+SkinLayerGeometry.captures+", bakes="+SkinLayerGeometry.bakes);
                }
                watch=true; orange=white=sameFrame=oldFrame=0;
                stage=2; since=System.nanoTime();
                contourBuilds=ModelContours.builds();
            } else if(seconds()>6) {
                var entity=mc.level.getEntity(id);
                String renderer=entity==null?"нет сущности":mc.getEntityRenderDispatcher().getRenderer(entity).getClass().getName();
                check(false,"нет полной цепочки рендер -> сеть: client="+(client!=null)+", server="+(server!=null)
                        +", оранжевых="+orange+", белых="+white+", renderer="+renderer);
                watch=false;
                stage=0;
            }
        } else if(stage==2 && seconds()>1) {
            if(client!=null && client.source().equals("gpu_rendered_geometry"))
                check(gpuChecks>0 && gpuMismatch==0,"сверка CPU/GPU-матриц="+gpuChecks+", расхождений="+gpuMismatch);
            if(geometryChecks>0) check(geometryMismatch==0,"кэш геометрии и полный пересчёт="+geometryChecks+", расхождений="+geometryMismatch);
            if(current().equals("hbm:entity_ufo")) {
                long rebuilt=ModelContours.builds()-contourBuilds;
                check(rebuilt<Math.max(1,orange/2),"жёсткое вращение: кадров="+orange+", перестроений контура="+rebuilt);
            }
            watch=false;
            check(orange>0 && white==0,"F3+B: оранжевых кадров="+orange+", белых="+white);
            if(client!=null && !client.source().startsWith("ntm_"))
                check(sameFrame>0 && oldFrame==0,"F3+B поза текущего кадра="+sameFrame+", предыдущего="+oldFrame);
            LivingEntity target=(LivingEntity)mc.level.getEntity(id);
            if(target==null) { check(false,"цель пропала"); stage=0; return; }
            rays(target,client,CASES.get(index)+": клиент, видимый");
            if(CASES.get(index).equals("minecraft:skeleton")) {
                anchor(target,0,1.65,true,"голова скелета");
                anchor(target,.125,.3,true,"нога скелета");
                anchor(target,0,.3,false,"пустота между ногами скелета");
                anchor(target,.56,1.3,false,"пустота рядом со скелетом внутри AABB+0.3");
            }
            // a case already sheared (#sheared, MobVariants) keeps the fleece off: the head and limb checks after this
            // run on the sheared model, not on the wool the shearing stage grows back (the root's stand regrows it)
            if(target instanceof net.minecraft.world.entity.animal.Sheep && !MobCatalog.variants(CASES.get(index)).contains("sheared")) {
                stage=6; since=System.nanoTime(); shearSheep();
            } else if(target.getType()==EntityType.SLIME) {
                stage=8; since=System.nanoTime(); checkSlimeAndResize(2);
            } else { serverRays(false); startRotation(); }
        } else if(stage==6) {
            if(target() instanceof net.minecraft.world.entity.animal.Sheep sheep && sheep.isSheared()
                    && client!=null && server!=null && !hasWool(client) && !hasWool(server) && seconds()>.6) {
                check(true,"после стрижки шерсть исчезла у клиента и сервера");
                rays(sheep,client,current()+": стриженая, клиент");
                stage=7; since=System.nanoTime(); regrowSheep();
            } else if(seconds()>6) { check(false,"шерсть не исчезла после стрижки"); startRotation(); }
        } else if(stage==7) {
            if(target() instanceof net.minecraft.world.entity.animal.Sheep sheep && !sheep.isSheared()
                    && hasWool(client) && hasWool(server) && seconds()>.6) {
                check(true,"после отрастания шерсть вернулась у клиента и сервера");
                rays(sheep,client,current()+": шерсть отросла, клиент");
                checkRegrownSheep(); startRotation();
            } else if(seconds()>6) { check(false,"шерсть не вернулась после отрастания"); startRotation(); }
        } else if(stage==8 || stage==9) {
            int expected=stage==8?2:4;
            if(target() instanceof net.minecraft.world.entity.monster.Slime slime && slime.getSize()==expected
                    && hasShell(client) && hasShell(server) && seconds()>1) {
                check(true,"оболочка обновилась при размере="+expected);
                checkSlimeScale(client,expected,"клиент");
                rays(slime,client,current()+": размер "+expected+", клиент");
                if(stage==8) { stage=9; since=System.nanoTime(); checkSlimeAndResize(4); }
                else { checkSlimeAndResize(0); startRotation(); }
            } else if(seconds()>6) { check(false,"оболочка не обновилась после изменения размера"); startRotation(); }
        } else if(stage==5 && seconds()>1) {
            watch=false;
            // the turn stopped on whatever frame came last; a trace takes it to one angle and keeps it (hold)
            turned=frozen();
            check(orange>0 && white==0,"F3+B при вращении: оранжевых="+orange+", белых="+white);
            if(client!=null && !client.source().startsWith("ntm_"))
                check(sameFrame>0 && oldFrame==0,"При вращении: применённая поза="+sameFrame+", отставших="+oldFrame);
            stage=3;
        } else if(stage==3) {
            pending=true;
            mc.getSingleplayerServer().execute(() -> {
                var target=(LivingEntity)mc.getSingleplayerServer().overworld().getEntity(id);
                if(target!=null) move(target);
                pending=false;
            });
            // EnderDragon.aiStep returns at once for a NoAi dragon, before it interpolates (1.21.1 as 26.2): its client
            // copy never follows the server, and the player sees (and hits) it where it was. Move that copy the same way.
            if(mc.level.getEntity(id) instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon && dragon.isNoAi()) move(dragon);
            stage=4; since=System.nanoTime();
        } else if(stage==4 && seconds()>1.2) {
            var target=(LivingEntity)mc.level.getEntity(id);
            if(target!=null) {
                check(!ModelHits.outlines(target).isEmpty(),"поза пережила уход из кадра, сдвиг и поворот");
                rays(target,client,CASES.get(index)+": клиент, вне кадра после движения");
                serverRays(true);
            } else check(false,"цель пропала после движения");
            stage=0;
            // -PcheckTurn: 1.21.1 has no render states (the root reports the state's class): the renderer's class instead
            if(TURN && target!=null) {
                RESULTS.add(new Result(true,CASES.get(index)+": клиент, рендерер="+mc.getEntityRenderDispatcher().getRenderer(target).getClass().getName()));
                stage=12; since=System.nanoTime();
            }
            // -PcheckOutlineDrift: the target walks, turns and stands, F3+B and the server's zone against the model
            // the dragon is NoAi on the server (stage 1), the client would not carry it, so there is nothing to follow
            else if(OutlineDrift.ENABLED && target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)
                check(true,"F3+B на ходу: дракон без ИИ, клиент его не ведёт, пропуск");
            else if(OutlineDrift.ENABLED && target!=null) { OutlineDrift.start(id); stage=30; since=System.nanoTime(); watch=false; }
        } else if(stage==30) {
            if(OutlineDrift.finished()) {
                for(var line:OutlineDrift.report()) check(line.ok(),line.text());
                stage=0;
            } else if(seconds()>20) { OutlineDrift.stop(); check(false,"F3+B на ходу: стенд не закончился за 20 с"); stage=0; }
        } else if(stage==12 && seconds()>2) {
            turnAway(); stage=11; since=System.nanoTime();
        } else if(stage==11 && seconds()>1.5) {
            turnCompare(); stage=0;
        }
    }

    // a trace: the target's age, walk and turn do not run on between frames (0 marks a mob just added, SpawnLerp).
    // After the turn its angle stays at the turn's end: the server's turn reaches the client's copy by packets, and a
    // slime's body takes its head's angle when its size comes, whenever in the head's turn that is
    private static boolean turned;
    private static void hold(LivingEntity mob) {
        if(mob.tickCount>0) mob.tickCount=100;
        // 1.21.1's WalkAnimationState has no stop(): speed and speedOld go to 0, the position (still 0 for the stand's
        // mob, which never walks) stays
        mob.walkAnimation.setSpeed(0); mob.walkAnimation.update(0,1);
        if(turned) { mob.yBodyRot=mob.yHeadRot=240; mob.setYRot(240); }
        mob.yBodyRotO=mob.yBodyRot; mob.yHeadRotO=mob.yHeadRot; mob.yRotO=mob.getYRot(); mob.xRotO=mob.getXRot();
    }

    private static void move(LivingEntity target) {
        target.setPos(target.position().add(.6,0,0));
        target.setYRot(90); target.yBodyRot=90; target.yHeadRot=90;
    }
    private static double seconds() { return (System.nanoTime()-since)/1e9; }
    private static boolean hasWool(Snapshot pose) { return pose!=null && pose.boxes().stream().anyMatch(ModelWool::isWool); }
    private static boolean hasShell(Snapshot pose) {
        return pose!=null && pose.boxes().stream().anyMatch(b -> b.part().endsWith(ModelBodyGeometry.SHELL_SUFFIX));
    }
    private static void checkSlimeScale(Snapshot pose,int size,String side) {
        var shell=pose.boxes().stream().filter(b -> b.part().endsWith(ModelBodyGeometry.SHELL_SUFFIX)).findFirst().orElseThrow();
        double width=Math.sqrt(shell.ux()*shell.ux()+shell.uy()*shell.uy()+shell.uz()*shell.uz());
        double height=Math.sqrt(shell.vx()*shell.vx()+shell.vy()*shell.vy()+shell.vz()*shell.vz());
        // В этом сценарии моб висит без ИИ и контакта с землёй: squish=0.
        // Допуск учитывает штатный downscale 0.999 обычного рендерера.
        RESULTS.add(new Result(Math.abs(width-size*.5)<size*.01 && Math.abs(height-size*.5)<size*.01,
                current()+": "+side+", реальный размер оболочки="+width+" x "+height+", ожидается="+size*.5));
    }
    private static void checkSlimeAndResize(int nextSize) {
        pending=true;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var level=Minecraft.getInstance().getSingleplayerServer().overworld();
                var slime=(net.minecraft.world.entity.monster.Slime)level.getEntity(id);
                String label=current()+": размер "+slime.getSize()+", сервер";
                checkSlimeScale(server,slime.getSize(),"сервер");
                rays(slime,server,label);
                var full=MobRayOracle.surface(slime,server.anchor(),server.body(),server.yRot(),server.age(),server.boxes());
                var core=MobRayOracle.surface(slime,server.anchor(),server.body(),server.yRot(),server.age(),server.boxes().stream().filter(b -> !b.part().endsWith(ModelBodyGeometry.SHELL_SUFFIX)).toList());
                var onlyShell=MobRayOracle.grid(full).stream().filter(ray -> ray.hit() && !MobRayOracle.hit(core,ray.from(),ray.to()) && clearRay(slime,ray)).findFirst().orElseThrow();
                nativeRay(level,slime,onlyShell,true,label+": попадание только во внешнюю оболочку");
                if(nextSize>0) slime.setSize(nextSize,true);
            } catch(Throwable t) { RESULTS.add(new Result(false,current()+": оболочка/размер: "+t)); }
            pending=false;
        });
    }
    private static void startRotation() {
        stage=5; since=System.nanoTime(); watch=true; orange=white=sameFrame=oldFrame=0;
    }
    private static void shearSheep() {
        pending=true;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var level=Minecraft.getInstance().getSingleplayerServer().overworld();
                var sheep=(net.minecraft.world.entity.animal.Sheep)level.getEntity(id);
                rays(sheep,server,current()+": с шерстью, сервер");
                var full=MobRayOracle.surface(sheep,server.anchor(),server.body(),server.yRot(),server.age(),server.boxes());
                var bare=MobRayOracle.surface(sheep,server.anchor(),server.body(),server.yRot(),server.age(),server.boxes().stream().filter(b -> !ModelWool.isWool(b)).toList());
                var woolOnly=MobRayOracle.grid(full).stream().filter(ray -> ray.hit() && !MobRayOracle.hit(bare,ray.from(),ray.to())).toList();
                woolRay=woolOnly.stream().filter(ray -> clearRay(sheep,ray)).findFirst().orElse(null);
                // a lamb's fleece is the size of the parts it covers or lies inside them: no point is on the wool alone
                if(woolRay==null) RESULTS.add(new Result(woolOnly.isEmpty() && MobCatalog.baby(current()),
                        current()+": шерсть не выступает за модель без неё, точек только на шерсти="+woolOnly.size()));
                else nativeRay(level,sheep,woolRay,true,current()+": точка только на шерсти до стрижки");
                sheep.shear(net.minecraft.sounds.SoundSource.PLAYERS);
                // Та же принятая поза, ни одного нового пакета между проверками.
                if(woolRay!=null) nativeRay(level,sheep,woolRay,false,current()+": та же точка сразу после стрижки, до нового пакета");
            } catch(Throwable t) { RESULTS.add(new Result(false,current()+": стрижка: "+t)); }
            pending=false;
        });
    }
    private static void regrowSheep() {
        pending=true;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var sheep=(net.minecraft.world.entity.animal.Sheep)Minecraft.getInstance().getSingleplayerServer().overworld().getEntity(id);
                rays(sheep,server,current()+": стриженая, сервер"); sheep.ate();
            } catch(Throwable t) { RESULTS.add(new Result(false,current()+": отрастание: "+t)); }
            pending=false;
        });
    }
    private static void checkRegrownSheep() {
        pending=true;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var level=Minecraft.getInstance().getSingleplayerServer().overworld();
                var sheep=(net.minecraft.world.entity.animal.Sheep)level.getEntity(id);
                rays(sheep,server,current()+": шерсть отросла, сервер");
                if(woolRay!=null) nativeRay(level,sheep,woolRay,true,current()+": та же точка после отрастания шерсти");
            } catch(Throwable t) { RESULTS.add(new Result(false,current()+": проверка отрастания: "+t)); }
            pending=false;
        });
    }
    private static void check(boolean ok,String text) { ClientSmoke.check(ok,CASES.get(index)+": "+text); }
    private static void next() {
        if(index+1>=CASES.size()) {
            ClientSmoke.check(!PROBES.isEmpty() && probeTracers==0,"Контрольные пули="+PROBES.size()+", сетевые трассеры="+probeTracers);
            stage=22;
            if(ExplosiveSmoke.finish())ClientSmoke.finish(); return;
        }
        select(index+1);
    }
    static void select(int selected) {
        var mc=Minecraft.getInstance();
        if(selected<0 || selected>=CASES.size() || pending) return;
        pending=true;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var level=mc.getSingleplayerServer().overworld();
                int peas=MobStageCleanup.clear(level,mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst());
                index=selected;
                id=-1; client=server=null; PHASES.clear(); woolRay=null; watch=false; orange=white=sameFrame=oldFrame=0; gpuChecks=gpuMismatch=geometryChecks=geometryMismatch=0;
                RESULTS.add(new Result(true,CASES.get(index)+": стенд очищен до спавна, оставшихся сущностей=0, peas="+peas));
                var type=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(MobCatalog.type(CASES.get(index))));
                Entity created=type.create(level);
                if(!(created instanceof LivingEntity target)) throw new IllegalStateException("не живой моб: "+created);
                if(MobCatalog.baby(CASES.get(index))) {
                    if(!(target instanceof Mob mob)) throw new IllegalStateException("Нет детского варианта: "+CASES.get(index));
                    mob.setBaby(true);
                    if(!mob.isBaby()) throw new IllegalStateException("Моб не поддерживает детский возраст: "+CASES.get(index));
                }
                MobVariants.apply(target,MobCatalog.variants(CASES.get(index)));
                // Ghost штатно исчезает рядом с обычным игроком даже с NoAI.
                // Наблюдатель исключён из его проверки присутствия игроков.
                mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst()
                        .setGameMode(CASES.get(index).equals("hbm:entity_ghost")?GameType.SPECTATOR:GameType.CREATIVE);
                target.setPos(8.5,100,16.5);
                target.setYRot(180); target.yBodyRot=180; target.yHeadRot=180;
                target.setNoGravity(true); target.setInvulnerable(true);
                // -PmobHealth=<доля>: моб ранен (Mask Man с половины здоровья носит череп вместо головы)
                float share=Float.parseFloat(System.getProperty("ntmgunfeel.mobHealth","1"));
                if(share<1) target.setHealth(target.getMaxHealth()*share);
                if(target instanceof Mob mob) { mob.setNoAi(true); mob.setPersistenceRequired(); }
                MobStageContext.prepare(level,target);
                id=target.getId();
                level.addFreshEntity(target);
                stage=1; since=System.nanoTime();
            } catch(Throwable t) {
                RESULTS.add(new Result(false,CASES.get(selected)+": очистка/создание: "+t));
                stage=22;
            }
            pending=false;
        });
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Integer,Object> poses(net.minecraft.world.level.Level level) throws ReflectiveOperationException {
        var field=ModelHits.class.getDeclaredField("POSES");field.setAccessible(true);
        return ((java.util.Map<net.minecraft.world.level.Level,java.util.Map<Integer,Object>>)field.get(null)).get(level);
    }
    private static Object part(Object record,String name) throws ReflectiveOperationException {
        var method=record.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(record);
    }
    private static void turnAway() {
        pending=true;
        String label=CASES.get(index)+": сервер, поворот";
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var target=(LivingEntity)Minecraft.getInstance().getSingleplayerServer().overworld().getEntity(id);
                var all=target==null?null:poses(target.level());
                kept=all==null?null:all.get(id);
                if(kept==null) RESULTS.add(new Result(false,label+": нет серверной позы до поворота"));
                else {
                    // yRot and the body turn apart (+40, -30), so a rule by the body and one by yRot give two angles;
                    // the head goes with the body, so that the body's control (it brings the body to the head) keeps it
                    float body=target.yBodyRot,yaw=target.getYRot();
                    target.setYRot(yaw+40); target.yBodyRot=body-30; target.yHeadRot=body-30;
                    RESULTS.add(new Result(true,label+": поза снята при yaw="+part(kept,"yaw")+", тело="+part(kept,"bodyYaw")
                            +"; сервер yRot "+yaw+"→"+target.getYRot()+", тело "+body+"→"+target.yBodyRot));
                }
            } catch(Throwable t) { RESULTS.add(new Result(false,label+": "+t)); }
            pending=false;
        });
    }
    private static void turnCompare() {
        pending=true;
        String label=CASES.get(index)+": сервер, поворот";
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var target=(LivingEntity)Minecraft.getInstance().getSingleplayerServer().overworld().getEntity(id);
                var level=target==null?null:(ServerLevel)target.level();var all=level==null?null:poses(level);
                var fresh=all==null?null:all.get(id);var player=level==null?null:level.getServer().getPlayerList().getPlayers().getFirst();
                var viewsField=ModelHits.class.getDeclaredField("VIEWS");viewsField.setAccessible(true);
                var views=player==null?null:((java.util.Map<?,java.util.Map<Integer,Object>>)viewsField.get(null)).get(player);
                var view=views==null?null:views.get(id);
                long age=view==null?-1:level.getGameTime()-(long)part(part(view,"pose"),"tick");
                if(kept==null || fresh==null || server==null) RESULTS.add(new Result(false,label+": нет позы для сравнения"));
                else if(view==null || age>ModelHits.MAX_SYNC_AGE) RESULTS.add(new Result(false,label+": нет свежей позы игрока, возраст="+age));
                else {
                    var grid=MobRayOracle.grid(MobRayOracle.surface(target,server.anchor(),server.body(),server.yRot(),server.age(),server.boxes()));
                    all.put(id,kept);
                    int seen=0,turret=0,query=0,rays=0;var truths=new java.util.ArrayList<Boolean>();String scan="";boolean fitted=false,ruleFits=false;
                    try {
                        for(var ray:grid) {
                            rays++;
                            var shot=ModelHits.beginProjectile(player,ray.from().distanceTo(ray.to()));
                            boolean truth;
                            try { truth=hit(level,target,ray); } finally { ModelHits.endShot(shot); }
                            var none=ModelHits.beginProjectile(null,0);
                            boolean plain;
                            try { plain=hit(level,target,ray); } finally { ModelHits.endShot(none); }
                            var bullet=new ProbeBullet(level,target,false);
                            float speed=(float)Math.max(1,ray.from().distanceTo(ray.to())/(XFactory9mm.p9_ap.velocity*ReaimTimes.number("bulletSpeed",1.6F)));
                            var d=ray.to().subtract(ray.from());
                            bullet.setPos(ray.from()); bullet.setThrowableHeading(d.x,d.y,d.z,speed,0); bullet.damage=0; bullet.ticksInAir=1;
                            bullet.tick(); bullet.discard();
                            if(truth) seen++;
                            if(bullet.hit!=truth) turret++;
                            if(plain!=truth) query++;
                            truths.add(truth);
                        }
                        // the turn the drawn model made: the angle that best lays the kept pose's box centres on the
                        // fresh pose's, what stays apart (a shape that changed, not a turn), and the rays at that angle
                        var was=(java.util.List<ModelBox>)part(kept,"boxes");var now=(java.util.List<ModelBox>)part(fresh,"boxes");
                        float captured=(float)part(kept,"yaw"),body=target.yBodyRot;
                        scan=", коробок "+was.size()+"/"+now.size()+", свежая поза yaw="+part(fresh,"yaw");
                        if(was.size()==now.size()) {
                            int bestA=0;double bestE=Double.MAX_VALUE,worstE=0;
                            for(int deg=-180;deg<180;deg++) { double e=boxError(was,now,Math.toRadians(deg)); worstE=Math.max(worstE,e); if(e<bestE) { bestE=e;bestA=deg; } }
                            double rule=Math.toDegrees(turnRule(target,later(kept,target.tickCount-(int)part(fresh,"age"))));double ruleE=boxError(was,now,Math.toRadians(rule));
                            // the boxes tell a turn when some angle lays them clearly worse than the best one
                            // a spin's rule counts the ticks between the server taking in the two poses, the model spins
                            // by the client's frames: they part by 5 degrees a tick of how much longer one pose took to
                            // come in than the other, and the fit by its 1 degree step. Without the client's frames, a tick
                            double tolerance=3;
                            if(ModelHits.turn(target.getClass())==ModelHits.Turn.SPIN) {
                                var a=phase(was);var b=phase(now);
                                double delay=a==null || b==null?1:Math.abs(((int)part(fresh,"age")-(int)part(kept,"age"))-(b.tick()-a.tick()));
                                tolerance=5*delay+(a==null || b==null?.5:1);
                                scan+=a==null || b==null?", кадры клиента не найдены":String.format(", кадры клиента %.2f→%.2f, приём %d→%d",
                                        a.tick(),b.tick(),(int)part(kept,"age"),(int)part(fresh,"age"));
                            }
                            fitted=Math.sqrt(worstE)-Math.sqrt(bestE)>.05;ruleFits=Math.abs(net.minecraft.util.Mth.wrapDegrees((float)(rule-bestA)))<=tolerance;
                            scan+=", поворот модели="+bestA+" (остаток "+String.format("%.3f",Math.sqrt(bestE))+"), по правилу="+String.format("%.1f",rule)
                                    +" (остаток "+String.format("%.3f",Math.sqrt(ruleE))+(fitted?"":", коробки поворота не различают")+", допуск "+tolerance+"°), дальше всех: "+farthest(was,now,Math.toRadians(bestA));
                        }
                    } finally { all.put(id,fresh); }
                    float health=target.getHealth();
                    boolean raysAgree=turret*20<=Math.max(1,seen) && query*20<=Math.max(1,seen);
                    // rays that part only because the model moved its limbs (or tilted) between the two poses: the turn is right
                    String verdict=fitted?ruleFits?raysAgree?"":", поворот верный, лучи расходятся из-за формы":", поворот неверный":raysAgree?"":", поворот лучами неверный";
                    RESULTS.add(new Result(fitted?ruleFits:raysAgree,label+verdict+": лучей="+rays+", видно попаданий="+seen
                            +", турель расходится="+turret+", запрос без стрелка расходится="+query+", возраст позы игрока="+age
                            +", старая поза yaw="+part(kept,"yaw")+", тело сейчас="+target.yBodyRot+", yRot сейчас="+target.getYRot()+", здоровье="+health+scan
                            +(Minecraft.getInstance().level.getEntity(id) instanceof LivingEntity drawnBy?", клиент: тело="+drawnBy.yBodyRot+", yRot="+drawnBy.getYRot():"")));
                }
            } catch(Throwable t) { RESULTS.add(new Result(false,label+": "+t)); }
            kept=null;pending=false;
        });
    }
    // the pose as if it had come in dt ticks later
    private static Object later(Object pose,int dt) throws ReflectiveOperationException {
        var parts=pose.getClass().getRecordComponents();var values=new Object[parts.length];var types=new Class<?>[parts.length];
        for(int i=0;i<parts.length;i++) {
            types[i]=parts[i].getType();var accessor=parts[i].getAccessor();accessor.setAccessible(true);values[i]=accessor.invoke(pose);
            if(parts[i].getName().equals("age")) values[i]=(int)values[i]+dt;
        }
        var constructor=pose.getClass().getDeclaredConstructor(types);constructor.setAccessible(true);return constructor.newInstance(values);
    }
    private static double turnRule(LivingEntity target,Object pose) throws ReflectiveOperationException {
        for(var m:ModelHits.class.getDeclaredMethods())
            if(m.getName().equals("turnAngle") && m.getParameterCount()==2 && m.getParameterTypes()[0]==LivingEntity.class) {
                m.setAccessible(true);return (double)m.invoke(null,target,pose);
            }
        return Math.toRadians(net.minecraft.util.Mth.wrapDegrees(target.yBodyRot-(float)part(pose,"yaw")));
    }
    // a box's corner: the origin and the ends of its three edges, turned as the server turns a pose
    private static net.minecraft.world.phys.Vec3 corner(ModelBox b,int k,double angle) {
        double x=b.x()+(k==1?b.ux():k==2?b.vx():k==3?b.wx():0),y=b.y()+(k==1?b.uy():k==2?b.vy():k==3?b.wy():0),z=b.z()+(k==1?b.uz():k==2?b.vz():k==3?b.wz():0);
        double cos=Math.cos(-angle),sin=Math.sin(-angle);
        return new net.minecraft.world.phys.Vec3(cos*x+sin*z,y,-sin*x+cos*z);
    }
    private static double boxError(ModelBox was,ModelBox now,double angle) {
        double e=0;for(int k=0;k<4;k++) e+=corner(was,k,angle).distanceToSqr(corner(now,k,0));
        return e/4;
    }
    // mean square distance of the corners, the kept pose turned by angle against the fresh one
    private static double boxError(java.util.List<ModelBox> was,java.util.List<ModelBox> now,double angle) {
        double e=0;for(int i=0;i<was.size();i++) e+=boxError(was.get(i),now.get(i),angle);
        return e/Math.max(1,was.size());
    }
    // the client's frame whose boxes lie nearest the pose's, if the history still has one that close
    private static Phase phase(java.util.List<ModelBox> boxes) {
        Phase best=null;double bestE=1e-4;
        for(var p:PHASES) if(p.boxes().size()==boxes.size()) { double e=boxError(p.boxes(),boxes,0); if(e<bestE) { bestE=e;best=p; } }
        return best;
    }
    private static String farthest(java.util.List<ModelBox> was,java.util.List<ModelBox> now,double angle) {
        var order=new java.util.ArrayList<Integer>();for(int i=0;i<was.size();i++) order.add(i);
        order.sort(java.util.Comparator.comparingDouble(i -> -boxError(was.get(i),now.get(i),angle)));
        var out=new StringBuilder();
        for(int i:order.subList(0,Math.min(3,order.size()))) out.append(out.isEmpty()?"":"; ").append(was.get(i).part()).append(' ').append(String.format("%.3f",Math.sqrt(boxError(was.get(i),now.get(i),angle))));
        return out.toString();
    }
    private static boolean hit(net.minecraft.world.level.Level level,LivingEntity target,MobRayOracle.Ray ray) {
        return ProjectileUtil.getEntityHitResult(level,(Entity)null,ray.from(),ray.to(),new AABB(ray.from(),ray.to()).inflate(.001),e -> e==target,.3F)!=null;
    }
    private static void serverRays(boolean moved) {
        pending=true;
        String label=CASES.get(index)+": сервер"+(moved?", после движения":"");
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var target=(LivingEntity)Minecraft.getInstance().getSingleplayerServer().overworld().getEntity(id);
                if(target==null || server==null) RESULTS.add(new Result(false,label+": нет цели или сетевой позы"));
                else {
                    // the angles the kept pose is turned by: the client's body it was drawn at, the server's when it came in
                    var all=poses(target.level());var pose=all==null?null:all.get(id);
                    if(moved && pose!=null) RESULTS.add(new Result(true,label+": поза снята при yaw="+part(pose,"yaw")+", тело="+part(pose,"bodyYaw")
                            +", yRot="+part(pose,"yRot")+"; сервер сейчас тело="+target.yBodyRot+", yRot="+target.getYRot()
                            +", поворот по правилу="+String.format("%.1f",Math.toDegrees(turnRule(target,pose)))
                            +(Minecraft.getInstance().level.getEntity(id) instanceof LivingEntity drawnBy?", клиент: тело="+drawnBy.yBodyRot+", yRot="+drawnBy.getYRot():"")));
                    rays(target,server,label);
                    if(Boolean.getBoolean("ntmgunfeel.checkHeadBindings")) HeadSmoke.bindings(target,(ok,text)->RESULTS.add(new Result(ok,label+": "+text)));
                    if(Boolean.getBoolean("ntmgunfeel.checkHeads")) HeadSmoke.run((ServerLevel)target.level(),target,(ok,text)->RESULTS.add(new Result(ok,label+": "+text)));
                    if(target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
                        var head=ModelHits.worldBoxes(dragon).stream().filter(b -> b.part().equals("root/head")).findFirst().orElseThrow();
                        var center=ModelContours.point(head,.5,.5,.5);
                        var a=center.add(0,0,-.01); var b=center.add(0,0,.01);
                        var result=ProjectileUtil.getEntityHitResult(dragon.level(),(Entity)null,a,b,new AABB(a,b).inflate(.3),
                                e -> e instanceof net.minecraft.world.entity.boss.EnderDragonPart part && part.parentMob==dragon,.3F);
                        RESULTS.add(new Result(result!=null && result.getEntity()==dragon.getSubEntities()[0],label+": точная голова сохраняет ванильного получателя урона head"));
                    }
                }
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
        var triangles=MobRayOracle.surface(target,pose.anchor(),pose.body(),pose.yRot(),pose.age(),pose.boxes());
        var grid=MobRayOracle.grid(triangles);
        if(grid.stream().noneMatch(MobRayOracle.Ray::hit)) {
            grid=new java.util.ArrayList<>(grid);
            grid.addAll(MobRayOracle.acrossFaces(triangles));
        }
        int hits=0,misses=0,mismatch=0,extra=0,gaps=0;
        MobRayOracle.Ray body=null,gap=null,wing=null;
        var model=target.getBoundingBox();
        var raw=new AABB(model.minX,model.minY,model.minZ,model.maxX,model.maxY,model.maxZ);
        StringBuilder got=new StringBuilder(grid.size()),want=new StringBuilder(grid.size());
        for(var ray:grid) {
            boolean actual=ProjectileUtil.getEntityHitResult(target.level(),(Entity)null,ray.from(),ray.to(),
                    new AABB(ray.from(),ray.to()).inflate(.001),e -> e==target,.3F)!=null;
            got.append(actual?'1':'0'); want.append(ray.hit()?'1':'0');
            if(ray.hit()) { hits++; if(body==null && clearRay(target,ray)) body=ray; } else misses++;
            if(actual!=ray.hit()) {
                mismatch++;
                ModelHitLog.event(target.level().isClientSide()?"client":"server","oracle_mismatch",target,
                        () -> "expected="+ray.hit()+" actual="+actual+" from="+ray.from()+" to="+ray.to()+" anchor="+pose.anchor()+" current="+target.yBodyRot);
            }
            if(ray.hit() && raw.clip(ray.from(),ray.to()).isEmpty()) { extra++; if(clearRay(target,ray)) wing=ray; }
            if(!ray.hit() && raw.inflate(.3).clip(ray.from(),ray.to()).isPresent()) { gaps++; if(clearRay(target,ray)) gap=ray; }
        }
        if(Trace.ON) trace(target,pose,label,grid.size(),got,want);
        RESULTS.add(new Result(hits>0 && misses>0 && mismatch==0,label+": лучи="+grid.size()+", модель="+hits+
                ", пустота="+misses+", расхождений="+mismatch+", вне AABB="+extra+", пустота внутри AABB+0.3="+gaps));
        if(!target.level().isClientSide()) {
            var seen=seen((ServerLevel)target.level(),target);
            nativeRay((ServerLevel)target.level(),target,body,true,label+": тело",seen);
            if(gap!=null) nativeRay((ServerLevel)target.level(),target,gap,false,label+": пустота внутри AABB+0.3",seen);
            if(wing!=null) nativeRay((ServerLevel)target.level(),target,wing,true,label+": выступ за AABB",seen);
        }
    }
    // The pose the player saw, while it is fresh (ModelHits.view): a player's bullet, arrow and beam take it as drawn,
    // not turned by the rule. A spinning mob (the UFO) is drawn a few ticks back from where the rule turns it now.
    @SuppressWarnings("unchecked")
    private static List<MobRayOracle.Triangle> seen(ServerLevel level,LivingEntity target) {
        try {
            var player=level.getServer().getPlayerList().getPlayers().getFirst();
            var field=ModelHits.class.getDeclaredField("VIEWS");field.setAccessible(true);
            var views=((java.util.Map<?,java.util.Map<Integer,Object>>)field.get(null)).get(player);
            var view=views==null?null:views.get(target.getId());if(view==null) return null;
            var pose=part(view,"pose");
            if(level.getGameTime()-(long)part(pose,"tick")>ModelHits.MAX_SYNC_AGE) return null;
            var boxes=((List<ModelBox>)part(pose,"boxes")).stream().filter(b -> ModelWool.visible(target,b)).toList();
            return MobRayOracle.drawn(target,boxes);
        } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    // the trace (-Dntmgunfeel.trace): the hitbox's parts as the check sees them (origin and the three edges, relative
    // to the mob, before the anchor's turn), then the ray grid's cells x by y (17 by 19, x outer), 1 = hit: what the
    // game's hit test gave and what the independent walk over the surface expects
    private static void trace(LivingEntity target,Snapshot pose,String label,int rays,CharSequence got,CharSequence want) {
        for(ModelBox b:pose.boxes())
            TestLog.at(target.level(),-1,"hitbox","case",label,"part",b.part(),"at",new double[]{b.x(),b.y(),b.z()},
                    "u",new double[]{b.ux(),b.uy(),b.uz()},"v",new double[]{b.vx(),b.vy(),b.vz()},"w",new double[]{b.wx(),b.wy(),b.wz()},
                    "triangles",b.mesh()==0?0:ModelMesh.get(b.mesh()).triangles().length/9);
        TestLog.at(target.level(),-1,"mask","case",label,"source",pose.source(),"anchor",pose.anchor(),"body",target.yBodyRot,
                "pos",target.position(),"parts",pose.boxes().size(),"rays",rays,"hit",got.toString(),"oracle",want.toString());
    }

    private static boolean clearRay(LivingEntity target,MobRayOracle.Ray ray) {
        return target.level().isClientSide() || target.level().clip(new net.minecraft.world.level.ClipContext(ray.from(),ray.to(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,target)).getType()==HitResult.Type.MISS;
    }

    private static void nativeRay(ServerLevel level,LivingEntity target,MobRayOracle.Ray ray,boolean expected,String label) {
        nativeRay(level,target,ray,expected,label,null);
    }
    private static void nativeRay(ServerLevel level,LivingEntity target,MobRayOracle.Ray ray,boolean wanted,String label,List<MobRayOracle.Triangle> seen) {
        if(ray==null) { RESULTS.add(new Result(false,label+": нет контрольного луча")); return; }
        // the probes are the player's: while the player's view is fresh they go by it
        boolean expected=seen==null?wanted:MobRayOracle.hit(seen,ray.from(),ray.to());
        if(expected!=wanted) label+=" (по позе, которую видел игрок: "+wanted+"→"+expected+")";
        var block=level.clip(new net.minecraft.world.level.ClipContext(ray.from(),ray.to(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,target));
        if(block.getType()!=HitResult.Type.MISS) {
            RESULTS.add(new Result(false,label+": ошибка стенда, луч перекрыт блоком "+block.getBlockPos()+", начало="+ray.from()));
            return;
        }
        float health=target.getHealth();
        var bullet=new ProbeBullet(level,target);
        PROBES.add(bullet.getId());
        float speed=(float)Math.max(1,ray.from().distanceTo(ray.to())/
                (XFactory9mm.p9_ap.velocity*ReaimTimes.number("bulletSpeed",1.6F)));
        var direction=ray.to().subtract(ray.from());
        bullet.setPos(ray.from()); bullet.setThrowableHeading(direction.x,direction.y,direction.z,speed,0); bullet.damage=0;
        // Это контрольный сегмент уже летящей пули. Её первый тик у дула
        // отдельно проверяет runCheckClient; перенос к глазам наблюдателя
        // здесь изменил бы заданный луч и дал ложное попадание в пустоту.
        bullet.ticksInAir=1;
        bullet.tick(); bullet.discard();
        var arrow=new ProbeArrow(level,target);
        arrow.setPos(ray.from()); arrow.setNoGravity(true); arrow.setDeltaMovement(ray.to().subtract(ray.from()));
        arrow.tick(); arrow.discard();
        var beam=new ProbeBeam(level,target);
        beam.setPos(ray.from()); beam.setRotationsFromVector(ray.to().subtract(ray.from()).normalize());
        Vec3 actualBeamEnd=ray.from().add(beam.headingX*ray.from().distanceTo(ray.to()),beam.headingY*ray.from().distanceTo(ray.to()),beam.headingZ*ray.from().distanceTo(ray.to()));
        var directBeam=target.getBoundingBox().inflate(.3).clip(ray.from(),actualBeamEnd);
        beam.performHitscanExternal(ray.from().distanceTo(ray.to())); beam.discard();
        boolean untouched=!target.isRemoved() && target.getHealth()==health;
        // тот же луч в выстреле игрока (ModelHits берёт позу, которую видел стрелок)
        var shot=ModelHits.beginProjectile(level.getServer().getPlayerList().getPlayers().getFirst(),ray.from().distanceTo(ray.to()));
        boolean inShot;
        try { inShot=ProjectileUtil.getEntityHitResult(level,(Entity)null,ray.from(),ray.to(),new AABB(ray.from(),ray.to()).inflate(.001),e -> e==target,.3F)!=null; }
        finally { ModelHits.endShot(shot); }
        RESULTS.add(new Result(bullet.hit==expected && arrow.hit==expected && beam.hit==expected && untouched,label+": ожидание="+expected+
                ", NTM-пуля="+bullet.hit+", стрела="+arrow.hit+", NTM-луч="+beam.hit+", цель сохранена="+untouched
                +", pickable="+target.isPickable()+", в выстреле игрока="+inShot+", луч попал="+beam.impact
                +", directBeam="+directBeam.isPresent()+", beamDelta="+actualBeamEnd.subtract(ray.to())
                +", box="+target.getBoundingBox().getClass().getSimpleName()));
    }

    private static final class ProbeBullet extends EntityBulletBaseMK4 implements CollisionProbe {
        private final Entity target;
        private boolean hit;
        ProbeBullet(ServerLevel level,Entity target) { this(level,target,true); }
        // without a thrower: a turret's bullet
        ProbeBullet(ServerLevel level,Entity target,boolean player) { super(level,XFactory9mm.p9_ap,1,0,0,0); this.target=target;
            if(player) setThrower(level.getServer().getPlayerList().getPlayers().getFirst()); }
        @Override protected void onImpact(HitResult result) {
            if(result instanceof EntityHitResult entity && entity.getEntity().is(target)) hit=true;
        }
    }
    private static final class ProbeArrow extends Arrow {
        private final Entity target;
        private boolean hit;
        ProbeArrow(ServerLevel level,Entity target) { super(EntityType.ARROW,level); this.target=target; setBaseDamage(0);
            setOwner(level.getServer().getPlayerList().getPlayers().getFirst()); }
        @Override protected net.minecraft.world.entity.projectile.ProjectileDeflection hitTargetOrDeflectSelf(HitResult result) {
            if(result instanceof EntityHitResult entity && entity.getEntity().is(target)) hit=true;
            return super.hitTargetOrDeflectSelf(result);
        }
        @Override protected void onHitEntity(EntityHitResult result) {
            if(result.getEntity().is(target)) hit=true;
        }
    }
    private static final class ProbeBeam extends EntityBulletBeamBase {
        private final Entity target;
        private boolean hit;
        private String impact="нет";
        ProbeBeam(ServerLevel level,Entity target) { super(level,beamConfig,0); this.target=target;
            this.thrower=level.getServer().getPlayerList().getPlayers().getFirst(); }
        @Override protected void onImpact(HitResult result) {
            if(result instanceof EntityHitResult entity) {
                impact=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getEntity().getType()).toString();
                if(entity.getEntity().is(target)) hit=true;
            }
        }
    }
}
