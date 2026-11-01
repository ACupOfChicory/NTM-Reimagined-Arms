package acup.gunfeel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import com.hbm.blocks.multiblock.BlockMultiblockCore;
import com.hbm.blocks.multiblock.MultiblockSurface;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;

// runGlassStand: the NTM machines whose models have clear pixels (MachineGlass), in rows, each under its number, for the
// owner to say which clear pixels are glass and which are empty. The clear pixels under the machine's triangles are
// drawn over it, pixel by pixel: cyan where they count as glass now, red where ntmgunfeel-machine-glass.txt lists the
// texture as empty. -PglassStand=survey places every machine core MeshShapes reads (Structures.taken), finds which
// have clear pixels and writes the numbered list (notes/glass-stand/list.tsv, list.md); the stand places those.
final class GlassStand {
    private static final String MODE = System.getProperty("ntmgunfeel.glassStand", "");
    static final boolean ENABLED = !MODE.isBlank();
    private static final boolean SURVEY = MODE.equals("survey");
    private static final Path DIR = Path.of(System.getProperty("ntmgunfeel.glassStandDir", "notes/glass-stand"));
    private static final int FLOOR = 99, WIDTH = 150, GAP = 8, ROW_GAP = 12, DEPTH = 64, HEIGHT = 80;
    private static final int GLASS = 0x7020E8FF, EMPTY = 0x80FF2020;
    private static final String TAG = "glass_stand";
    // textures whose clear pixels are seen to be both (a window and a grate, a checkerboard column)
    private static final Set<String> MIXED = Set.of("hbm:textures/block/models/fusion/plasma_forge.png", "hbm:textures/block/models/reactors/icf.png",
            "hbm:textures/block/models/machines/icf.png", "hbm:textures/block/models/fusion/torus.png", "hbm:textures/block/models/machines/orbus.png",
            "hbm:textures/block/models/machines/mixer.png", "hbm:textures/block/models/machines/mining_drill.png",
            "hbm:textures/block/models/machines/fensu2.png");

    // a line of the list
    record Row(int number, String block, String texture) {
    }

    // the clear pixels of one texture under one machine's triangles
    static final class Found {
        final String texture;
        final int w, h;
        final Map<Long, double[]> at = new LinkedHashMap<>();
        final List<float[]> quads = new ArrayList<>();
        List<Region> regions = List.of();
        int number = -1;

        Found(String texture, int w, int h) {
            this.texture = texture;
            this.w = w;
            this.h = h;
        }

        boolean empty() {
            return MachineGlass.empty(texture);
        }
    }

    record Region(int x0, int y0, int x1, int y1, int pixels, double[] at) {
    }

    static final class Machine {
        final Block block;
        final String id;
        BlockPos core;
        AABB box;
        final Map<String, Found> found = new TreeMap<>();
        List<Row> rows = List.of();
        String problem;
        boolean read;

        Machine(Block block) {
            this.block = block;
            id = BuiltInRegistries.BLOCK.getKey(block).toString();
        }
    }

    private static final List<Machine> MACHINES = Collections.synchronizedList(new ArrayList<>());
    private static final List<Row> LIST = new ArrayList<>();
    private static volatile boolean built;
    private static volatile String status = "Строю стенд…";
    private static volatile String failure;
    private static int visiting = -1, waitFrames;
    private static long visitAt;
    private static boolean ready, overlay = true;
    // -PglassStandCheck: the stand looks at a few machines, keeps a picture of each and closes (GlassStand.check)
    private static final boolean CHECK = Boolean.getBoolean("ntmgunfeel.glassStandCheck");
    private static final String[] LOOK = {"hbm:machine_chemical_plant", "hbm:machine_hephaestus", "hbm:icf", "hbm:machine_exposure_chamber"};
    private static int look = -1, lookFrames;
    private static volatile boolean shooting;
    private static Vec3 start = Vec3.ZERO;

    private GlassStand() {
    }

