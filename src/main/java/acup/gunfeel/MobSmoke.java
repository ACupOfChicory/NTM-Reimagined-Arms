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
    private static volatile List<String> CASES = List.of();
    private record Snapshot(float anchor, List<ModelBox> boxes, String source) {}
    private record Result(boolean ok, String text) {}
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    private static volatile int id = -1;
    private static volatile Snapshot client, server;
    private static volatile boolean pending;
    private static int index=-1, stage, orange, white, sameFrame, oldFrame;
    private static boolean watch;
    private static final boolean CONTOUR_SHOTS=Boolean.getBoolean("ntmgunfeel.contourShots");
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
        if(!ENABLED || review() || !CONTOUR_SHOTS || stage!=2 || seconds()<.3 || photographed==index) return;
        photographed=index; String name=current().replace(':','_');
        net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(),image -> {
            try(image) { image.writeToFile(java.nio.file.Path.of("../notes/accurate-hitboxes/contour-"+name+".png")); }
            catch(java.io.IOException ex) { debugProbe(false,"сохранение контура: "+ex); }
        });
    }
    static boolean review() { return Boolean.getBoolean("ntmgunfeel.mobReview"); }
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
                +"\ncamera="+mc.gameRenderer.mainCamera().position()+"\nclient="+client+"\nserver="+server+"\n";
    }

    static void captured(LivingEntity e, float anchor, List<ModelBox> boxes, String source) {
        if (ENABLED && e.getId()==id) client=new Snapshot(anchor,List.copyOf(boxes),source);
    }
    static void accepted(LivingEntity e, float anchor, List<ModelBox> boxes) {
        if (ENABLED && e.getId()==id) server=new Snapshot(anchor,List.copyOf(boxes),"network");
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
        mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.ALWAYS_ON);
        pending=true;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var level=mc.getSingleplayerServer().overworld();
                CASES=System.getProperty("ntmgunfeel.mobCases", "").isBlank()?MobCatalog.discover():
                        java.util.Arrays.stream(System.getProperty("ntmgunfeel.mobCases").split(",")).map(String::trim).toList();
                java.nio.file.Path catalog=java.nio.file.Path.of(System.getProperty("ntmgunfeel.checkClient")).resolveSibling("catalog.txt");
                java.nio.file.Files.createDirectories(catalog.getParent());
                java.nio.file.Files.write(catalog,CASES);
                RESULTS.add(new Result(!CASES.isEmpty(),"Реестр живых сущностей: "+CASES.size()+", все пространства имён"));
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
        if(review()) MobReview.init();
    }

    static void frame() {
        Result result;
        while((result=RESULTS.poll())!=null) {
            ClientSmoke.check(result.ok(),result.text());
            if(review() && result.text().startsWith(current()+":")) MobReview.checked(result.ok(),result.text());
        }
        var mc=Minecraft.getInstance();
        if(mc.player==null || pending) return;
        if(stage==22 && !review()) { ClientSmoke.finish(); return; }
        if(review()) { reviewFrame(); return; }
        mc.player.setYRot(stage==4?180:0);
        mc.player.setXRot(0);
        if(CONTOUR_SHOTS && ModelDebugRender.probesFinished() && stage!=4 && target() instanceof LivingEntity lookingAt) {
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
                    check(EntityTypes.GOAT.getDimensions().height()==PlaneHitPolicy.LARGE_SIZE,"порог размера совпадает со взрослой козой");
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
            if(index==0) check(ModelDebugRender.probesFinished(),"проверки видимости прочитали настоящие пиксели GPU");
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
            if(target instanceof net.minecraft.world.entity.animal.sheep.Sheep) {
                stage=6; since=System.nanoTime(); shearSheep();
            } else if(target.getType()==EntityTypes.SLIME) {
                stage=8; since=System.nanoTime(); checkSlimeAndResize(2);
            } else { serverRays(false); startRotation(); }
        } else if(stage==6) {
            if(target() instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && sheep.isSheared()
                    && client!=null && server!=null && !hasWool(client) && !hasWool(server) && seconds()>.6) {
                check(true,"после стрижки шерсть исчезла у клиента и сервера");
                rays(sheep,client,current()+": стриженая, клиент");
                stage=7; since=System.nanoTime(); regrowSheep();
            } else if(seconds()>6) { check(false,"шерсть не исчезла после стрижки"); startRotation(); }
        } else if(stage==7) {
            if(target() instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && !sheep.isSheared()
                    && hasWool(client) && hasWool(server) && seconds()>.6) {
                check(true,"после отрастания шерсть вернулась у клиента и сервера");
                rays(sheep,client,current()+": шерсть отросла, клиент");
                checkRegrownSheep(); startRotation();
            } else if(seconds()>6) { check(false,"шерсть не вернулась после отрастания"); startRotation(); }
        } else if(stage==8 || stage==9) {
            int expected=stage==8?2:4;
            if(target() instanceof net.minecraft.world.entity.monster.cubemob.Slime slime && slime.getSize()==expected
                    && hasShell(client) && hasShell(server) && seconds()>1) {
                check(true,"оболочка обновилась при размере="+expected);
                checkSlimeScale(client,expected,"клиент");
                rays(slime,client,current()+": размер "+expected+", клиент");
                if(stage==8) { stage=9; since=System.nanoTime(); checkSlimeAndResize(4); }
                else { checkSlimeAndResize(0); startRotation(); }
            } else if(seconds()>6) { check(false,"оболочка не обновилась после изменения размера"); startRotation(); }
        } else if(stage==5 && seconds()>1) {
            watch=false;
            check(orange>0 && white==0,"F3+B при вращении: оранжевых="+orange+", белых="+white);
            if(client!=null && !client.source().startsWith("ntm_"))
                check(sameFrame>0 && oldFrame==0,"При вращении: применённая поза="+sameFrame+", отставших="+oldFrame);
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
                var slime=(net.minecraft.world.entity.monster.cubemob.Slime)level.getEntity(id);
                String label=current()+": размер "+slime.getSize()+", сервер";
                checkSlimeScale(server,slime.getSize(),"сервер");
                rays(slime,server,label);
                var full=MobRayOracle.surface(slime,server.anchor(),server.boxes());
                var core=MobRayOracle.surface(slime,server.anchor(),server.boxes().stream().filter(b -> !b.part().endsWith(ModelBodyGeometry.SHELL_SUFFIX)).toList());
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
                var sheep=(net.minecraft.world.entity.animal.sheep.Sheep)level.getEntity(id);
                rays(sheep,server,current()+": с шерстью, сервер");
                var full=MobRayOracle.surface(sheep,server.anchor(),server.boxes());
                var bare=MobRayOracle.surface(sheep,server.anchor(),server.boxes().stream().filter(b -> !ModelWool.isWool(b)).toList());
                woolRay=MobRayOracle.grid(full).stream().filter(ray -> ray.hit() && !MobRayOracle.hit(bare,ray.from(),ray.to()) && clearRay(sheep,ray)).findFirst().orElseThrow();
                nativeRay(level,sheep,woolRay,true,current()+": точка только на шерсти до стрижки");
                sheep.shear(level,net.minecraft.sounds.SoundSource.PLAYERS,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));
                // Та же принятая поза, ни одного нового пакета между проверками.
                nativeRay(level,sheep,woolRay,false,current()+": та же точка сразу после стрижки, до нового пакета");
            } catch(Throwable t) { RESULTS.add(new Result(false,current()+": стрижка: "+t)); }
            pending=false;
        });
    }
    private static void regrowSheep() {
        pending=true;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var sheep=(net.minecraft.world.entity.animal.sheep.Sheep)Minecraft.getInstance().getSingleplayerServer().overworld().getEntity(id);
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
                var sheep=(net.minecraft.world.entity.animal.sheep.Sheep)level.getEntity(id);
                rays(sheep,server,current()+": шерсть отросла, сервер");
                nativeRay(level,sheep,woolRay,true,current()+": та же точка после отрастания шерсти");
            } catch(Throwable t) { RESULTS.add(new Result(false,current()+": проверка отрастания: "+t)); }
            pending=false;
        });
    }
    private static void check(boolean ok,String text) { ClientSmoke.check(ok,CASES.get(index)+": "+text); }
    private static void next() {
        if(index+1>=CASES.size()) {
            ClientSmoke.check(!PROBES.isEmpty() && probeTracers==0,"Контрольные пули="+PROBES.size()+", сетевые трассеры="+probeTracers);
            ClientSmoke.finish(); return;
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
                id=-1; client=server=null; woolRay=null; watch=false; orange=white=0; gpuChecks=gpuMismatch=geometryChecks=geometryMismatch=0;
                RESULTS.add(new Result(true,CASES.get(index)+": стенд очищен до спавна, оставшихся сущностей=0, peas="+peas));
                var type=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(CASES.get(index)));
                Entity created=type.create(level,EntitySpawnReason.COMMAND);
                if(!(created instanceof LivingEntity target)) throw new IllegalStateException("не живой моб: "+created);
                // Ghost штатно исчезает рядом с обычным игроком даже с NoAI.
                // Наблюдатель исключён из его проверки присутствия игроков.
                mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst()
                        .setGameMode(CASES.get(index).equals("hbm:entity_ghost")?GameType.SPECTATOR:GameType.CREATIVE);
                target.setPos(8.5,100,16.5);
                target.setYRot(180); target.yBodyRot=180; target.yHeadRot=180;
                target.setNoGravity(true); target.setInvulnerable(true);
                if(target instanceof Mob mob) { mob.setNoAi(true); mob.setPersistenceRequired(); }
                id=target.getId();
                level.addFreshEntity(target);
                if(review()) positionCamera(target);
                stage=1; since=System.nanoTime();
            } catch(Throwable t) {
                RESULTS.add(new Result(false,CASES.get(selected)+": очистка/создание: "+t));
                stage=22;
            }
            pending=false;
        });
    }

    private static void reviewFrame() {
        if(stage==22) {
            MobReview.autoStatus("Очистка не удалась. Новый моб не создан; переключение можно повторить.");
            MobReview.frame(); return;
        }
        if(index<0) {
            select(MobReview.resume());
            return;
        }
        if(stage==1 && seconds()>1 && target()!=null) watch=true;
        if(stage==1 && ((hasPose() && seconds()>1) || seconds()>6)) {
            watch=false;
            MobReview.autoStatus(hasPose()?"Геометрия получена; выполняется проверка лучей":"Нет полной цепочки модели и серверной позы");
            if(hasPose() && target() instanceof LivingEntity living) {
                rays(living,client,current()+": просмотр, клиент");
                serverRays(false);
            }
            stage=20;
        }
        MobReview.frame();
    }

    private static void positionCamera(LivingEntity target) {
        var p=Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayers().getFirst();
        double distance=Math.min(75,Math.max(3,Math.max(target.getBbWidth(),target.getBbHeight())*1.3));
        p.getAbilities().flying=true;
        p.onUpdateAbilities();
        p.teleportTo((ServerLevel)target.level(),target.getX(),target.getY()+target.getBbHeight()/2-p.getEyeHeight(),
                target.getZ()-distance,java.util.Set.of(),0,0,false);
        p.setDeltaMovement(Vec3.ZERO);
    }

    static void camera() {
        var mc=Minecraft.getInstance();
        mc.getSingleplayerServer().execute(() -> {
            var target=mc.getSingleplayerServer().overworld().getEntity(id);
            if(target instanceof LivingEntity living) positionCamera(living);
        });
    }
    static void rotate(float delta) {
        var mc=Minecraft.getInstance();
        mc.getSingleplayerServer().execute(() -> {
            var target=mc.getSingleplayerServer().overworld().getEntity(id);
            if(target instanceof LivingEntity living) {
                living.setYRot(living.getYRot()+delta); living.yBodyRot=living.getYRot(); living.yHeadRot=living.getYRot();
            }
        });
    }

    private static void serverRays(boolean moved) {
        pending=true;
        String label=CASES.get(index)+": сервер"+(moved?", после движения":"");
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var target=(LivingEntity)Minecraft.getInstance().getSingleplayerServer().overworld().getEntity(id);
                if(target==null || server==null) RESULTS.add(new Result(false,label+": нет цели или сетевой позы"));
                else {
                    rays(target,server,label);
                    if(target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
                        var head=ModelHits.worldBoxes(dragon).stream().filter(b -> b.part().equals("root/head")).findFirst().orElseThrow();
                        var center=ModelContours.point(head,.5,.5,.5);
                        var a=center.add(0,0,-.01); var b=center.add(0,0,.01);
                        var result=ProjectileUtil.getEntityHitResult(dragon.level(),(Entity)null,a,b,new AABB(a,b).inflate(.3),
                                e -> e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragonPart part && part.parentMob==dragon,.3F);
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
        var triangles=MobRayOracle.surface(target,pose.anchor(),pose.boxes());
        var grid=MobRayOracle.grid(triangles);
        int hits=0,misses=0,mismatch=0,extra=0,gaps=0;
        MobRayOracle.Ray body=null,gap=null,wing=null;
        var model=target.getBoundingBox();
        var raw=new AABB(model.minX,model.minY,model.minZ,model.maxX,model.maxY,model.maxZ);
        for(var ray:grid) {
            boolean actual=ProjectileUtil.getEntityHitResult(target.level(),(Entity)null,ray.from(),ray.to(),
                    new AABB(ray.from(),ray.to()).inflate(.001),e -> e==target,.3F)!=null;
            if(ray.hit()) { hits++; if(body==null && clearRay(target,ray)) body=ray; } else misses++;
            if(actual!=ray.hit()) {
                mismatch++;
                ModelHitLog.event(target.level().isClientSide()?"client":"server","oracle_mismatch",target,
                        () -> "expected="+ray.hit()+" actual="+actual+" from="+ray.from()+" to="+ray.to()+" anchor="+pose.anchor()+" current="+target.yBodyRot);
            }
            if(ray.hit() && raw.clip(ray.from(),ray.to()).isEmpty()) { extra++; if(clearRay(target,ray)) wing=ray; }
            if(!ray.hit() && raw.inflate(.3).clip(ray.from(),ray.to()).isPresent()) { gaps++; if(clearRay(target,ray)) gap=ray; }
        }
        RESULTS.add(new Result(hits>0 && misses>0 && mismatch==0,label+": лучи="+grid.size()+", модель="+hits+
                ", пустота="+misses+", расхождений="+mismatch+", вне AABB="+extra+", пустота внутри AABB+0.3="+gaps));
        if(!target.level().isClientSide()) {
            nativeRay((ServerLevel)target.level(),target,body,true,label+": тело");
            if(gap!=null) nativeRay((ServerLevel)target.level(),target,gap,false,label+": пустота внутри AABB+0.3");
            if(wing!=null) nativeRay((ServerLevel)target.level(),target,wing,true,label+": выступ за AABB");
        }
    }

    private static boolean clearRay(LivingEntity target,MobRayOracle.Ray ray) {
        return target.level().isClientSide() || target.level().clip(new net.minecraft.world.level.ClipContext(ray.from(),ray.to(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,target)).getType()==HitResult.Type.MISS;
    }

    private static void nativeRay(ServerLevel level,LivingEntity target,MobRayOracle.Ray ray,boolean expected,String label) {
        if(ray==null) { RESULTS.add(new Result(false,label+": нет контрольного луча")); return; }
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
        bullet.setPos(ray.from()); bullet.setThrowableHeading(0,0,1,speed,0); bullet.damage=0;
        bullet.tick(); bullet.discard();
        var arrow=new ProbeArrow(level,target);
        arrow.setPos(ray.from()); arrow.setNoGravity(true); arrow.setDeltaMovement(ray.to().subtract(ray.from()));
        arrow.tick(); arrow.discard();
        var beam=new ProbeBeam(level,target);
        beam.setPos(ray.from()); beam.setRotationsFromVector(ray.to().subtract(ray.from()).normalize());
        beam.performHitscanExternal(ray.from().distanceTo(ray.to())); beam.discard();
        boolean untouched=!target.isRemoved() && target.getHealth()==health;
        RESULTS.add(new Result(bullet.hit==expected && arrow.hit==expected && beam.hit==expected && untouched,label+": ожидание="+expected+
                ", NTM-пуля="+bullet.hit+", стрела="+arrow.hit+", NTM-луч="+beam.hit+", цель сохранена="+untouched));
    }

    private static final class ProbeBullet extends EntityBulletBaseMK4 implements CollisionProbe {
        private final Entity target;
        private boolean hit;
        ProbeBullet(ServerLevel level,Entity target) { super(level,XFactory9mm.p9_ap,1,0,0,0); this.target=target; }
        @Override protected void onImpact(HitResult result) {
            if(result instanceof EntityHitResult entity && entity.getEntity().is(target)) hit=true;
        }
    }
    private static final class ProbeArrow extends Arrow {
        private final Entity target;
        private boolean hit;
        ProbeArrow(ServerLevel level,Entity target) { super(EntityTypes.ARROW,level); this.target=target; setBaseDamage(0); }
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
        ProbeBeam(ServerLevel level,Entity target) { super(level,beamConfig,0); this.target=target; }
        @Override protected void onImpact(HitResult result) {
            if(result instanceof EntityHitResult entity && entity.getEntity().is(target)) hit=true;
        }
    }
}
