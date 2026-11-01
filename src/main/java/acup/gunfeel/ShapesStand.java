package acup.gunfeel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.hbm.blocks.multiblock.BlockMultiblockCore;
import com.hbm.blocks.multiblock.MultiblockSurface;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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

// runCheckClient -PshapesStand=<file>: the table of machine shapes the jar carries (ShapePack). Every NTM machine
// MeshShapes reads (Structures.taken) is set in the test world one at a time, as a player sets it; once the client has
// taken it (MeshShapes, the jar's table turned off) the shapes of its blocks are kept, the machine is taken away and
// the next one set. The table is written to <file> (it goes to src/main/resources/ntmgunfeel-machine-shapes.pack),
// with a report beside it: per machine its blocks and the longest frame its taking cost.
final class ShapesStand {
    private static final String OUT = System.getProperty("ntmgunfeel.shapesStand", "");
    static final boolean ENABLED = !OUT.isBlank();
    private static final List<String> ONLY = java.util.Arrays.stream(System.getProperty("ntmgunfeel.shapesOnly", "").split(",")).map(String::trim)
            .filter(x -> !x.isEmpty()).toList();
    private static final int FLOOR = 99, X = 16, Z = 20, CLEAR = 26, HEIGHT = 48;
    private static final double WAIT = 60;
    // each machine taken is kept at once in <file>.parts/ (a pack of its own and its report line), written on a thread
    // of its own; -PshapesResume takes up a stand cut short: the machines found there (made with this NTM) are not
    // taken again
    private static final Path PARTS = Path.of(OUT + ".parts");
    private static final boolean RESUME = Boolean.getBoolean("ntmgunfeel.shapesResume");
    private static final Set<String> DONE = new HashSet<>();
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ntm-gunfeel shapes stand writer");
        t.setDaemon(true);
        return t;
    });
    private static Map<String, long[]> lastMade = Map.of();

    private static final List<Block> BLOCKS = new ArrayList<>();
    private static final Map<String, long[]> SHAPES = new TreeMap<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static final List<String> MISSED = new ArrayList<>();
    private static int next;
    private static volatile BlockPos core;
    private static volatile String problem;
    private static volatile boolean placing;
    private static boolean asked;
    private static AABB last;
    private static long placedAt;
    private static int capturesBefore, madeBlocks;
    private static double longest;
    private static String longestOf = "";

    private ShapesStand() {
    }

    static long timeout() {
        return 3_600_000_000_000L;
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        // the jar's table off: every machine taken here
        ShapePack.off = true;
        // what is taken comes here, not into the game's table (with their faces, every machine's shapes are gigabytes)
        MeshShapes.sink = made -> {
            SHAPES.putAll(made);
            madeBlocks = made.size();
            lastMade = new TreeMap<>(made);
        };
        resume();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (!BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("hbm")) continue;
            String name = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (!ONLY.isEmpty() && ONLY.stream().noneMatch(name::contains)) continue;
            if (Structures.taken(b.defaultBlockState())) BLOCKS.add(b);
        }
        BLOCKS.sort(java.util.Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString()));
        GunFeel.LOGGER.info("[shapesstand] {} machines", BLOCKS.size());
        var server = mc.getSingleplayerServer();
        placing = true;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                var rules = level.getGameRules();
                rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
                rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
                BlockMultiblockCore.withoutTeardown(() -> {
                    BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                    for (int x = X - CLEAR; x <= X + CLEAR; x++) for (int z = Z - CLEAR; z <= Z + CLEAR; z++) for (int y = FLOOR; y <= FLOOR + HEIGHT; y++) {
                        p.set(x, y, z);
                        BlockState want = (y == FLOOR ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState();
                        if (level.getBlockState(p) != want) level.setBlock(p, want, 2);
                    }
                });
            } catch (Throwable t) {
                problem = t.toString();
            }
            placing = false;
        });
    }

    static void frame() {
        if (placing) return;
        Minecraft mc = Minecraft.getInstance();
        if (problem != null && core == null && next == 0) {
            ClientSmoke.check(false, "стенд форм: " + problem);
            ClientSmoke.finish();
            return;
        }
        if (core == null) {
            if (next > 0 || problem != null) {
                // the last one was not set
                if (problem != null) MISSED.add(BuiltInRegistries.BLOCK.getKey(BLOCKS.get(next - 1)) + " не поставлена: " + problem);
                problem = null;
            }
            while (next < BLOCKS.size() && DONE.contains(BuiltInRegistries.BLOCK.getKey(BLOCKS.get(next)).toString())) next++;
            if (next >= BLOCKS.size()) {
                finish();
                return;
            }
            GunFeel.LOGGER.info("[shapesstand] setting {}", BuiltInRegistries.BLOCK.getKey(BLOCKS.get(next)));
            place(mc, BLOCKS.get(next++));
            return;
        }
        var level = mc.level;
        double waited = (System.nanoTime() - placedAt) / 1e9;
        boolean taken = MeshShapes.capturesCompleted > capturesBefore;
        // not found by MeshShapes.tick (no block entity at its core): asked for
        if (!taken && !asked && waited > 4 && level.getBlockState(core).getBlock() == BLOCKS.get(next - 1)) {
            asked = true;
            MeshShapes.take(core, level.getBlockState(core));
        }
        if (!taken && waited < WAIT) return;
        Block block = BLOCKS.get(next - 1);
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        int blocks = 0;
        if (taken) {
            AABB box = new AABB(core);
            for (BlockPos p : MultiblockSurface.rigidStructureBlocks(level, core)) box = box.minmax(new AABB(p));
            blocks = madeBlocks;
            last = box;
            double peak = MeshShapes.captureFramePeak / 1e6;
            if (peak > longest) {
                longest = peak;
                longestOf = id;
            }
            String report = id + "\t" + blocks + "\t" + String.format(java.util.Locale.ROOT, "%.2f", peak) + "\t" + MeshShapes.describe(MeshShapes.lastSlowest) + "\t"
                    + quads(MeshShapes.lastQuads) + "\t" + (RenderedMesh.lastCost == null ? "" : RenderedMesh.lastCost);
            REPORT.add(report);
            keep(id, report);
        } else {
            MISSED.add(id + " не снята за " + (int) WAIT + " с");
            last = new AABB(core).inflate(CLEAR - 2, 0, CLEAR - 2).expandTowards(0, HEIGHT - 2, 0);
        }
        GunFeel.LOGGER.info("[shapesstand] {}/{} {}: {}", next, BLOCKS.size(), id, taken ? blocks + " blocks" : "not taken");
        core = null;
    }

    // the machine set by its item on the floor (the core alone when it has none or the item would not go), the last one
    // taken away first
    private static void place(Minecraft mc, Block block) {
        var server = mc.getSingleplayerServer();
        AABB away = last;
        last = null;
        placing = true;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                if (away != null) BlockMultiblockCore.withoutTeardown(() -> {
                    for (BlockPos p : BlockPos.betweenClosed((int) Math.floor(away.minX), Math.max(FLOOR + 1, (int) Math.floor(away.minY)),
                            (int) Math.floor(away.minZ), (int) Math.ceil(away.maxX) - 1, (int) Math.ceil(away.maxY) - 1, (int) Math.ceil(away.maxZ) - 1))
                        if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                });
                player.teleportTo(level, X + .5, FLOOR + 1, Z - 14.5, Set.of(), 0F, 15F, false);
                BlockPos floor = new BlockPos(X, FLOOR, Z);
                Set<BlockPos> before = cores(level, block, floor);
                Item item = block.asItem();
                if (item != Items.AIR) {
                    ItemStack stack = new ItemStack(item);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(new Vec3(X + .5, FLOOR + 1, Z + .5), Direction.UP, floor, false)));
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                }
                BlockPos got = null;
                for (BlockPos p : cores(level, block, floor)) if (!before.contains(p)) got = p;
                if (got == null) {
                    BlockPos at = floor.above();
                    BlockState s = block.defaultBlockState();
                    if (s.hasProperty(BlockMultiblockCore.FACING)) s = s.setValue(BlockMultiblockCore.FACING, Direction.NORTH);
                    BlockState set = s;
                    BlockMultiblockCore.withoutTeardown(() -> level.setBlock(at, set, 3));
                    if (level.getBlockState(at).getBlock() == block) got = at;
                }
                if (got == null) problem = "ни предмет, ни ядро не поставились";
                else {
                    capturesBefore = MeshShapes.capturesCompleted;
                    MeshShapes.captureFramePeak = 0;
                    RenderedMesh.lastCost = null;
                    placedAt = System.nanoTime();
                    asked = false;
                    core = got.immutable();
                }
            } catch (Throwable t) {
                problem = t.toString();
                GunFeel.LOGGER.warn("[shapesstand] {}", BuiltInRegistries.BLOCK.getKey(block), t);
            }
            placing = false;
        });
    }

    private static String quads(MeshShapes.QuadsCost q) {
        return q == null ? "" : String.format(java.util.Locale.ROOT, "faces piece %.2f ms of %d at %s", q.nanos() / 1e6, q.of(), q.at().toShortString());
    }

    private static Set<BlockPos> cores(ServerLevel level, Block block, BlockPos floor) {
        Set<BlockPos> out = new HashSet<>();
        for (int cx = (floor.getX() - 24) >> 4; cx <= (floor.getX() + 24) >> 4; cx++)
            for (int cz = (floor.getZ() - 24) >> 4; cz <= (floor.getZ() + 24) >> 4; cz++)
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values())
                    if (be.getBlockState().getBlock() == block) out.add(be.getBlockPos().immutable());
        return out;
    }

    private static String partName(String id) {
        return id.replace(':', '_');
    }

    private static void resume() {
        try {
            if (!RESUME) {
                if (Files.isDirectory(PARTS)) try (var files = Files.list(PARTS)) {
                    for (Path f : files.toList()) Files.delete(f);
                }
                Files.createDirectories(PARTS);
                return;
            }
            Files.createDirectories(PARTS);
            String ntm = acup.gunfeel.compat.Compat.hbmVersion();
            try (var files = Files.list(PARTS)) {
                for (Path f : files.filter(f -> f.toString().endsWith(".pack")).sorted().toList()) {
                    byte[] b = Files.readAllBytes(f);
                    Path line = Path.of(f.toString().replaceAll("\\.pack$", ".tsv"));
                    if (!ntm.equals(ShapePack.ntmOf(b)) || !Files.exists(line)) continue;
                    String report = Files.readString(line, StandardCharsets.UTF_8).strip();
                    SHAPES.putAll(ShapePack.read(b));
                    REPORT.add(report);
                    DONE.add(report.substring(0, report.indexOf('\t')));
                }
            }
            GunFeel.LOGGER.info("[shapesstand] taken up: {} machines already taken", DONE.size());
        } catch (Exception e) {
            GunFeel.LOGGER.warn("[shapesstand] parts in {}", PARTS, e);
        }
    }

    // the machine just taken, kept in PARTS
    private static void keep(String id, String report) {
        Map<String, long[]> made = lastMade;
        String ntm = acup.gunfeel.compat.Compat.hbmVersion();
        WRITER.execute(() -> {
            try {
                Path pack = PARTS.resolve(partName(id) + ".pack");
                Files.write(pack, ShapePack.write(made, ntm, ShapePack.XZ));
                Files.writeString(PARTS.resolve(partName(id) + ".tsv"), report + "\n", StandardCharsets.UTF_8);
            } catch (Exception e) {
                GunFeel.LOGGER.warn("[shapesstand] keeping {}", id, e);
            }
        });
    }

    private static void finish() {
        WRITER.shutdown();
        try {
            WRITER.awaitTermination(5, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException ignored) {
        }
        try {
            Path out = Path.of(OUT);
            Files.createDirectories(out.toAbsolutePath().getParent());
            byte[] pack = ShapePack.write(SHAPES, acup.gunfeel.compat.Compat.hbmVersion(), ShapePack.XZ);
            Files.write(out, pack);
            List<String> lines = new ArrayList<>();
            lines.add("# NTM " + acup.gunfeel.compat.Compat.hbmVersion() + ": " + REPORT.size() + " of " + BLOCKS.size() + " machines, " + SHAPES.size()
                    + " blocks, " + pack.length + " bytes; longest frame " + String.format(java.util.Locale.ROOT, "%.2f", longest) + " ms (" + longestOf + ")");
            lines.add("# machine\tblocks\tlongest frame, ms\tlongest piece of each stage, ms");
            lines.addAll(REPORT);
            for (String m : MISSED) lines.add("# " + m);
            Files.write(Path.of(OUT + ".tsv"), lines, StandardCharsets.UTF_8);
            ClientSmoke.check(!SHAPES.isEmpty(), "стенд форм: " + REPORT.size() + " машин из " + BLOCKS.size() + ", " + SHAPES.size() + " блоков, "
                    + pack.length + " байт, самый длинный кадр " + String.format(java.util.Locale.ROOT, "%.2f", longest) + " мс (" + longestOf + ")");
            for (String m : MISSED) GunFeel.LOGGER.warn("[shapesstand] {}", m);
        } catch (Throwable t) {
            ClientSmoke.check(false, "стенд форм: запись " + t);
        }
        ClientSmoke.finish();
    }
}