    // the survey gives up after half an hour; the stand stays open as long as the owner looks
    static long timeout() {
        return SURVEY ? 1_800_000_000_000L : Long.MAX_VALUE;
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (!SURVEY) readList();
        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent.class, GlassStand::submit);
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, e -> hud(e.getGuiGraphics()));
        NeoForge.EVENT_BUS.addListener(InputEvent.Key.class, e -> {
            if (e.getAction() == GLFW.GLFW_PRESS && e.getKey() == GLFW.GLFW_KEY_G && Minecraft.getInstance().gui.screen() == null) overlay = !overlay;
        });
        mc.getSingleplayerServer().execute(() -> {
            try {
                build(mc.getSingleplayerServer());
            } catch (Throwable t) {
                failure = t.toString();
                GunFeel.LOGGER.error("[glassstand]", t);
            }
            built = true;
        });
    }

    // ---- the list

    private static void readList() {
        Path file = DIR.resolve("list.tsv");
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("number")) continue;
                String[] f = line.split("\t");
                LIST.add(new Row(Integer.parseInt(f[0]), f[1], f[2]));
            }
        } catch (Exception e) {
            failure = "нет списка " + file + " (сначала runGlassStand -PglassStand=survey): " + e;
        }
    }

    private static List<Block> candidates() {
        List<Block> out = new ArrayList<>();
        if (SURVEY) {
            for (Block b : BuiltInRegistries.BLOCK) {
                if (!BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("hbm")) continue;
                BlockState s = b.defaultBlockState();
                if (Structures.taken(s)) out.add(b);
            }
            out.sort(java.util.Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString()));
        } else {
            Set<String> seen = new HashSet<>();
            for (Row r : LIST) if (seen.add(r.block())) BuiltInRegistries.BLOCK.getOptional(Identifier.parse(r.block())).ifPresent(out::add);
        }
        return out;
    }

    // ---- building, on the server

    private static void build(MinecraftServer server) {
        if (failure != null) return;
        ServerLevel level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.setGameMode(GameType.CREATIVE);
        var rules = level.getGameRules();
        rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
        rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
        rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
        command(server, "time set noon");
        command(server, "weather clear");
        command(server, "kill @e[type=minecraft:text_display,tag=" + TAG + "]");
        command(server, "kill @e[type=minecraft:item]");
        List<Block> blocks = candidates();
        status = "Ставлю машины: 0 / " + blocks.size();
        int x = 0, rowZ = 0, rowMaxZ = 0;
        clearRow(level, rowZ);
        for (int i = 0; i < blocks.size(); i++) {
            Machine m = new Machine(blocks.get(i));
            status = "Ставлю машины: " + (i + 1) + " / " + blocks.size() + " · " + m.id;
            try {
                place(level, player, m, x, rowZ);
            } catch (Throwable t) {
                m.problem = "не поставлена: " + t;
                GunFeel.LOGGER.warn("[glassstand] {} {}", m.id, m.problem, t);
            }
            if (m.box != null) {
                x = (int) Math.ceil(m.box.maxX) + GAP;
                rowMaxZ = Math.max(rowMaxZ, (int) Math.ceil(m.box.maxZ));
                if (x > WIDTH) {
                    x = 0;
                    rowZ = rowMaxZ + ROW_GAP;
                    clearRow(level, rowZ);
                }
            }
            MACHINES.add(m);
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        command(server, "kill @e[type=minecraft:item]");
        start = new Vec3(-3.5, FLOOR + 1, -8.5);
        status = "Обхожу машины";
    }

    private static void command(MinecraftServer server, String cmd) {
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
        } catch (Throwable t) {
            GunFeel.LOGGER.warn("[glassstand] {}: {}", cmd, t.toString());
        }
    }

    // a row's ground: a floor at FLOOR and air above it
    private static void clearRow(ServerLevel level, int rowZ) {
        BlockState floor = Blocks.SMOOTH_STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), path = Blocks.POLISHED_ANDESITE.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockMultiblockCore.withoutTeardown(() -> {
            for (int x = -12; x <= WIDTH + 48; x++) for (int z = rowZ - (rowZ == 0 ? 14 : ROW_GAP - 2); z <= rowZ + DEPTH; z++) {
                p.set(x, FLOOR, z);
                level.setBlock(p, z < rowZ - 1 ? path : floor, 2);
                for (int y = FLOOR + 1; y <= FLOOR + HEIGHT; y++) {
                    p.set(x, y, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, air, 2);
                }
            }
        });
    }

    // the machine as a player sets it: its item used on the floor, facing the path (north); moved on when it stands
    // over the last one or the path
    private static void place(ServerLevel level, ServerPlayer player, Machine m, int x, int z) {
        for (int attempt = 0; attempt < 4; attempt++) {
            BlockPos core = put(level, player, m.block, x, z);
            if (core == null) {
                x += 4;
                continue;
            }
            AABB box = new AABB(core);
            try {
                for (BlockPos p : MultiblockSurface.rigidStructureBlocks(level, core)) box = box.minmax(new AABB(p));
            } catch (Throwable t) {
                // a single block: its own box
            }
            int dx = Math.max(0, x - (int) Math.floor(box.minX)), dz = Math.max(0, z - (int) Math.floor(box.minZ));
            if ((dx == 0 && dz == 0) || attempt == 3) {
                m.core = core;
                m.box = box;
                return;
            }
            remove(level, box);
            x += dx;
            z += dz;
        }
        throw new IllegalStateException("ни одна попытка не удалась");
    }

    private static BlockPos put(ServerLevel level, ServerPlayer player, Block block, int x, int z) {
        BlockPos floor = new BlockPos(x, FLOOR, z);
        Item item = block.asItem();
        Set<BlockPos> before = cores(level, block, floor);
        if (item != Items.AIR) {
            ItemStack stack = new ItemStack(item);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            player.teleportTo(level, x + .5, FLOOR + 1, z - 6.5, Set.of(), 0F, 20F, false);
            player.setYRot(0F);
            player.setYHeadRot(0F);
            stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(x + .5, FLOOR + 1, z + .5), Direction.UP, floor, false)));
        }
        for (BlockPos p : cores(level, block, floor)) if (!before.contains(p)) return p;
        // no item, or it would not go: the core alone
        BlockPos at = floor.above();
        BlockState s = block.defaultBlockState();
        if (s.hasProperty(BlockMultiblockCore.FACING)) s = s.setValue(BlockMultiblockCore.FACING, Direction.NORTH);
        BlockState set = s;
        BlockMultiblockCore.withoutTeardown(() -> level.setBlock(at, set, 3));
        return level.getBlockState(at).getBlock() == block ? at : null;
    }

    // block entities of this block near the floor position
    private static Set<BlockPos> cores(ServerLevel level, Block block, BlockPos floor) {
        Set<BlockPos> out = new HashSet<>();
        for (int cx = (floor.getX() - 24) >> 4; cx <= (floor.getX() + 24) >> 4; cx++)
            for (int cz = (floor.getZ() - 24) >> 4; cz <= (floor.getZ() + 24) >> 4; cz++)
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values())
                    if (be.getBlockState().getBlock() == block) out.add(be.getBlockPos().immutable());
        return out;
    }

    private static void remove(ServerLevel level, AABB box) {
        BlockMultiblockCore.withoutTeardown(() -> {
            for (BlockPos p : BlockPos.betweenClosed((int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
                    (int) Math.ceil(box.maxX) - 1, (int) Math.ceil(box.maxY) - 1, (int) Math.ceil(box.maxZ) - 1))
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        });
    }

    // ---- the tour: each machine read on the client once it is there

    static void frame() {
        if (!built) return;
        if (failure != null) {
            status = "Ошибка: " + failure;
            if (SURVEY) {
                ClientSmoke.check(false, "стенд стекла: " + failure);
                ClientSmoke.finish();
            }
            return;
        }
        if (ready) {
            if (CHECK && !SURVEY) check();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (visiting < 0 || visiting >= MACHINES.size() || MACHINES.get(visiting).read || MACHINES.get(visiting).core == null) {
            visiting++;
            while (visiting < MACHINES.size() && MACHINES.get(visiting).core == null) visiting++;
            if (visiting >= MACHINES.size()) {
                finishTour(server);
                return;
            }
            Machine m = MACHINES.get(visiting);
            double cx = (m.box.minX + m.box.maxX) / 2;
            server.execute(() -> server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), cx, FLOOR + 1, m.box.minZ - 7, Set.of(), 0F, 15F, false));
            visitAt = System.nanoTime();
            waitFrames = 0;
            status = "Обхожу машины: " + (visiting + 1) + " / " + MACHINES.size() + " · " + m.id;
            return;
        }
        Machine m = MACHINES.get(visiting);
        ClientLevel level = mc.level;
        // a machine with no block entity (its body baked into the chunk) is read from its block model
        boolean there = level != null && (level.getBlockEntity(m.core) != null
                || level.getBlockState(m.core).getBlock() == m.block && (System.nanoTime() - visitAt) / 1e9 > 3);
        double waited = (System.nanoTime() - visitAt) / 1e9;
        if (there) waitFrames++;
        if ((!there || waitFrames < 20 || waited < 1.0) && waited < 15) return;
        try {
            if (!there) m.problem = "машины нет на клиенте";
            else read(level, m);
        } catch (Throwable t) {
            m.problem = "не прочитана: " + t;
            GunFeel.LOGGER.warn("[glassstand] {}", m.id, t);
        }
        m.read = true;
    }

    private static void finishTour(MinecraftServer server) {
        ready = true;
        number();
        if (SURVEY) {
            write();
            int with = 0;
            for (Machine m : MACHINES) if (!m.found.isEmpty()) with++;
            ClientSmoke.check(with > 0, "стенд стекла: машин с прозрачными пикселями " + with + " из " + MACHINES.size() + ", строк " + LIST.size());
            ClientSmoke.finish();
            return;
        }
        server.execute(() -> {
            for (Machine m : MACHINES) labels(server, m);
            server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), start.x, start.y, start.z, Set.of(), 0F, 10F, false);
        });
        status = "Готово";
    }

    // the self-check: in front of each machine of LOOK, labels there and the overlay on screen (cyan or red pixels)
    private static void check() {
        if (shooting) return;
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (look < 0 || lookFrames > 90) {
            look++;
            lookFrames = 0;
            if (look >= LOOK.length) {
                ClientSmoke.finish();
                return;
            }
            Machine m = machine(LOOK[look]);
            if (m == null || m.box == null) {
                ClientSmoke.check(false, "стенд стекла: нет машины " + LOOK[look]);
                lookFrames = 1000;
                return;
            }
            double cx = (m.box.minX + m.box.maxX) / 2;
            double back = Math.max(6, (m.box.maxY - m.box.minY) * 1.1);
            server.execute(() -> server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), cx, FLOOR + 2, m.box.minZ - back,
                    Set.of(), 0F, 12F, false));
            return;
        }
        if (++lookFrames != 80) return;
        Machine m = machine(LOOK[look]);
        int labels = 0;
        for (var e : mc.level.entitiesForRendering())
            if (e instanceof net.minecraft.world.entity.Display.TextDisplay && e.position().distanceTo(m.box.getCenter()) < 40) labels++;
        ClientSmoke.check(labels > 0, "стенд стекла: " + m.id + ": подписей рядом " + labels);
        boolean empty = !m.found.isEmpty() && m.found.values().iterator().next().empty();
        String id = m.id.replace("hbm:", "");
        shooting = true;
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                long hits = java.util.Arrays.stream(image.getPixels()).filter(px -> {
                    int r = px >> 16 & 255, g = px >> 8 & 255, b = px & 255;
                    return empty ? r > 180 && g < 90 && b < 90 : r < 110 && g > 170 && b > 190;
                }).count();
                ClientSmoke.check(hits > 30, "стенд стекла: " + id + ": подсветка на экране, " + (empty ? "красных" : "голубых") + " пикселей " + hits);
                Files.createDirectories(DIR);
                image.writeToFile(DIR.resolve("check-" + id + ".png"));
            } catch (Exception e) {
                ClientSmoke.check(false, "стенд стекла: снимок " + id + ": " + e);
            }
            shooting = false;
        });
    }

    private static Machine machine(String id) {
        synchronized (MACHINES) {
            for (Machine m : MACHINES) if (m.id.equals(id)) return m;
        }
        return null;
    }

    // the clear pixels under the machine's triangles: of what its renderer draws and of its block model
    private static void read(ClientLevel level, Machine m) {
        BlockEntity be = level.getBlockEntity(m.core);
        List<RenderedMesh.Tri> tris = new ArrayList<>(be == null ? List.of() : RenderedMesh.of(be));
        // only the machine's own blocks: a big model of the next one reaches into its box (the torus, the ICF)
        Set<BlockPos> own = new HashSet<>();
        own.add(m.core);
        try {
            for (BlockPos p : MultiblockSurface.rigidStructureBlocks(level, m.core)) own.add(p.immutable());
        } catch (Throwable t) {
            // a single block
        }
        for (RenderedMesh.Tri t : RenderedMesh.models(level, m.box.inflate(.5), m.core, new AABB(m.core), new boolean[1],
                Collections.newSetFromMap(new java.util.IdentityHashMap<>())))
            if (t.block() != null && own.contains(t.block())) tris.add(t);
        for (RenderedMesh.Tri t : tris) {
            RenderedMesh.Tex tex = t.tex();
            if (tex instanceof RenderedMesh.Window) continue;
            if (tex instanceof RenderedMesh.Image img) {
                String name = RenderedMesh.texName(img);
                if (!name.startsWith("hbm:") || !img.clearIn(t.uv())) continue;
                pixels(m, t, t.uv(), name, img.w, img.h, (x, y) -> !img.shown(x, y));
                continue;
            }
            TextureAtlasSprite sp = RenderedMesh.spriteOf(tex);
            if (sp == null || !RenderedMesh.clearUnder(sp, t.uv())) continue;
            var c = sp.contents();
            float du = sp.getU1() - sp.getU0(), dv = sp.getV1() - sp.getV0();
            float[] local = new float[6];
            for (int i = 0; i < 3; i++) {
                local[2 * i] = (t.uv()[2 * i] - sp.getU0()) / du;
                local[2 * i + 1] = (t.uv()[2 * i + 1] - sp.getV0()) / dv;
            }
            pixels(m, t, local, c.name().getNamespace() + ":textures/" + c.name().getPath() + ".png", c.width(), c.height(),
                    (x, y) -> c.isTransparent(0, Math.floorMod(x, c.width()), Math.floorMod(y, c.height())));
        }
        for (Found f : m.found.values()) f.regions = regions(f);
        GunFeel.LOGGER.info("[glassstand] {}: {} triangles, clear pixels {}", m.id, tris.size(),
                m.found.values().stream().map(f -> f.texture + "=" + f.at.size() + (f.regions.size() > 1 ? "/" + f.regions.size() : "")).toList());
    }

    private interface Clear {
        boolean at(int x, int y);
    }

    // each clear pixel the triangle covers: a little square over it, both sides, a hair off the surface
    private static void pixels(Machine m, RenderedMesh.Tri t, float[] uv, String name, int w, int h, Clear clear) {
        Vector3d n = t.normal();
        if (n == null) return;
        double[] x = {uv[0] * (double) w, uv[2] * (double) w, uv[4] * (double) w};
        double[] y = {uv[1] * (double) h, uv[3] * (double) h, uv[5] * (double) h};
        double den = (y[1] - y[2]) * (x[0] - x[2]) + (x[2] - x[1]) * (y[0] - y[2]);
        if (Math.abs(den) < 1e-9) return;
        int x0 = (int) Math.floor(Math.min(x[0], Math.min(x[1], x[2]))), x1 = (int) Math.ceil(Math.max(x[0], Math.max(x[1], x[2]))) - 1;
        int y0 = (int) Math.floor(Math.min(y[0], Math.min(y[1], y[2]))), y1 = (int) Math.ceil(Math.max(y[0], Math.max(y[1], y[2]))) - 1;
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > 65536) return;
        Found f = m.found.computeIfAbsent(name, k -> new Found(k, w, h));
        double[] p = t.p();
        for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) {
            if (!clear.at(px, py) || !MachineGlass.overlaps(x, y, px, py)) continue;
            float[] q = new float[24];
            double[][] corner = {{px, py}, {px + 1, py}, {px + 1, py + 1}, {px, py + 1}};
            for (int side = 0; side < 2; side++) {
                double off = side == 0 ? .004 : -.004;
                for (int k = 0; k < 4; k++) {
                    int c = side == 0 ? k : 3 - k;
                    double a = ((y[1] - y[2]) * (corner[c][0] - x[2]) + (x[2] - x[1]) * (corner[c][1] - y[2])) / den;
                    double b = ((y[2] - y[0]) * (corner[c][0] - x[2]) + (x[0] - x[2]) * (corner[c][1] - y[2])) / den;
                    double g = 1 - a - b;
                    q[side * 12 + k * 3] = (float) (a * p[0] + b * p[3] + g * p[6] + n.x * off);
                    q[side * 12 + k * 3 + 1] = (float) (a * p[1] + b * p[4] + g * p[7] + n.y * off);
                    q[side * 12 + k * 3 + 2] = (float) (a * p[2] + b * p[5] + g * p[8] + n.z * off);
                }
            }
            f.quads.add(q);
            long key = ((long) Math.floorMod(px, w) << 32) | Math.floorMod(py, h);
            if (!f.at.containsKey(key)) f.at.put(key, new double[] {(q[0] + q[6]) / 2 + n.x * .25, (q[1] + q[7]) / 2 + n.y * .25, (q[2] + q[8]) / 2 + n.z * .25});
        }
    }

    // the clear pixels in groups: touching (corners too), and groups closer than 3 pixels together
    private static List<Region> regions(Found f) {
        List<long[]> px = new ArrayList<>();
        Map<Long, Integer> index = new HashMap<>();
        for (long k : f.at.keySet()) {
            index.put(k, px.size());
            px.add(new long[] {k >>> 32, k & 0xFFFFFFFFL});
        }
        int[] parent = new int[px.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < px.size(); i++) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
            Integer j = index.get(((px.get(i)[0] + dx) << 32) | (px.get(i)[1] + dy));
            if (j != null) union(parent, i, j);
        }
        Map<Integer, int[]> box = new LinkedHashMap<>();
        for (int i = 0; i < px.size(); i++) {
            int r = find(parent, i);
            int x = (int) px.get(i)[0], y = (int) px.get(i)[1];
            int[] b = box.computeIfAbsent(r, k -> new int[] {x, y, x, y});
            b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.max(b[2], x); b[3] = Math.max(b[3], y);
        }
        List<int[]> boxes = new ArrayList<>(box.values());
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < boxes.size(); i++) for (int j = i + 1; j < boxes.size(); j++) {
                int[] a = boxes.get(i), b = boxes.get(j);
                if (a[0] - 3 <= b[2] && b[0] - 3 <= a[2] && a[1] - 3 <= b[3] && b[1] - 3 <= a[3]) {
                    a[0] = Math.min(a[0], b[0]); a[1] = Math.min(a[1], b[1]); a[2] = Math.max(a[2], b[2]); a[3] = Math.max(a[3], b[3]);
                    boxes.remove(j);
                    merged = true;
                    break outer;
                }
            }
        }
        boxes.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
        List<Region> out = new ArrayList<>();
        for (int[] b : boxes) {
            int n = 0;
            double[] at = new double[3];
            for (var e : f.at.entrySet()) {
                int x = (int) (e.getKey() >>> 32), y = (int) (e.getKey() & 0xFFFFFFFFL);
                if (x < b[0] || x > b[2] || y < b[1] || y > b[3]) continue;
                n++;
                for (int k = 0; k < 3; k++) at[k] += e.getValue()[k];
            }
            for (int k = 0; k < 3; k++) at[k] /= Math.max(1, n);
            out.add(new Region(b[0], b[1], b[2], b[3], n, at));
        }
        return out;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    // ---- numbers, labels, the list

    private static void number() {
        if (SURVEY) {
            LIST.clear();
            int n = 1;
            for (Machine m : MACHINES) for (Found f : m.found.values()) {
                f.number = n;
                LIST.add(new Row(n++, m.id, f.texture));
            }
        } else {
            for (Machine m : MACHINES) {
                List<Row> rows = new ArrayList<>();
                for (Row r : LIST) if (r.block().equals(m.id)) rows.add(r);
                m.rows = rows;
                for (Row r : rows) {
                    Found f = m.found.get(r.texture());
                    if (f != null) f.number = r.number();
                }
            }
        }
    }

    private static boolean labelled(Found f) {
        return f.regions.size() >= 2 && f.regions.size() <= 12;
    }

    private static String shortName(String texture) {
        return texture.replace("hbm:textures/", "").replace("block/models/", "").replace(".png", "");
    }

    private static void labels(MinecraftServer server, Machine m) {
        if (m.box == null) return;
        StringBuilder text = new StringBuilder("[{text:\"" + m.id.replace("hbm:", "") + "\",color:\"yellow\"}");
        for (Found f : m.found.values()) {
            String no = f.number < 0 ? "?" : String.valueOf(f.number);
            text.append(",{text:\"\\n№").append(no).append("  ").append(shortName(f.texture)).append(" — ").append(f.empty() ? "ПУСТОТА" : "СТЕКЛО")
                    .append("\",color:\"").append(f.empty() ? "red" : "aqua").append("\"}");
            if (MIXED.contains(f.texture)) text.append(",{text:\"\\n  ⚠ в этой текстуре похоже и стекло, и пустота\",color:\"gold\"}");
            if (labelled(f)) text.append(",{text:\"\\n  области ").append(no).append(".1–").append(no).append(".").append(f.regions.size()).append("\",color:\"gray\"}");
        }
        for (Row r : m.rows) if (!m.found.containsKey(r.texture()))
            text.append(",{text:\"\\n№").append(r.number()).append("  ").append(shortName(r.texture())).append(" — сейчас не найдено\",color:\"gray\"}");
        if (m.problem != null) text.append(",{text:\"\\n").append(m.problem.replace("\"", "'").replace("\\", "/")).append("\",color:\"gray\"}");
        text.append("]");
        double cx = (m.box.minX + m.box.maxX) / 2;
        summon(server, cx, FLOOR + 3.2, m.box.minZ - 1.5, text.toString(), 1.0F);
        for (Found f : m.found.values()) {
            if (!labelled(f)) continue;
            for (int i = 0; i < f.regions.size(); i++) {
                double[] at = f.regions.get(i).at();
                summon(server, at[0], at[1], at[2], "{text:\"" + f.number + "." + (i + 1) + "\",color:\"" + (f.empty() ? "red" : "aqua") + "\"}", .6F);
            }
        }
    }

    private static void summon(MinecraftServer server, double x, double y, double z, String text, float scale) {
        command(server, String.format(java.util.Locale.ROOT, "summon minecraft:text_display %.3f %.3f %.3f {Tags:[\"%s\"],billboard:\"center\",see_through:1b,"
                + "line_width:400,background:%d,transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[%sf,%sf,%sf]},text:%s}",
                x, y, z, TAG, 0x90000000, scale, scale, scale, text));
    }

    private static void write() {
        try {
            Files.createDirectories(DIR);
            List<String> tsv = new ArrayList<>();
            tsv.add("number\tblock\ttexture\tnow\tpixels\tregions");
            List<String> md = new ArrayList<>();
            md.add("# Стенд стекла: машины NTM с прозрачными пикселями");
            md.add("");
            md.add("Номер — строка списка; «сейчас» — как пуля видит эти пиксели по ntmgunfeel-machine-glass.txt. "
                    + "Области (N.1, N.2 …) — группы прозрачных пикселей одной текстуры, рамка в пикселях текстуры.");
            md.add("");
            md.add("| № | машина | текстура | сейчас | px | области |");
            md.add("|---|---|---|---|---|---|");
            for (Machine m : MACHINES) for (Found f : m.found.values()) {
                StringBuilder reg = new StringBuilder();
                for (int i = 0; i < f.regions.size(); i++) {
                    Region r = f.regions.get(i);
                    if (reg.length() > 0) reg.append("; ");
                    reg.append(f.number).append('.').append(i + 1).append(' ').append(r.x0()).append(',').append(r.y0()).append('-').append(r.x1()).append(',').append(r.y1())
                            .append(" (").append(r.pixels()).append(')');
                }
                String now = f.empty() ? "пустота" : "стекло";
                tsv.add(f.number + "\t" + m.id + "\t" + f.texture + "\t" + now + "\t" + f.at.size() + "\t" + reg);
                md.add("| " + f.number + " | " + m.id.replace("hbm:", "") + " | " + shortName(f.texture) + " | " + now + (MIXED.contains(f.texture) ? " ⚠ смешанная" : "")
                        + " | " + f.at.size() + " | " + (labelled(f) ? reg : f.regions.size() + " шт.") + " |");
            }
            md.add("");
            md.add("## Без прозрачных пикселей или не прочитаны");
            md.add("");
            for (Machine m : MACHINES) if (m.found.isEmpty()) md.add("- " + m.id + (m.problem != null ? " — " + m.problem : ""));
            Files.write(DIR.resolve("list.tsv"), tsv, StandardCharsets.UTF_8);
            Files.write(DIR.resolve("list.md"), md, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ClientSmoke.check(false, "стенд стекла: список не записан: " + e);
        }
    }

    // ---- drawing

    private static void submit(SubmitCustomGeometryEvent event) {
        if (!overlay || !ready) return;
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        List<Found> near = new ArrayList<>();
        synchronized (MACHINES) {
            for (Machine m : MACHINES) {
                if (m.box == null || m.found.isEmpty() || m.box.getCenter().distanceTo(cam) > 96) continue;
                near.addAll(m.found.values());
            }
        }
        if (near.isEmpty()) return;
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.debugQuads(), (p, vc) -> {
            for (Found f : near) {
                int color = f.empty() ? EMPTY : GLASS;
                for (float[] q : f.quads) for (int k = 0; k < 8; k++)
                    vc.addVertex(p.pose(), (float) (q[k * 3] - cam.x), (float) (q[k * 3 + 1] - cam.y), (float) (q[k * 3 + 2] - cam.z)).setColor(color);
            }
        });
    }

    private static void hud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String near = "";
        double best = 12;
        synchronized (MACHINES) {
            for (Machine m : MACHINES) {
                if (m.box == null) continue;
                double d = Math.sqrt(m.box.distanceToSqr(mc.player.position()));
                if (d >= best) continue;
                best = d;
                StringBuilder s = new StringBuilder(m.id.replace("hbm:", ""));
                for (Found f : m.found.values()) s.append("  ·  №").append(f.number < 0 ? "?" : f.number).append(' ').append(shortName(f.texture)).append(f.empty() ? " — пустота" : " — стекло");
                near = s.toString();
            }
        }
        String[] lines = {"Стенд стекла NTM  ·  машин: " + MACHINES.size() + "  ·  строк списка: " + LIST.size(),
                "Голубой — стекло (пуля пробивает), красный — пустота (пролёт)  ·  G — скрыть подсветку",
                "Ответ Claude: номера и решение, например «3, 7 — пустота; 12.2 — пустота». Список: " + DIR.resolve("list.md").toString().replace(System.getProperty("user.home"), "~"),
                ready ? (near.isEmpty() ? "Подойдите к машине" : "Рядом: " + near) : status};
        g.fill(3, 3, g.guiWidth() - 3, 48, 0xB0181B25);
        for (int i = 0; i < lines.length; i++)
            g.text(mc.font, mc.font.plainSubstrByWidth(lines[i], g.guiWidth() - 16), 8, 7 + i * 10, i == 0 ? 0xFFFFCC55 : i == 3 ? 0xFF9DCFFF : 0xFFF2EFF8);
    }
}
