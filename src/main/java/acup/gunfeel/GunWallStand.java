package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.client.Keybinds;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

// runCheckClient -PgunWallStand: the gun at a wall (GunAtWall) in the game. A survival player on a stone floor faces a
// stone wall one block thick (+Z); a still husk (a zombie the sun does not burn) stands behind it.
//  - every NTM gun: its third person scale read (the ones without are named);
//  - three guns (the revolver, the G3, the Stinger): the muzzle taken from the model, in blocks forward of the eyes by
//    the gun's third person scale; at 2, 1, 0.6 and 0.3 blocks from the wall the gun goes in by how close it is:
//    nothing where the muzzle does not reach, all of it pressed against the wall, never less nearer; there the muzzle
//    drawn is back and up against the rest's, and before the wall unless turned up all the way;
//  - a turned camera (yaw ±135°, pitch ±30°) in the open: the muzzle at rest holds the same place in view space, and it
//    lies in the world as far forward along the look as its depth says;
//  - the G3 pressed against the wall: a fire key held lowers the muzzle (the gun stays back); the shot
//    is logged (where the bullets go, the husk behind the wall): not a check of the gun at a wall.
// Lines "[gun-wall] ..." in the log, PASS/FAIL in the report.
final class GunWallStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.gunWallStand");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    // the wall's near face at this z, the player at x = O.x + 0.5
    private static final int WALL_Z = O.getZ() + 6;
    private static final float[] DIST = {2.0F, 1.0F, 0.6F, 0.3F};
    private static final String[] GUNS = {"hbm:gun_light_revolver", "hbm:gun_g3", "hbm:gun_stinger"};
    private static int step, until, gun, dist;
    private static float yaw, pitch;
    private static final float[][] WALL = new float[GUNS.length][DIST.length];
    private static final float[] DEPTH = new float[GUNS.length];
    private static Vector3f rest, turnedRest;
    private static final List<Entity> BULLETS = new ArrayList<>();
    private static volatile double bulletMaxZ = Double.NEGATIVE_INFINITY;
    private static volatile int bullets;
    private static Zombie zombie;
    private static float zombieHealth;
    private static java.lang.reflect.Method gunChanged;

    private GunWallStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        try {
            gunChanged = Keybinds.class.getDeclaredMethod("gunChanged", int.class, boolean.class);
            gunChanged.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            check(false, "подготовка: " + e);
            ClientSmoke.finish();
            step = 999;
            return;
        }
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            if (!e.getLevel().isClientSide() && e.getEntity() instanceof EntityBulletBaseMK4 b) {
                synchronized (BULLETS) {
                    BULLETS.add(b);
                    bullets++;
                    bulletMaxZ = Math.max(bulletMaxZ, b.getZ());
                }
                log("пуля {} появилась в {} {} {}, скорость {}", b.getId(), f(b.getX()), f(b.getY()), f(b.getZ()), b.getDeltaMovement());
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            synchronized (BULLETS) {
                for (Entity b : BULLETS) {
                    bulletMaxZ = Math.max(bulletMaxZ, b.getZ());
                    if (b.isRemoved()) log("пуля {} снята в {} {} {}", b.getId(), f(b.getX()), f(b.getY()), f(b.getZ()));
                }
                BULLETS.removeIf(Entity::isRemoved);
            }
        });
        onServer(p -> {
            ServerLevel level = (ServerLevel) p.level();
            var server = level.getServer();
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
            for (int x = -8; x <= 8; x++) for (int z = -8; z <= 12; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y <= 8; y++) {
                    boolean wall = O.getZ() + z == WALL_Z && Math.abs(x) <= 6 && y <= 5;
                    level.setBlock(O.offset(x, y, z), wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
            p.setGameMode(GameType.SURVIVAL);
            p.getAbilities().flying = false;
            zombie = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
            zombie.snapTo(O.getX() + 0.5, O.getY(), WALL_Z + 2.5, 180F, 0F);
            zombie.setNoAi(true);
            zombie.setPersistenceRequired();
            level.addFreshEntity(zombie);
            place(p, 3F, 0F, 0F);
            return null;
        });
        step = 0;
        gun = 0;
        until = now() + 40;
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.setXRot(pitch);
        mc.player.setYRot(yaw);
        if (step == 999 || now() < until) return;
        try {
            switch (step) {
                // a gun in hand, a step back from the wall
                case 0 -> {
                    if (gun == 0) noScale();
                    hold(GUNS[gun]);
                    look(3F, 0F, 0F);
                    dist = 0;
                    wait(50, 1);
                }
                case 1 -> {
                    Vector3f m = GunAtWall.muzzle(mc.player.getMainHandItem().getItem());
                    check(m != null, GUNS[gun] + ": дуло снято с модели");
                    DEPTH[gun] = m == null ? Float.NaN : -m.z();
                    var held = mc.player.getMainHandItem().getItem();
                    log("{}: дуло в покое {} (вперёд {} бл), казённик {}, масштаб 3-го лица {}, блоков на единицу рук {}", GUNS[gun], m, f(DEPTH[gun]),
                            GunAtWall.backEnd(held), f(GunAtWall.thirdPersonScale(held)), f(GunAtWall.blocksPerUnitNow()));
                    Vector3f grip = new Vector3f();
                    var camera = mc.gameRenderer.mainCamera();
                    if (Arms.rightGripRendered(mc.player.getMainHandItem().getItem(), grip))
                        log("{}: хват правой руки {} (в виде {}), FOV мира {}, рук {}", GUNS[gun], grip, GunAtWall.toView(
                                new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()), grip,
                                GunAtWall.fovScale(camera.getFov(), Tracers.hudFov(camera))), f(camera.getFov()), f(Tracers.hudFov(camera)));
                    check(DEPTH[gun] > GunAtWall.NEAR && DEPTH[gun] < 2.5F, GUNS[gun] + ": дуло вперёд от глаз в блоках (" + f(DEPTH[gun]) + ")");
                    check(WalkSprint.wallNow() == 0F, GUNS[gun] + ": в 3 бл от стены пушка на месте (" + f(WalkSprint.wallNow()) + ")");
                    look(DIST[dist], 0F, 0F);
                    wait(15, 2);
                }
                case 2 -> {
                    float w = WalkSprint.wallNow();
                    WALL[gun][dist] = w;
                    Vector3f d = GunAtWall.drawnMuzzle(), r = GunAtWall.muzzle(mc.player.getMainHandItem().getItem());
                    log("{} в {} бл от стены: вес {}, цель {}, дуло нарисовано {} (вперёд {}), в покое {}", GUNS[gun], DIST[dist], f(w),
                            f(GunAtWall.into(mc.player, mc.player.getMainHandItem().getItem())), d, f(-d.z()), r);
                    if (DIST[dist] == 0.3F && r != null) {
                        check(w >= 0.9F, GUNS[gun] + ": вплотную к стене пушка отведена (" + f(w) + ")");
                        check(-d.z() < -r.z() - 0.02F && d.y() > r.y() + 0.02F, GUNS[gun] + ": дуло нарисовано ближе и выше покоя (вперёд "
                                + f(-d.z()) + " < " + f(-r.z()) + ", вверх " + f(d.y()) + " > " + f(r.y()) + ")");
                        float pitch = WalkSprint.wallPitchNow();
                        log("{}: дуло за плоскостью стены на {} бл (до стены {}), дуло вверх на {}°, предел {}", GUNS[gun], f(-d.z() - DIST[dist]), DIST[dist],
                                f(pitch), f(GunAtWall.limitNow()));
                        check(-d.z() <= DIST[dist] + 0.1F || pitch >= GunAtWall.MAX_PITCH - 0.5F, GUNS[gun] + ": нарисованное дуло не уходит в стену (вперёд "
                                + f(-d.z()) + ", до стены " + DIST[dist] + ", вверх " + f(pitch) + "°)");
                    }
                    if (DIST[dist] >= 2.0F && DEPTH[gun] < 1.9F) check(w == 0F, GUNS[gun] + ": в 2 бл дуло не достаёт, пушка на месте (" + f(w) + ")");
                    if (++dist < DIST.length) {
                        look(DIST[dist], 0F, 0F);
                        wait(15, 2);
                        return;
                    }
                    boolean grows = true;
                    for (int i = 1; i < DIST.length; i++) grows &= WALL[gun][i] >= WALL[gun][i - 1] - 1e-4F;
                    check(grows, GUNS[gun] + ": ближе к стене — не меньше (" + java.util.Arrays.toString(WALL[gun]) + ")");
                    if (++gun < GUNS.length) {
                        step = 0;
                        return;
                    }
                    // not a check: the reach is each gun's own third person size, not its kind's
                    log("дуло вперёд: револьвер {}, G3 {}, Стингер {}", f(DEPTH[0]), f(DEPTH[1]), f(DEPTH[2]));
                    // the turned camera, the G3 in the open
                    hold("hbm:gun_g3");
                    look(5F, 0F, 0F);
                    wait(50, 3);
                }
                case 3 -> {
                    rest = GunAtWall.muzzle(mc.player.getMainHandItem().getItem());
                    look(5F, 135F, 30F);
                    wait(20, 4);
                }
                case 4 -> {
                    turned("yaw 135, pitch 30");
                    look(5F, -135F, -30F);
                    wait(20, 5);
                }
                case 5 -> {
                    turned("yaw -135, pitch -30");
                    // the G3 two blocks from the wall, a control shot; then pressed against it
                    hold(loaded("hbm:gun_g3"));
                    look(2.0F, 0F, 0F);
                    wait(50, 10);
                }
                case 6 -> {
                    zombieHealth = onServer(p -> zombie.getHealth());
                    synchronized (BULLETS) {
                        bullets = 0;
                        bulletMaxZ = Double.NEGATIVE_INFINITY;
                    }
                    log("G3 вплотную перед выстрелом: вес {}, опущено {}", f(WalkSprint.wallNow()), f(WalkSprint.wallLowNow()));
                    gunChanged.invoke(null, 0, true);
                    wait(4, 7);
                }
                case 7 -> {
                    float low = WalkSprint.wallLowNow();
                    log("G3 с зажатым огнём: вес {}, опущено {}", f(WalkSprint.wallNow()), f(low));
                    check(low >= 0.9F && WalkSprint.wallNow() >= 0.9F, "огонь зажат: дуло опущено, пушка отведена (опущено " + f(low)
                            + ", вес " + f(WalkSprint.wallNow()) + ")");
                    gunChanged.invoke(null, 0, false);
                    wait(30, 8);
                }
                case 8 -> {
                    float h = onServer(p -> zombie.getHealth());
                    int n;
                    double maxZ;
                    synchronized (BULLETS) {
                        n = bullets;
                        maxZ = bulletMaxZ;
                    }
                    log("выстрел в упор: пуль {}, дальше всех по z {} (стена {}..{}), зомби {} → {}", n, f(maxZ), WALL_Z, WALL_Z + 1, f(zombieHealth), f(h));
                    check(n > 0, "выстрел в упор: пуля вылетела (" + n + ")");
                    // not a check: the bullets are the coordinator's (they pass the wall from 2 blocks on this stand too)
                    log("выстрел в упор: дальше всех по z {} (стена до {}), зомби {} → {}", f(maxZ), WALL_Z + 1, f(zombieHealth), f(h));
                    ClientSmoke.finish();
                    step = 999;
                }
                case 10 -> {
                    zombieHealth = onServer(p -> zombie.getHealth());
                    synchronized (BULLETS) {
                        bullets = 0;
                        bulletMaxZ = Double.NEGATIVE_INFINITY;
                    }
                    log("контроль: выстрел с 2 бл");
                    gunChanged.invoke(null, 0, true);
                    wait(1, 11);
                }
                case 11 -> {
                    gunChanged.invoke(null, 0, false);
                    wait(30, 12);
                }
                case 12 -> {
                    float h = onServer(p -> zombie.getHealth());
                    log("контроль с 2 бл: пуль {}, дальше всех по z {} (стена {}..{}), зомби {} → {}", bullets, f(bulletMaxZ), WALL_Z, WALL_Z + 1, f(zombieHealth), f(h));
                    look(0.3F, 0F, 0F);
                    wait(30, 6);
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            GunFeel.LOGGER.error("[gun-wall] сбой", t);
            check(false, "без сбоя: " + t);
            ClientSmoke.finish();
            step = 999;
        }
    }

    // the guns with no third person scale of NTM's: the gun at a wall leaves them as they are
    private static void noScale() {
        List<String> none = new ArrayList<>();
        int all = 0;
        for (var item : BuiltInRegistries.ITEM) {
            if (!(item instanceof ItemGunBaseNT)) continue;
            all++;
            if (!(GunAtWall.thirdPersonScale(item) > 0F)) none.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        log("пушек NTM {}, без масштаба 3-го лица {}: {}", all, none.size(), none);
        check(all > 0 && none.size() < all, "масштаб 3-го лица есть у " + (all - none.size()) + " из " + all + " пушек");
    }

    // at a turned camera the muzzle at rest keeps its place in view space, and lies in the world where that says
    private static void turned(String what) {
        Minecraft mc = Minecraft.getInstance();
        turnedRest = GunAtWall.muzzle(mc.player.getMainHandItem().getItem());
        var camera = mc.gameRenderer.mainCamera();
        Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        Vec3 w = turnedRest == null ? null : GunAtWall.inWorld(turnedRest);
        double ahead = w == null ? Double.NaN : w.subtract(camera.position()).dot(look);
        log("{}: дуло в покое {} (прямо {}), вперёд по взгляду в мире {}, взгляд {} {}", what, turnedRest, rest, f(ahead), f(camera.yRot()), f(camera.xRot()));
        check(rest != null && turnedRest != null && rest.distance(turnedRest) < 0.03F,
                what + ": дуло на том же месте в виде (" + (rest == null || turnedRest == null ? "нет" : f(rest.distance(turnedRest))) + " бл)");
        check(turnedRest != null && Math.abs(ahead - -turnedRest.z()) < 0.02, what + ": дуло в мире на своей глубине (" + f(ahead) + ")");
        check(WalkSprint.wallNow() == 0F, what + ": на открытом месте пушка на месте (" + f(WalkSprint.wallNow()) + ")");
    }

    // the eyes d blocks before the wall's face, looking at it (yaw 0 is +Z), or turned
    private static void look(float d, float yaw, float pitch) {
        GunWallStand.yaw = yaw;
        GunWallStand.pitch = pitch;
        onServer(p -> {
            place(p, d, yaw, pitch);
            return null;
        });
    }

    private static void place(ServerPlayer p, float d, float yaw, float pitch) {
        p.teleportTo((ServerLevel) p.level(), O.getX() + 0.5, O.getY(), WALL_Z - d, Set.of(), yaw, pitch, false);
        p.setDeltaMovement(Vec3.ZERO);
    }

    private static void hold(String id) {
        hold(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))));
    }

    private static void hold(ItemStack stack) {
        onServer(p -> {
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            return null;
        });
    }

    // the gun drawn and idle, its magazine full of its first ammo
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ItemStack loaded(String id) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        var magazine = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
        if (magazine instanceof com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase m) {
            magazine.setType(stack, m.acceptedBullets.get(0));
            magazine.setAmount(stack, m.capacity);
        }
        ItemGunBaseNT.setIsDrawn(stack, true);
        ItemGunBaseNT.setIsEquipped(stack, true);
        ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
        ItemGunBaseNT.setTimer(stack, 0, 0);
        return stack;
    }

    private static void wait(int ticks, int next) {
        until = now() + ticks;
        step = next;
    }

    private static int now() {
        return Minecraft.getInstance().getSingleplayerServer().getTickCount();
    }

    private static <T> T onServer(java.util.function.Function<ServerPlayer, T> f) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        Object[] out = new Object[1];
        server.submit(() -> out[0] = f.apply(server.getPlayerList().getPlayers().getFirst())).join();
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static void check(boolean ok, String text) {
        GunFeel.LOGGER.info("[gun-wall] {} {}", ok ? "PASS" : "FAIL", text);
        ClientSmoke.check(ok, "пушка у стены: " + text);
    }

    private static void log(String fmt, Object... args) {
        GunFeel.LOGGER.info("[gun-wall] " + fmt, args);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
