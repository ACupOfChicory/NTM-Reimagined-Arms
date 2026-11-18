package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.client.Keybinds;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.HbmAnimations;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.StairBlock;
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
//    is logged (where the bullets go, the husk behind the wall): not a check of the gun at a wall;
//  - a gun drawn in the open is not turned up; drawn at the wall it stays out of the wall's pose while NTM's draw
//    animation plays, and so does one whose reload key starts an animation (it comes back after);
//  - running into the wall the sprint pose gives way to the wall's (its weight at most 1 - the wall's); running along
//    the wall (on the left and on the right) and up a flight of stairs (running, walking, looking down) the gun is not
//    drawn back or turned up.
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
    // the frames watched: the most of the wall's weight, its turn up and the sprint pose's; while a non-shot NTM
    // animation plays, the most and the least of the weight and the least of the turn; frames the sprint pose was over
    // 1 - the wall's weight; the highest feet; nothing watched past trackMinZ (the stairs' top)
    private static boolean track;
    private static double trackMinZ, startY, maxY;
    private static float maxWall, maxPitch, maxShare, maxWallAnim, minWallAnim, minPitchAnim;
    private static int animFrames, overShare, run;
    private static String overText;
    private static final Set<String> ANIMS = new java.util.TreeSet<>();
    // up the stairs: the look down (degrees) and running or walking
    private static final float[] STAIR_PITCH = {0F, 20F, 0F, 35F};
    private static final boolean[] STAIR_SPRINT = {true, true, false, true};

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
            // a flight of stone stairs going up toward -Z, three wide, seven high, from the floor before the player
            var stair = Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
            for (int i = 0; i < 7; i++) for (int x = -1; x <= 1; x++) level.setBlock(O.offset(x, i, -2 - i), stair, 2);
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
        if (track) trackFrame(mc);
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
                    // a gun drawn in the open, from the G3 held and measured
                    look(5F, 0F, 0F);
                    hold("hbm:gun_light_revolver");
                    startTrack(Double.NEGATIVE_INFINITY);
                    wait(60, 20);
                }
                case 20 -> {
                    drawnInTheOpen("револьвер");
                    hold("hbm:gun_g3");
                    startTrack(Double.NEGATIVE_INFINITY);
                    wait(60, 21);
                }
                case 21 -> {
                    drawnInTheOpen("G3");
                    // drawn pressed against the wall
                    look(0.3F, 0F, 0F);
                    hold("hbm:gun_light_revolver");
                    startTrack(Double.NEGATIVE_INFINITY);
                    wait(80, 22);
                }
                case 22 -> {
                    track = false;
                    log("револьвер достали вплотную к стене: анимации {}, кадров анимации {}, вес на ней {}..{}, после {}", ANIMS, animFrames,
                            f(minWallAnim), f(maxWallAnim), f(WalkSprint.wallNow()));
                    check(animFrames > 0, "доставание у стены: анимация NTM шла (" + ANIMS + ")");
                    check(maxWallAnim <= 0.02F, "пока пушку достают, она не в позе у стены (вес до " + f(maxWallAnim) + ")");
                    check(WalkSprint.wallNow() >= 0.9F, "достали — пушка у стены отведена (" + f(WalkSprint.wallNow()) + ")");
                    hold(loaded("hbm:gun_g3"));
                    wait(60, 23);
                }
                case 23 -> {
                    log("G3 вплотную перед клавишей перезарядки: вес {}, вверх {}°", f(WalkSprint.wallNow()), f(WalkSprint.wallPitchNow()));
                    check(WalkSprint.wallNow() >= 0.9F, "G3 у стены перед перезарядкой отведена (" + f(WalkSprint.wallNow()) + ")");
                    startTrack(Double.NEGATIVE_INFINITY);
                    gunChanged.invoke(null, 3, true);
                    wait(1, 24);
                }
                case 24 -> {
                    gunChanged.invoke(null, 3, false);
                    wait(100, 25);
                }
                case 25 -> {
                    track = false;
                    log("клавиша перезарядки у стены: анимации {}, кадров {}, вес на ней {}..{}, вверх до {}°, после {}", ANIMS, animFrames,
                            f(minWallAnim), f(maxWallAnim), f(minPitchAnim), f(WalkSprint.wallNow()));
                    check(animFrames > 0, "клавиша перезарядки у стены: анимация NTM пошла (" + ANIMS + ")");
                    check(minWallAnim <= 0.05F && minPitchAnim <= 1F, "на время анимации пушка выходит из позы у стены (вес до " + f(minWallAnim)
                            + ", вверх до " + f(minPitchAnim) + "°)");
                    check(WalkSprint.wallNow() >= 0.9F, "анимация кончилась — снова у стены (" + f(WalkSprint.wallNow()) + ")");
                    // running into the wall
                    look(5F, 0F, 0F);
                    wait(30, 26);
                }
                case 26 -> {
                    startTrack(Double.NEGATIVE_INFINITY);
                    keys(true, true);
                    wait(40, 27);
                }
                case 27 -> {
                    keys(false, false);
                    track = false;
                    log("бег в стену: поза бега до {}, вес стены до {}, кадров бег > 1 − стена: {} ({})", f(maxShare), f(maxWall), overShare, overText);
                    check(maxShare > 0.3F && maxWall >= 0.9F, "бег в стену: была поза бега и добежал до стены (бег " + f(maxShare) + ", стена " + f(maxWall) + ")");
                    check(overShare == 0, "бег в стену: поза бега уступает стене, не складывается (" + overShare + " кадров, первый: " + overText + ")");
                    // along the wall, at 0.4 from its face: on the left (yaw 90 goes to -X), then on the right
                    place(O.getX() + 5.5, WALL_Z - 0.4, 90F, 0F);
                    wait(30, 28);
                }
                case 28, 30 -> {
                    startTrack(Double.NEGATIVE_INFINITY);
                    keys(true, true);
                    wait(25, step + 1);
                }
                case 29, 31 -> {
                    keys(false, false);
                    track = false;
                    String side = step == 29 ? "слева" : "справа";
                    log("бег вдоль стены ({}): поза бега до {}, вес стены до {}, вверх до {}°", side, f(maxShare), f(maxWall), f(maxPitch));
                    check(maxShare > 0.3F, "бег вдоль стены (" + side + "): поза бега была (" + f(maxShare) + ")");
                    check(maxWall <= 0.05F && maxPitch <= 2F, "бег вдоль стены (" + side + "): пушка не отводится (вес до " + f(maxWall) + ", вверх до "
                            + f(maxPitch) + "°)");
                    if (step == 29) {
                        place(O.getX() - 4.5, WALL_Z - 0.4, -90F, 0F);
                        wait(30, 30);
                        return;
                    }
                    run = 0;
                    place(O.getX() + 0.5, O.getZ() + 1.5, 180F, STAIR_PITCH[run]);
                    wait(30, 32);
                }
                case 32 -> {
                    startTrack(O.getZ() - 7.5);
                    startY = mc.player.getY();
                    keys(true, STAIR_SPRINT[run]);
                    wait(STAIR_SPRINT[run] ? 34 : 50, 33);
                }
                case 33 -> {
                    keys(false, false);
                    track = false;
                    String what = "лестница " + (STAIR_SPRINT[run] ? "бегом" : "шагом") + ", взгляд вниз " + STAIR_PITCH[run] + "°";
                    log("{}: поднялся на {}, вес стены до {}, вверх до {}°, поза бега до {}", what, f(maxY - startY), f(maxWall), f(maxPitch), f(maxShare));
                    check(maxY - startY >= 5.0, what + ": поднялся (" + f(maxY - startY) + ")");
                    check(maxWall <= 0.05F && maxPitch <= 2F, what + ": пушка не поднимается (вес до " + f(maxWall) + ", вверх до " + f(maxPitch) + "°)");
                    if (++run < STAIR_PITCH.length) {
                        place(O.getX() + 0.5, O.getZ() + 1.5, 180F, STAIR_PITCH[run]);
                        wait(40, 32);
                        return;
                    }
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

    private static void startTrack(double minZ) {
        track = true;
        trackMinZ = minZ;
        maxY = Double.NEGATIVE_INFINITY;
        maxWall = maxPitch = maxShare = maxWallAnim = 0F;
        minWallAnim = minPitchAnim = Float.POSITIVE_INFINITY;
        animFrames = overShare = 0;
        overText = "";
        ANIMS.clear();
    }

    private static void trackFrame(Minecraft mc) {
        if (mc.player.getZ() < trackMinZ) return;
        float w = WalkSprint.wallNow(), pt = WalkSprint.wallPitchNow(), s = WalkSprint.sprintShareNow();
        maxY = Math.max(maxY, mc.player.getY());
        maxWall = Math.max(maxWall, w);
        maxPitch = Math.max(maxPitch, pt);
        maxShare = Math.max(maxShare, s);
        if (s > (1F - WalkSprint.ease(w)) * Config.sprintPose + 0.01F && overShare++ == 0) overText = "бег " + f(s) + " при стене " + f(w);
        if (HbmAnimations.getRelevantAnim(0) != null) {
            var type = HbmAnimations.getGunAnimation(0);
            ANIMS.add(String.valueOf(type));
            if (GunAtWall.givesWay(type)) {
                animFrames++;
                maxWallAnim = Math.max(maxWallAnim, w);
                minWallAnim = Math.min(minWallAnim, w);
                minPitchAnim = Math.min(minPitchAnim, pt);
            }
        }
    }

    // drawn in the open: never turned up nor drawn back
    private static void drawnInTheOpen(String what) {
        track = false;
        log("{} достали в поле: вес до {}, вверх до {}°, анимации {}", what, f(maxWall), f(maxPitch), ANIMS);
        check(maxWall <= 0.02F && maxPitch <= 1F, what + " достали в поле: пушка не задирается (вес до " + f(maxWall) + ", вверх до " + f(maxPitch) + "°)");
    }

    private static void keys(boolean up, boolean sprint) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(up);
        mc.options.keySprint.setDown(sprint);
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

    // the feet at x, z on the floor, the camera at yaw and pitch
    private static void place(double x, double z, float yaw, float pitch) {
        GunWallStand.yaw = yaw;
        GunWallStand.pitch = pitch;
        onServer(p -> {
            p.teleportTo((ServerLevel) p.level(), x, O.getY(), z, Set.of(), yaw, pitch, false);
            p.setDeltaMovement(Vec3.ZERO);
            return null;
        });
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
