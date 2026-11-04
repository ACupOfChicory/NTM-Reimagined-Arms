package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3d;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PfenceSeam: holes where two fences meet. Fences, walls, panes and bars in pairs, corners and
// T-joints; client bullets (Tracers.handle, as from the server) across the joint from several sides. Every hole's
// polygons must lie on pixels the blocks draw (a point of a hole off them hangs in the air); then the neighbour is
// taken away, and what holes are left must still lie on drawn pixels. Lines "[fenceseam] ..." in the log.
final class FenceSeamCheck {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.fenceSeamCheck");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    // block pairs: the first at O, the second (and the rest of the layout) beside it
    private static final String[][] TYPES = {{"minecraft:oak_fence", "minecraft:oak_fence"}, {"minecraft:oak_fence", "minecraft:spruce_fence"},
            {"hbm:fence_metal", "hbm:fence_metal"}, {"minecraft:cobblestone_wall", "minecraft:cobblestone_wall"},
            {"minecraft:glass_pane", "minecraft:glass_pane"}, {"minecraft:iron_bars", "minecraft:iron_bars"}};
    private static final String[] LAYOUTS = {"pair", "corner", "tee"};
    // where the shots come from: name, direction, the plane they aim at (z = 8.5 or x = 8.5)
    private record Dir(String name, Vec3 d, boolean alongX) {
    }
    private static final Dir[] DIRS = {
            new Dir("north", new Vec3(0, 0, 1), true),
            new Dir("north+40", new Vec3(Math.sin(Math.toRadians(40)), 0, Math.cos(Math.toRadians(40))), true),
            new Dir("north-40", new Vec3(-Math.sin(Math.toRadians(40)), 0, Math.cos(Math.toRadians(40))), true),
            new Dir("north-down", new Vec3(0, -Math.sin(Math.toRadians(35)), Math.cos(Math.toRadians(35))), true),
            new Dir("south", new Vec3(0, 0, -1), true),
            new Dir("east", new Vec3(-1, 0, 0), false),
            new Dir("west", new Vec3(1, 0, 0), false)};

    private static int type, layout, dir, step, wait, shotId = -1_000_000;
    private static volatile boolean placed;
    private static volatile String failure;
    private static List<BlockPos> blocks = List.of();
    private static int totalAir, totalHoles, totalLeftAir, totalLeft;
    private static final Map<String, int[]> CASE = new LinkedHashMap<>();
    // the camera, aslant on the joint from the north-east, so both blocks' faces and the air about them show
    private static final Vec3 EYE = new Vec3(11.5, 101, 4.5);
    private static final float YAW = 32F, PITCH = 24F;
    // the screen's pixels: with the area empty, with the layout and no holes, with the holes
    private static volatile int[] shot;
    private static volatile int shotWidth;
    private static int[] sky, base;
    private static final Map<String, int[]> AIR = new LinkedHashMap<>();

    private FenceSeamCheck() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.getSingleplayerServer().execute(() -> {
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(server.overworld(), EYE.x, EYE.y, EYE.z, Set.of(), YAW, PITCH, false);
            // noon, held: the sun overhead, out of the shots, and still between them
            server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
        });
        // the screenshots show the blocks and their holes alone: no tracer lines, no particles, no hand
        Config.tracers = false;
        Config.bulletFx = false;
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        // drifting clouds would change the sky's pixels between the shots
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        step = 0;
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    private static List<BlockPos> layoutBlocks(String name) {
        List<BlockPos> out = new ArrayList<>(List.of(O, O.east()));
        if (!name.equals("pair")) out.add(O.south());
        if (name.equals("tee")) out.add(O.west());
        return out;
    }

    private static String caseName() {
        return TYPES[type][0].replace("minecraft:", "") + (TYPES[type][0].equals(TYPES[type][1]) ? "" : "+" + TYPES[type][1].replace("minecraft:", ""))
                + " " + LAYOUTS[layout];
    }

