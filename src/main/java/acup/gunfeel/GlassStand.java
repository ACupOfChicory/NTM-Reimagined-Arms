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
        // the triangles over its clear pixels, each with the pixels it covers
        final List<Cover> covers = new ArrayList<>();
        List<Region> regions = List.of();
        final Map<Long, Integer> regionOf = new HashMap<>();
        // what is drawn, by region: quads over runs of clear pixels in a row, both sides (8 corners x 3 floats each)
        float[][] mesh = new float[0][];
        // the same pixel by pixel (the first stand's drawing), only for the measurement of -PglassStandCheck
        float[][] pixelMesh = new float[0][];
        // the front quads, for what the crosshair is on: 12 floats and the region
        final List<Pick> picks = new ArrayList<>();
        boolean[] emptyRegion = new boolean[0];
        int number = -1;

        Found(String texture, int w, int h) {
            this.texture = texture;
            this.w = w;
            this.h = h;
        }

        // the decision now, region by region (MachineGlass, as the list says)
        void decide() {
            emptyRegion = new boolean[regions.size()];
            for (int i = 0; i < regions.size(); i++) emptyRegion[i] = MachineGlass.emptyAt(texture, regions.get(i).sx(), regions.get(i).sy());
        }

        // "пустота", "стекло" or both
        String now() {
            int e = 0;
            for (boolean b : emptyRegion) if (b) e++;
            return e == 0 ? "стекло" : e == emptyRegion.length ? "пустота" : "стекло и пустота";
        }

        boolean empty() {
            for (boolean b : emptyRegion) if (!b) return false;
            return emptyRegion.length > 0;
        }
    }

    // sx, sy: one of its pixels
    record Region(int x0, int y0, int x1, int y1, int pixels, double[] at, int sx, int sy) {
    }

    // a triangle over clear pixels: its corners in the world and in pixels of the texture, its normal, the pixels
    record Cover(double[] p, double[] x, double[] y, double den, Vector3d n, List<int[]> pixels) {
    }

    record Pick(float[] q, int region) {
    }

    static final class Machine {
        final Block block;
        final String id;
        BlockPos core;
        AABB box;
        final Map<String, Found> found = new TreeMap<>();
        // what is lit, a little wider than the drawn quads
        AABB lit;
        int index;
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
        if (!SURVEY) {
            readList();
            openRules();
        }
        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent.class, GlassStand::submit);
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered.class, GlassStand::click);
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, e -> hud(e.getGuiGraphics()));
        NeoForge.EVENT_BUS.addListener(InputEvent.Key.class, e -> {
            if (e.getAction() != GLFW.GLFW_PRESS || Minecraft.getInstance().gui.screen() != null) return;
            if (e.getKey() == GLFW.GLFW_KEY_G) overlay = !overlay;
            if (e.getKey() == GLFW.GLFW_KEY_Z && ready) undo();
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
            m.index = MACHINES.size();
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
        if (look >= LOOK.length) {
            checkMore();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (look < 0 || lookFrames > 90) {
            look++;
            lookFrames = 0;
            if (look >= LOOK.length) {
                lookFrames = -1000000;
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

    // then: aim at a numbered area as the player, toggle it, undo; then the overlay timed off / old / new from two places
    private static int more, moreFrames;
    private static Machine aimM;
    private static Found aimF;
    private static long lastFrame;
    private static final String[] MODES = {"выкл", "старая", "новая"};
    private static final List<String> REPORT = new ArrayList<>();
    private static double frameSum, frameMax;
    private static int frameCount;

    private static void checkMore() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        moreFrames++;
        if (more == 0) {
            // a lit quad of a numbered area: stand 3 blocks out along its normal, looking at it
            Pick pick = null;
            synchronized (MACHINES) {
                for (Machine m : MACHINES) for (Found f : m.found.values())
                    if (pick == null && labelled(f) && f.picks.size() > 0) {
                        aimM = m;
                        aimF = f;
                        pick = f.picks.get(f.picks.size() / 2);
                    }
            }
            if (pick == null) {
                ClientSmoke.check(false, "стенд стекла: нет текстуры с областями");
                more = 2;
                return;
            }
            float[] q = pick.q();
            Vec3 c = new Vec3((q[0] + q[3] + q[6] + q[9]) / 4, (q[1] + q[4] + q[7] + q[10]) / 4, (q[2] + q[5] + q[8] + q[11]) / 4);
            Vec3 n = new Vec3(q[3] - q[0], q[4] - q[1], q[5] - q[2]).cross(new Vec3(q[9] - q[0], q[10] - q[1], q[11] - q[2])).normalize();
            if (n.dot(c.subtract(aimM.box.getCenter())) < 0) n = n.scale(-1);
            Vec3 eye = c.add(n.scale(3)), d = n.scale(-1);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.asin(d.y));
            server.execute(() -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(server.overworld(), eye.x, eye.y - player.getEyeHeight(), eye.z, Set.of(), yaw, pitch, false);
            });
            more = 1;
            moreFrames = 0;
            return;
        }
        if (more == 1) {
            if (moreFrames < 60) return;
            Target t = aim();
            ClientSmoke.check(t != null && t.f() == aimF, "стенд стекла: прицел на области " + aimF.texture + ": " + (t == null ? "ничего" : t.f().texture + " обл. " + (t.region() + 1)));
            if (t != null && t.f() == aimF) try {
                List<String> before = Files.readAllLines(rules, StandardCharsets.UTF_8);
                boolean was = aimF.emptyRegion[t.region()];
                int empties = 0;
                for (boolean b : aimF.emptyRegion) if (b) empties++;
                toggle(t, false);
                int after = 0;
                for (boolean b : aimF.emptyRegion) if (b) after++;
                List<String> written = Files.readAllLines(rules, StandardCharsets.UTF_8);
                long lines = written.stream().filter(l -> aimF.texture.equals(MachineGlass.parse(l))).count();
                ClientSmoke.check(aimF.emptyRegion[t.region()] != was && Math.abs(after - empties) == 1,
                        "стенд стекла: ПКМ переключил одну область " + (t.region() + 1) + " из " + aimF.emptyRegion.length + " (пустых " + empties + " → " + after + ")");
                ClientSmoke.check(lines == after || after == aimF.emptyRegion.length && lines == 1,
                        "стенд стекла: в списке строк текстуры " + lines + " при пустых областях " + after);
                ClientSmoke.check(MachineGlass.emptyAt(aimF.texture, aimF.regions.get(t.region()).sx(), aimF.regions.get(t.region()).sy()) == aimF.emptyRegion[t.region()],
                        "стенд стекла: правила пуль видят решение");
                toggle(t, true);
                boolean all = true;
                for (boolean b : aimF.emptyRegion) all &= b == aimF.emptyRegion[0];
                ClientSmoke.check(all, "стенд стекла: Shift+ПКМ переключил всю текстуру");
                undo();
                undo();
                ClientSmoke.check(Files.readAllLines(rules, StandardCharsets.UTF_8).equals(before) && aimF.emptyRegion[t.region()] == was,
                        "стенд стекла: две отмены вернули список и подсветку");
            } catch (Exception e) {
                ClientSmoke.check(false, "стенд стекла: переключение: " + e);
            }
            more = 2;
            moreFrames = 0;
            return;
        }
        // timing: place 0 above the stand looking over it, place 1 on the floor at the start; each mode 40 + 240 frames
        int window = more - 2, place = window / MODES.length, mode = window % MODES.length;
        if (place >= 2) {
            for (String l : REPORT) GunFeel.LOGGER.info("[glassstand] замер: {}", l);
            try {
                Files.createDirectories(DIR);
                Files.write(DIR.resolve("timing-check.txt"), REPORT, StandardCharsets.UTF_8);
            } catch (Exception e) {
                GunFeel.LOGGER.warn("[glassstand] timing", e);
            }
            ClientSmoke.finish();
            more = -100;
            return;
        }
        long now = System.nanoTime();
        if (moreFrames == 1) {
            overlay = mode != 0;
            drawMode = mode == 1 ? DRAW_OLD : DRAW_NEW;
            if (mode == 0) {
                double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z0 = Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
                synchronized (MACHINES) {
                    for (Machine m : MACHINES) if (m.box != null) {
                        x0 = Math.min(x0, m.box.minX);
                        x1 = Math.max(x1, m.box.maxX);
                        z0 = Math.min(z0, m.box.minZ);
                        z1 = Math.max(z1, m.box.maxZ);
                    }
                }
                Vec3 at = place == 0 ? new Vec3((x0 + x1) / 2, FLOOR + 45, z0 - 25) : start;
                float pitch = place == 0 ? 40F : 10F;
                server.execute(() -> server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), 0F, pitch, false));
            }
        }
        int warm = mode == 0 ? 100 : 40;
        if (moreFrames == warm) {
            drawNanos = drawQuads = drawFrames = 0;
            frameSum = frameMax = 0;
            frameCount = 0;
        } else if (moreFrames > warm) {
            double ms = (now - lastFrame) / 1e6;
            frameSum += ms;
            frameMax = Math.max(frameMax, ms);
            frameCount++;
        }
        lastFrame = now;
        if (moreFrames < warm + 240) return;
        double avg = frameSum / frameCount;
        REPORT.add(String.format(java.util.Locale.ROOT, "%s, подсветка %s: кадр %.2f мс (%.0f FPS), худший %.1f мс, заполнение подсветки %.3f мс/кадр, квадов %d/кадр",
                place == 0 ? "над стендом" : "у входа", MODES[mode], avg, 1000 / avg, frameMax,
                drawFrames == 0 ? 0 : drawNanos / 1e6 / drawFrames, drawFrames == 0 ? 0 : drawQuads / drawFrames));
        more++;
        moreFrames = 0;
        if (more - 2 >= 2 * MODES.length) {
            overlay = true;
            drawMode = DRAW_NEW;
        }
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
        for (Found f : m.found.values()) {
            f.regions = regions(f);
            mesh(m, f);
        }
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
        Cover cover = new Cover(t.p(), x, y, den, n, new ArrayList<>());
        for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) {
            if (!clear.at(px, py) || !MachineGlass.overlaps(x, y, px, py)) continue;
            cover.pixels().add(new int[] {px, py});
            long key = ((long) Math.floorMod(px, w) << 32) | Math.floorMod(py, h);
            if (!f.at.containsKey(key)) {
                double[] c = world(cover, px + .5, py + .5, .25);
                f.at.put(key, c);
            }
        }
        if (!cover.pixels().isEmpty()) f.covers.add(cover);
    }

    // the point of the cover's plane at (px, py) in pixels of the texture, off it by `off` along the normal
    private static double[] world(Cover c, double px, double py, double off) {
        double[] x = c.x(), y = c.y(), p = c.p();
        double a = ((y[1] - y[2]) * (px - x[2]) + (x[2] - x[1]) * (py - y[2])) / c.den();
        double b = ((y[2] - y[0]) * (px - x[2]) + (x[0] - x[2]) * (py - y[2])) / c.den();
        double g = 1 - a - b;
        return new double[] {a * p[0] + b * p[3] + g * p[6] + c.n().x * off, a * p[1] + b * p[4] + g * p[7] + c.n().y * off,
                a * p[2] + b * p[5] + g * p[8] + c.n().z * off};
    }

    // the drawing of a found texture: each cover's clear pixels in runs along a row (one region each), a quad over a
    // run on each side of the surface; and, for the measurement, a quad a pixel
    private static void mesh(Machine m, Found f) {
        f.regionOf.clear();
        for (int i = 0; i < f.regions.size(); i++) {
            Region r = f.regions.get(i);
            for (long k : f.at.keySet()) {
                int x = (int) (k >>> 32), y = (int) (k & 0xFFFFFFFFL);
                if (x >= r.x0() && x <= r.x1() && y >= r.y0() && y <= r.y1()) f.regionOf.put(k, i);
            }
        }
        List<List<float[]>> runs = new ArrayList<>(), single = new ArrayList<>();
        for (int i = 0; i < f.regions.size(); i++) {
            runs.add(new ArrayList<>());
            single.add(new ArrayList<>());
        }
        f.picks.clear();
        // the two triangles of a face map the texture the same way: their pixels go together
        Map<String, Cover> faces = new LinkedHashMap<>();
        Map<String, Set<Long>> seen = new HashMap<>();
        for (Cover c : f.covers) {
            double[] o = world(c, 0, 0, 0), ex = world(c, 1, 0, 0), ey = world(c, 0, 1, 0);
            StringBuilder k = new StringBuilder();
            for (double[] v : new double[][] {o, ex, ey}) for (double d : v) k.append(Math.round(d * 2000)).append(',');
            String id = k.toString();
            Cover face = faces.computeIfAbsent(id, x -> new Cover(c.p(), c.x(), c.y(), c.den(), c.n(), new ArrayList<>()));
            Set<Long> has = seen.computeIfAbsent(id, x -> new HashSet<>());
            for (int[] px : c.pixels()) if (has.add(((long) px[0] << 32) | (px[1] & 0xFFFFFFFFL))) face.pixels().add(px);
        }
        for (Cover c : faces.values()) {
            // runs along each row, then runs of the same span in the rows below grow into rectangles
            Map<Long, int[]> open = new HashMap<>();
            List<int[]> rects = new ArrayList<>();
            List<int[]> px = new ArrayList<>(c.pixels());
            px.sort((p, q) -> p[1] != q[1] ? Integer.compare(p[1], q[1]) : Integer.compare(p[0], q[0]));
            int i = 0, row = Integer.MIN_VALUE;
            Map<Long, int[]> next = new HashMap<>();
            while (i <= px.size()) {
                if (i == px.size() || px.get(i)[1] != row) {
                    // the row is done: spans not continued close
                    for (var e : open.entrySet()) if (!next.containsKey(e.getKey())) rects.add(e.getValue());
                    open = next;
                    next = new HashMap<>();
                    if (i == px.size()) break;
                    if (px.get(i)[1] != row + 1) {
                        rects.addAll(open.values());
                        open = new HashMap<>();
                    }
                    row = px.get(i)[1];
                }
                int[] first = px.get(i);
                int region = f.regionOf.getOrDefault(key(f, first), 0);
                int j = i;
                while (j + 1 < px.size() && px.get(j + 1)[1] == first[1] && px.get(j + 1)[0] == px.get(j)[0] + 1
                        && f.regionOf.getOrDefault(key(f, px.get(j + 1)), 0) == region) j++;
                long span = ((long) first[0] << 40) ^ ((long) px.get(j)[0] << 16) ^ region;
                int[] r = open.get(span);
                if (r != null && r[3] == row - 1) r[3] = row;
                else r = new int[] {first[0], row, px.get(j)[0], row, region};
                next.put(span, r);
                if (CHECK) for (int k = i; k <= j; k++) single.get(region).add(quad(c, px.get(k)[0], px.get(k)[1], px.get(k)[0] + 1, px.get(k)[1] + 1));
                i = j + 1;
            }
            rects.addAll(open.values());
            for (int[] r : rects) {
                float[] q = quad(c, r[0], r[1], r[2] + 1, r[3] + 1);
                runs.get(r[4]).add(q);
                f.picks.add(new Pick(java.util.Arrays.copyOf(q, 12), r[4]));
            }
        }
        f.mesh = flat(runs);
        f.pixelMesh = flat(single);
        f.decide();
        for (float[] r : f.mesh) for (int k = 0; k + 2 < r.length; k += 3) {
            AABB pt = new AABB(r[k], r[k + 1], r[k + 2], r[k], r[k + 1], r[k + 2]);
            m.lit = m.lit == null ? pt : m.lit.minmax(pt);
        }
    }

    private static long key(Found f, int[] px) {
        return ((long) Math.floorMod(px[0], f.w) << 32) | Math.floorMod(px[1], f.h);
    }

    // the quad over pixels x0..x1, y0..y1 of the cover, on both sides of it (8 corners)
    private static float[] quad(Cover c, double x0, double y0, double x1, double y1) {
        float[] q = new float[24];
        double[][] corner = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        for (int side = 0; side < 2; side++) for (int k = 0; k < 4; k++) {
            int i = side == 0 ? k : 3 - k;
            double[] w = world(c, corner[i][0], corner[i][1], side == 0 ? .004 : -.004);
            for (int a = 0; a < 3; a++) q[side * 12 + k * 3 + a] = (float) w[a];
        }
        return q;
    }

    private static float[][] flat(List<List<float[]>> byRegion) {
        float[][] out = new float[byRegion.size()][];
        for (int r = 0; r < out.length; r++) {
            List<float[]> l = byRegion.get(r);
            out[r] = new float[l.size() * 24];
            for (int i = 0; i < l.size(); i++) System.arraycopy(l.get(i), 0, out[r], i * 24, 24);
        }
        return out;
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
            int n = 0, sx = -1, sy = -1;
            double[] at = new double[3];
            for (var e : f.at.entrySet()) {
                int x = (int) (e.getKey() >>> 32), y = (int) (e.getKey() & 0xFFFFFFFFL);
                if (x < b[0] || x > b[2] || y < b[1] || y > b[3]) continue;
                if (n == 0) {
                    sx = x;
                    sy = y;
                }
                n++;
                for (int k = 0; k < 3; k++) at[k] += e.getValue()[k];
            }
            for (int k = 0; k < 3; k++) at[k] /= Math.max(1, n);
            out.add(new Region(b[0], b[1], b[2], b[3], n, at, sx, sy));
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
        command(server, "kill @e[type=minecraft:text_display,tag=gs" + m.index + "]");
        StringBuilder text = new StringBuilder("[{text:\"" + m.id.replace("hbm:", "") + "\",color:\"yellow\"}");
        for (Found f : m.found.values()) {
            String no = f.number < 0 ? "?" : String.valueOf(f.number);
            text.append(",{text:\"\\n№").append(no).append("  ").append(shortName(f.texture)).append(" — ").append(f.now().toUpperCase(java.util.Locale.ROOT))
                    .append("\",color:\"").append(f.now().equals("пустота") ? "red" : f.now().equals("стекло") ? "aqua" : "light_purple").append("\"}");
            if (MIXED.contains(f.texture)) text.append(",{text:\"\\n  ⚠ в этой текстуре похоже и стекло, и пустота\",color:\"gold\"}");
            if (labelled(f)) text.append(",{text:\"\\n  области ").append(no).append(".1–").append(no).append(".").append(f.regions.size()).append("\",color:\"gray\"}");
        }
        for (Row r : m.rows) if (!m.found.containsKey(r.texture()))
            text.append(",{text:\"\\n№").append(r.number()).append("  ").append(shortName(r.texture())).append(" — сейчас не найдено\",color:\"gray\"}");
        if (m.problem != null) text.append(",{text:\"\\n").append(m.problem.replace("\"", "'").replace("\\", "/")).append("\",color:\"gray\"}");
        text.append("]");
        double cx = (m.box.minX + m.box.maxX) / 2;
        summon(server, m, cx, FLOOR + 3.2, m.box.minZ - 1.5, text.toString(), 1.0F);
        for (Found f : m.found.values()) {
            if (!labelled(f)) continue;
            for (int i = 0; i < f.regions.size(); i++) {
                double[] at = f.regions.get(i).at();
                summon(server, m, at[0], at[1], at[2], "{text:\"" + f.number + "." + (i + 1) + "\",color:\"" + (f.emptyRegion[i] ? "red" : "aqua") + "\"}", .6F);
            }
        }
    }

    private static void summon(MinecraftServer server, Machine m, double x, double y, double z, String text, float scale) {
        command(server, String.format(java.util.Locale.ROOT, "summon minecraft:text_display %.3f %.3f %.3f {Tags:[\"%s\",\"gs" + m.index + "\"],billboard:\"center\",see_through:1b,"
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
                String now = f.now();
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

    // ---- the owner's decisions: right click on a lit area turns it glass <-> empty, written to the list at once

    // the list the bullets read (the source of ntmgunfeel-machine-glass.txt); the self-check edits a copy
    private static Path rules;
    private static final String HEADER = "# Решения со стенда стекла (runGlassStand): владелец переключил в игре";
    private static final java.util.ArrayDeque<String[]> UNDO = new java.util.ArrayDeque<>();
    private static String message = "";

    record Target(Machine m, Found f, int region, double distance) {
    }

    private static volatile Target target;

    private static void openRules() {
        String to = System.getProperty("ntmgunfeel.glassStandRules", "");
        if (to.isBlank()) return;
        try {
            Path source = Path.of(to);
            if (CHECK) {
                Files.createDirectories(DIR);
                rules = DIR.resolve("rules-check.txt");
                Files.copy(source, rules, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else rules = source;
            MachineGlass.use(Files.readAllLines(rules, StandardCharsets.UTF_8));
        } catch (Exception e) {
            rules = null;
            message = "Список исключений не открыт: " + e;
        }
    }

    // what the crosshair is on: the nearest lit quad along the look, within 64 blocks
    private static Target aim() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ready) return null;
        Vec3 eye = mc.player.getEyePosition(1F), dir = mc.player.getViewVector(1F), end = eye.add(dir.scale(64));
        Target best = null;
        synchronized (MACHINES) {
            for (Machine m : MACHINES) {
                if (m.lit == null) continue;
                AABB lit = m.lit.inflate(.1);
                if (!lit.contains(eye) && lit.clip(eye, end).isEmpty()) continue;
                for (Found f : m.found.values()) for (Pick p : f.picks) {
                    double t = ray(eye, dir, p.q());
                    if (t > 0 && (best == null || t < best.distance())) best = new Target(m, f, p.region(), t);
                }
            }
        }
        return best;
    }

    // where the ray meets the quad (two triangles 0 1 2, 0 2 3), or -1
    private static double ray(Vec3 o, Vec3 d, float[] q) {
        double t = tri(o, d, q, 0, 3, 6);
        return t > 0 ? t : tri(o, d, q, 0, 6, 9);
    }

    private static double tri(Vec3 o, Vec3 d, float[] q, int a, int b, int c) {
        double e1x = q[b] - q[a], e1y = q[b + 1] - q[a + 1], e1z = q[b + 2] - q[a + 2];
        double e2x = q[c] - q[a], e2y = q[c + 1] - q[a + 1], e2z = q[c + 2] - q[a + 2];
        double px = d.y * e2z - d.z * e2y, py = d.z * e2x - d.x * e2z, pz = d.x * e2y - d.y * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-12) return -1;
        double inv = 1 / det, tx = o.x - q[a], ty = o.y - q[a + 1], tz = o.z - q[a + 2];
        double u = (tx * px + ty * py + tz * pz) * inv;
        if (u < 0 || u > 1) return -1;
        double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        double v = (d.x * qx + d.y * qy + d.z * qz) * inv;
        if (v < 0 || u + v > 1) return -1;
        return (e2x * qx + e2y * qy + e2z * qz) * inv;
    }

    private static void click(InputEvent.InteractionKeyMappingTriggered e) {
        if (!e.isUseItem() || !ready) return;
        Target t = aim();
        if (t == null) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        Minecraft mc = Minecraft.getInstance();
        toggle(t, mc.player != null && mc.player.isShiftKeyDown());
    }

    // the area (or, whole, the texture) the other way round; numbered areas go one by one, the rest as one
    static void toggle(Target t, boolean whole) {
        Found f = t.f();
        if (rules == null) {
            message = "Решение не записано: нет файла списка исключений";
            return;
        }
        boolean byRegion = !whole && labelled(f);
        boolean[] next = f.emptyRegion.clone();
        if (byRegion) next[t.region()] = !next[t.region()];
        else java.util.Arrays.fill(next, !f.empty());
        List<String> lines = new ArrayList<>();
        int e = 0;
        for (boolean b : next) if (b) e++;
        String no = f.number < 0 ? "?" : String.valueOf(f.number);
        if (e == next.length) lines.add(f.texture + "  # стенд стекла: пустота, №" + no);
        else if (e > 0) for (int i = 0; i < next.length; i++) if (next[i]) {
            Region r = f.regions.get(i);
            lines.add(f.texture + " " + r.x0() + "," + r.y0() + "," + r.x1() + "," + r.y1() + "  # стенд стекла: пустота, область " + no + "." + (i + 1));
        }
        String what = byRegion ? "№" + no + "." + (t.region() + 1) : "№" + no;
        String to = byRegion ? (next[t.region()] ? "пустота" : "стекло") : (e > 0 ? "пустота" : "стекло");
        try {
            String before = Files.readString(rules, StandardCharsets.UTF_8);
            write(f.texture, lines);
            UNDO.push(new String[] {f.texture, before, what});
            message = what + " " + shortName(f.texture) + " → " + to + " (записано)";
            log(what + "\t" + f.texture + "\t" + to);
        } catch (Exception ex) {
            message = "Решение не записано: " + ex;
            GunFeel.LOGGER.warn("[glassstand] write", ex);
        }
        changed(f.texture);
    }

    private static void undo() {
        String[] last = UNDO.poll();
        if (last == null) {
            message = "Отменять нечего";
            return;
        }
        try {
            save(last[1]);
            message = "Отменено: " + last[2] + " " + shortName(last[0]);
            log("отмена " + last[2] + "\t" + last[0]);
        } catch (Exception ex) {
            message = "Отмена не записана: " + ex;
        }
        changed(last[0]);
    }

    // the list with the texture's lines replaced by these (at its end, under HEADER); its old lines back
    private static List<String> write(String texture, List<String> lines) throws java.io.IOException {
        List<String> all = Files.readAllLines(rules, StandardCharsets.UTF_8), out = new ArrayList<>(), old = new ArrayList<>();
        for (String l : all) {
            if (texture.equals(MachineGlass.parse(l))) old.add(l);
            else out.add(l);
        }
        while (!lines.isEmpty() && !out.isEmpty() && out.getLast().isBlank()) out.removeLast();
        if (!lines.isEmpty() && !out.contains(HEADER)) {
            out.add("");
            out.add(HEADER);
        }
        out.addAll(lines);
        // a heading with nothing under it goes
        while (!out.isEmpty() && out.getLast().isBlank()) out.removeLast();
        if (!out.isEmpty() && out.getLast().equals(HEADER)) {
            out.removeLast();
            while (!out.isEmpty() && out.getLast().isBlank()) out.removeLast();
        }
        save(String.join("\n", out) + "\n");
        return old;
    }

    private static void save(String text) throws java.io.IOException {
        Path tmp = rules.resolveSibling(rules.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, rules, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        MachineGlass.use(List.of(text.split("\n")));
    }

    private static void log(String line) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve(CHECK ? "decisions-check.log" : "decisions.log"),
                    java.time.LocalDateTime.now().withNano(0) + "\t" + line + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            GunFeel.LOGGER.warn("[glassstand] log", e);
        }
    }

    // the decision changed: every machine with this texture shows it, labels too
    private static void changed(String texture) {
        List<Machine> touched = new ArrayList<>();
        synchronized (MACHINES) {
            for (Machine m : MACHINES) {
                Found f = m.found.get(texture);
                if (f == null) continue;
                f.decide();
                touched.add(m);
            }
        }
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) server.execute(() -> {
            for (Machine m : touched) labels(server, m);
        });
    }

    // ---- drawing: the lit machines in view and within 128 blocks, by region, quads over runs of pixels

    private static final int DRAW_NEW = 0, DRAW_OLD = 1;
    private static int drawMode = DRAW_NEW;
    // measured: nanoseconds spent filling the buffer and quads drawn, since the last reset
    private static long drawNanos, drawQuads, drawFrames;

    private static void submit(SubmitCustomGeometryEvent event) {
        if (!overlay || !ready) return;
        var cs = event.getLevelRenderState().cameraRenderState;
        Vec3 cam = cs.pos;
        net.minecraft.client.renderer.culling.Frustum frustum = cs.cullFrustum;
        boolean old = drawMode == DRAW_OLD;
        List<Machine> near = new ArrayList<>();
        synchronized (MACHINES) {
            for (Machine m : MACHINES) {
                if (m.lit == null) continue;
                if (old ? m.box.getCenter().distanceTo(cam) > 96 : m.lit.distanceToSqr(cam) > 128 * 128 || frustum != null && !frustum.isVisible(m.lit)) continue;
                near.add(m);
            }
        }
        drawFrames++;
        if (near.isEmpty()) return;
        Target aimed = target;
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.debugQuads(), (p, vc) -> {
            long t0 = System.nanoTime();
            long quads = 0;
            float cx = (float) cam.x, cy = (float) cam.y, cz = (float) cam.z;
            for (Machine m : near) for (Found f : m.found.values()) {
                float[][] mesh = old ? f.pixelMesh : f.mesh;
                for (int r = 0; r < mesh.length && r < f.emptyRegion.length; r++) {
                    boolean on = aimed != null && aimed.f() == f && (!labelled(f) || aimed.region() == r);
                    int color = f.emptyRegion[r] ? (on ? 0xC0FF7070 : EMPTY) : (on ? 0xC0A0F8FF : GLASS);
                    float[] q = mesh[r];
                    if (old) {
                        for (int k = 0; k + 2 < q.length; k += 3) vc.addVertex(p.pose(), q[k] - cx, q[k + 1] - cy, q[k + 2] - cz).setColor(color);
                        quads += q.length / 12;
                        continue;
                    }
                    // a quad is kept on both sides of the surface (corner 0 at 0 and at 21): the side toward the camera
                    for (int k = 0; k + 23 < q.length; k += 24) {
                        float nx = q[k] - q[k + 21], ny = q[k + 1] - q[k + 22], nz = q[k + 2] - q[k + 23];
                        int o = nx * (cx - q[k]) + ny * (cy - q[k + 1]) + nz * (cz - q[k + 2]) >= 0 ? k : k + 12;
                        for (int v = o; v < o + 12; v += 3) vc.addVertex(p.pose(), q[v] - cx, q[v + 1] - cy, q[v + 2] - cz).setColor(color);
                        quads++;
                    }
                }
            }
            drawNanos += System.nanoTime() - t0;
            drawQuads += quads;
        });
    }

    private static void hud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Target t = target = aim();
        String near = "";
        double best = 12;
        synchronized (MACHINES) {
            for (Machine m : MACHINES) {
                if (m.box == null) continue;
                double d = Math.sqrt(m.box.distanceToSqr(mc.player.position()));
                if (d >= best) continue;
                best = d;
                StringBuilder s = new StringBuilder(m.id.replace("hbm:", ""));
                for (Found f : m.found.values()) s.append("  ·  №").append(f.number < 0 ? "?" : f.number).append(' ').append(shortName(f.texture)).append(" — ").append(f.now());
                near = s.toString();
            }
        }
        String aimed;
        if (t == null) aimed = near.isEmpty() ? "Подойдите к машине и наведите прицел на подсветку" : "Рядом: " + near;
        else {
            Found f = t.f();
            String no = f.number < 0 ? "?" : String.valueOf(f.number);
            boolean region = labelled(f);
            boolean empty = f.emptyRegion[t.region()];
            aimed = "Прицел: " + (region ? "№" + no + "." + (t.region() + 1) : "№" + no) + "  " + shortName(f.texture) + " (" + t.m().id.replace("hbm:", "") + ") — "
                    + (region ? (empty ? "пустота" : "стекло") : f.now()) + "  ·  ПКМ — сделать " + (region ? (empty ? "стеклом" : "пустотой") : (f.empty() ? "стеклом" : "пустотой"))
                    + (region ? ", Shift+ПКМ — всю текстуру" : "");
        }
        String[] lines = {"Стенд стекла NTM  ·  машин: " + MACHINES.size() + "  ·  строк списка: " + LIST.size(),
                "Голубой — стекло (пуля пробивает), красный — пустота (пролёт)  ·  G — скрыть подсветку  ·  Z — отменить последнее",
                "ПКМ по подсветке — переключить стекло/пустота, решение сразу пишется в ntmgunfeel-machine-glass.txt",
                ready ? aimed : status,
                message};
        g.fill(3, 3, g.guiWidth() - 3, 58, 0xB0181B25);
        for (int i = 0; i < lines.length; i++)
            g.text(mc.font, mc.font.plainSubstrByWidth(lines[i], g.guiWidth() - 16), 8, 7 + i * 10,
                    i == 0 ? 0xFFFFCC55 : i == 3 ? 0xFF9DCFFF : i == 4 ? 0xFFB8F0A0 : 0xFFF2EFF8);
    }
}
