package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Настоящий клиент, отдельный мир и штатный обработчик выстрела NTM.
 * Без свойства JVM не регистрирует событий и не меняет часы игры. */
public final class ClientSmoke {
    private static final String FILE = System.getProperty("ntmgunfeel.checkClient");
    private static final boolean ENABLED = FILE != null && !FILE.isBlank();
    private static final List<String> REPORT = new ArrayList<>();
    private static final long START = System.nanoTime();
    private static volatile int phase;
    private static int frames, holdUntil, packets, zeroFrames, zeroExpired, traces, particles, holes;
    private static double held = Double.NaN, pausedClock;
    private static long since;
    private static long screenLogAt;
    private static volatile boolean ready, pending, broken;
    private static volatile int remaining;
    private static int serverTick;
    private static boolean passed = true, finished;
    private static org.joml.Matrix4f drawnArm, armBefore;
    private static final boolean WEAPON_SWAY=Boolean.getBoolean("ntmgunfeel.weaponSwaySelfTest");
    private static WeaponSway.Turn observedTurn;
    private static int suppressedLag;
    public static void weaponSway(WeaponSway.Turn turn,float aiming) {
        if(ENABLED && WEAPON_SWAY)observedTurn=turn;
    }
    public static void weaponLag(float value) {
        if(ENABLED && WEAPON_SWAY && value==0)suppressedLag++;
    }
    static void armPose(org.joml.Matrix4f pose,boolean right) {
        if(Boolean.getBoolean("ntmgunfeel.armEditorSelfTest") && !right) drawnArm=new org.joml.Matrix4f(pose);
    }
    private static void editorButton(String name) {
        var screen=Minecraft.getInstance().gui.screen();
        var button=screen.children().stream().filter(w->w instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(name)).findFirst().orElseThrow();
        ((net.minecraft.client.gui.components.Button)button).onPress(new net.minecraft.client.input.KeyEvent(257,0,0));
    }

    private ClientSmoke() {}

