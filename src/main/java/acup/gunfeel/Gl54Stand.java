package acup.gunfeel;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.HbmAnimations;
import com.hbm.util.GameTime;

import acup.gunfeel.gl54.Gl54Ammo;
import acup.gunfeel.gl54.Gl54Grenade;
import acup.gunfeel.gl54.Gl54Items;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// runCheckClient -Pgl54Stand=<dir>: the GL-54 in the game, in numbers. Frames (the GUI hidden) go to <dir> as PNG for the
// Python scripts to count (the pictures are not looked at), the rest into <dir>/stand.tsv:
//  - pose-<yaw>-<pitch>-<gun|full>.png: first person at five view turns, with the arms and without (Config.arms), on white
//    walls: the gun is what is dark, the arms are the difference between the two;
//  - bolt-idle / bolt-fire: the shot's animation 110 ms in (the bolt carrier back), and the case that comes out;
//  - third / ground / gui pictures with the gun and without it;
//  - grenade: a real shot, the blast out in the room, husks at gaps from the blast (damage by distance), glass at gaps,
//    a dud at less than 3 blocks (into a husk and into a wall), a shot through leaves.
final class Gl54Stand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.gl54Stand");
    private static final Path DIR = Path.of(System.getProperty("ntmgunfeel.gl54StandDir", "../gl54-stand"));
    private static final BlockPos O = new BlockPos(8, 100, 8);
    // the room: walls at 12 blocks around, the target wall in front (+Z)
    private static final int R = 12;
    // the aimed place under test (ItemRenderGl54.aimY)
    private static final float AIM_Y = acup.gunfeel.gl54.ItemRenderGl54.aimY;
    private static final float[][] POSES = {{0, 0}, {135, 0}, {-135, 0}, {0, 30}, {0, -30}};

    private static final Deque<BooleanSupplier> TODO = new ArrayDeque<>();
    private static final List<String> OUT = new ArrayList<>();
    private static boolean started;
    private static int wait;
    private static volatile int pending;
    // the view turned every frame (null: the player's own)
    private static volatile float[] view;
    private static volatile Object[] blast;
    // the port of the model before a shot, hip and aimed (Gl54Probe): the first frame of the case is set against it
    private static float[] restHip, restAim;
    private static final List<Object[]> BLASTS = new ArrayList<>();

    private Gl54Stand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        Gl54Probe.ON = true;
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.SNOW_BLOCK.defaultBlockState(), 3);
                for (int y = 0; y <= 14; y++) {
                    boolean wall = Math.abs(x) == R || Math.abs(z) == R;
                    level.setBlock(O.offset(x, y, z), wall ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
            }
            Gl54Grenade.watch = o -> {
                synchronized (BLASTS) {
                    BLASTS.add(o);
                }
            };
        }).join();
        try {
            Files.createDirectories(DIR);
        } catch (java.io.IOException e) {
            ClientSmoke.check(false, "gl54: папка " + e);
        }
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        float[] v = view;
        if (v != null && mc.player != null) {
            mc.player.setYRot(v[0]);
            mc.player.yRotO = v[0];
            mc.player.setXRot(v[1]);
            mc.player.xRotO = v[1];
        }
        if (!started) {
            started = true;
            plan(mc);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        var next = TODO.peek();
        try {
            if (next != null && next.getAsBoolean()) TODO.poll();
        } catch (Throwable t) {
            GunFeel.LOGGER.error("[gl54] failed", t);
            ClientSmoke.check(false, "gl54: " + t);
            TODO.clear();
            TODO.add(Gl54Stand::done);
        }
    }

    private static void sleep(int frames) {
        TODO.add(() -> {
            wait = frames;
            return true;
        });
    }

    private static void run(Runnable r) {
        TODO.add(() -> {
            r.run();
            return true;
        });
    }

    private static void line(Object... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i++) {
            if (i > 0) b.append('\t');
            Object o = kv[i];
            b.append(o instanceof Double d ? String.format(Locale.ROOT, "%.4f", d) : o instanceof Float f ? String.format(Locale.ROOT, "%.4f", f) : String.valueOf(o));
        }
        OUT.add(b.toString());
        GunFeel.LOGGER.info("[gl54] {}", b);
    }

    // ---------------------------------------------------------------- the plan

    private static void plan(Minecraft mc) {
        if (Boolean.getBoolean("ntmgunfeel.gl54StandMag")) {
            magPlan(mc);
            return;
        }
        run(() -> {
            noon(mc);
            Config.cornerHud = false;
        });
        sleep(300);
        // a full magazine, in the hand, drawn
        run(() -> give(mc, false));
        TODO.add(() -> mc.player.getMainHandItem().getItem() == Gl54Items.GUN.get());
        sleep(200);
        run(() -> line("gun", "in hand", "item", mc.player.getMainHandItem().getItem()));

        // five turns of the view, with the arms and without
        for (float[] pose : POSES) {
            for (boolean arms : new boolean[] {true, false}) {
                run(() -> {
                    Config.arms = arms;
                    turn(mc, pose[0], pose[1]);
                });
                sleep(40);
                String name = "pose-" + (int) pose[0] + "-" + (int) pose[1] + (arms ? "-full" : "-gun") + ".png";
                run(() -> shot(name));
                run(() -> probe(name.replace(".png", "")));
                TODO.add(() -> pending == 0);
            }
        }
        run(() -> {
            Config.arms = false;
            turn(mc, 0, 0);
            give(mc, true);
        });
        sleep(60);
        run(() -> shot("empty.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            Config.arms = true;
            give(mc, false);
        });
        sleep(120);
        // Congo Lake, the gun as long as this one, for comparison
        for (boolean arms : new boolean[] {true, false}) {
            run(() -> {
                Config.arms = arms;
                congo(mc);
            });
            sleep(150);
            String name = "ref-congo" + (arms ? "-full" : "-gun") + ".png";
            run(() -> shot(name));
            TODO.add(() -> pending == 0);
        }
        run(() -> {
            Config.arms = true;
            give(mc, false);
        });
        sleep(120);
        // the sights: the gun really aimed (the stack's flag) and held, the same pose
        for (float ay : new float[] {-1.35F, -1.0F}) {
            run(() -> {
                acup.gunfeel.gl54.ItemRenderGl54.aimY = ay;
                aim(mc, true);
            });
            sleep(70);
            run(() -> probe("aimY" + ay));
        }
        run(() -> acup.gunfeel.gl54.ItemRenderGl54.aimY = AIM_Y);
        sleep(20);
        run(() -> {
            line("aiming progress", ItemGunBaseNT.aimingProgress, "stack aims", ItemGunBaseNT.getIsAiming(mc.player.getMainHandItem()));
            probe("aim");
            Config.arms = true;
            shot("aimed-full.png");
        });
        TODO.add(() -> pending == 0);
        run(() -> Config.arms = false);
        sleep(10);
        run(() -> shot("aimed-gun.png"));
        TODO.add(() -> pending == 0);
        // a shot while aimed: the case in its first frames
        run(() -> {
            Config.arms = true;
            restAim = Gl54Probe.POINTS.get("port");
            fireReal(mc);
        });
        for (int i = 0; i < 5; i++) {
            int n = 100 + i;
            sleep(1);
            run(() -> casings(mc, n));
        }
        run(() -> aim(mc, false));
        sleep(90);

        // the shot: idle frame, then the real shot, the animation 110 ms in
        run(() -> shot("bolt-idle.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            restHip = Gl54Probe.POINTS.get("port");
            probe("rest-hip");
            fireReal(mc);
            blast = null;
        });
        TODO.add(() -> HbmAnimations.getRelevantAnim(0) != null);
        run(() -> {
            var a = HbmAnimations.getRelevantAnim(0);
            line("animation", "started", "key", a.key, "ms", GameTime.now() - a.startMillis);
            a.startMillis = GameTime.now() - 110;
        });
        TODO.add(() -> {
            // the frame after the one that set the time: the bolt is back
            shot("bolt-fire.png");
            return true;
        });
        TODO.add(() -> pending == 0);
        // the case: the particles in the next frames
        for (int i = 0; i < 90; i++) {
            int n = i;
            run(() -> casings(mc, n));
            sleep(1);
        }
        sleep(60);

        // the room for grenades
        run(() -> {
            hide(mc, true);
            view = null;
        });
        grenades(mc);

        // pictures of the item: third person, on the ground, in the inventory
        run(() -> {
            give(mc, false);
            turn(mc, 0, 0);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        });
        sleep(150);
        run(() -> shot("third-gun.png"));
        TODO.add(() -> pending == 0);
        run(() -> give(mc, true));
        sleep(60);
        run(() -> shot("third-empty.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            drop(mc, true);
            turn(mc, 0, 55);
        });
        sleep(80);
        run(() -> shot("ground-gun.png"));
        TODO.add(() -> pending == 0);
        run(() -> drop(mc, false));
        sleep(60);
        run(() -> shot("ground-empty.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            turn(mc, 0, 0);
            view = null;
            give(mc, false);
            hide(mc, false);
            mc.gui.setScreen(new InventoryScreen(mc.player));
        });
        sleep(30);
        run(() -> shot("gui-gun.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            mc.gui.setScreen(null);
            give(mc, true);
            mc.gui.setScreen(new InventoryScreen(mc.player));
        });
        sleep(30);
        run(() -> shot("gui-empty.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            mc.gui.setScreen(null);
            give(mc, false);
        });
        sleep(30);
        TODO.add(Gl54Stand::silhouette);
        TODO.add(Gl54Stand::done);
    }


    // ---------------------------------------------------------------- the magazines (-Pgl54StandMag)

    private static final String[] MOCK_STOCK = {"airburst:8", "airburst:8", "airburst:5", "he:8", "he:3", "inc:8", "smoke:8", "smoke:4",
            "ap:8", "chlorine:8", "phosgene:6", "mustard:8", "acid:8"};
    private static int tick0;

    private static <T> T onServer(java.util.function.Supplier<T> f) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        Object[] out = new Object[1];
        server.submit(() -> out[0] = f.get()).join();
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static ServerPlayer player() {
        return Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayers().getFirst();
    }

    // the gun in the hand with that load, the inventory with the stock (list "kind:count" or "mixed" for the sample)
    private static void loadout(acup.gunfeel.gl54.GunMag gun, String... stock) {
        onServer(() -> {
            ServerPlayer p = player();
            p.setGameMode(GameType.CREATIVE);
            p.getInventory().clearContent();
            ItemStack stack = new ItemStack(Gl54Items.GUN.get());
            stack.set(Gl54Items.GUN_MAG.get(), gun);
            ItemGunBaseNT.setIsDrawn(stack, true);
            ItemGunBaseNT.setIsEquipped(stack, true);
            ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
            ItemGunBaseNT.setTimer(stack, 0, 0);
            p.getInventory().setSelectedSlot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            int slot = 9;
            for (String spec : stock) {
                String[] kn = spec.split(":");
                ItemStack mag = kn[0].equals("mixed") ? Gl54Items.magazine(new acup.gunfeel.gl54.MagContents(acup.gunfeel.gl54.GrenadeKind.SAMPLE_MIX))
                        : kn[0].equals("empty") ? new ItemStack(Gl54Items.MAG_EMPTY.get(), Integer.parseInt(kn[1]))
                                : Gl54Items.magazine(acup.gunfeel.gl54.MagContents.filled(kn[0], Integer.parseInt(kn[1])));
                p.getInventory().setItem(slot++, mag);
            }
            return null;
        });
    }

    // what the gun and the inventory hold now, as one line
    private static void snap(String tag) {
        String text = onServer(() -> {
            ServerPlayer p = player();
            ItemStack g = p.getMainHandItem();
            var gm = acup.gunfeel.gl54.GrenadeMagazine.gun(g);
            StringBuilder inv = new StringBuilder();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (s.getItem() == Gl54Items.MAG.get()) {
                    var c = acup.gunfeel.gl54.MagItem.contents(s);
                    inv.append(i).append('=').append(c.kind()).append('x').append(c.size()).append(' ');
                } else if (s.getItem() == Gl54Items.MAG_EMPTY.get()) inv.append(i).append("=EMPTYx").append(s.getCount()).append(' ');
            }
            return "mag=" + gm.mag().size() + " chamber=" + gm.chamber() + " count=" + gm.count() + " kind=" + gm.kind() + " sel=" + gm.sel()
                    + " next=" + gm.next() + " state=" + ItemGunBaseNT.getState(g, 0) + " | " + inv;
        });
        line("mag", tag, text);
    }

    private static void key(EnumKeybind k) {
        onServer(() -> {
            ServerPlayer p = player();
            ItemStack stack = p.getMainHandItem();
            ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
            gun.handleKeybind(p, p.getInventory(), stack, k, true);
            gun.handleKeybind(p, p.getInventory(), stack, k, false);
            return null;
        });
    }

    private static int serverTick() {
        return onServer(() -> Minecraft.getInstance().getSingleplayerServer().getTickCount());
    }

    private static boolean idle() {
        return onServer(() -> ItemGunBaseNT.getState(player().getMainHandItem(), 0) == ItemGunBaseNT.GunState.IDLE);
    }

    private static int gunCount() {
        return onServer(() -> acup.gunfeel.gl54.GrenadeMagazine.gun(player().getMainHandItem()).count());
    }

    // R, then the ticks to the swap (the gun's count or magazine changes) and to idle again, one line
    private static void reload(String tag) {
        int[] t = new int[3];
        String[] before = new String[1];
        run(() -> {
            before[0] = String.valueOf(onServer(() -> acup.gunfeel.gl54.GrenadeMagazine.gun(player().getMainHandItem())));
            t[0] = serverTick();
            key(EnumKeybind.RELOAD);
        });
        TODO.add(() -> {
            if (t[1] == 0 && !String.valueOf(onServer(() -> acup.gunfeel.gl54.GrenadeMagazine.gun(player().getMainHandItem()))).equals(before[0])) t[1] = serverTick();
            if (t[1] != 0 && idle()) {
                t[2] = serverTick();
                return true;
            }
            return false;
        });
        run(() -> line("reload", tag, "swapAfterTicks", t[1] - t[0], "idleAfterTicks", t[2] - t[0]));
    }

    private static void fire(String tag, int times) {
        for (int i = 0; i < times; i++) {
            run(() -> fireReal(Minecraft.getInstance()));
            TODO.add(() -> idle());
        }
        run(() -> snap(tag));
    }

    // a frame of the corner HUD with the block and one without (the difference is the block), by name
    private static void hudShot(String name) {
        run(() -> Config.cornerHud = true);
        sleep(6);
        run(() -> shot(name + ".png"));
        TODO.add(() -> pending == 0);
        run(() -> Config.cornerHud = false);
        sleep(6);
        run(() -> shot(name + "-bare.png"));
        TODO.add(() -> pending == 0);
        run(() -> Config.cornerHud = true);
    }

    private static void magPlan(Minecraft mc) {
        run(() -> {
            noon(mc);
            Config.arms = false;
            Config.cornerHud = true;
            mc.options.guiScale().set(3);
            hide(mc, false);
            turn(mc, 0, 0);
            onServer(() -> {
                player().setGameMode(GameType.CREATIVE);
                player().getAbilities().flying = true;
                player().onUpdateAbilities();
                return null;
            });
            Hud.lowClockFixed = 0L;
        });
        sleep(200);
        // 1. a new gun, no magazine: R with 13 magazines in the inventory: 8 (7 + the first fed), 50 ticks, nothing given back
        run(() -> loadout(new acup.gunfeel.gl54.GunMag(java.util.List.of(), "", "airburst", false, false), MOCK_STOCK));
        sleep(30);
        run(() -> snap("start"));
        reload("new-gun R");
        sleep(10);
        run(() -> snap("after new-gun R"));
        hudShot("hud-full");
        // 2. a shot: 7, the next fed into the barrel
        fire("1 shot", 1);
        sleep(10);
        hudShot("hud-shot1");
        // 3. down to 2 left, 1 left, 0
        fire("to 3", 4);
        fire("to 2", 1);
        sleep(10);
        run(() -> Hud.lowClockFixed = 0L);
        hudShot("hud-low-on");
        run(() -> Hud.lowClockFixed = 500L);
        hudShot("hud-low-off");
        run(() -> Hud.lowClockFixed = 0L);
        fire("to 0", 2);
        sleep(10);
        hudShot("hud-empty");
        // 4. reload the empty gun: 2.5 s, 8, the empty magazine back as an item
        reload("empty R");
        sleep(10);
        run(() -> snap("after empty R"));
        // 5. not empty, a part-fired magazine: 2.0 s, 8 + 1 = 9
        fire("4 shots", 4);
        reload("part-fired R");
        sleep(10);
        run(() -> snap("after part-fired R"));
        // 6. a reload cut short by changing the slot loses nothing
        fire("2 shots", 2);
        run(() -> snap("before cut"));
        run(() -> key(EnumKeybind.RELOAD));
        sleep(8);
        run(() -> onServer(() -> {
            player().getInventory().setSelectedSlot(1);
            return null;
        }));
        sleep(60);
        run(() -> onServer(() -> {
            player().getInventory().setSelectedSlot(0);
            return null;
        }));
        sleep(60);
        run(() -> snap("after cut"));
        // 7. the pour: 5 left in the gun (+ barrel), the stock 8, 6, 2 of the same kind: the 5 fill the 6 to 8, 3 come out as a stub
        run(() -> loadout(new acup.gunfeel.gl54.GunMag(java.util.Collections.nCopies(5, "airburst"), "airburst", "airburst", true, false),
                "airburst:8", "airburst:6", "airburst:2"));
        sleep(20);
        reload("pour R");
        sleep(10);
        run(() -> snap("after pour R"));
        // 8. change of kind: HE chosen through the wheel's path; the barrel's airburst goes first
        run(() -> loadout(new acup.gunfeel.gl54.GunMag(java.util.Collections.nCopies(6, "airburst"), "airburst", "airburst", true, false), MOCK_STOCK));
        sleep(20);
        run(() -> {
            int id = acup.gunfeel.gl54.Gl54Ammo.type("he").id;
            onServer(() -> {
                AmmoLock.select(player(), id);
                return null;
            });
        });
        TODO.add(() -> !idle());
        TODO.add(() -> idle());
        sleep(10);
        run(() -> snap("after change to HE"));
        run(() -> {
            fireReal(mc);
        });
        TODO.add(() -> idle());
        run(() -> snap("first shot after the change"));
        run(() -> fireReal(mc));
        TODO.add(() -> idle());
        run(() -> snap("second shot after the change"));
        // 9. a mixed magazine
        run(() -> loadout(acup.gunfeel.gl54.GunMag.NONE, "mixed:8", "airburst:8", "he:8"));
        sleep(20);
        run(() -> {
            int id = acup.gunfeel.gl54.Gl54Ammo.mixed().id;
            onServer(() -> {
                AmmoLock.select(player(), id);
                return null;
            });
        });
        TODO.add(() -> !idle());
        TODO.add(() -> idle());
        sleep(10);
        run(() -> snap("mixed loaded"));
        hudShot("hud-mixed");
        run(() -> fireReal(mc));
        TODO.add(() -> idle());
        sleep(10);
        hudShot("hud-mixed-shot1");
        // 10. a reload in progress: the bar, a frame at about a third and at the end phase
        run(() -> loadout(new acup.gunfeel.gl54.GunMag(java.util.Collections.nCopies(3, "he"), "he", "he", true, false), MOCK_STOCK));
        sleep(20);
        run(() -> key(EnumKeybind.RELOAD));
        sleep(14);
        hudShot("hud-reload-a");
        sleep(10);
        hudShot("hud-reload-b");
        TODO.add(() -> idle());
        // 11. the wheel, with the mock's stock and the mixed magazine
        run(() -> loadout(new acup.gunfeel.gl54.GunMag(java.util.Collections.nCopies(7, "he"), "he", "he", true, false),
                "airburst:8", "airburst:8", "airburst:5", "he:8", "he:3", "inc:8", "smoke:8", "smoke:4", "ap:8", "chlorine:8", "phosgene:6", "mustard:8", "acid:8", "mixed:8"));
        sleep(30);
        run(() -> {
            RadialMenu.open(mc.player.getMainHandItem());
            RadialMenu.move(0, -60);
        });
        sleep(30);
        run(() -> shot("wheel-up.png"));
        TODO.add(() -> pending == 0);
        run(() -> RadialMenu.move(40, 40));
        sleep(30);
        run(() -> shot("wheel-hover.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            RadialMenu.close();
            Config.cornerHud = false;
        });
        sleep(10);
        run(() -> shot("wheel-bare.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            Config.cornerHud = true;
            RadialMenu.open(mc.player.getMainHandItem());
            RadialMenu.move(0, -60);
        });
        sleep(30);
        // the turned camera: the wheel and the HUD stay put
        for (float[] pose : new float[][] {{135, 30}, {-135, -30}}) {
            run(() -> turn(mc, pose[0], pose[1]));
            sleep(40);
            String n = "wheel-turn-" + (int) pose[0] + "-" + (int) pose[1];
            run(() -> shot(n + ".png"));
            TODO.add(() -> pending == 0);
        }
        run(() -> {
            RadialMenu.close();
            turn(mc, 0, 0);
        });
        sleep(20);
        for (float[] pose : new float[][] {{135, 30}, {-135, -30}, {0, 0}}) {
            run(() -> turn(mc, pose[0], pose[1]));
            sleep(40);
            hudShot("hud-turn-" + (int) pose[0] + "-" + (int) pose[1]);
        }
        sleep(10);
        TODO.add(Gl54Stand::done);
    }

    // ---------------------------------------------------------------- the pieces

    private static void turn(Minecraft mc, float yaw, float pitch) {
        view = new float[] {yaw, pitch};
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.teleportTo(server.overworld(), O.getX() + 0.5, O.getY(), O.getZ() + 0.5, Set.of(), yaw, pitch, false);
            p.setDeltaMovement(0, 0, 0);
        }).join();
    }

    private static void noon(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            for (String c : new String[] {"time set noon", "weather clear", "gamerule advance_time false", "gamerule advance_weather false"}) {
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                } catch (RuntimeException e) {
                    GunFeel.LOGGER.warn("[gl54] {}: {}", c, e.toString());
                }
            }
        }).join();
    }

    private static void hide(Minecraft mc, boolean hidden) {
        // F1 hides the hand as well: the mod's own HUD is switched off instead (Config.cornerHud)
    }

    private static void congo(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.getInventory().clearContent();
            ItemStack stack = new ItemStack(com.hbm.items.ModItems.GUN_CONGOLAKE.get());
            ItemGunBaseNT.setIsDrawn(stack, true);
            ItemGunBaseNT.setIsEquipped(stack, true);
            p.getInventory().setSelectedSlot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        }).join();
    }

    // the gun in the main hand with eight grenades loaded; empty: a bare hand
    private static void give(Minecraft mc, boolean empty) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            p.getInventory().clearContent();
            if (!empty) {
                ItemStack stack = new ItemStack(Gl54Items.GUN.get());
                stack.set(Gl54Items.GUN_MAG.get(), new acup.gunfeel.gl54.GunMag(java.util.Collections.nCopies(7, "he"), "he", "he", true, false));
                ItemGunBaseNT.setIsDrawn(stack, true);
                ItemGunBaseNT.setIsEquipped(stack, true);
                ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
                ItemGunBaseNT.setTimer(stack, 0, 0);
                p.getInventory().setSelectedSlot(0);
                p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            }
            p.setDeltaMovement(0, 0, 0);
        }).join();
    }

    // the item on the floor in front of the player (or not)
    private static void drop(Minecraft mc, boolean on) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (var e : level.getEntitiesOfClass(ItemEntity.class, new AABB(O).inflate(8))) e.discard();
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.getInventory().clearContent();
            if (on) {
                ItemEntity item = new ItemEntity(level, O.getX() + 0.5, O.getY() + 0.1, O.getZ() + 2.0, new ItemStack(Gl54Items.GUN.get()), 0, 0, 0);
                item.setNoGravity(true);
                item.setUnlimitedLifetime();
                item.setPickUpDelay(32767);
                level.addFreshEntity(item);
            }
        }).join();
    }

    private static void aim(Minecraft mc, boolean on) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            ItemGunBaseNT.setIsAiming(p.getMainHandItem(), on);
        }).join();
    }

    // the probe's points of the last frame: view space and screen
    private static void probe(String tag) {
        for (var e : Gl54Probe.POINTS.entrySet()) {
            float[] v = e.getValue();
            line("probe", tag, e.getKey(), "view", v[0], v[1], v[2], "px", v[3], v[4], "fov", v[5]);
        }
    }

    private static void fireReal(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            ItemStack stack = p.getMainHandItem();
            ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
            gun.handleKeybind(p, p.getInventory(), stack, EnumKeybind.GUN_PRIMARY, true);
            gun.handleKeybind(p, p.getInventory(), stack, EnumKeybind.GUN_PRIMARY, false);
        }).join();
    }

    // ---------------------------------------------------------------- the case

    private static final boolean[] spawnChecked = new boolean[2];
    private static final Set<Object> SEEN = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private static void casings(Minecraft mc, int n) {
        List<Object> found = new ArrayList<>();
        Map<Object, Boolean> seenObjects = new IdentityHashMap<>();
        walk(mc.particleEngine, 0, found, seenObjects);
        if (n == 0 || n == 45) {
            int particles = 0;
            for (Object o : seenObjects.keySet()) if (o instanceof net.minecraft.client.particle.Particle) particles++;
            line("casing", "frame", n, "walked objects", seenObjects.size(), "particles", particles, "casings", found.size());
        }
        for (Object o : found) {
            try {
                Field fx = field(o.getClass(), "x"), fy = field(o.getClass(), "y"), fz = field(o.getClass(), "z");
                double x = fx.getDouble(o), y = fy.getDouble(o), z = fz.getDouble(o);
                Vec3 eye = mc.player.getEyePosition();
                Vec3 look = Vec3.directionFromRotation(0, mc.player.getYRot());
                Vec3 left = new Vec3(look.z, 0, -look.x);
                Vec3 d = new Vec3(x, y, z).subtract(eye);
                boolean fresh = SEEN.add(o);
                line("casing", "frame", n, "new", fresh, "front", d.dot(look), "height", d.y, "side", d.dot(left));
                boolean first = fresh;
                if (fresh) {
                    // the case's own speed as the game has it (world axes at yaw 0: x left, y up, z front)
                    double sx = field(o.getClass(), "xd").getDouble(o), sy = field(o.getClass(), "yd").getDouble(o), sz = field(o.getClass(), "zd").getDouble(o);
                    Vec3 sv = new Vec3(sx, sy, sz);
                    double side = sv.dot(left), front = sv.dot(look);
                    line("case speed", "frame", n, "front", front, "height", sy, "side", side, "total", sv.length());
                    ClientSmoke.check(Math.abs(sv.length() - acup.gunfeel.gl54.Gl54Casing.SPEED) < 0.06 && side < 0,
                            "gl54: скорость гильзы " + String.format(Locale.ROOT, "%.3f", sv.length()) + " вправо (" + String.format(Locale.ROOT, "%.3f", side) + "), вверх " + String.format(Locale.ROOT, "%.3f", sy));
                }
                if (first && !spawnChecked[n < 100 ? 0 : 1]) {
                    spawnChecked[n < 100 ? 0 : 1] = true;
                    float[] rest = n < 100 ? restHip : restAim;
                    if (rest != null) {
                        if (n < 100) {
                            // the view's own field of view effects (flying) are not the gun's: the probe taken at 70 degrees
                            double f = Math.tan(Math.toRadians(rest[5] * 0.5)) / Math.tan(Math.toRadians(35));
                            rest = new float[] {(float) (rest[0] / f), (float) (rest[1] / f), rest[2], 0F, 0F, 70F};
                            rest[3] = (float) (mc.getWindow().getWidth() / 2.0 + rest[0] / -rest[2] * (mc.getWindow().getHeight() / 2.0) / Math.tan(Math.toRadians(35)));
                            rest[4] = (float) (mc.getWindow().getHeight() / 2.0 - rest[1] / -rest[2] * (mc.getWindow().getHeight() / 2.0) / Math.tan(Math.toRadians(35)));
                        }
                        // the spawned case as a point of the view space, set against the port of the model before the shot
                        double cx = -d.dot(left), cy = d.y, cz = -d.dot(look);
                        double dBlocks = Math.sqrt(Math.pow(cx - rest[0], 2) + Math.pow(cy - rest[1], 2) + Math.pow(cz - rest[2], 2));
                        float w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
                        double t = Math.tan(Math.toRadians(n < 100 ? 35 : mc.gameRenderer.mainCamera().getFov() * 0.5));
                        double px = w / 2 + cx / -cz * (h / 2) / t, py = h / 2 - cy / -cz * (h / 2) / t;
                        double dpx = Math.hypot(px - rest[3], py - rest[4]);
                        line("case at the port", n < 100 ? "hip" : "aimed", "case view", cx, cy, cz, "port view", (double) rest[0], (double) rest[1], (double) rest[2],
                                "apart blocks", dBlocks, "px", dpx, "fov now", mc.gameRenderer.mainCamera().getFov(), "fov at the probe", (double) rest[5]);
                        ClientSmoke.check(n < 100 ? dBlocks <= 0.03 : dBlocks <= 0.01, "gl54: гильза у окна выброса (" + (n < 100 ? "от бедра" : "в прицеле") + "): "
                                + String.format(Locale.ROOT, "%.4f бл., %.1f px", dBlocks, dpx));
                    }
                }
                if (n <= 3 || (n >= 100 && n <= 104)) {
                    float[] pt = Gl54Probe.POINTS.get("port");
                    if (pt != null) line("port", "frame", n, "front", -pt[2], "height", pt[1], "side", -pt[0], "px", pt[3], pt[4]);
                }
            } catch (ReflectiveOperationException e) {
                line("casing", "no position", e.toString());
            }
        }
        if (found.isEmpty() && n % 15 == 0) line("casing", "frame", n, "none");
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // the parent's
            }
        }
        throw new NoSuchFieldException(name);
    }

    // the casing particles somewhere under `o` (the particle engine keeps them in groups inside maps and queues)
    private static void walk(Object o, int depth, List<Object> out, Map<Object, Boolean> seen) {
        if (o == null || depth > 6 || seen.put(o, true) != null) return;
        String n = o.getClass().getName();
        if (n.endsWith("ParticleSpentCasing")) {
            out.add(o);
            return;
        }
        if (o instanceof Collection<?> c) {
            for (Object e : c) walk(e, depth + 1, out, seen);
            return;
        }
        if (o instanceof Map<?, ?> m) {
            for (Object e : m.values()) walk(e, depth + 1, out, seen);
            return;
        }
        if (!(n.startsWith("net.minecraft.client.particle") || n.startsWith("java.util") || n.startsWith("com.hbm.particle"))) return;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    walk(f.get(o), depth + 1, out, seen);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // not readable
                }
            }
        }
    }

    // ---------------------------------------------------------------- the grenade

    private static BlockPos wall() {
        return O.offset(0, 0, R);
    }

    // a grenade of the gun's own kind fired from `from` at +Z, as exactly as the game does it (no spread)
    private static void shoot(Minecraft mc, Vec3 from, float pitchUp) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            Vec3 dir = new Vec3(0, Math.sin(Math.toRadians(pitchUp)), Math.cos(Math.toRadians(pitchUp)));
            var bullet = new EntityBulletBaseMK4(level, p, Gl54Ammo.he(), Gl54Grenade.BASE_DAMAGE, 0F, from.x, from.y, from.z, dir.x, dir.y, dir.z);
            level.addFreshEntity(bullet);
        }).join();
    }

    private static void clearRoom(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (var e : level.getEntitiesOfClass(Husk.class, new AABB(O).inflate(R))) e.discard();
            for (var e : level.getEntitiesOfClass(EntityBulletBaseMK4.class, new AABB(O).inflate(R * 2))) e.discard();
            for (int x = -R + 1; x < R; x++) for (int z = -R + 1; z < R; z++) for (int y = 0; y < 6; y++)
                level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            BLASTS.clear();
        }).join();
    }

    private static Vec3 lastBlast;
    private static double lastFlown;
    private static boolean lastWent;

    private static boolean blastIn() {
        synchronized (BLASTS) {
            if (BLASTS.isEmpty()) return false;
            Object[] b = BLASTS.getLast();
            lastBlast = (Vec3) b[0];
            lastFlown = (Double) b[1];
            lastWent = (Boolean) b[2];
            return true;
        }
    }

    private static void grenades(Minecraft mc) {
        Vec3[] calibrated = new Vec3[1];
        Vec3 muzzle = new Vec3(O.getX() + 0.5, O.getY() + 1.9, O.getZ() - 6.0);
        // 1. the real shot of the gun at the wall, 18 blocks off: the blast is out in the room
        run(() -> {
            clearRoom(mc);
            give(mc, false);
            turn(mc, 0, 0);
        });
        sleep(30);
        run(() -> fireReal(mc));
        TODO.add(Gl54Stand::blastIn);
        run(() -> {
            line("real shot", "blast at", lastBlast, "flown", lastFlown, "went off", lastWent);
            ClientSmoke.check(lastWent && lastFlown > 3.0, "gl54: настоящий выстрел взорвался на " + String.format(Locale.ROOT, "%.2f", lastFlown) + " блока");
        });
        sleep(40);
        // 2. calibration: the exact shot, then the same with husks at gaps
        run(() -> {
            clearRoom(mc);
            var server = mc.getSingleplayerServer();
            // the player out of the line of fire
            server.submit(() -> server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), O.getX() - 9.0, O.getY(), O.getZ() + 0.5, Set.of(), 0F, 0F, false)).join();
            shoot(mc, muzzle, 0F);
        });
        TODO.add(Gl54Stand::blastIn);
        run(() -> {
            calibrated[0] = lastBlast;
            line("calibration", "blast at", lastBlast, "flown", lastFlown, "went off", lastWent);
        });
        sleep(20);
        double[] gaps = {0.0, 0.7, 1.4, 2.1, 2.8, 3.5};
        List<Husk> husks = new ArrayList<>();
        run(() -> {
            clearRoom(mc);
            var server = mc.getSingleplayerServer();
            server.submit(() -> {
                ServerLevel level = server.overworld();
                Vec3 h = calibrated[0];
                for (double g : gaps) {
                    Husk husk = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
                    husk.setNoAi(true);
                    husk.setPersistenceRequired();
                    husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
                    husk.getAttribute(Attributes.ARMOR).setBaseValue(0);
                    husk.setHealth(1000);
                    // the gap from the blast to the husk's box along X: the box is 0.6 wide
                    husk.snapTo(h.x + g + 0.3, Math.floor(h.y) - 0.0, h.z - 0.5, 0F, 0F);
                    level.addFreshEntity(husk);
                    husks.add(husk);
                }
            }).join();
            shoot(mc, muzzle, 0F);
        });
        TODO.add(Gl54Stand::blastIn);
        sleep(10);
        run(() -> {
            var server = mc.getSingleplayerServer();
            server.submit(() -> {
                for (Husk husk : husks) {
                    AABB box = husk.getBoundingBox();
                    Vec3 b = lastBlast;
                    double dx = box.minX <= b.x && box.maxX >= b.x ? 0 : Math.min(Math.abs(box.minX - b.x), Math.abs(box.maxX - b.x));
                    double dy = box.minY <= b.y && box.maxY >= b.y ? 0 : Math.min(Math.abs(box.minY - b.y), Math.abs(box.maxY - b.y));
                    double dz = box.minZ <= b.z && box.maxZ >= b.z ? 0 : Math.min(Math.abs(box.minZ - b.z), Math.abs(box.maxZ - b.z));
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float got = husk.getMaxHealth() - husk.getHealth();
                    float want = Gl54Grenade.areaDamageAt(d);
                    line("husk", "gap", d, "damage", got, "expected", want);
                    ClientSmoke.check(Math.abs(got - want) <= 0.1F * want + 0.5F,
                            "gl54: зомби на " + String.format(Locale.ROOT, "%.2f", d) + " бл. получил " + got + " (по площади " + want + ")");
                }
            }).join();
        });
        // 3. glass at gaps from the blast
        run(() -> clearRoom(mc));
        // panes round the blast in different directions (none in another's way), at gaps from half a block to nearly three
        int[][] glassAt = {{1, 0, 0}, {0, 0, 2}, {0, 1, -2}, {0, 2, -1}, {-3, 0, 0}, {0, 2, -2}};
        List<BlockPos> panes = new ArrayList<>();
        run(() -> {
            var server = mc.getSingleplayerServer();
            Vec3 h = calibrated[0];
            server.submit(() -> {
                ServerLevel level = server.overworld();
                for (int[] o : glassAt) {
                    BlockPos pos = new BlockPos((int) Math.floor(h.x) + o[0], (int) Math.floor(h.y) + o[1], (int) Math.floor(h.z) + o[2]);
                    level.setBlock(pos, Blocks.GLASS.defaultBlockState(), 3);
                    panes.add(pos);
                }
            }).join();
            shoot(mc, muzzle, 0F);
        });
        TODO.add(Gl54Stand::blastIn);
        sleep(10);
        run(() -> {
            var server = mc.getSingleplayerServer();
            server.submit(() -> {
                ServerLevel level = server.overworld();
                for (BlockPos pos : panes) {
                    double near = nearest(lastBlast, pos);
                    float damage = Gl54Grenade.damageAt(near);
                    boolean broken = level.getBlockState(pos).isAir();
                    line("glass", "gap", near, "damage there", damage, "broken", broken);
                    ClientSmoke.check(broken == (damage >= 10F), "gl54: стекло на " + String.format(Locale.ROOT, "%.2f", near) + " бл. (урон " + damage + ") "
                            + (broken ? "разбито" : "цело") + ", ожидалось " + (damage >= 10F ? "разбито" : "цело"));
                }
            }).join();
        });
        // 4. a dud: into a husk at 1.5 blocks, then into a wall at 1.5 blocks with a husk beside it
        Husk[] pair = new Husk[2];
        run(() -> {
            clearRoom(mc);
            var server = mc.getSingleplayerServer();
            server.submit(() -> {
                ServerLevel level = server.overworld();
                for (int i = 0; i < 2; i++) {
                    Husk husk = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
                    husk.setNoAi(true);
                    husk.setPersistenceRequired();
                    husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
                    husk.setHealth(1000);
                    husk.snapTo(O.getX() + 0.5 + (i == 0 ? 0 : 2.0), O.getY(), O.getZ() + 3.0, 0F, 0F);
                    level.addFreshEntity(husk);
                    pair[i] = husk;
                }
            }).join();
            // from 1.5 blocks in front of the first one, at its belly
            shoot(mc, new Vec3(O.getX() + 0.5, O.getY() + 0.9, O.getZ() + 1.5), 0F);
        });
        TODO.add(Gl54Stand::blastIn);
        sleep(10);
        run(() -> {
            float hit = pair[0].getMaxHealth() - pair[0].getHealth(), beside = pair[1].getMaxHealth() - pair[1].getHealth();
            line("dud into a husk", "flown", lastFlown, "went off", lastWent, "damage", hit, "husk 2 blocks beside", beside);
            ClientSmoke.check(!lastWent && lastFlown < 3.0, "gl54: болванка у зомби не взорвалась (пролетела " + lastFlown + ")");
            ClientSmoke.check(Math.abs(hit - Gl54Grenade.DUD_DAMAGE) < 1.0F, "gl54: удар болванки " + hit);
            ClientSmoke.check(beside == 0F, "gl54: рядом с болванкой урона нет: " + beside);
        });
        run(() -> {
            var server = mc.getSingleplayerServer();
            server.submit(() -> {
                pair[0].setHealth(1000);
                pair[0].snapTo(O.getX() + 6.5, O.getY(), O.getZ() + R - 0.5, 0F, 0F);
                pair[1].setHealth(1000);
            }).join();
            BLASTS.clear();
            shoot(mc, new Vec3(O.getX() + 4.5, O.getY() + 0.9, O.getZ() + R - 2.0), 0F);
        });
        TODO.add(Gl54Stand::blastIn);
        sleep(10);
        run(() -> {
            float beside = pair[0].getMaxHealth() - pair[0].getHealth();
            line("dud into a wall", "flown", lastFlown, "went off", lastWent, "husk beside", beside);
            ClientSmoke.check(!lastWent && lastFlown < 3.0, "gl54: болванка у стены не взорвалась (пролетела " + lastFlown + ")");
        });
        // 4b. an armed grenade straight into a husk: the mod's contact damage and the blast together
        run(() -> {
            clearRoom(mc);
            var server = mc.getSingleplayerServer();
            server.submit(() -> {
                ServerLevel level = server.overworld();
                Husk husk = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
                husk.setNoAi(true);
                husk.setPersistenceRequired();
                husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
                husk.getAttribute(Attributes.ARMOR).setBaseValue(0);
                husk.setHealth(1000);
                husk.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 6.0, 0F, 0F);
                level.addFreshEntity(husk);
                pair[0] = husk;
            }).join();
            shoot(mc, new Vec3(O.getX() + 0.5, O.getY() + 0.9, O.getZ() - 1.0), 0F);
        });
        TODO.add(Gl54Stand::blastIn);
        sleep(10);
        run(() -> {
            float hit = pair[0].getMaxHealth() - pair[0].getHealth();
            line("direct hit", "flown", lastFlown, "went off", lastWent, "damage", hit);
            ClientSmoke.check(lastWent, "gl54: прямое попадание взвешенной гранатой: урон " + hit);
        });
        // 5. leaves in the way: the grenade flies on and goes off at the wall
        run(() -> {
            clearRoom(mc);
            var server = mc.getSingleplayerServer();
            server.submit(() -> {
                ServerLevel level = server.overworld();
                for (int y = 1; y <= 4; y++) for (int x = -2; x <= 2; x++)
                    level.setBlock(new BlockPos(O.getX() + x, O.getY() + y, O.getZ() + 2), Blocks.OAK_LEAVES.defaultBlockState(), 3);
            }).join();
            shoot(mc, muzzle, 0F);
        });
        TODO.add(Gl54Stand::blastIn);
        run(() -> {
            line("leaves", "blast at", lastBlast, "flown", lastFlown, "went off", lastWent, "wall face z", (double) (O.getZ() + R));
            ClientSmoke.check(lastWent && lastBlast.z > O.getZ() + 3.5, "gl54: сквозь листву до стены: взрыв на z=" + lastBlast.z);
        });
        // 6. the switch: gl54 = false in ntmgunfeel-server.properties is read by Gl54Server (the unit test), here the live key
        run(() -> {
            line("server key", "gl54 on", acup.gunfeel.gl54.Gl54Server.enabled());
            clearRoom(mc);
        });
    }

    private static double nearest(Vec3 p, BlockPos pos) {
        double dx = Math.max(Math.max(pos.getX() - p.x, 0), p.x - (pos.getX() + 1));
        double dy = Math.max(Math.max(pos.getY() - p.y, 0), p.y - (pos.getY() + 1));
        double dz = Math.max(Math.max(pos.getZ() - p.z, 0), p.z - (pos.getZ() + 1));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---------------------------------------------------------------- the end

    private static boolean silhouette() {
        try {
            var geo = Arms.geometry(Gl54Items.GUN.get());
            ClientSmoke.check(geo != null, "gl54: у GL-54 есть геометрия для силуэта");
            if (geo != null) {
                try (var image = Silhouette.render(geo)) {
                    image.writeToFile(DIR.resolve("silhouette.png"));
                }
                ClientSmoke.check(Silhouette.get(Gl54Items.GUN.get()) != null, "gl54: Silhouette.get(GL-54) не пуст");
            }
        } catch (Exception e) {
            ClientSmoke.check(false, "gl54: силуэт " + e);
        }
        return true;
    }

    private static boolean done() {
        if (pending != 0) return false;
        try {
            Files.write(DIR.resolve("stand.tsv"), OUT);
        } catch (java.io.IOException e) {
            ClientSmoke.check(false, "gl54: " + e);
        }
        ClientSmoke.check(true, "gl54: кадры и числа в " + DIR);
        ClientSmoke.finish();
        return true;
    }

    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        pending++;
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(DIR.resolve(name));
                OUT.add("#file\t" + name + "\t" + image.getWidth() + "x" + image.getHeight());
            } catch (java.io.IOException e) {
                GunFeel.LOGGER.warn("[gl54] кадр не записан", e);
            } finally {
                pending--;
            }
        });
    }
}
