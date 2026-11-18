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

import acup.gunfeel.hitmarker.client.HitMarkerClient;
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
//  - a shell into the player: the shock by ticks (exactly shockSeconds at full, then ShockServer.FADE_TICKS wearing
//    off), the spread of the Maresleg's shot (NTM's calcSpread) up by shockSpread at full, by half of it halfway through
//    the fade, not after; the speed on the server and on the client down by ShockServer.SLOW at full, by half that
//    halfway, as before after; the client's view narrowed by ShockServer.FOV at full, by half that halfway, and back; the client told (its
//    shock, the view turned on its own);
//  - immunity: a shell during the shock and one after it (within shockImmunity) shock no more, one after that shocks again;
//    the mannequin holding a Maresleg, the two in between do ShockServer.REPEAT of its slug (the final scheme's click on
//    the player), the first one little (the shells made with NTM's damage of the Maresleg times the shell's);
//  - the agent: the same spread through NTM's calcSpread with it as the shooter, its speed and its step on a path (its
//    move control's speed) down by SLOW, immunity, and the shooter's marks (one that shocked, one that did not).
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
    private static int shockedTicks, firstFree;
    private static float spreadOut;
    // the server's ticks {tick, shocked, spread, speed}; the client's frames {server tick, its speed, its FOV modifier}
    private static final List<float[]> TICKS = new ArrayList<>();
    private static final List<float[]> FRAMES = new ArrayList<>();
    private static int shocks0, marks0, indicators0, heads0, kills0;

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
                // the player's shells in its shock are most of a slug now (ShockServer.REPEAT): health to take them
                var hp = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
                if (hp != null) hp.setBaseValue(200.0);
                mob.setHealth(mob.getMaxHealth());
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
        if (watching && mc.player != null)
            FRAMES.add(new float[] {now(), (float) mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED),
                    mc.player.getFieldOfViewModifier(true, 1F)});
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
                    indicators0 = HitMarkerClient.indicators;
                    TICKS.clear();
                    FRAMES.clear();
                    SERIES.setLength(0);
                    shockedTicks = 0;
                    firstFree = -1;
                    hitAt = -1;
                    // watched from before the shell: the ticks before the hit are the base
                    watching = true;
                    fire(false);
                    step = 41;
                    until = now() + 40 + ShockServer.FADE_TICKS + 35;
                }
                case 41 -> {
                    watching = false;
                    judge();
                    int len = 40 + ShockServer.FADE_TICKS;
                    log("игрок: попадание на такте {}, шок {} тактов, первый свободный такт {}, по тактам:{}", hitAt, shockedTicks, firstFree, SERIES);
                    check(hitAt >= 0, "игрок: шок пришёл");
                    check(shockedTicks == len && firstFree == hitAt + len,
                            "игрок: шок ровно " + len + " тактов, 40 на полной и затухание (" + shockedTicks + ", свободен с +" + (firstFree - hitAt) + ")");
                    windowChecks();
                    check(ShockClient.shocks == shocks0 + 1, "игрок: клиент узнал о шоке");
                    // the damage indicator: one arc, towards the mannequin that fired (behind the player, who faces south)
                    var from = HitMarkerClient.lastFrom;
                    log("игрок: указателей урона {}, откуда {}, направление {}°", HitMarkerClient.indicators - indicators0, from, f(HitMarkerClient.firstDir));
                    check(HitMarkerClient.indicators - indicators0 == 1 && from != null && Math.abs(from.x - EX) < 0.6 && Math.abs(from.z - (EZ - 20)) < 0.6
                            && Math.abs(Math.abs(HitMarkerClient.firstDir) - 180F) < 5F, "игрок: указатель урона один, на стрелка сзади ("
                            + (HitMarkerClient.indicators - indicators0) + ", " + f(HitMarkerClient.firstDir) + "°)");
                    // at most shockCamera (45°/s) × (1 + 0.45 the pitch's share) over 2 s at full and the fade's 1.5 s worth
                    check(ShockClient.turned > 5F && ShockClient.turned < 230F, "игрок: взгляд водило само (" + f(ShockClient.turned) + "°)");
                    immunity();
                }
                case 50 -> agent();
                case 52 -> agentShocked();
                case 53 -> agentAfter();
                case 54 -> {
                    int n = HitMarkerClient.marks - marks0;
                    log("стрелку: хитмаркеров {}", n);
                    check(n == 2, "стрелку хитмаркер на каждое попадание: 2 (" + n + ")");
                    // an NTM bullet that counts the head (×2) into the agent's head, then one into its body at 1 health
                    marks0 = HitMarkerClient.marks;
                    heads0 = HitMarkerClient.heads;
                    kills0 = HitMarkerClient.kills;
                    bullet(true, false);
                    wait(10, 55);
                }
                case 55 -> {
                    bullet(false, true);
                    wait(10, 56);
                }
                case 56 -> {
                    int n = HitMarkerClient.marks - marks0, h = HitMarkerClient.heads - heads0, k = HitMarkerClient.kills - kills0;
                    float left = onServer(p -> agent.getHealth());
                    boolean alive = onServer(p -> agent.isAlive());
                    log("стрелку пулей NTM: хитмаркеров {}, в голову {}, убийств {}; агент: здоровье {}, жив {}", n, h, k, f(left), alive);
                    check(n == 2 && h == 1 && k == 1, "стрелку: хитмаркер в голову, потом убийство (" + n + ", " + h + ", " + k + ")");
                    // a grenade into the ground beside the agent: its blast alone, on the body too, gives the mark
                    marks0 = HitMarkerClient.marks;
                    grenade();
                    wait(20, 57);
                }
                case 57 -> {
                    int n = HitMarkerClient.marks - marks0;
                    log("стрелку гранатой рядом: хитмаркеров {}", n);
                    check(n >= 1, "стрелку: хитмаркер от взрыва (" + n + ")");
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

    // the HUD's rows changed by the glitch, the sky above not (away from the edges: ShockArcs' vignette and discharges
    // are there), the top edge lighter by the vignette; after the shock all as at first. A share of the pixels changed by
    // more than 12 of 255 (the sky's own light drifts by a level or two, a speck may cross it)
    private static void compare(com.mojang.blaze3d.platform.NativeImage now, String what, boolean glitched) {
        int w = Math.min(base.getWidth(), now.getWidth()), h = Math.min(base.getHeight(), now.getHeight());
        int hud = 0, sky = 0, edge = 0, nh = 0, ns = 0, ne = 0;
        for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) {
            int was = base.getPixel(x, y), is = now.getPixel(x, y);
            boolean changed = diff(was, is) > 12.0;
            // the middle of the hotbar (the gun in the hand is to the right of it), the sky over the crosshair, the top edge
            if (y > h * 22 / 25 && Math.abs(x - w / 2) < w / 8) {
                if (changed) hud++;
                nh++;
            } else if (y > h * 7 / 25 && y < h * 2 / 5 && Math.abs(x - w / 2) < w / 8) {
                if (changed) sky++;
                ns++;
            } else if (y < h / 40 && Math.abs(x - w / 2) < w / 4) {
                if (light(is) > light(was) + 6.0) edge++;
                ne++;
            }
        }
        double sh = (double) hud / Math.max(1, nh), ss = (double) sky / Math.max(1, ns), se = (double) edge / Math.max(1, ne);
        log("{}: изменилось в рядах HUD {} точек, в небе {}; светлее у верхнего края {}", what, f(sh), f(ss), f(se));
        if (glitched) {
            check(sh > 0.05, what + ": HUD изменился (" + f(sh) + ")");
            check(ss < 0.003, what + ": небо над HUD то же (" + f(ss) + ")");
            check(se > 0.5, what + ": у края светлая виньетка (" + f(se) + ")");
        // the top edge: the sky's own light there drifts a little lighter now and then (0.4 % of it after a shock)
        } else check(sh < 0.003 && ss < 0.003 && se < 0.05, what + ": как до шока (" + f(sh) + ", " + f(ss) + ", " + f(se) + ")");
    }

    private static double light(int c) {
        return ((c & 0xFF) + (c >> 8 & 0xFF) + (c >> 16 & 0xFF)) / 3.0;
    }

    private static double diff(int a, int b) {
        int d = 0;
        for (int k = 0; k < 24; k += 8) d += Math.abs((a >> k & 0xFF) - (b >> k & 0xFF));
        return d / 3.0;
    }

    // one server tick of the window (on the server's thread): shocked or not, the spread, the speed
    private static void watch(ServerPlayer p) {
        TICKS.add(new float[] {p.level().getServer().getTickCount(), ShockServer.shocked(p) ? 1F : 0F, spreadOn(p, p),
                (float) p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)});
    }

    // the server's tick `at` of the window, or null
    private static float[] at(int at) {
        for (float[] t : TICKS) if ((int) t[0] == at) return t;
        return null;
    }

    // the client's last frame on the server's tick `at` or before it (the client hears a tick late), or null
    private static float[] frameAt(int at) {
        float[] out = null;
        for (float[] fr : FRAMES) if (fr[0] <= at) out = fr;
        return out;
    }

    // the spread, the speed and the view at full (the middle of the 40 ticks), halfway through the fade (the envelope
    // is a half there) and after: by the first free tick on the server, a few ticks later on the client
    private static void windowChecks() {
        float[] before = at(hitAt - 1), full = at(hitAt + 20), half = at(hitAt + 40 + ShockServer.FADE_TICKS / 2), after = at(firstFree);
        if (before == null || full == null || half == null || after == null) {
            check(false, "игрок: такты окна есть");
            return;
        }
        float sp0 = after[2];
        log("игрок: разброс до/полная/середина затухания/после {} {} {} {}; скорость {} {} {} {}", f(before[2]), f(full[2]), f(half[2]), f(after[2]), f(before[3]),
                f(full[3]), f(half[3]), f(after[3]));
        check(Math.abs(full[2] - sp0 - 0.12F) < 0.005F, "игрок: на полной разброс на 0,12 выше (" + f(full[2] - sp0) + ")");
        check(Math.abs(half[2] - sp0 - 0.06F) < 0.005F, "игрок: в середине затухания на 0,06 выше (" + f(half[2] - sp0) + ")");
        float v0 = before[3], slow = ShockServer.SLOW;
        check(v0 > 0F && Math.abs(full[3] / v0 - (1F - slow)) < 0.005F, "игрок: скорость на полной ×" + f(1F - slow) + " (" + f(full[3] / v0) + ")");
        check(Math.abs(half[3] / v0 - (1F - slow / 2F)) < 0.005F, "игрок: в середине затухания ×" + f(1F - slow / 2F) + " (" + f(half[3] / v0) + ")");
        check(Math.abs(after[3] - v0) < 1e-5F, "игрок: после шока скорость прежняя (" + f(after[3]) + ")");
        boolean down = true;
        for (float[] t : TICKS) {
            float[] next = at((int) t[0] + 1);
            if (t[0] >= hitAt + 40 && t[0] < firstFree && next != null && next[3] < t[3] - 1e-6F) down = false;
        }
        check(down, "игрок: в затухании скорость только растёт");
        float[] c0 = frameAt(hitAt - 1), c1 = frameAt(hitAt + 20), c2 = frameAt(hitAt + 40 + ShockServer.FADE_TICKS / 2), c3 = frameAt(firstFree + 5);
        if (c0 == null || c1 == null || c2 == null || c3 == null) {
            check(false, "игрок: кадры клиента в окне есть");
            return;
        }
        log("клиент: скорость до/полная/середина/после {} {} {} {}; FOV ×{} ×{} ×{} ×{}", f(c0[1]), f(c1[1]), f(c2[1]), f(c3[1]), f(c0[2]), f(c1[2]), f(c2[2]),
                f(c3[2]));
        check(Math.abs(c1[1] / c0[1] - (1F - slow)) < 0.01F, "клиент: скорость на полной ×" + f(1F - slow) + " (" + f(c1[1] / c0[1]) + ")");
        // the view narrowed by the shock's own share (ShockServer.FOV), not by the game's tunnel for such a slowing; the
        // client hears a tick late, a tick halfway through the fade is 0.005 of the view
        float fov = ShockServer.FOV;
        check(Math.abs(c1[2] / c0[2] - (1F - fov)) < 0.005F, "клиент: обзор на полной ×" + f(1F - fov) + " от прежнего (×" + f(c0[2]) + " → ×" + f(c1[2]) + ")");
        check(Math.abs(c2[2] / c0[2] - (1F - fov / 2F)) < 0.012F, "клиент: в середине затухания ×" + f(1F - fov / 2F) + " (×" + f(c2[2] / c0[2]) + ")");
        check(Math.abs(c3[2] - c0[2]) < 0.002F && Math.abs(c3[1] - c0[1]) < 1e-5F, "клиент: после шока обзор и скорость прежние (×" + f(c3[2]) + ")");
    }

    // the window from the ticks. The player's own spread grows once hit (the hit knocks it about, the spread of
    // moving): the base is its spread at the first tick after the shock; a tick is up (S, ! out of the shock) when 0.03
    // over it
    private static void judge() {
        hitAt = -1;
        firstFree = -1;
        shockedTicks = 0;
        spreadOut = -1F;
        for (float[] t : TICKS) {
            if (t[1] > 0F && hitAt < 0) hitAt = (int) t[0];
            if (hitAt >= 0 && t[1] == 0F && firstFree < 0) {
                firstFree = (int) t[0];
                spreadOut = t[2];
            }
        }
        for (float[] t : TICKS) {
            boolean in = t[1] > 0F, up = t[2] > spreadOut + 0.03F;
            if (in) shockedTicks++;
            SERIES.append(' ').append(in ? (up ? 'S' : 's') : (up ? '!' : '.'));
        }
        StringBuilder v = new StringBuilder();
        for (float[] t : TICKS) v.append(' ').append(f(t[2]));
        log("игрок: разброс по тактам:{}", v);
    }

    // the immunity: a shell in the shock (at +20) and after it (+110: the shock 100 ticks, the immunity 60 more) shock
    // nothing, one at +170 shocks again
    private static void immunity() throws Exception {
        onServer(p -> {
            ShockServer.clear();
            // the shooter's gun: a repeated hit is a part of its slug
            shooter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.GUN_MARESLEG.get()));
            return null;
        });
        fire(false);
        waitTicks(5);
        boolean first = onServer(p -> ShockServer.shocked(p));
        long[] s0 = onServer(p -> ShockServer.state(p));
        float lost0 = onServer(ShockStand::lost);
        waitTicks(15);
        fire(false);
        waitTicks(5);
        long[] s1 = onServer(p -> ShockServer.state(p));
        float lost1 = onServer(ShockStand::lost);
        waitTicks(85);
        fire(false);
        waitTicks(5);
        boolean afterEnd = onServer(p -> ShockServer.shocked(p));
        float lost2 = onServer(ShockStand::lost);
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
        // the Maresleg's slug on the player as the final scheme has it (its armour: none), the range at 19 blocks none
        float slug = onServer(p -> BalanceFinal.clickOn(p, "maresleg.g12_slug")), low = ShockServer.REPEAT * slug - 0.3F, high = ShockServer.REPEAT * slug + 0.3F;
        log("повтор: урон первого {}, в шоке {}, в иммунитете {} (слаг {}, ждём {}–{})", lost0, lost1, lost2, slug, low, high);
        check(lost0 > 0F && lost0 < 3.5F, "повтор: первый патрон бьёт слабо");
        check(lost1 >= low && lost1 <= high, "повтор: патрон в шоке бьёт как почти слаг");
        check(lost2 >= low && lost2 <= high, "повтор: патрон в иммунитете бьёт как почти слаг");
        onServer(p -> {
            ShockServer.clear();
            shooter.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return null;
        });
        wait(10, 50);
    }

    // what the player lost of its health since the last shell (fire heals it first)
    private static float lost(ServerPlayer p) {
        return p.getMaxHealth() - p.getHealth();
    }

    // the agent: shocked by the player's shells, its shots' spread through calcSpread, the immunity and the marks. In
    // steps, the client drawing meanwhile: the player's shells wait for its client
    private static float agentBefore, agentDuring;
    private static float[] stepBefore, stepDuring;
    private static boolean agentIn;

    private static void agent() {
        if (agent == null) {
            check(false, "агент ФБР не появился");
            wait(1, 54);
            return;
        }
        marks0 = HitMarkerClient.marks;
        agentBefore = onServer(p -> spreadOn(agent, agent));
        stepBefore = onServer(p -> step(agent));
        fire(true);
        wait(8, 52);
    }

    private static void agentShocked() {
        agentIn = onServer(p -> ShockServer.shocked(agent));
        agentDuring = onServer(p -> spreadOn(agent, agent));
        stepDuring = onServer(p -> step(agent));
        log("агент: добавка шока сама по себе {}", f(onServer(p -> ShockServer.spread(agent, XFactory12ga.g12_slug))));
        fire(true);
        // past the shock (at full and its fade) from the first shell
        wait(40 + ShockServer.FADE_TICKS + 10, 53);
    }

    private static void agentAfter() {
        long[] s = onServer(p -> ShockServer.state(agent));
        boolean out = onServer(p -> ShockServer.shocked(agent));
        float after = onServer(p -> spreadOn(agent, agent));
        float[] stepAfter = onServer(p -> step(agent));
        log("агент: шок {}, разброс {} → {} → {}, после {}", agentIn, f(agentBefore), f(agentDuring), f(after), out);
        log("агент: скорость {} → {} → {}, шаг по пути {} → {} → {}", f(stepBefore[0]), f(stepDuring[0]), f(stepAfter[0]), f(stepBefore[1]), f(stepDuring[1]),
                f(stepAfter[1]));
        float k = 1F - ShockServer.SLOW;
        check(stepBefore[0] > 0F && Math.abs(stepDuring[0] / stepBefore[0] - k) < 0.005F,
                "агент: скорость под шоком ×" + f(k) + " (" + f(stepDuring[0] / stepBefore[0]) + ")");
        check(stepBefore[1] > 0F && Math.abs(stepDuring[1] / stepBefore[1] - k) < 0.005F,
                "агент: шаг по пути под шоком ×" + f(k) + " (" + f(stepDuring[1] / stepBefore[1]) + ")");
        check(Math.abs(stepAfter[0] - stepBefore[0]) < 1e-5F && Math.abs(stepAfter[1] - stepBefore[1]) < 1e-5F, "агент: после шока скорость и шаг прежние");
        check(agentIn, "агент: шок пришёл");
        check(Math.abs(agentDuring - agentBefore - 0.12F) < 0.005F, "агент: разброс его выстрела +0,12 (" + f(agentDuring - agentBefore) + ")");
        check(!out && Math.abs(after - agentBefore) < 0.005F, "агент: после шока разброс прежний");
        check(s != null, "агент: состояние шока есть");
        wait(10, 54);
    }

    // a mob's speed and the speed its move control gives its step to a place 5 blocks off (as on a path: the
    // navigation's moveTo sets the place, the move control the step); its step taken back after, it stays where it is
    private static float[] step(Mob mob) {
        float v = (float) mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        mob.getMoveControl().setWantedPosition(mob.getX() + 5.0, mob.getY(), mob.getZ(), 1.0);
        mob.getMoveControl().tick();
        float s = mob.getSpeed();
        mob.setZza(0F);
        mob.setXxa(0F);
        mob.setSpeed(0F);
        return new float[] {v, s};
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
                b = new EntityBulletBaseMK4(level, p, c, 16F * c.damageMult, -c.spread, EX, O.getY() + 1.62, EZ, dx, agent.getY() + 1.0 - (O.getY() + 1.62), dz);
            } else b = new EntityBulletBaseMK4(level, shooter, c, 16F * c.damageMult, -c.spread, EX, O.getY() + 1.0, EZ - 19, 0, 0, 1);
            level.addFreshEntity(b);
            return null;
        });
    }

    // the player's GL-54 HE grenade straight down from 5 blocks up (past its arming 3) into the ground a block short of
    // the agent: no direct hit, the blast's
    private static void grenade() {
        onServer(p -> {
            ServerLevel level = (ServerLevel) p.level();
            if (!agent.isAlive()) {
                agent = (Mob) agent.getType().create(level, EntitySpawnReason.COMMAND);
                agent.snapTo(EX + 3, O.getY(), EZ + 6, 180F, 0F);
                agent.setNoAi(true);
                level.addFreshEntity(agent);
            }
            agent.setHealth(agent.getMaxHealth());
            double x = agent.getX(), z = agent.getZ() - 1.2;
            level.addFreshEntity(new EntityBulletBaseMK4(level, p, acup.gunfeel.gl54.Gl54Ammo.he(), acup.gunfeel.gl54.Gl54Grenade.BASE_DAMAGE, 0F,
                    x, O.getY() + 5.0, z, 0, -1, 0));
            return null;
        });
    }

    // the player's plain NTM bullet (it counts the head ×2) at the agent's head or body; `kill`: the agent nearly dead
    private static void bullet(boolean head, boolean kill) {
        onServer(p -> {
            ServerLevel level = (ServerLevel) p.level();
            // the kill: under any armour's and the damage cap's cut, the bullet still takes more than is left
            agent.setHealth(kill ? 0.05F : agent.getMaxHealth());
            BulletConfig c = new BulletConfig().setDamage(4F).setHeadshot(2F);
            double y = head ? agent.getY() + agent.getBbHeight() - 0.12 : agent.getY() + agent.getBbHeight() * 0.45;
            double sy = O.getY() + 1.62, dx = agent.getX() - EX, dy = y - sy, dz = agent.getZ() - EZ;
            level.addFreshEntity(new EntityBulletBaseMK4(level, p, c, 4F, 0F, EX, sy, EZ, dx, dy, dz));
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