    public static void init() {
        if (!ENABLED) return;
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, e -> frame());
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> tick());
    }

    static void packet() {
        if (!ENABLED) return;
        if(HoleBenchMetrics.ENABLED) { HoleBenchMetrics.projectiles++; return; }
        packets++;
        held = Double.NaN;
        holdUntil = frames + 2;
    }

    static double flightTime(double real) {
        if (!ENABLED || frames > holdUntil || packets == 0) return real;
        if (Double.isNaN(held)) held = real;
        return held;
    }

    static void segment(double age, boolean expired) {
        if (ENABLED && age == 0) {
            zeroFrames++;
            if (expired) zeroExpired++;
        }
    }
    static void tracerDrawn(int count) { if (ENABLED) traces += count; }
    static void particleAdded() { if (ENABLED) particles++; }
    static void holeDrawn() { if (ENABLED) holes++; }

    private static void frame() {
        if (finished) return;
        frames++;
        Minecraft mc = Minecraft.getInstance();
        if(WEAPON_SWAY && phase>=30 && phase<=36 && mc.player!=null) {
            float pitch=phase==36?0:10,yaw=phase==36?0:phase==32||phase==34?-220:phase==33||phase==35?220:20;
            mc.player.setXRot(pitch);mc.player.xRotO=pitch;mc.player.xBobO=mc.player.xBob=0;
            mc.player.setYRot(yaw);mc.player.yRotO=yaw;mc.player.yBobO=mc.player.yBob=0;
            ItemGunBaseNT.prevAimingProgress=ItemGunBaseNT.aimingProgress=phase==31||phase==34||phase==35?1:0;
        }
        try {
            if((HoleBenchMetrics.ENABLED || MobSmoke.enabled()) && phase==0 && mc.gui.screen()!=null
                    && mc.gui.screen().getClass().getName().equals("net.neoforged.neoforge.client.gui.LoadingErrorScreen")) {
                var screen=mc.gui.screen();
                var errors=screen.getClass().getDeclaredField("modLoadErrors"); errors.setAccessible(true);
                if(!((List<?>)errors.get(screen)).isEmpty()) { check(false,"ошибки загрузки модов в тестовом клиенте"); finish(); return; }
                var next=screen.getClass().getDeclaredField("nextScreenTask"); next.setAccessible(true);
                GunFeel.LOGGER.info("[clientcheck] продолжаем после предупреждений NeoForge без ошибок загрузки");
                ((Runnable)next.get(screen)).run(); return;
            }
            if(HoleBenchMetrics.ENABLED && phase<2 && System.currentTimeMillis()>screenLogAt) {
                screenLogAt=System.currentTimeMillis()+5000;
                GunFeel.LOGGER.info("[holebench] запуск phase={} screen={} world={}",phase,
                        mc.gui.screen()==null?"null":mc.gui.screen().getClass().getName(),mc.level!=null);
            }
            if (!MobSmoke.review() && System.nanoTime() - START > (TraceScenario.enabled()?TraceScenario.timeout():MobBenchMetrics.ENABLED || HoleBenchMetrics.ENABLED?900_000_000_000L:MobSmoke.enabled() ? 3_600_000_000_000L : 180_000_000_000L)) {
                check(false, "таймаут клиента; экран=" + mc.gui.screen());
                finish();
                return;
            }
            if (phase == 0 && mc.gui.screen() instanceof TitleScreen) {
                phase = 1;
                mc.options.pauseOnLostFocus = false;
                mc.createWorldOpenFlows().openWorld("gunfeel-smoke", () -> {
                    check(false, "мир не открыт");
                    finish();
                });
            } else if (phase == 1 && mc.level != null && mc.player != null && mc.getSingleplayerServer() != null) {
                if(HoleBenchMetrics.ENABLED) { phase=12; mc.gui.setScreen(null); HoleBenchmark.start(); return; }
                if(MobBenchMetrics.ENABLED) { phase=11; mc.gui.setScreen(null); MobBenchmark.start(); return; }
                if(TraceScenario.enabled()) { phase=13; mc.gui.setScreen(null); TraceScenario.start(); return; }
                if (MobSmoke.enabled()) {
                    phase = 10;
                    mc.gui.setScreen(null);
                    MobSmoke.start();
                    return;
                }
                phase = 2;
                since = System.nanoTime();
                prepare(20);
            } else if (phase == 2 && ready && elapsed() > 5) {
                if(WEAPON_SWAY) {
                    var gun=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.parse("hbm:gun_g3")).orElseThrow().value();
                    mc.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(gun));mc.gui.setScreen(null);
                    phase=30;since=System.nanoTime();return;
                }
                if (Boolean.getBoolean("ntmgunfeel.armEditorSelfTest")) {
                    var gun=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.parse("hbm:gun_g3")).orElseThrow().value();
                    mc.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(gun));
                    Config.select("hbm:gun_g3");
                    Grips.put("hbm:gun_g3","armLeftAdjustments","",false);
                    Grips.put("hbm:gun_g3","armRightAdjustments","",false);Config.select("hbm:gun_g3");
                    mc.gui.setScreen(new ArmAnimationEditor(null,gun,"hbm:gun_g3"));
                    check(ArmAnimationPreview.active(),"редактор открыл настоящий предпросмотр G3");
                    phase=20;since=System.nanoTime();return;
                }
                mc.gui.setScreen(null);
                reset();
                remaining = 5;
                phase = 3;
                since = System.nanoTime();
            } else if (phase == 3 && elapsed() > 6 && remaining == 0) {
                inspect("20 TPS");
                phase = 4;
                since = System.nanoTime();
            } else if (phase == 4 && !pending) {
                prepare(2);
                phase = 5;
                since = System.nanoTime();
            } else if (phase == 5 && ready && elapsed() > 2) {
                reset();
                remaining = 5;
                phase = 6;
                since = System.nanoTime();
            } else if (phase == 6 && elapsed() > 9 && remaining == 0) {
                inspect("2 TPS");
                phase = 7;
            } else if (phase == 7 && !pending) {
                mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().tickRateManager().setTickRate(20));
                mc.pauseGame(false);
                phase = 8;
                since = System.nanoTime();
            } else if (phase == 8 && elapsed() > 1) {
                check(mc.gui.screen() instanceof PauseScreen, "настоящая пауза открыта");
                pausedClock = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
                since = System.nanoTime();
                phase = 9;
            } else if (phase == 9 && elapsed() > 1) {
                double now = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
                check(Math.abs(now - pausedClock) < 0.001, "игровые часы неподвижны в паузе: " + pausedClock + " -> " + now);
                mc.gui.setScreen(null);
                finish();
            }
            if(WEAPON_SWAY && phase>=30 && phase<=36 && elapsed()>1) {
                var expected=WeaponSway.sample(phase==36?0:10,phase==36?0:phase==32||phase==34?-220:phase==33||phase==35?220:20,phase==31||phase==34||phase==35?1:0);
                check(observedTurn!=null && Math.abs(observedTurn.pitch()-expected.pitch())<1e-5
                        && Math.abs(observedTurn.yaw()-expected.yaw())<1e-5
                        && Math.abs(observedTurn.roll()-expected.roll())<1e-5
                        && Math.abs(observedTurn.x()-expected.x())<1e-5,
                        "Superb Warfare в рендере "+(phase==30?"от бедра":phase==31?"в прицеле":phase==32?"от бедра -220°":phase==33?"от бедра +220°":phase==34?"в прицеле -220°":phase==35?"в прицеле +220°":"после остановки")+": "+observedTurn);
                check(suppressedLag>0,"штатное отставание NTM отключено в настоящем рендере");
                if(phase==36){finish();return;}
                suppressedLag=0;observedTurn=null;phase++;since=System.nanoTime();return;
            }
            if (phase == 20 && elapsed() > 2) {
                ArmAnimationPreview.play(false);ArmAnimationPreview.seek(510);
                check(ArmAnimationPreview.millis()==510,"курсор установлен на 510 мс");
                check(com.hbm.render.anim.HbmAnimations.getGunAnimation(0)==com.hbm.render.anim.AnimationEnums.GunAnimation.RELOAD,"тип анимации синхронизирован");
                var expected=ArmAnimationPreview.animation().animation.getBus("PLUG").getTransformation(510);
                var actual=com.hbm.render.anim.HbmAnimations.getRelevantTransformation("PLUG");
                check(java.util.Arrays.equals(expected,actual),"деталь оружия перемотана к курсору");
                phase=21;since=System.nanoTime();
            } else if (phase == 21 && elapsed() > 1) {
                check(ArmAnimationPreview.millis()==510,"пауза держит кадр при настоящем рендере");
                net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(),image -> {
                    try(image) { image.writeToFile(Path.of("../notes/arm-editor-check.png")); }
                    catch(java.io.IOException e) { GunFeel.LOGGER.warn("Не удалось сохранить кадр редактора",e); }
                });
                ArmAnimationPreview.seek(0);phase=23;since=System.nanoTime();
            } else if(phase==23 && elapsed()>1) {
                armBefore=drawnArm;
                var inputs=mc.gui.screen().children().stream().filter(w->w instanceof net.minecraft.client.gui.components.EditBox).toList();
                ((net.minecraft.client.gui.components.EditBox)inputs.get(3)).setValue("30");
                check(ArmAnimationPreview.millis()==0,"правка показывает редактируемый момент");
                check(ArmPoseEdits.sample(Config.armLeftAdjustments,"RELOAD",0)[3]==30,"поворот обычного хвата сохранён");
                phase=24;since=System.nanoTime();
            } else if(phase==24 && elapsed()>1) {
                check(drawnArm!=null && armBefore!=null && !drawnArm.equals(armBefore,1e-4F),"изменена настоящая матрица отрисованной руки");
                editorButton("Отменить шаг");
                check(ArmPoseEdits.sample(Config.armLeftAdjustments,"RELOAD",0)[3]==0,"отмена вернула обычный хват");
                editorButton("Действие →");editorButton("Одинаково весь этап");
                ArmAnimationPreview.seek(400);
                var inputs=mc.gui.screen().children().stream().filter(w->w instanceof net.minecraft.client.gui.components.EditBox).toList();
                ((net.minecraft.client.gui.components.EditBox)inputs.get(0)).setValue("1");
                check(ArmAnimationPreview.millis()==400,"правка положения сохраняет курсор внутри этапа");
                ((net.minecraft.client.gui.components.EditBox)inputs.get(10)).setValue("20");
                check(ArmAnimationPreview.millis()==400,"правка начала сохраняет курсор внутри этапа");
                ((net.minecraft.client.gui.components.EditBox)inputs.get(11)).setValue("50");
                check(ArmPoseEdits.sample(Config.armLeftAdjustments,"RELOAD",180)[5]==20,"начало действия: 20 градусов");
                check(ArmPoseEdits.sample(Config.armLeftAdjustments,"RELOAD",585)[5]==50,"конец действия: 50 градусов");
                check(ArmAnimationPreview.millis()==400,"правка конца сохраняет курсор внутри этапа");
                editorButton("Касаться углом кисти");
                check(ArmAnimationPreview.millis()==400,"смена контакта сохраняет курсор внутри этапа");
                check(Config.armLeftPath.contains("Handle!10@"),"выбрана середина внутреннего ребра торца");
                editorButton("Ребро: внутреннее");
                check(Config.armLeftPath.contains("Handle!11@"),"выбрана середина внешнего ребра торца");
                editorButton("Касаться ребром кисти");
                check(Config.armLeftPath.contains("Handle!5@"),"выбран контакт серединой внутренней грани");
                editorButton("Плоскость: внутренняя");
                check(Config.armLeftPath.contains("Handle!6@"),"выбрана середина внешней грани");
                editorButton("Касаться серединой плоскости");editorButton("Контакт: как в исходной анимации");
                editorButton("Угол: внутренний, передний");
                check(Config.armLeftPath.contains("Handle!2@"),"выбран другой угол кисти");
                ArmAnimationPreview.seek(1900);
                check(ArmAnimationPreview.millis()==1900,"перемотка вперёд");
                ArmAnimationPreview.seek(100);
                check(ArmAnimationPreview.millis()==100,"перемотка назад");
                ArmAnimationPreview.play(true);ArmAnimationPreview.speed(.1);
                phase=22;since=System.nanoTime();
            } else if (phase == 22 && elapsed() > 1) {
                int position=ArmAnimationPreview.millis();
                check(position>150 && position<400,"замедление ×0,1 работает в клиенте, t="+position);
                ((ArmAnimationEditor)mc.gui.screen()).onClose();
                check(!ArmAnimationPreview.active(),"после закрытия предпросмотр отключён");
                finish();return;
            }
            if (phase == 10) MobSmoke.frame();
            if (phase == 11) MobBenchmark.frame();
            if (phase == 12) HoleBenchmark.frame();
            if (phase == 13) TraceScenario.frame();
            if (mc.player != null && phase >= 2 && phase <= 7) {
                mc.player.setYRot(0);
                mc.player.setXRot(0);
            }
        } catch (Throwable t) {
            check(false, "исключение: " + t);
            GunFeel.LOGGER.error("[clientcheck]", t);
            finish();
        }
    }

    private static double elapsed() { return (System.nanoTime() - since) / 1e9; }

    @SuppressWarnings("unchecked")
    private static void prepare(float rate) {
        ready = false;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var server = Minecraft.getInstance().getSingleplayerServer();
                var level = server.overworld();
                ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                p.setGameMode(GameType.CREATIVE);
                p.teleportTo(level, 8.5, 100, 8.5, java.util.Set.of(), 0, 0, false);
                for (int x = 4; x <= 12; x++) for (int z = 4; z <= 36; z++) {
                    level.setBlock(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState(), 3);
                    for (int y = 100; y <= 106; y++)
                        level.setBlock(new BlockPos(x, y, z),
                                (z == 16 ? Blocks.GLASS : z == 32 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
                }
                ItemStack stack = new ItemStack(com.hbm.items.ModItems.GUN_UZI.get());
                ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
                var magazine = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
                magazine.setType(stack, XFactory9mm.p9_ap);
                magazine.setAmount(stack, 30);
                ItemGunBaseNT.setIsDrawn(stack, true);
                ItemGunBaseNT.setIsEquipped(stack, true);
                ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
                ItemGunBaseNT.setTimer(stack, 0, 0);
                p.getInventory().setSelectedSlot(0);
                p.setItemInHand(InteractionHand.MAIN_HAND, stack);
                server.tickRateManager().setTickRate(rate);
                ready = true;
            } catch (Throwable t) {
                Minecraft.getInstance().execute(() -> { check(false, "подготовка: " + t); finish(); });
            }
        });
    }

    private static void tick() {
        if(HoleBenchMetrics.ENABLED && phase==12) { HoleBenchmark.tick(); return; }
        if (remaining <= 0 || !ready) return;
        if (++serverTick % (phase == 6 ? 2 : 8) != 0) return;
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        try {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.setYRot(0);
            p.setXRot(0);
            ItemStack stack = p.getMainHandItem();
            ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
            var config = gun.getConfig(stack, 0);
            config.getReceivers(stack)[0].getOnFire(stack).accept(stack,
                    new ItemGunBaseNT.LambdaContext(config, p, p.getInventory(), 0));
            remaining--;
        } catch (Throwable t) {
            remaining = 0;
            mc.execute(() -> { check(false, "стрельба: " + t); finish(); });
        }
    }

    private static void reset() {
        Tracers.clearHoles();
        packets = zeroFrames = zeroExpired = traces = particles = holes = 0;
        serverTick = 0;
    }

    private static void inspect(String label) {
        check(packets >= 5, label + ": пакеты выстрелов=" + packets);
        check(zeroFrames > 0 && zeroExpired == 0, label + ": нулевые кадры=" + zeroFrames + ", удалено=" + zeroExpired);
        check(traces > 0, label + ": отрисованные трассеры=" + traces);
        check(particles > 0, label + ": частицы попадания добавлены в движок=" + particles);
        check(holes > 0, label + ": вызовы рисования дырок=" + holes);
        pending = true;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            var level = Minecraft.getInstance().getSingleplayerServer().overworld();
            broken = false;
            for (int x = 4; x <= 12; x++) for (int y = 100; y <= 106; y++)
                if (level.getBlockState(new BlockPos(x, y, 16)).isAir()) broken = true;
            Minecraft.getInstance().execute(() -> {
                check(broken, label + ": серверная пуля разбила стекло");
                pending = false;
            });
        });
    }

    static void check(boolean success, String text) {
        passed &= success;
        String line = (success ? "PASS " : "FAIL ") + text;
        REPORT.add(line);
        GunFeel.LOGGER.info("[clientcheck] {}", line);
    }

    static void finish() {
        if (finished) return;
        finished = true;
        REPORT.add(passed ? "PASS клиентская проверка" : "FAIL клиентская проверка");
        try {
            Path file = Path.of(FILE);
            Files.createDirectories(file.getParent());
            Files.write(file, REPORT);
        } catch (Exception e) { GunFeel.LOGGER.error("[clientcheck] запись отчёта", e); }
        Minecraft.getInstance().stop();
    }
}
