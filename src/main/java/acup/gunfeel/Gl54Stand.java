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
    private static final float[][] POSES = {{0, 0}, {135, 0}, {-135, 0}, {0, 30}, {0, -30}};

    private static final Deque<BooleanSupplier> TODO = new ArrayDeque<>();
    private static final List<String> OUT = new ArrayList<>();
    private static boolean started;
    private static int wait;
    private static volatile int pending;
    // the view turned every frame (null: the player's own)
    private static volatile float[] view;
    private static volatile Object[] blast;
    private static final List<Object[]> BLASTS = new ArrayList<>();

    private Gl54Stand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
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
                TODO.add(() -> pending == 0);
            }
        }
        // other places of the gun at the hip
        for (float y : new float[] {-2.4F, -2.2F}) {
            for (float x : new float[] {-1.5F, -1.9F}) {
                for (boolean arms : new boolean[] {true, false}) {
                    run(() -> {
                        acup.gunfeel.gl54.ItemRenderGl54.hipX = x;
                        acup.gunfeel.gl54.ItemRenderGl54.hipY = y;
                        Config.arms = arms;
                    });
                    sleep(25);
                    String name = "hip-" + x + "_" + y + (arms ? "-full" : "-gun") + ".png";
                    run(() -> shot(name));
                    TODO.add(() -> pending == 0);
                }
            }
        }
        run(() -> {
            acup.gunfeel.gl54.ItemRenderGl54.hipX = -1.5F;
            acup.gunfeel.gl54.ItemRenderGl54.hipY = -2.2F;
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
        // the sights: the gun aimed, the same pose
        run(() -> {
            ItemGunBaseNT.aimingProgress = ItemGunBaseNT.prevAimingProgress = 1F;
        });
        sleep(30);
        run(() -> shot("aimed-full.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            Config.arms = false;
        });
        sleep(10);
        run(() -> shot("aimed-gun.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            Config.arms = true;
            ItemGunBaseNT.aimingProgress = ItemGunBaseNT.prevAimingProgress = 0F;
        });
        sleep(30);

        // the shot: idle frame, then the real shot, the animation 110 ms in
        run(() -> shot("bolt-idle.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
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
                var mag = (MagazineSingleTypeBase) Gl54Items.GUN.get().getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
                mag.setType(stack, Gl54Ammo.he());
                mag.setAmount(stack, mag.getCapacity(stack));
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
                line("casing", "frame", n, "new", SEEN.add(o), "front", d.dot(look), "height", d.y, "side", d.dot(left));
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
            var bullet = new EntityBulletBaseMK4(level, p, Gl54Ammo.he(), Gl54Grenade.CENTRE_DAMAGE, 0F, from.x, from.y, from.z, dir.x, dir.y, dir.z);
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
                    float want = Gl54Grenade.damageAt(d) * 0.4F;
                    line("husk", "gap", d, "damage", got, "expected", want);
                    ClientSmoke.check(Math.abs(got - want) <= 0.1F * want + 0.5F,
                            "gl54: зомби на " + String.format(Locale.ROOT, "%.2f", d) + " бл. получил " + got + " (формула мода: 0,4 * спад NTM " + want + ")");
                }
            }).join();
        });
        // 3. glass at gaps from the blast
        run(() -> clearRoom(mc));
        double[] glassGaps = {0.5, 1.2, 2.0, 2.6};
        List<BlockPos> panes = new ArrayList<>();
        run(() -> {
            var server = mc.getSingleplayerServer();
            Vec3 h = calibrated[0];
            server.submit(() -> {
                ServerLevel level = server.overworld();
                // along X from the blast, in the row of the blast, a block's width apart: the gap is to the nearest face
                int y = (int) Math.floor(h.y);
                int z = (int) Math.floor(h.z) - 1;
                for (double g : glassGaps) {
                    int x = (int) Math.floor(h.x + g);
                    BlockPos pos = new BlockPos(x, y, z);
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
