package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PshapesFallback: the jar's table of machine shapes (ShapePack) and the taking in the game behind it.
// The chemical plant is in the table: its cells come from there at once and the client does not take it. The chemical
// factory is left out of the table here (ntmgunfeel.shapesSkip), as a machine of an addon or of another NTM version
// would be: the client takes it in the game (MeshShapes), no frame over 2 ms, and what it takes is what the table holds
// (cells alike, faces within the table's steps).
final class ShapesFallbackCheck {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.shapesFallback");
    static final String IN_TABLE = "hbm:machine_chemical_plant", LEFT_OUT = "hbm:machine_chemical_factory";
    private static final String[] MACHINES = {IN_TABLE, LEFT_OUT};
    private static final List<BlockPos> CORES = new ArrayList<>();
    private static volatile boolean placed;
    private static volatile String failure;
    private static long readyAt;
    private static int step, capturesBefore;

    private ShapesFallbackCheck() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        capturesBefore = MeshShapes.capturesCompleted;
        MeshShapes.captureFramePeak = 0;
        MeshShapes.peakFrame = "";
        mc.getSingleplayerServer().execute(() -> {
            try {
                var server = mc.getSingleplayerServer();
                var level = server.overworld();
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                com.hbm.blocks.multiblock.BlockMultiblockCore.withoutTeardown(() -> {
                    for (int x = -8; x <= 28; x++) for (int z = -8; z <= 24; z++) for (int y = 99; y <= 112; y++)
                        level.setBlock(new BlockPos(x, y, z), (y == 99 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 3);
                });
                player.teleportTo(level, 10.5, 100, -6.5, Set.of(), 0, 0, false);
                for (int i = 0; i < MACHINES.length; i++) {
                    ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(MACHINES[i])));
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    BlockPos floor = new BlockPos(2 + 14 * i, 99, 8);
                    var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(new Vec3(floor.getX() + .5, 100, floor.getZ() + .5), Direction.UP, floor, false)));
                    BlockPos core = null;
                    for (int dx = -4; dx <= 4 && core == null; dx++) for (int dz = -4; dz <= 4 && core == null; dz++)
                        for (int dy = 0; dy <= 4 && core == null; dy++) {
                            BlockPos p = floor.offset(dx, 1 + dy, dz);
                            if (level.getBlockEntity(p) != null && Structures.taken(level.getBlockState(p))) core = p.immutable();
                        }
                    if (core == null) throw new IllegalStateException(MACHINES[i] + " не поставлен: " + result);
                    CORES.add(core);
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            } catch (Throwable t) {
                failure = t.toString();
                GunFeel.LOGGER.error("[shapesfallback]", t);
            }
            placed = true;
        });
    }

    static void frame() {
        if (!placed) return;
        if (failure != null) {
            ClientSmoke.check(false, "таблица форм: " + failure);
            ClientSmoke.finish();
            return;
        }
        var level = Minecraft.getInstance().level;
        if (readyAt == 0) readyAt = System.nanoTime();
        double waited = (System.nanoTime() - readyAt) / 1e9;
        // every block of both machines has cells (the plant's from the table) and the factory is taken (till then its
        // cells may be the older ones of the config's table)
        boolean all = MeshShapes.capturesCompleted > capturesBefore;
        for (BlockPos core : CORES) all &= known(level, core) == blocks(level, core);
        if (!all && waited < 90) return;
        if (step++ < 40) return;
        ClientSmoke.check(ShapePack.ready() && ShapePack.machines() > 0, "таблица форм: в jar " + ShapePack.machines() + " машин, сняты с NTM " + ShapePack.madeWith());
        for (int i = 0; i < CORES.size(); i++) {
            BlockPos core = CORES.get(i);
            int n = blocks(level, core), k = known(level, core);
            ClientSmoke.check(n > 0 && k == n, MACHINES[i] + ": ячейки у " + k + " блоков из " + n);
        }
        String plant = Structures.key(level.getBlockState(CORES.get(0)), 0, 0, 0);
        ClientSmoke.check(ShapePack.has(plant), IN_TABLE + ": формы из таблицы jar");
        int taken = MeshShapes.capturesCompleted - capturesBefore;
        ClientSmoke.check(taken == 1, "снята в игре одна машина (" + LEFT_OUT + "), снято " + taken);
        ClientSmoke.check(MeshShapes.captureFramePeak < 2_000_000L, "снятие в игре: самый долгий кадр " + MeshShapes.captureFramePeak / 1e6 + " мс (в нём "
                + MeshShapes.peakFrame + "; самые долгие куски этапов: " + MeshShapes.describe(MeshShapes.lastSlowest) + ")");
        compare(level, CORES.get(1));
        ClientSmoke.finish();
    }

    // what the client took of the left-out machine against what the table holds for it
    private static void compare(net.minecraft.client.multiplayer.ClientLevel level, BlockPos core) {
        var coreState = level.getBlockState(core);
        int same = 0, n = 0;
        String first = null;
        for (BlockPos p : com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level, core)) {
            String key = Structures.key(coreState, p.getX() - core.getX(), p.getY() - core.getY(), p.getZ() - core.getZ());
            long[] took = PassableClip.localByKey(key), table = ShapePack.fromFile(key);
            if (took == null && table == null) continue;
            n++;
            if (took != null && table != null && java.util.Arrays.equals(ShapePack.quantized(took), table)) same++;
            else if (first == null) first = key + (took == null ? ": не снят" : table == null ? ": нет в таблице" : ": отличается, " + difference(ShapePack.quantized(took), table));
        }
        ClientSmoke.check(n > 0 && same == n, LEFT_OUT + ": снятое в игре совпадает с таблицей у " + same + " блоков из " + n + (first == null ? "" : ", " + first));
    }

    // what differs between two shapes: their cells, or which face and how
    private static String difference(long[] a, long[] b) {
        if (!java.util.Arrays.equals(java.util.Arrays.copyOf(a, 65), java.util.Arrays.copyOf(b, 65))) {
            int na = 0, nb = 0, nx = 0;
            for (int k = 0; k < 64; k++) {
                na += Long.bitCount(a[k]);
                nb += Long.bitCount(b[k]);
                nx += Long.bitCount(a[k] ^ b[k]);
            }
            return "ячейки разные: снято " + na + ", в таблице " + nb + ", отличаются " + nx + (a[64] != b[64] ? ", слово 64: " + a[64] + " и " + b[64] : "");
        }
        var fa = ModelSurface.faces(a);
        var fb = ModelSurface.faces(b);
        if (fa.size() != fb.size()) return "граней " + fa.size() + " и " + fb.size();
        for (int i = 0; i < fa.size(); i++) {
            var x = fa.get(i);
            var y = fb.get(i);
            if (!java.util.Arrays.equals(x.p(), y.p())) return "грань " + i + ": вершины " + java.util.Arrays.toString(x.p()) + " и " + java.util.Arrays.toString(y.p());
            if (!java.util.Arrays.equals(x.uv(), y.uv())) return "грань " + i + ": UV " + java.util.Arrays.toString(x.uv()) + " и " + java.util.Arrays.toString(y.uv());
            if (x.width() != y.width() || x.height() != y.height() || x.glass() != y.glass() || !java.util.Arrays.equals(x.alpha(), y.alpha()))
                return "грань " + i + ": маска " + x.width() + "x" + x.height() + (x.glass() ? " стекло" : "") + " и " + y.width() + "x" + y.height() + (y.glass() ? " стекло" : "");
        }
        return "слова после граней (" + a.length + " и " + b.length + ")";
    }

    private static int blocks(net.minecraft.client.multiplayer.ClientLevel level, BlockPos core) {
        return com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level, core).size();
    }

    private static int known(net.minecraft.client.multiplayer.ClientLevel level, BlockPos core) {
        int k = 0;
        for (BlockPos p : com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level, core))
            if (Structures.cells(level, p, level.getBlockState(p)) != null) k++;
        return k;
    }
}