    // the layout built on the server, every block's state worked out from its neighbours
    private static void build(boolean take) {
        placed = false;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                if (take) {
                    level.setBlock(O.east(), Blocks.AIR.defaultBlockState(), 3);
                } else {
                    for (int x = 3; x <= 13; x++) for (int y = 97; y <= 104; y++) for (int z = 3; z <= 13; z++)
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    for (int i = 0; i < blocks.size(); i++)
                        level.setBlock(blocks.get(i), block(TYPES[type][i == 0 ? 0 : 1]).defaultBlockState(), 3);
                }
                for (int pass = 0; pass < 2; pass++) for (BlockPos p : layoutBlocks(LAYOUTS[layout])) {
                    BlockState st = level.getBlockState(p);
                    if (!st.isAir()) level.setBlock(p, Block.updateFromNeighbourShapes(st, level, p), 3);
                }
                placed = true;
            } catch (Throwable t) {
                failure = t.toString();
            }
        });
    }

    private static void fire(Dir d) {
        Minecraft mc = Minecraft.getInstance();
        BulletConfig round = BulletCheck.pick();
        int index = BulletConfig.configs.indexOf(round);
        Vec3 u = d.d().normalize();
        for (int i = 0; i <= 20; i++) for (int j = 0; j < 6; j++) {
            double a = 7.75 + i / 8.0, y = O.getY() + 0.125 + j * 0.25;
            Vec3 at = d.alongX() ? new Vec3(a, y, 8.5) : new Vec3(8.5, y, a);
            Vec3 from = at.subtract(u.scale(2.5));
            Vec3 v = u.scale(round.velocity);
            Tracers.handle(new ShotPayload(index, -1, from.x, from.y, from.z, (float) v.x, (float) v.y, (float) v.z, 0F, 3, 30F,
                    Double.NaN, Double.NaN, Double.NaN, shotId--), null);
        }
        if (mc.level == null) failure = "no level";
    }

    // how many holes, and of them how many have a point off the drawn pixels
    private static int[] look(String what) {
        var level = Minecraft.getInstance().level;
        AABB area = new AABB(O).inflate(3);
        List<RenderedMesh.Tri> tris = new ArrayList<>(RenderedMesh.within(level, area));
        tris.addAll(RenderedMesh.models(level, area, O, new AABB(O), new boolean[1], new HashSet<>()));
        Map<BlockPos, Boolean> whole = new java.util.HashMap<>();
        int holes = 0, air = 0, plain = 0, samples = 0, off = 0, seam = 0;
        int logged = 0;
        for (Tracers.HoleView h : Tracers.holeViews()) {
            holes++;
            if (h.polys() == null) {
                plain++;
                continue;
            }
            boolean bad = false;
            for (List<Vec3> pl : h.polys()) {
                Vec3 c = Vec3.ZERO;
                for (Vec3 p : pl) c = c.add(p);
                c = c.scale(1.0 / pl.size());
                List<Vec3> pts = new ArrayList<>();
                pts.add(c);
                for (Vec3 p : pl) pts.add(p.add(c.subtract(p).scale(0.3)));
                for (Vec3 p : pts) {
                    samples++;
                    Vector3d pt = new Vector3d(p.x, p.y, p.z);
                    double nearest = Double.POSITIVE_INFINITY;
                    boolean on = false;
                    for (RenderedMesh.Tri t : tris) {
                        Vector3d n = t.normal();
                        if (n == null) continue;
                        double dist = Math.abs(new Vector3d(pt).sub(t.corner(0)).dot(n));
                        if (dist > 0.004) continue;
                        double[] w = t.weights(pt, 1e-3);
                        if (w == null) continue;
                        nearest = Math.min(nearest, dist);
                        if (t.block() != null && whole.computeIfAbsent(t.block(), b -> Tracers.wholeFaces(level.getBlockState(b))) || t.opaqueAt(w)) {
                            on = true;
                            break;
                        }
                    }
                    if (!on) {
                        off++;
                        bad = true;
                        // on a block's boundary across the hole's normal: a face between two blocks, which neither draws
                        Vec3 n = h.normal();
                        double along = n == null ? Double.NaN : Math.abs(n.x) > 0.99 ? p.x : Math.abs(n.y) > 0.99 ? p.y : Math.abs(n.z) > 0.99 ? p.z : Double.NaN;
                        boolean between = Math.abs(along - Math.rint(along)) < 1e-3;
                        if (between) seam++;
                        if (logged++ < 6 || !between && logged < 40) GunFeel.LOGGER.info(String.format(Locale.ROOT, "[fenceseam]   %s: off the pixels at %.4f %.4f %.4f %s (hole on %s %s, normal %s, %s; in a face's plane %s)",
                                what, p.x, p.y, p.z, between ? "between blocks" : "ELSEWHERE", h.block().toShortString(), h.state(), n,
                                h.hit() == null ? "by the bullet shape (meshHole)" : "by the drawn pixels, hit " + h.hit(), Double.isInfinite(nearest) ? "no" : "yes, see-through"));
                        if (!between && logged < 40) {
                            StringBuilder sb = new StringBuilder();
                            for (Vec3 q : pl) sb.append(String.format(Locale.ROOT, " (%.4f %.4f %.4f)", q.x, q.y, q.z));
                            GunFeel.LOGGER.info("[fenceseam]     its polygon:{}", sb);
                            for (RenderedMesh.Tri t : tris) {
                                Vector3d tn = t.normal();
                                if (tn == null || Math.abs(new Vector3d(pt).sub(t.corner(0)).dot(tn)) > 0.004) continue;
                                double[] w = t.weights(pt, 1e-3);
                                if (w == null) continue;
                                float[] uv = t.uv();
                                GunFeel.LOGGER.info(String.format(Locale.ROOT, "[fenceseam]     tri on %s %s n=(%.2f %.2f %.2f) uv at it %.5f %.5f shown %s corners %s",
                                        t.block(), RenderedMesh.texName(t.tex()), tn.x, tn.y, tn.z, w[0] * uv[0] + w[1] * uv[2] + w[2] * uv[4],
                                        w[0] * uv[1] + w[1] * uv[3] + w[2] * uv[5], t.opaqueAt(w), java.util.Arrays.toString(t.p())));
                            }
                        }
                    }
                }
            }
            if (bad) air++;
        }
        GunFeel.LOGGER.info("[fenceseam] {} {}: holes {} (no mesh {}), off the pixels {} ({} points of {}, {} of them between blocks)", caseName(), what, holes, plain, air, off, samples, seam);
        return new int[] {holes, air};
    }

    private static void clear() {
        placed = false;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            var level = Minecraft.getInstance().getSingleplayerServer().overworld();
            for (int x = 3; x <= 13; x++) for (int y = 97; y <= 104; y++) for (int z = 3; z <= 13; z++)
                level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            placed = true;
        });
    }

    private static boolean shooting;

    // the frame's pixels into shot; true once they are there
    private static boolean screenshot() {
        Minecraft mc = Minecraft.getInstance();
        if (shooting) {
            if (shot == null) return false;
            shooting = false;
            return true;
        }
        shot = null;
        shooting = true;
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                shotWidth = image.getWidth();
                shot = image.getPixels();
            } catch (Exception e) {
                failure = "снимок: " + e;
            }
        });
        return false;
    }

    private static int diff(int a, int b) {
        int d = 0;
        for (int k = 0; k < 24; k += 8) d = Math.max(d, Math.abs((a >> k & 255) - (b >> k & 255)));
        return d;
    }

    private static int light(int a) {
        return (a & 255) + (a >> 8 & 255) + (a >> 16 & 255);
    }

    // air: the pixels the layout leaves as they were with the area empty. Holes darken some blocks' pixels; any
    // air pixel they darken is a hole in the air
    private static void compare(int[] after) {
        int air = 0, darkAir = 0, edgeAir = 0, changed = 0, logged = 0;
        for (int i = 0; i < after.length; i++) {
            boolean isAir = diff(sky[i], base[i]) <= 6;
            if (isAir) air++;
            // darker than before the holes and than the empty area: the sky's own change does not count
            if (light(base[i]) - light(after[i]) <= 30 || light(sky[i]) - light(after[i]) <= 30) continue;
            if (!isAir) {
                changed++;
                continue;
            }
            // next to a block's pixel: the edge of a see-through texel (a chain-link's wire), where the pixel's middle
            // falls on either side of the texel's edge for the face and for the hole drawn on it. A hole in the air is
            // a texel or more across, ten pixels and more here, so it has darkened pixels with only air round them.
            int x = i % shotWidth, y = i / shotWidth;
            boolean edge = false;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy, j = ny * shotWidth + nx;
                if (nx >= 0 && nx < shotWidth && j >= 0 && j < after.length && diff(sky[j], base[j]) > 6) edge = true;
            }
            if (edge) edgeAir++;
            else darkAir++;
            if (logged++ < 20) GunFeel.LOGGER.info("[fenceseam]   {}: an air pixel darkened at {},{}{}: {} -> {}", caseName(), x, y, edge ? " (by a block's pixel)" : "",
                    Integer.toHexString(base[i]), Integer.toHexString(after[i]));
        }
        GunFeel.LOGGER.info("[fenceseam] {} screen: air pixels {}, darkened by holes {} (and {} by a block's pixel); block pixels darkened {}", caseName(), air,
                darkAir, edgeAir, changed);
        AIR.put(caseName(), new int[] {darkAir, changed, air, edgeAir});
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) {
            ClientSmoke.check(false, "заборы: " + failure);
            ClientSmoke.finish();
            return;
        }
        // the camera held still for the screenshots
        if (mc.player != null && mc.player.position().distanceTo(EYE) < 1) {
            mc.player.setYRot(YAW);
            mc.player.setXRot(PITCH);
            mc.player.yRotO = YAW;
            mc.player.xRotO = PITCH;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        switch (step) {
            case 0 -> { // the area emptied
                if (mc.player == null || mc.player.position().distanceTo(EYE) > 1) return;
                blocks = layoutBlocks(LAYOUTS[layout]);
                Tracers.clearHoles();
                clear();
                step = 10;
            }
            case 10 -> {
                if (!placed) return;
                for (BlockPos p : blocks) if (!mc.level.getBlockState(p).isAir()) return;
                wait = 5;
                step = 11;
            }
            case 11 -> {
                if (!screenshot()) return;
                sky = shot;
                build(false);
                step = 1;
            }
            case 1 -> { // the client has it
                if (!placed) return;
                var server = mc.getSingleplayerServer().overworld();
                for (BlockPos p : blocks) if (mc.level.getBlockState(p) != server.getBlockState(p)) return;
                StringBuilder sb = new StringBuilder();
                for (BlockPos p : blocks) sb.append(' ').append(mc.level.getBlockState(p));
                GunFeel.LOGGER.info("[fenceseam] {}:{}", caseName(), sb);
                dir = 0;
                wait = 20;
                step = 2;
            }
            case 2 -> { // a side's shots
                Tracers.clearHoles();
                fire(DIRS[dir]);
                wait = 10;
                step = 3;
            }
            case 3 -> { // their holes made
                if (Tracers.pendingHoles() > 0) return;
                int[] r = look(DIRS[dir].name());
                totalHoles += r[0];
                totalAir += r[1];
                CASE.computeIfAbsent(caseName(), k -> new int[4])[0] += r[0];
                CASE.get(caseName())[1] += r[1];
                if (++dir < DIRS.length) {
                    step = 2;
                    return;
                }
                // all the sides' holes again at once, the screen without and with them, then the neighbour taken away
                Tracers.clearHoles();
                wait = 5;
                step = 7;
            }
            case 7 -> {
                if (!screenshot()) return;
                base = shot;
                for (Dir d : DIRS) fire(d);
                wait = 10;
                step = 4;
            }
            case 4 -> {
                if (Tracers.pendingHoles() > 0) return;
                wait = 5;
                step = 8;
            }
            case 8 -> {
                if (!screenshot()) return;
                compare(shot);
                build(true);
                step = 5;
            }
            case 5 -> {
                if (!placed || !mc.level.getBlockState(O.east()).isAir()) return;
                // the holes' check runs four times a second
                wait = 40;
                step = 6;
            }
            case 6 -> {
                GunFeel.LOGGER.info("[fenceseam] {}: neighbour taken, now {}", caseName(), mc.level.getBlockState(O));
                int[] r = look("neighbour taken");
                totalLeft += r[0];
                totalLeftAir += r[1];
                CASE.get(caseName())[2] += r[0];
                CASE.get(caseName())[3] += r[1];
                if (++layout >= LAYOUTS.length) {
                    layout = 0;
                    type++;
                }
                if (type < TYPES.length) {
                    step = 0;
                    return;
                }
                for (var e : CASE.entrySet()) {
                    int[] c = e.getValue();
                    GunFeel.LOGGER.info("[fenceseam] summary {}: holes {}, off the pixels {}; after the neighbour taken left {}, off {}", e.getKey(), c[0], c[1], c[2], c[3]);
                    ClientSmoke.check(c[1] == 0 && c[3] == 0, "заборы " + e.getKey() + ": дырок " + c[0] + ", вне пикселей " + c[1]
                            + "; без соседа осталось " + c[2] + ", вне пикселей " + c[3]);
                }
                int drawn = 0;
                for (var e : AIR.entrySet()) {
                    int[] a = e.getValue();
                    drawn += a[1];
                    // glass is seen through: its holes darken what lies behind it, as they should
                    if (e.getKey().startsWith("glass_pane")) continue;
                    ClientSmoke.check(a[0] == 0, "заборы " + e.getKey() + " на экране: пикселей воздуха, затемнённых дырками, " + a[0]
                            + " (и у кромки блока " + a[3] + "; воздуха " + a[2] + ", дырки изменили пикселей блоков " + a[1] + ")");
                }
                ClientSmoke.check(drawn > 500, "заборы: дырки видны на снимках, пикселей " + drawn);
                ClientSmoke.check(totalHoles > 1000, "заборы: дырок всего " + totalHoles);
                Tracers.clearHoles();
                ClientSmoke.finish();
            }
            default -> {
            }
        }
    }
}
