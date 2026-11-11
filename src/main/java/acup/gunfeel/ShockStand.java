package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.ModItems;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;

import acup.gunfeel.shock.ShockAmmo;
import acup.gunfeel.shock.ShockServer;
import acup.gunfeel.shock.client.ShockClient;
import acup.gunfeel.shock.client.ShockHud;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

// runCheckClient -PshockStand: the 12 gauge electroshock shell (ShockAmmo, ShockServer, ShockClient, ShockHud). A flat
// world, the player in survival looking along +Z with a Maresleg, a mannequin 20 blocks off that shoots the player, an
// FBI agent (an AI bot's body) 6 blocks ahead.
//  - the shell is in the 12 gauge magazines (the Maresleg, the SPAS-12), not in the Shredder's;
//  - the HUD's frames: none, shocked (three, the view held still: shockCamera, shockShake 0), none again; by pixels the
//    HUD's rows change and the sky above them does not, and the frame after the shock is the first one again;
//  - a shell into the player: the shock by ticks (exactly shockSeconds), the spread of the Maresleg's shot (NTM's
//    calcSpread) up by shockSpread only in that window, the client told (its shock, the view turned on its own);
//  - immunity: a shell during the shock and one after it (within shockImmunity) do nothing, one after that shocks again;
//  - the agent: the same window of spread through NTM's calcSpread with it as the shooter, immunity, and the shooter's
//    marks (one that shocked, one that did not).
// Lines "[shock-stand] ..." in the log, PASS/FAIL in the report, frames shock-*.png in the run dir.
final class ShockStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.shockStand");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    private static final double EX = 8.5, EZ = 8.5;
    private static final Identifier FBI = Identifier.fromNamespaceAndPath("hbm", "entity_fbi");
    private static final String[] KEYS = {"shock", "shockSeconds", "shockImmunity", "shockSpread", "shockGlitch", "shockGlitchMin", "shockCamera",
            "shockShake"};

    private static int step, until;
    private static Mannequin shooter;
    private static Mob agent;
    private static String fileBefore;
    private static boolean fileExisted, prepared;
    private static com.mojang.blaze3d.platform.NativeImage base;
    private static volatile com.mojang.blaze3d.platform.NativeImage shot;
    private static int frameNo, frames0;
    // the window: the hit's tick, the ticks seen shocked and with the spread up, the first tick after
    private static volatile boolean watching;
    private static int hitAt;
    private static boolean listening;
    private static final StringBuilder SERIES = new StringBuilder();
    private static int shockedTicks, spreadTicks, firstFree;
    private static float spread0, spreadIn, spreadOut;
    private static final List<float[]> TICKS = new ArrayList<>();
    private static int shocks0, marks0, took0;

    private ShockStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        try {
            Path f = file();
            fileExisted = Files.exists(f);
            fileBefore = fileExisted ? Files.readString(f) : null;
            prepared = true;
            String[] kv = new String[KEYS.length * 2];
            for (int i = 0; i < KEYS.length; i++) kv[2 * i] = KEYS[i];
            keys(kv);
        } catch (Exception e) {
            fail("подготовка: " + e);
            return;
        }
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -24; x <= 24; x++) for (int z = -30; z <= 30; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 8; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            ServerPlayer p = player(server);
            p.setGameMode(GameType.SURVIVAL);
            p.teleportTo(level, EX, O.getY(), EZ, Set.of(), 0F, 0F, false);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.GUN_MARESLEG.get()));
            shooter = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
            shooter.snapTo(EX, O.getY(), EZ - 20, 0F, 0F);
            shooter.setNoGravity(true);
            level.addFreshEntity(shooter);
            var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
            if (created instanceof Mob mob) {
                agent = mob;
                mob.snapTo(EX + 3, O.getY(), EZ + 6, 180F, 0F);
                mob.setNoAi(true);
                mob.setPersistenceRequired();
                mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.GUN_MARESLEG.get()));
                level.addFreshEntity(mob);
            }
        }).join();
        if (!listening) {
            listening = true;
            // the window watched on the server's own ticks, none missed
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> {
                if (watching) watch(player(e.getServer()));
            });
        }
        step = 0;
        until = now() + 60;
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        frameNo++;
        try {
            if (now() < until) return;
            switch (step) {
                case 0 -> magazines();
                case 10 -> { // the frames: the view still, the HUD as it is
                    keys("shockCamera", "0", "shockShake", "0");
                    mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                    mc.player.setYRot(0F);
                    mc.player.setXRot(-10F);
                    mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[shock-stand] a line of chat: not glitched"));
                    // the server's file is read again every 2 s
                    wait(60, 11);
                }
                case 11 -> grab(12);
                case 12 -> {
                    if (shot == null) return;
                    base = shot;
                    shot = null;
                    save(base, "shock-none.png");
                    frames0 = ShockHud.frames;
                    ShockClient.start(400);
                    wait(4, 13);
                }
                case 13, 15, 17 -> grab(step + 1);
                case 14, 16, 18 -> {
                    if (shot == null) return;
                    int k = (step - 14) / 2 + 1;
                    save(shot, "shock-glitch-" + k + ".png");
                    compare(shot, "кадр шока " + k, true);
                    shot.close();
                    shot = null;
                    wait(3, step + 1);
                }
                case 19 -> {
                    check(ShockHud.frames > frames0, "HUD шёл через глич: кадров " + (ShockHud.frames - frames0));
                    ShockClient.clear();
                    wait(5, 20);
                }
                case 20 -> grab(21);
                case 21 -> {
                    if (shot == null) return;
                    save(shot, "shock-after.png");
                    compare(shot, "кадр после шока", false);
                    shot.close();
                    shot = null;
                    base.close();
                    keys("shockCamera", null, "shockShake", null);
                    onServer(p -> {
                        ShockServer.clear();
                        return null;
                    });
                    wait(10, 40);
                }
                case 40 -> { // a shell into the player
                    shocks0 = ShockClient.shocks;
                    TICKS.clear();
                    SERIES.setLength(0);
                    shockedTicks = spreadTicks = 0;
                    firstFree = -1;
                    spreadIn = 0F;
                    hitAt = -1;
                    fire(false);
                    watching = true;
                    step = 41;
                    until = now() + 75;
                }
                case 41 -> {
                    watching = false;
                    judge();
                    log("игрок: попадание на такте {}, шок {} тактов, разброс выше {} тактов (+{}), первый свободный такт {}, по тактам:{}", hitAt, shockedTicks,
                            spreadTicks, f(spreadIn - spread0), firstFree, SERIES);
                    check(hitAt >= 0, "игрок: шок пришёл");
                    check(shockedTicks == 40 && firstFree == hitAt + 40, "игрок: шок ровно 40 тактов (" + shockedTicks + ", свободен с +" + (firstFree - hitAt) + ")");
                    check(spreadTicks == 40, "игрок: разброс выше ровно в окне шока (" + spreadTicks + " тактов)");
                    check(Math.abs(spreadIn - spreadOut - 0.12F) < 0.005F, "игрок: в последнем такте шока разброс на 0,12 выше, чем в первом после (" + f(spreadIn - spreadOut) + ")");
                    check(ShockClient.shocks == shocks0 + 1, "игрок: клиент узнал о шоке");
                    check(ShockClient.turned > 5F && ShockClient.turned < 120F, "игрок: взгляд водило само (" + f(ShockClient.turned) + "°)");
                    immunity();
                }
                case 50 -> agent();
                case 52 -> agentShocked();
                case 53 -> agentAfter();
                case 54 -> {
                    int n = ShockClient.marks - marks0, t = ShockClient.marksTook - took0;
                    log("стрелку: отметок {}, из них с шоком {}", n, t);
                    check(n == 2 && t == 1, "стрелку отметка попадания: 2, с шоком 1 (" + n + ", " + t + ")");
                    restore();
                    ClientSmoke.finish();
                    step = 999;
                }
                default -> {
                }
            }
        } catch (Exception e) {
            GunFeel.LOGGER.error("[shock-stand]", e);
            fail("исключение: " + e);
        }
    }

    // the shell in the 12 gauge buckshot magazines, not in the Shredder's
    private static void magazines() {
        BulletConfig c = ShockAmmo.config();
        check(c != null, "конфиг патрона есть");
        for (var gun : List.of(ModItems.GUN_MARESLEG.get(), ModItems.GUN_SPAS12.get(), ModItems.GUN_AUTOSHOTGUN.get(), ModItems.GUN_AUTOSHOTGUN_SHREDDER.get())) {
            ItemStack s = new ItemStack(gun);
            var list = AmmoLock.accepted(gun.getConfig(s, 0).getReceivers(s)[0].getMagazine(s));
            boolean takes = list.contains(c);
            boolean want = list.contains(XFactory12ga.g12);
            log("{}: патронов {}, шоковый {}", BuiltInRegistries.ITEM.getKey(gun), list.size(), takes);
            check(takes == want, BuiltInRegistries.ITEM.getKey(gun) + ": шоковый патрон " + (want ? "есть" : "нет"));
        }
        wait(5, 10);
    }

    // the HUD's rows changed by the glitch, the sky above not; after the shock all as at first. A share of the pixels
    // changed by more than 12 of 255 (the sky's own light drifts by a level or two, a speck may cross it)
    private static void compare(com.mojang.blaze3d.platform.NativeImage now, String what, boolean glitched) {
        int w = Math.min(base.getWidth(), now.getWidth()), h = Math.min(base.getHeight(), now.getHeight());
        int hud = 0, sky = 0, nh = 0, ns = 0;
        for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) {
            boolean changed = diff(base.getPixel(x, y), now.getPixel(x, y)) > 12.0;
            // the middle of the hotbar (the gun in the hand is to the right of it), the sky over the middle
            if (y > h * 22 / 25 && Math.abs(x - w / 2) < w / 8) {
                if (changed) hud++;
                nh++;
            } else if (y < h / 5 && Math.abs(x - w / 2) < w / 4) {
                if (changed) sky++;
                ns++;
            }
        }
        double sh = (double) hud / Math.max(1, nh), ss = (double) sky / Math.max(1, ns);
        log("{}: изменилось в рядах HUD {} точек, в небе {}", what, f(sh), f(ss));
        if (glitched) {
            check(sh > 0.05, what + ": HUD изменился (" + f(sh) + ")");
            check(ss < 0.003, what + ": небо над HUD то же (" + f(ss) + ")");
        } else check(sh < 0.003 && ss < 0.003, what + ": как до шока (" + f(sh) + ", " + f(ss) + ")");
    }

    private static double diff(int a, int b) {
        int d = 0;
        for (int k = 0; k < 24; k += 8) d += Math.abs((a >> k & 0xFF) - (b >> k & 0xFF));
        return d / 3.0;
    }

    // one server tick of the window (on the server's thread): shocked or not, the spread
    private static void watch(ServerPlayer p) {
        TICKS.add(new float[] {p.level().getServer().getTickCount(), ShockServer.shocked(p) ? 1F : 0F, spreadOn(p, p)});
    }

    // the window from the ticks. The player's own spread grows once hit (the hit knocks it about, the spread of
    // moving): the base is its spread at the first tick after the shock; a tick is up when 0.06 over it
    private static void judge() {
        hitAt = -1;
        firstFree = -1;
        shockedTicks = spreadTicks = 0;
        spreadOut = -1F;
        for (float[] t : TICKS) {
            if (t[1] > 0F && hitAt < 0) hitAt = (int) t[0];
            if (hitAt >= 0 && t[1] == 0F && firstFree < 0) {
                firstFree = (int) t[0];
                spreadOut = t[2];
            }
        }
        spread0 = spreadOut;
        for (float[] t : TICKS) {
            boolean in = t[1] > 0F, up = t[2] > spreadOut + 0.06F;
            if (in) {
                shockedTicks++;
                spreadIn = t[2];
            }
            if (up) spreadTicks++;
            SERIES.append(' ').append(in ? (up ? 'S' : 's') : (up ? '!' : '.'));
        }
        StringBuilder v = new StringBuilder();
        for (float[] t : TICKS) v.append(' ').append(f(t[2]));
        log("игрок: разброс по тактам:{}", v);
    }

    // the immunity: a shell in the shock (at +20) and after it (+45) shock nothing, one at +105 shocks again
    private static void immunity() throws Exception {
        // the last shock started at hitAt; now is about hitAt + 75
        onServer(p -> {
            ShockServer.clear();
            return null;
        });
        fire(false);
        waitTicks(5);
        boolean first = onServer(p -> ShockServer.shocked(p));
        long[] s0 = onServer(p -> ShockServer.state(p));
        waitTicks(15);
        fire(false);
        waitTicks(5);
        long[] s1 = onServer(p -> ShockServer.state(p));
        waitTicks(25);
        fire(false);
        waitTicks(5);
        boolean afterEnd = onServer(p -> ShockServer.shocked(p));
        waitTicks(55);
        fire(false);
        waitTicks(5);
        boolean again = onServer(p -> ShockServer.shocked(p));
        log("иммунитет: первый {}, второй в шоке не продлил {}, после конца в иммунитете {}, после иммунитета {}", first, s0 != null && s1 != null && s0[0] == s1[0],
                afterEnd, again);
        check(first, "иммунитет: первый патрон шокирует");
        check(s0 != null && s1 != null && s0[0] == s1[0], "иммунитет: патрон в шоке его не продлевает");
        check(!afterEnd, "иммунитет: патрон сразу после шока не шокирует");
        check(again, "иммунитет: после иммунитета шокирует снова");
        onServer(p -> {
            ShockServer.clear();
            return null;
        });
        wait(10, 50);
    }

    // the agent: shocked by the player's shells, its shots' spread through calcSpread, the immunity and the marks. In
    // steps, the client drawing meanwhile: the player's shells wait for its client
    private static float agentBefore, agentDuring;
    private static boolean agentIn;

    private static void agent() {
        if (agent == null) {
            check(false, "агент ФБР не появился");
            wait(1, 54);
            return;
        }
        marks0 = ShockClient.marks;
        took0 = ShockClient.marksTook;
        agentBefore = onServer(p -> spreadOn(agent, agent));
        fire(true);
        wait(8, 52);
    }

    private static void agentShocked() {
        agentIn = onServer(p -> ShockServer.shocked(agent));
        agentDuring = onServer(p -> spreadOn(agent, agent));
        log("агент: добавка шока сама по себе {}", f(onServer(p -> ShockServer.spread(agent, XFactory12ga.g12_slug))));
        fire(true);
        wait(50, 53);
    }

    private static void agentAfter() {
        long[] s = onServer(p -> ShockServer.state(agent));
        boolean out = onServer(p -> ShockServer.shocked(agent));
        float after = onServer(p -> spreadOn(agent, agent));
        log("агент: шок {}, разброс {} → {} → {}, после {}", agentIn, f(agentBefore), f(agentDuring), f(after), out);
        check(agentIn, "агент: шок пришёл");
        check(Math.abs(agentDuring - agentBefore - 0.12F) < 0.005F, "агент: разброс его выстрела +0,12 (" + f(agentDuring - agentBefore) + ")");
        check(!out && Math.abs(after - agentBefore) < 0.005F, "агент: после шока разброс прежний");
        check(s != null, "агент: состояние шока есть");
        wait(10, 54);
    }

    // a shell: from the mannequin into the player, or from the player into the agent
    private static void fire(boolean byPlayer) {
        onServer(p -> {
            ServerLevel level = (ServerLevel) p.level();
            p.setHealth(p.getMaxHealth());
            if (agent != null) agent.setHealth(agent.getMaxHealth());
            BulletConfig c = ShockAmmo.config();
            EntityBulletBaseMK4 b;
            if (byPlayer) {
                double dx = agent.getX() - EX, dz = agent.getZ() - EZ;
                b = new EntityBulletBaseMK4(level, p, c, 16F, -c.spread, EX, O.getY() + 1.62, EZ, dx, agent.getY() + 1.0 - (O.getY() + 1.62), dz);
            } else b = new EntityBulletBaseMK4(level, shooter, c, 16F, -c.spread, EX, O.getY() + 1.0, EZ - 19, 0, 0, 1);
            level.addFreshEntity(b);
            return null;
        });
    }

    // the spread NTM works out for a shot of `who` with the Maresleg's slug, `who` holding it
    private static float spreadOn(LivingEntity who, LivingEntity holder) {
        ItemStack stack = holder.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return -1F;
        var config = gun.getConfig(stack, 0);
        Receiver r = config.getReceivers(stack)[0];
        var inv = who instanceof ServerPlayer sp ? sp.getInventory() : null;
        var ctx = new ItemGunBaseNT.LambdaContext(config, who, inv, 0);
        return Lego.calcSpread(ctx, stack, r, XFactory12ga.g12_slug, false, 0, true);
    }

    // ---- helpers

    // the server's ticks pass while the client draws (frames go on, so the shells fly)
    private static void waitTicks(int n) throws InterruptedException {
        int end = now() + n;
        while (now() < end) Thread.sleep(5);
    }

    private static void grab(int next) {
        shot = null;
        net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> shot = image);
        step = next;
    }

    private static void save(com.mojang.blaze3d.platform.NativeImage im, String name) {
        try {
            im.writeToFile(Path.of(name));
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.warn("[shock-stand] кадр {} не сохранён", name, e);
        }
    }

    private static void wait(int ticks, int next) {
        until = now() + ticks;
        step = next;
    }

    private static int now() {
        return Minecraft.getInstance().getSingleplayerServer().getTickCount();
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static <T> T onServer(java.util.function.Function<ServerPlayer, T> f) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        Object[] out = new Object[1];
        server.submit(() -> out[0] = f.apply(player(server))).join();
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
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
            GunFeel.LOGGER.warn("[shock-stand] файл настроек не восстановлен", e);
        }
    }

    private static void fail(String text) {
        ClientSmoke.check(false, "шок: " + text);
        restore();
        ClientSmoke.finish();
        step = 999;
    }

    private static void check(boolean ok, String text) {
        GunFeel.LOGGER.info("[shock-stand] {} {}", ok ? "PASS" : "FAIL", text);
        ClientSmoke.check(ok, "шок: " + text);
    }

    private static void log(String fmt, Object... args) {
        GunFeel.LOGGER.info("[shock-stand] " + fmt, args);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
