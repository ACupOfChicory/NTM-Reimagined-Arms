package acup.gunfeel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hbm.client.Keybinds;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.ModItems;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;

import acup.gunfeel.tacz.CrawlKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

// runCheckClient -PproneStand: lying down on the prone key (Prone). In a flat world, with an Uzi (9 mm FMJ):
//  - the pose and the box on the client and the server, standing, lying, up again (the key pressed as a player does);
//  - NTM's spread through Spread (base 0.025) and the recoil multipliers standing, sneaking, lying, lying crawling
//    along; the camera's kick of three single shots each (the view's highest point after the shot);
//  - the crawl speed against walking, the view held 45 up and 30 down (the mouse moved far);
//  - the server getting the player up gets the client up (ProneStandPayload);
//  - no lying in the air, with an empty hand by proneWithoutGun, nothing with prone = false;
//  - a mannequin (a player's model and box) standing and lying 6 blocks off, shot with no spread at its chest's height
//    and at a lying body's: what hits, by its health.
// Lines "[prone] ..." in the log, PASS/FAIL in the report.
final class ProneStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.proneStand");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    private static final double EX = 8.5, EZ = 8.5, DIST = 6;

    private static int step, until, shots, frames;
    private static volatile int bullets;
    private static Method gunChanged;
    private static Field accumulatedDY;
    private static String fileBefore;
    private static boolean fileExisted;
    private static Mannequin dummy;
    private static float pitchBefore, peak, health;
    private static double x0, z0;
    private static String state;
    private static final Map<String, List<Float>> KICKS = new LinkedHashMap<>();
    private static final Map<String, Float> SPREAD = new LinkedHashMap<>(), CAMERA = new LinkedHashMap<>();

    private ProneStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        try {
            gunChanged = Keybinds.class.getDeclaredMethod("gunChanged", int.class, boolean.class);
            gunChanged.setAccessible(true);
            accumulatedDY = MouseHandler.class.getDeclaredField("accumulatedDY");
            accumulatedDY.setAccessible(true);
            Path f = file();
            fileExisted = Files.exists(f);
            fileBefore = fileExisted ? Files.readString(f) : null;
        } catch (Exception e) {
            fail("подготовка: " + e);
            return;
        }
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            if (!e.getLevel().isClientSide() && e.getEntity() instanceof EntityBulletBaseMK4) bullets++;
        });
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -6; x <= 6; x++) for (int z = -30; z <= 14; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 8; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            dummy = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
            dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            dummy.setHealth(1000);
            dummy.snapTo(EX, O.getY(), EZ + DIST, 90F, 0F);
            dummy.setYHeadRot(90F);
            dummy.setYBodyRot(90F);
            level.addFreshEntity(dummy);
            ServerPlayer p = player(server);
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.teleportTo(level, EX, O.getY(), EZ, Set.of(), 0F, 0F, false);
            gun(p);
        }).join();
        step = 0;
        until = now() + 40;
    }

    private static ServerPlayer player(net.minecraft.server.MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void gun(ServerPlayer p) {
        ItemStack stack = new ItemStack(ModItems.GUN_UZI.get());
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        var magazine = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
        magazine.setType(stack, XFactory9mm.p9_fmj);
        magazine.setAmount(stack, 30);
        ItemGunBaseNT.setIsDrawn(stack, true);
        ItemGunBaseNT.setIsEquipped(stack, true);
        ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
        ItemGunBaseNT.setTimer(stack, 0, 0);
        p.getInventory().setSelectedSlot(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
    }

    private static Path file() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
    }

    // the server's file with these keys set (the rest as before the stand)
    private static void keys(String... kv) throws Exception {
        String base = fileBefore == null ? "" : fileBefore;
        StringBuilder add = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            base = base.replaceAll("(?m)^" + java.util.regex.Pattern.quote(kv[i]) + "\\s*=.*\\n?", "");
            add.append(kv[i]).append(" = ").append(kv[i + 1]).append('\n');
        }
        Files.writeString(file(), base + (base.isEmpty() || base.endsWith("\n") ? "" : "\n") + add);
        Files.setLastModifiedTime(file(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
    }

    private static void restore() {
        try {
            if (fileExisted) Files.writeString(file(), fileBefore);
            else Files.deleteIfExists(file());
        } catch (Exception e) {
            GunFeel.LOGGER.warn("[prone] файл настроек не восстановлен", e);
        }
    }

    private static void fail(String text) {
        ClientSmoke.check(false, "лёжа: " + text);
        restore();
        ClientSmoke.finish();
    }

    private static void check(boolean ok, String text) {
        ClientSmoke.check(ok, "лёжа: " + text);
    }

    private static int now() {
        return Minecraft.getInstance().getSingleplayerServer().getTickCount();
    }

    private static void key() {
        KeyMapping.click(CrawlKey.CRAWL_KEY.getKey());
    }

    private static void press(boolean down) throws Exception {
        gunChanged.invoke(null, 0, down);
    }

    // what the server sees of the player: pose, box height, eye, lying by the mod
    private static String serverPose() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        String[] out = new String[1];
        server.submit(() -> {
            ServerPlayer p = player(server);
            out[0] = p.getPose().name() + " " + f(p.getBbHeight()) + " " + f(p.getEyeHeight()) + " " + Prone.serverLying(p);
        }).join();
        return out[0];
    }

    private static String clientPose() {
        var p = Minecraft.getInstance().player;
        return p.getPose().name() + " " + f(p.getBbHeight()) + " " + f(p.getEyeHeight()) + " " + ProneClient.localLying();
    }

    private static float spreadNow() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        float[] out = new float[1];
        server.submit(() -> {
            ServerPlayer p = player(server);
            out[0] = Spread.apply(p, p.getMainHandItem(), false, 0.025F);
        }).join();
        return out[0];
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        try {
            if (step >= 100 && step < 200 && shots > 0) peak = Math.min(peak, mc.player.getXRot());
            switch (step) {
                case 0 -> {
                    if (now() < until) return;
                    if (!(mc.player.getMainHandItem().getItem() instanceof ItemGunBaseNT) || !mc.player.onGround()) return;
                    mc.player.setYRot(0F);
                    mc.player.setXRot(0F);
                    String c = clientPose(), s = serverPose();
                    GunFeel.LOGGER.info("[prone] стоя: клиент {} сервер {}", c, s);
                    check(c.startsWith("STANDING 1.800") && s.startsWith("STANDING 1.800"), "стоя поза и коробка: клиент " + c + ", сервер " + s);
                    // walking speed, for the crawl's
                    x0 = mc.player.getX();
                    z0 = mc.player.getZ();
                    mc.options.keyUp.setDown(true);
                    until = now() + 20;
                    step = 1;
                }
                case 1 -> {
                    if (now() < until) return;
                    mc.options.keyUp.setDown(false);
                    double walk = Math.hypot(mc.player.getX() - x0, mc.player.getZ() - z0) / 20;
                    CAMERA.put("шаг стоя", (float) walk);
                    back(server);
                    until = now() + 20;
                    state = "стоя";
                    step = 10;
                }
                // ---- a state measured: spread, multipliers, three shots (steps 10 → 100 → 11)
                case 10 -> {
                    if (now() < until) return;
                    SPREAD.put(state, spreadNow());
                    CAMERA.put(state, Prone.cameraRecoil(mc.player));
                    KICKS.put(state, new ArrayList<>());
                    shots = 0;
                    step = 100;
                }
                case 100 -> { // a shot: the view at 0, the key down a tick
                    mc.player.setXRot(0F);
                    mc.player.xRotO = 0F;
                    pitchBefore = 0F;
                    peak = 0F;
                    shots++;
                    bullets = 0;
                    press(true);
                    until = now() + 1;
                    step = 101;
                }
                case 101 -> {
                    if (now() < until) return;
                    press(false);
                    until = now() + 25;
                    step = 102;
                }
                case 102 -> {
                    if (now() < until) return;
                    KICKS.get(state).add(bullets == 0 ? Float.NaN : (pitchBefore - peak) / bullets);
                    GunFeel.LOGGER.info("[prone] {}: выстрел {}, пуль {}, подскок {}", state, shots, bullets, f(pitchBefore - peak));
                    if (shots < 3) {
                        step = 100;
                        return;
                    }
                    shots = 0;
                    mc.player.setXRot(0F);
                    step = 11;
                }
                case 11 -> { // the next state
                    switch (state) {
                        case "стоя" -> {
                            mc.options.keyShift.setDown(true);
                            state = "сидя";
                            until = now() + 15;
                            step = 10;
                        }
                        case "сидя" -> {
                            mc.options.keyShift.setDown(false);
                            until = now() + 10;
                            step = 12;
                        }
                        case "лёжа" -> {
                            // crawling along: the speed, and the spread while moving
                            x0 = mc.player.getX();
                            z0 = mc.player.getZ();
                            mc.options.keyUp.setDown(true);
                            until = now() + 20;
                            step = 14;
                        }
                        default -> step = 20;
                    }
                }
                case 12 -> { // lie down, as a player does: the key
                    if (now() < until) return;
                    key();
                    until = now() + 10;
                    step = 13;
                }
                case 13 -> {
                    if (now() < until) return;
                    String c = clientPose(), s = serverPose();
                    GunFeel.LOGGER.info("[prone] лёжа: клиент {} сервер {}", c, s);
                    check(c.equals("SWIMMING 0.600 0.400 true") && s.equals("SWIMMING 0.600 0.400 true"),
                            "лёжа поза и коробка как у ползания (0.6, глаза 0.4): клиент " + c + ", сервер " + s);
                    state = "лёжа";
                    step = 10;
                }
                case 14 -> {
                    if (now() >= until - 2 && SPREAD.get("лёжа ползком") == null) SPREAD.put("лёжа ползком", spreadNow());
                    if (now() < until) return;
                    mc.options.keyUp.setDown(false);
                    double crawl = Math.hypot(mc.player.getX() - x0, mc.player.getZ() - z0) / 20;
                    CAMERA.put("шаг лёжа", (float) crawl);
                    float walk = CAMERA.get("шаг стоя");
                    GunFeel.LOGGER.info("[prone] шаг стоя {}, ползком {}", f(walk), f(crawl));
                    check(crawl > 0.2 * walk && crawl < 0.45 * walk, "ползком медленнее: " + f(crawl) + " против " + f(walk) + " блока за тик");
                    check(ProneClient.localLying() && mc.player.getPose() == Pose.SWIMMING, "ползком не встаёт: " + clientPose());
                    until = now() + 5;
                    step = 15;
                }
                case 15 -> { // the view: the mouse far up, then far down
                    if (now() < until) return;
                    mc.player.setXRot(0F);
                    accumulatedDY.setDouble(mc.mouseHandler, -100000);
                    until = now() + 3;
                    step = 16;
                }
                case 16 -> {
                    if (now() < until) return;
                    float up = mc.player.getXRot();
                    accumulatedDY.setDouble(mc.mouseHandler, 100000);
                    until = now() + 3;
                    step = 17;
                    CAMERA.put("вверх", up);
                }
                case 17 -> {
                    if (now() < until) return;
                    float up = CAMERA.get("вверх"), down = mc.player.getXRot();
                    GunFeel.LOGGER.info("[prone] наклон лёжа: вверх {}, вниз {}", f(up), f(down));
                    if (up == 0F && down == 0F) {
                        // the window does not take the mouse: the same arithmetic straight
                        double u = acup.gunfeel.tacz.CrawlPitch.getCrawlPitch(mc.player, -100000), d = acup.gunfeel.tacz.CrawlPitch.getCrawlPitch(mc.player, 100000);
                        check(Math.abs(u * 0.15 + 45) < 1e-3 && Math.abs(d * 0.15 - 30) < 1e-3, "наклон лёжа (окно без мыши, расчёт): вверх " + f(u * 0.15) + ", вниз " + f(d * 0.15));
                    } else {
                        check(Math.abs(up + 45) < 0.01 && Math.abs(down - 30) < 0.01, "наклон лёжа: вверх до " + f(-up) + "°, вниз до " + f(down) + "°");
                    }
                    mc.player.setXRot(0F);
                    key(); // up
                    until = now() + 10;
                    step = 18;
                }
                case 18 -> {
                    if (now() < until) return;
                    String c = clientPose(), s = serverPose();
                    check(c.equals("STANDING 1.800 1.620 false") && s.equals("STANDING 1.800 1.620 false"), "встал: клиент " + c + ", сервер " + s);
                    report();
                    until = now() + 12; // past the 10 ticks between lying downs
                    step = 19;
                }
                case 19 -> { // the server alone gets the player up (its pose taken): the client gets up too (ProneStandPayload)
                    if (now() < until) return;
                    key();
                    until = now() + 10;
                    step = 20;
                }
                case 20 -> {
                    if (now() < until) return;
                    if (!ProneClient.localLying()) {
                        fail("не лёг второй раз: " + clientPose());
                        return;
                    }
                    server.submit(() -> player(server).setForcedPose(null)).join();
                    until = now() + 10;
                    step = 21;
                }
                case 21 -> {
                    if (now() < until) return;
                    String c = clientPose(), s = serverPose();
                    check(c.equals("STANDING 1.800 1.620 false") && s.equals("STANDING 1.800 1.620 false"), "сервер поднял, клиент встал тоже: клиент " + c + ", сервер " + s);
                    // in the air: no lying
                    back(server);
                    server.submit(() -> player(server).teleportTo(EX, O.getY() + 6, EZ)).join();
                    until = now() + 3;
                    step = 30;
                }
                case 30 -> {
                    if (now() < until) return;
                    key();
                    until = now() + 3;
                    step = 31;
                }
                case 31 -> {
                    if (now() < until) return;
                    check(!ProneClient.localLying() && mc.player.getPose() != Pose.SWIMMING, "в воздухе не ложится: " + clientPose());
                    until = now() + 40;
                    step = 32;
                }
                case 32 -> { // an empty hand: lies down (proneWithoutGun), then not with it off, then nothing with prone off
                    if (now() < until || !mc.player.onGround()) return;
                    server.submit(() -> player(server).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)).join();
                    until = now() + 15; // past the 10 ticks between lying downs
                    step = 33;
                }
                case 33 -> {
                    if (now() < until) return;
                    key();
                    until = now() + 5;
                    step = 34;
                }
                case 34 -> {
                    if (now() < until) return;
                    check(ProneClient.localLying() && serverPose().endsWith("true"), "без пушки ложится: " + clientPose() + " / " + serverPose());
                    key();
                    keys("proneWithoutGun", "false");
                    until = now() + 30;
                    step = 35;
                }
                case 35 -> {
                    if (now() < until) return;
                    key();
                    until = now() + 5;
                    step = 36;
                }
                case 36 -> {
                    if (now() < until) return;
                    check(!ProneClient.localLying() && serverPose().startsWith("STANDING"), "без пушки при proneWithoutGun = false не ложится: " + clientPose());
                    server.submit(() -> gun(player(server))).join();
                    keys("prone", "false");
                    until = now() + 30;
                    step = 37;
                }
                case 37 -> {
                    if (now() < until) return;
                    key();
                    until = now() + 5;
                    step = 38;
                }
                case 38 -> {
                    if (now() < until) return;
                    check(!ProneClient.localLying() && serverPose().startsWith("STANDING"), "при prone = false не ложится: " + clientPose());
                    // the shots at the mannequin: no spread at all
                    keys("spreadStand", "0", "spreadSpeed", "0", "spreadAir", "0", "spreadBloom", "0");
                    until = now() + 30;
                    step = 40;
                    state = "стоит, грудь";
                }
                // ---- the mannequin: standing at chest height (1.2), lying at 1.2 and at its body (0.3)
                case 40 -> {
                    if (now() < until) return;
                    boolean lying = !state.startsWith("стоит");
                    double h = state.endsWith("грудь") ? 1.2 : state.endsWith("над спиной") ? 0.9 : 0.3;
                    server.submit(() -> {
                        dummy.setPose(lying ? Pose.SWIMMING : Pose.STANDING);
                        dummy.teleportTo(EX, O.getY(), EZ + DIST);
                        dummy.setDeltaMovement(0, 0, 0);
                        dummy.setHealth(1000);
                        dummy.invulnerableTime = 0;
                        ServerPlayer p = player(server);
                        p.teleportTo(EX, O.getY(), EZ);
                        gun(p);
                    }).join();
                    float pitch = (float) Math.toDegrees(Math.atan2(1.62 - h, DIST - (lying ? 0 : 0.0)));
                    mc.player.setYRot(0F);
                    mc.player.yRotO = 0F;
                    mc.player.setXRot(pitch);
                    mc.player.xRotO = pitch;
                    until = now() + 25; // the client has the pose and draws it, the model's hitbox follows
                    step = 41;
                }
                case 41 -> {
                    if (now() < until) return;
                    health = health();
                    bullets = 0;
                    press(true);
                    until = now() + 1;
                    step = 42;
                }
                case 42 -> {
                    if (now() < until) return;
                    press(false);
                    until = now() + 20;
                    step = 43;
                }
                case 43 -> {
                    if (now() < until) return;
                    float lost = health - health();
                    String pose = serverDummy();
                    GunFeel.LOGGER.info("[prone] манекен {}: {}, пуль {}, урон {}", state, pose, bullets, f(lost));
                    boolean hit = lost > 0;
                    switch (state) {
                        case "стоит, грудь" -> {
                            check(bullets > 0 && hit, "манекен стоя, выстрел в грудь (1.2) попал: урон " + f(lost) + ", " + pose);
                            state = "лежит, грудь";
                        }
                        case "лежит, грудь" -> {
                            check(bullets > 0 && !hit, "манекен лёжа, выстрел на высоте груди стоящего (1.2) мимо: урон " + f(lost) + ", " + pose);
                            state = "лежит, над спиной";
                        }
                        case "лежит, над спиной" -> {
                            check(bullets > 0 && !hit, "манекен лёжа, выстрел над спиной (0.9) мимо: урон " + f(lost) + ", " + pose);
                            state = "лежит, тело";
                        }
                        default -> {
                            check(bullets > 0 && hit, "манекен лёжа, выстрел в тело (0.3) попал: урон " + f(lost) + ", " + pose);
                            restore();
                            ClientSmoke.finish();
                            step = 999;
                            return;
                        }
                    }
                    until = now() + 5;
                    step = 40;
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            GunFeel.LOGGER.error("[prone] failed", t);
            fail(t.toString());
        }
    }

    private static float health() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        float[] out = new float[1];
        server.submit(() -> out[0] = dummy.getHealth()).join();
        return out[0];
    }

    private static String serverDummy() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        String[] out = new String[1];
        server.submit(() -> out[0] = dummy.getPose().name() + " " + f(dummy.getBbHeight())).join();
        return out[0];
    }

    private static void back(net.minecraft.server.MinecraftServer server) {
        server.submit(() -> {
            ServerPlayer p = player(server);
            p.teleportTo(EX, O.getY(), EZ);
            p.setDeltaMovement(0, 0, 0);
        }).join();
    }

    // the numbers of the states, and what must hold between them
    private static void report() {
        for (String s : SPREAD.keySet()) {
            List<Float> k = KICKS.getOrDefault(s, List.of());
            GunFeel.LOGGER.info("[prone] {}: разброс {}, отдача камеры x{}, подскок на пулю {}", s, f(SPREAD.get(s)), CAMERA.get(s), k);
        }
        float stand = SPREAD.get("стоя"), sneak = SPREAD.get("сидя"), lie = SPREAD.get("лёжа"), crawl = SPREAD.get("лёжа ползком");
        check(Math.abs(lie - stand * 0.5F) < 1e-5, "разброс лёжа вдвое меньше: " + f(lie) + " против " + f(stand) + " стоя, сидя " + f(sneak));
        check(Math.abs(crawl - lie) < 1e-5, "ползком разброс как лёжа на месте: " + f(crawl) + " против " + f(lie));
        check(CAMERA.get("стоя") == 1F && CAMERA.get("сидя") == 1F && CAMERA.get("лёжа") == 0.5F,
                "множитель отдачи: стоя " + CAMERA.get("стоя") + ", сидя " + CAMERA.get("сидя") + ", лёжа " + CAMERA.get("лёжа"));
        float ks = mean(KICKS.get("стоя")), kl = mean(KICKS.get("лёжа"));
        check(kl > 0.3F * ks && kl < 0.7F * ks, "подскок камеры на пулю лёжа около половины: " + f(kl) + "° против " + f(ks) + "° стоя, сидя " + f(mean(KICKS.get("сидя"))) + "°");
    }

    private static float mean(List<Float> v) {
        float s = 0F;
        int n = 0;
        for (float x : v) if (!Float.isNaN(x)) {
            s += x;
            n++;
        }
        return n == 0 ? Float.NaN : s / n;
    }
}
