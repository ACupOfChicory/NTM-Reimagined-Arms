package acup.gunfeel;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.hbm.client.Keybinds;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.ModItems;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;

import acup.gunfeel.tacz.CrawlKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// runCheckClient -PproneFireStand: no shot while crawling (ProneFire). In a flat world, a survival player with an Uzi
// (9 mm FMJ, 30 rounds), the fire key pressed as a player does (Keybinds.gunChanged, NTM's packet to the server):
//  - a click standing, walking, lying still and lying while the view turns: it fires;
//  - a click crawling: no bullet, no round spent, no shot on the client (NTM's CYCLE, by the gun's lastShot), the gun idle;
//  - the ticks from letting the crawl key go to the server letting the gun fire;
//  - a burst held lying still, then crawling along, then still again with the key held: it stops and does not go on;
//  - crawling with proneCrawlNoFire = false, and with only the Uzi's own key off: it fires;
//  - crawling with the drill and the charge thrower (tools, their own key false in the mod): they work; the flamer not;
//  - a mannequin with the Uzi (NTM's handleKeybind, as a bot would) lying still, crawling along, walking: no shot only
//    crawling.
// Lines "[pronefire] ..." in the log, PASS/FAIL in the report.
final class ProneFireStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.proneFireStand");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    private static final double EX = 8.5, EZ = 8.5;
    private static final double MOB_STEP = 0.065;

    private static int step, until, after, t0, tCrawl, tStop, decay = -1;
    private static int b0, c0, a0, cyclesAtLate;
    private static int cycles;
    private static long lastShotSeen;
    private static net.minecraft.world.item.Item lastHeld;
    private static int tool;
    private static boolean rotate, firstSample;
    private static int tick;
    private static final StringBuilder TRACE = new StringBuilder();
    private static volatile boolean mobMoving;
    private static String name;
    private static Method gunChanged;
    private static String fileBefore;
    private static boolean fileExisted, prepared;
    private static Mannequin dummy;
    private static final List<Integer> BULLETS = Collections.synchronizedList(new ArrayList<>());

    private ProneFireStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        try {
            // the file first: a failure below restores it as it was
            Path f = file();
            fileExisted = Files.exists(f);
            fileBefore = fileExisted ? Files.readString(f) : null;
            prepared = true;
            gunChanged = Keybinds.class.getDeclaredMethod("gunChanged", int.class, boolean.class);
            gunChanged.setAccessible(true);
            // the mod's defaults: none of the stand's keys left from an earlier run
            keys(ProneFire.KEY, null, ProneFire.SPEED, null, uziKey(), null);
        } catch (Exception e) {
            fail("подготовка: " + e);
            return;
        }
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            if (!e.getLevel().isClientSide() && e.getEntity() instanceof EntityBulletBaseMK4) BULLETS.add(now());
        });
        // the mannequin crawls along by a step a tick, before it ticks: its known speed is that step
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, e -> {
            if (mobMoving && dummy != null) dummy.setPos(dummy.getX() + MOB_STEP, dummy.getY(), dummy.getZ());
        });
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -8; x <= 8; x++) for (int z = -30; z <= 20; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 6; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            ServerPlayer p = player(server);
            p.setGameMode(GameType.SURVIVAL);
            p.teleportTo(level, EX, O.getY(), EZ, Set.of(), 0F, 0F, false);
            gun(p);
        }).join();
        step = 0;
        until = now() + 40;
    }

    private static net.minecraft.world.item.Item[] tools() {
        return new net.minecraft.world.item.Item[] {ModItems.GUN_DRILL.get(), ModItems.GUN_CHARGE_THROWER.get(), ModItems.GUN_FLAMER.get()};
    }

    private static ItemGunBaseNT uzi() {
        return (ItemGunBaseNT) ModItems.GUN_UZI.get();
    }

    private static String uziKey() {
        return BuiltInRegistries.ITEM.getKey(ModItems.GUN_UZI.get()).toString().replace(":", "\\:") + "." + ProneFire.KEY;
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static ItemStack loaded() {
        return loaded(ModItems.GUN_UZI.get());
    }

    // a gun drawn and idle, its magazine full: the Uzi with 30 FMJ, the charge thrower with a mortar (its hook would
    // pull the player), the drill with gasoline, others with their first ammo
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ItemStack loaded(net.minecraft.world.item.Item item) {
        ItemStack stack = new ItemStack(item);
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        com.hbm.items.weapon.sedna.mags.IMagazine magazine = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
        if (item == ModItems.GUN_UZI.get()) {
            magazine.setType(stack, XFactory9mm.p9_fmj);
            magazine.setAmount(stack, 30);
        } else if (magazine instanceof com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase m) {
            magazine.setType(stack, m.acceptedBullets.get(item == ModItems.GUN_CHARGE_THROWER.get() ? 1 : 0));
            magazine.setAmount(stack, m.capacity);
        } else if (magazine instanceof com.hbm.items.weapon.sedna.mags.MagazineLiquidEngine m) {
            magazine.setType(stack, m.accepted()[0]);
            magazine.setAmount(stack, m.getCapacity(stack));
        }
        ItemGunBaseNT.setIsDrawn(stack, true);
        ItemGunBaseNT.setIsEquipped(stack, true);
        ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
        ItemGunBaseNT.setTimer(stack, 0, 0);
        return stack;
    }

    private static void gun(ServerPlayer p) {
        p.getInventory().setSelectedSlot(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, loaded());
    }

    private static Path file() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
    }

    // the server's file with these keys set, or dropped for a null value (the rest as before the stand)
    private static void keys(String... kv) throws Exception {
        String base = fileBefore == null ? "" : fileBefore;
        StringBuilder add = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            base = base.replaceAll("(?m)^" + java.util.regex.Pattern.quote(kv[i]) + "\\s*=.*\\n?", "");
            if (kv[i + 1] != null) add.append(kv[i]).append(" = ").append(kv[i + 1]).append('\n');
        }
        Files.writeString(file(), base + (base.isEmpty() || base.endsWith("\n") ? "" : "\n") + add);
        Files.setLastModifiedTime(file(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
    }

    private static void restore() {
        if (!prepared) return;
        try {
            if (fileExisted) Files.writeString(file(), fileBefore);
            else Files.deleteIfExists(file());
        } catch (Exception e) {
            GunFeel.LOGGER.warn("[pronefire] файл настроек не восстановлен", e);
        }
    }

    private static void fail(String text) {
        ClientSmoke.check(false, "ползком: " + text);
        restore();
        ClientSmoke.finish();
        step = 999;
    }

    private static void check(boolean ok, String text) {
        GunFeel.LOGGER.info("[pronefire] {} {}", ok ? "PASS" : "FAIL", text);
        ClientSmoke.check(ok, "ползком: " + text);
    }

    private static int now() {
        return Minecraft.getInstance().getSingleplayerServer().getTickCount();
    }

    private static void press(boolean down) throws Exception {
        gunChanged.invoke(null, 0, down);
    }

    private static <T> T onServer(java.util.function.Function<ServerPlayer, T> f) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        Object[] out = new Object[1];
        server.submit(() -> out[0] = f.apply(player(server))).join();
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static int ammo(LivingEntity e) {
        ItemStack stack = e.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return -1;
        return gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmount(stack, new SimpleContainer(1));
    }

    private static String serverState() {
        return onServer(p -> ItemGunBaseNT.getState(p.getMainHandItem(), 0).name() + " " + ItemGunBaseNT.getTimer(p.getMainHandItem(), 0));
    }

    private static double knownSpeed() {
        return onServer(p -> ((acup.gunfeel.mixin.mc.EntityAccess) p).gunfeel$lastKnownSpeed().horizontalDistance() * 20.0);
    }

    // bullets of ticks from..to-1 (a press's first bullet is fired in the tick its packet is handled, the one read
    // just before it)
    private static int bulletsIn(int from, int to) {
        synchronized (BULLETS) {
            int n = 0;
            for (int t : BULLETS) if (t >= from && t < to) n++;
            return n;
        }
    }

    private static int lastBullet() {
        synchronized (BULLETS) {
            return BULLETS.isEmpty() ? Integer.MIN_VALUE : BULLETS.getLast();
        }
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    // a click as a player's: the key down a tick, then the result 20 ticks on (step 900), then step `next`
    private static void click(String what, int next) {
        name = what;
        after = next;
        step = 900;
    }

    private static void wait(int ticks, int next) {
        until = now() + ticks;
        step = next;
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        try {
            // NTM's CYCLE reached the client: the held gun's last shot moved (another gun in hand: only noted)
            var held = mc.player.getMainHandItem().getItem();
            long shot = held instanceof ItemGunBaseNT g ? g.lastShot[0] : lastShotSeen;
            if (held != lastHeld) {
                lastHeld = held;
                lastShotSeen = shot;
            } else if (shot != lastShotSeen) {
                cycles++;
                lastShotSeen = shot;
            }
            if (rotate) mc.player.setYRot(mc.player.getYRot() + 3F);
            if (step != 900 && now() < until) return;
            switch (step) {
                case 0 -> {
                    if (!(mc.player.getMainHandItem().getItem() instanceof ItemGunBaseNT) || !mc.player.onGround()) return;
                    mc.player.setYRot(0F);
                    mc.player.setXRot(0F);
                    click("стоя", 1);
                }
                case 1 -> {
                    mc.options.keyUp.setDown(true);
                    wait(8, 2);
                }
                case 2 -> click("стоя на ходу", 3);
                case 3 -> {
                    mc.options.keyUp.setDown(false);
                    server.submit(() -> {
                        ServerPlayer p = player(server);
                        p.teleportTo(EX, O.getY(), EZ);
                        p.setDeltaMovement(0, 0, 0);
                        gun(p);
                    }).join();
                    wait(15, 4);
                }
                case 4 -> {
                    KeyMapping.click(CrawlKey.CRAWL_KEY.getKey());
                    wait(10, 5);
                }
                case 5 -> {
                    if (!ProneClient.localLying() || !onServer(Prone::serverLying)) {
                        fail("не лёг: " + mc.player.getPose());
                        return;
                    }
                    click("лёжа", 6);
                }
                case 6 -> {
                    rotate = true;
                    click("лёжа, поворот", 7);
                }
                case 7 -> {
                    rotate = false;
                    mc.player.setYRot(0F);
                    server.submit(() -> gun(player(server))).join();
                    mc.options.keyUp.setDown(true);
                    wait(8, 8);
                }
                case 8 -> {
                    GunFeel.LOGGER.info("[pronefire] ползком: скорость на сервере {} блока/с, на клиенте {}", f(knownSpeed()),
                            f(Math.hypot(mc.player.getX() - mc.player.xo, mc.player.getZ() - mc.player.zo) * 20));
                    click("ползком", 9);
                }
                case 9 -> { // the key let go: ticks until the server lets the gun fire again
                    mc.options.keyUp.setDown(false);
                    t0 = now();
                    until = t0;
                    firstSample = true;
                    step = 10;
                }
                case 10 -> {
                    if (now() == until) return;
                    until = now();
                    boolean blocked = onServer(p -> ProneFire.blocks(p, p.getMainHandItem()));
                    if (firstSample) {
                        firstSample = false;
                        check(blocked, "сразу после отпускания хода стрелять ещё нельзя");
                    }
                    if (!blocked) {
                        // the client sees the key let go on its next tick, the server the step a tick after that
                        decay = now() - t0;
                        check(decay <= 5, "отпустил ход: стрелять можно через " + decay + " тиков");
                        wait(10, 11);
                    } else if (now() - t0 > 40) {
                        check(false, "отпустил ход: через 40 тиков стрелять всё ещё нельзя");
                        wait(10, 11);
                    }
                }
                // ---- a burst: lying still, then crawling along, then still again, the key held all along
                case 11 -> {
                    server.submit(() -> gun(player(server))).join();
                    a0 = 30;
                    t0 = now();
                    c0 = cycles;
                    press(true);
                    wait(10, 12);
                }
                case 12 -> {
                    tCrawl = now();
                    mc.options.keyUp.setDown(true);
                    wait(4, 13);
                }
                case 13 -> {
                    cyclesAtLate = cycles;
                    tick = now();
                    TRACE.setLength(0);
                    step = 130;
                }
                case 130 -> { // each tick of the crawl: the speed the server knows, the rule, the gun's state, the client's step
                    if (now() == tick) return;
                    tick = now();
                    TRACE.append(' ').append(tick - tCrawl).append(":").append(ProneFireStand.<String>onServer(p -> f(((acup.gunfeel.mixin.mc.EntityAccess) p).gunfeel$lastKnownSpeed().horizontalDistance() * 20)
                            + (ProneFire.blocks(p, p.getMainHandItem()) ? "x" : "-") + ItemGunBaseNT.getState(p.getMainHandItem(), 0).name().charAt(0)
                            + (Prone.lying(p) ? "L" : "S"))).append('/').append(f(Math.hypot(mc.player.getX() - mc.player.xo, mc.player.getZ() - mc.player.zo) * 20));
                    if (tick - tCrawl >= 25) {
                        GunFeel.LOGGER.info("[pronefire] ход очереди (тик: сервер видит блока/с, x = нельзя, состояние, L = лёжа / шаг клиента):{}", TRACE);
                        step = 14;
                    }
                }
                case 14 -> {
                    mc.options.keyUp.setDown(false);
                    tStop = now();
                    wait(15, 15);
                }
                case 15 -> {
                    press(false);
                    int end = now() + 1;
                    int still = bulletsIn(t0, tCrawl), crawlLate = bulletsIn(tCrawl + 4, tStop), stopped = bulletsIn(tStop, end);
                    int total = bulletsIn(t0, end), spent = a0 - onServer(ProneFireStand::ammo);
                    int last = lastBullet() - tCrawl;
                    String state = serverState();
                    GunFeel.LOGGER.info("[pronefire] очередь: лёжа {} пуль, последняя через {} тиков после хода, ползком (с 4-го тика) {}, "
                            + "встал с зажатой {}, всего {}, патронов ушло {}, на клиенте выстрелов {} (после 4-го тика хода {}), состояние {}",
                            still, last, crawlLate, stopped, total, spent, cycles - c0, cycles - cyclesAtLate, state);
                    check(still > 0, "очередь лёжа на месте стреляет: " + still + " пуль за 10 тиков");
                    check(crawlLate == 0 && cycles == cyclesAtLate, "очередь, прерванная ползанием, остановилась: пуль " + crawlLate
                            + ", выстрелов на клиенте " + (cycles - cyclesAtLate) + ", последняя пуля через " + last + " тиков после хода");
                    check(stopped == 0, "очередь после остановки с зажатой клавишей не продолжилась: " + stopped + " пуль");
                    check(spent == total, "патронов ушло столько, сколько пуль: " + spent + " / " + total);
                    check(state.startsWith("IDLE"), "пушка после очереди в покое: " + state);
                    wait(10, 16);
                }
                case 16 -> click("лёжа после очереди", 17);
                case 17 -> {
                    keys(ProneFire.KEY, "false");
                    wait(30, 18);
                }
                case 18 -> {
                    server.submit(() -> gun(player(server))).join();
                    mc.options.keyUp.setDown(true);
                    wait(8, 19);
                }
                case 19 -> click("ползком, " + ProneFire.KEY + " = false", 20);
                case 20 -> {
                    mc.options.keyUp.setDown(false);
                    keys(ProneFire.KEY, null, uziKey(), "false");
                    wait(30, 21);
                }
                case 21 -> {
                    mc.options.keyUp.setDown(true);
                    wait(8, 22);
                }
                case 22 -> click("ползком, у Uzi свой ключ false", 23);
                case 23 -> {
                    mc.options.keyUp.setDown(false);
                    keys(uziKey(), null);
                    tool = 0;
                    wait(30, 230);
                }
                // ---- the drill and the charge thrower (tools: their own key false in the mod) work crawling, the
                // flamer does not
                case 230 -> {
                    var item = tools()[tool];
                    server.submit(() -> player(server).setItemInHand(InteractionHand.MAIN_HAND, loaded(item))).join();
                    wait(10, 231);
                }
                case 231 -> {
                    mc.options.keyUp.setDown(true);
                    wait(8, 232);
                }
                case 232 -> click("ползком, " + BuiltInRegistries.ITEM.getKey(tools()[tool]), 233);
                case 233 -> {
                    mc.options.keyUp.setDown(false);
                    tool++;
                    wait(15, tool < tools().length ? 230 : 24);
                }
                case 24 -> { // up, and the mannequin: a bot's shots through NTM's handleKeybind
                    KeyMapping.click(CrawlKey.CRAWL_KEY.getKey());
                    server.submit(() -> {
                        ServerLevel level = server.overworld();
                        dummy = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
                        dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
                        dummy.setHealth(1000);
                        // 12 blocks behind the player, shooting away from it (to -X)
                        dummy.snapTo(EX - 2, O.getY(), EZ - 14, 90F, 0F);
                        dummy.setYHeadRot(90F);
                        dummy.setYBodyRot(90F);
                        level.addFreshEntity(dummy);
                        dummy.setPose(Pose.SWIMMING);
                    }).join();
                    wait(20, 25);
                }
                case 25 -> mobClick("манекен лёжа на месте", false, Pose.SWIMMING, 26);
                case 26 -> mobClick("манекен ползком", true, Pose.SWIMMING, 27);
                case 27 -> mobClick("манекен стоя на ходу", true, Pose.STANDING, 28);
                case 28 -> {
                    mobMoving = false;
                    restore();
                    ClientSmoke.finish();
                    step = 999;
                }
                // ---- a player's click (see click)
                case 900 -> {
                    b0 = BULLETS.size();
                    c0 = cycles;
                    a0 = onServer(ProneFireStand::ammo);
                    t0 = now();
                    press(true);
                    until = now() + 1;
                    step = 901;
                }
                case 901 -> {
                    press(false);
                    wait(20, 902);
                }
                case 902 -> {
                    int db = BULLETS.size() - b0, dc = cycles - c0, spent = a0 - onServer(ProneFireStand::ammo);
                    String state = serverState();
                    boolean lying = mc.player.getPose() == Pose.SWIMMING;
                    GunFeel.LOGGER.info("[pronefire] {}: пуль {}, патронов ушло {}, выстрелов на клиенте {}, состояние {}, поза {}, сервер видит {} блока/с",
                            name, db, spent, dc, state, mc.player.getPose(), f(knownSpeed()));
                    if (name.startsWith("ползком, hbm:")) {
                        boolean works = !name.endsWith("gun_flamer");
                        if (works) check(lying && dc > 0, name + " работает (инструмент): выстрелов на клиенте " + dc + ", патронов " + spent + ", " + state);
                        else check(lying && db == 0 && spent == 0 && dc == 0 && state.startsWith("IDLE"),
                                name + " не стреляет: пуль " + db + ", патронов " + spent + ", выстрелов на клиенте " + dc + ", " + state);
                    } else if (name.equals("ползком")) {
                        check(lying && db == 0 && spent == 0 && dc == 0 && state.startsWith("IDLE"),
                                "клик ползком не стреляет: пуль " + db + ", патронов " + spent + ", выстрелов на клиенте " + dc + ", " + state);
                    } else {
                        check(db > 0 && spent == db && dc > 0, "клик " + name + " стреляет: пуль " + db + ", патронов " + spent
                                + ", выстрелов на клиенте " + dc);
                    }
                    step = after;
                }
                // ---- the mannequin's click (see mobClick)
                case 949 -> {
                    server.submit(() -> {
                        b0 = BULLETS.size();
                        a0 = ammo(dummy);
                        uzi().handleKeybind(dummy, new SimpleContainer(1), dummy.getMainHandItem(), EnumKeybind.GUN_PRIMARY, true);
                    }).join();
                    wait(1, 950);
                }
                case 950 -> {
                    server.submit(() -> {
                        uzi().handleKeybind(dummy, new SimpleContainer(1), dummy.getMainHandItem(), EnumKeybind.GUN_PRIMARY, false);
                    }).join();
                    wait(10, 951);
                }
                case 951 -> {
                    int db = BULLETS.size() - b0;
                    int spent = a0 - onServer(p -> ammo(dummy));
                    double speed = onServer(p -> ((acup.gunfeel.mixin.mc.EntityAccess) dummy).gunfeel$lastKnownSpeed().horizontalDistance() * 20.0);
                    GunFeel.LOGGER.info("[pronefire] {}: поза {}, скорость {} блока/с, пуль {}, патронов ушло {}", name,
                            onServer(p -> dummy.getPose().name()), f(speed), db, spent);
                    if (name.equals("манекен ползком")) check(db == 0 && spent == 0, name + " не стреляет: пуль " + db + ", патронов " + spent);
                    else check(db > 0 && spent == db, name + " стреляет: пуль " + db + ", патронов " + spent);
                    mobMoving = false;
                    step = after;
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            GunFeel.LOGGER.error("[pronefire] failed", t);
            fail(t.toString());
        }
    }

    // the mannequin, given a loaded Uzi in this pose, moving or not, presses the fire key (step 949) once its known
    // speed follows, and lets it go a tick later (950)
    private static void mobClick(String what, boolean moving, Pose pose, int next) {
        name = what;
        after = next;
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            dummy.setItemInHand(InteractionHand.MAIN_HAND, loaded());
            dummy.setPose(pose);
            mobMoving = moving;
        }).join();
        wait(4, 949);
    }
}
