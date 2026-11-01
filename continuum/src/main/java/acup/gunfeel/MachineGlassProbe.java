package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

// port/continuum: a dev check that machine windows are read on Continuum's renderers (-Pntmgunfeel.machineGlassProbe=<report>
// with -Pcontinuum.world=<save>). It sets up NTM machines in the world and reads each one's drawn triangles
// (RenderedMesh.of): the chemical plant must show windows on Continuum's texture (hbm:textures/models/...), the
// chemical factory and the small cooling tower none (the factory's clear pixels are its texture's empty part, the
// tower's texture is listed whole as empty). The fusion torus has areas listed: its windows are logged. Then the cells
// bullets meet (Structures.cells, taken by MeshShapes within a frame's budget): every block of each machine has them,
// with glass faces where windows are. Report to the file, then the machines are taken away and the game quits. Continuum's
// machines are drawn by Gem renderers (GemBER), read through GemMesh; the small cooling tower is drawn by its core.
@EventBusSubscriber(modid = "ntmgunfeel", value = Dist.CLIENT)
public final class MachineGlassProbe {
    private static final String REPORT = System.getProperty("ntmgunfeel.machineGlassProbe");
    private static final String[] MACHINES = {"hbm:machine_chemical_plant", "hbm:machine_chemical_factory", "hbm:machine_tower_small", "hbm:fusion_torus"};
    // windows wanted: yes, no, or null (only logged)
    private static final Boolean[] WINDOWS = {true, false, false, null};
    private static final BlockPos[] FLOORS = new BlockPos[MACHINES.length];
    private static final List<String> lines = new ArrayList<>();
    private static int ticks;
    private static volatile boolean placed, cleared;
    private static volatile String failure;
    private static boolean ok = true, done, clearing;

    private MachineGlassProbe() {
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (REPORT == null || REPORT.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (done) {
            // the machines taken away again (BulletCheck sets its blocks in the same place of the same world), then quit
            if (!cleared && mc.getSingleplayerServer() != null) {
                if (!clearing) {
                    clearing = true;
                    var server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        try {
                            clear(server.overworld());
                        } catch (Throwable t) {
                            GunFeel.LOGGER.error("[machineglass-probe] clear", t);
                        }
                        cleared = true;
                    });
                }
                return;
            }
            mc.stop();
            return;
        }
        mc.options.pauseOnLostFocus = false;
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.player == null || server == null || mc.getOverlay() != null) return;
        if (mc.screen != null) mc.setScreen(null);
        ticks++;
        if (ticks == 40) server.execute(() -> place(server));
        if (ticks < 40 || !placed) return;
        if (failure != null) {
            check(false, "placed: " + failure);
            finish();
            return;
        }
        if (ticks < 40 + 20 * 15) return; // the chunks and the block entities on the client, a few frames drawn
        // the machines' cells taken from what is drawn (MeshShapes, a slice a frame), up to a minute
        if (MeshShapes.capturesCompleted < MACHINES.length && ticks < 40 + 20 * 75) return;
        lines.add("     captures completed " + MeshShapes.capturesCompleted + ", peak render work a frame " + MeshShapes.captureFramePeak / 1e6 + " ms");
        check(MeshShapes.captureFramePeak < 4_000_000L, "capture within a frame's budget: peak " + MeshShapes.captureFramePeak / 1e6 + " ms");
        for (int i = 0; i < MACHINES.length; i++) probe(mc, i);
        finish();
    }

    private static void place(MinecraftServer server) {
        try {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            clear(level);
            player.teleportTo(level, 24.5, 100, -6.5, java.util.Set.of(), 0, 0, false);
            for (int i = 0; i < MACHINES.length; i++) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(MACHINES[i])));
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                BlockPos floor = new BlockPos(2 + 16 * i, 99, 8);
                FLOORS[i] = floor;
                var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(floor.getX() + .5, 100, floor.getZ() + .5), Direction.UP, floor, false)));
                lines.add("     " + MACHINES[i] + " placed at " + floor.above().toShortString() + ": " + result);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } catch (Throwable t) {
            failure = t.toString();
            GunFeel.LOGGER.error("[machineglass-probe]", t);
        }
        placed = true;
    }

    // the place of the machines emptied, on bedrock (what earlier runs left there is gone)
    private static void clear(net.minecraft.server.level.ServerLevel level) {
        com.hbm.blocks.multiblock.BlockMultiblockCore.withoutTeardown(() -> {
            for (int x = -8; x <= 60; x++) for (int z = -8; z <= 24; z++) for (int y = 99; y <= 135; y++)
                level.setBlock(new BlockPos(x, y, z), (y == 99 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 3);
        });
    }

    // the machine's block entities near where it was placed, the one drawn with most triangles
    private static void probe(Minecraft mc, int i) {
        String name = MACHINES[i];
        BlockPos floor = FLOORS[i];
        List<RenderedMesh.Tri> best = List.of();
        BlockEntity bestBe = null, core = null;
        for (BlockPos p : BlockPos.betweenClosed(floor.offset(-7, 1, -7), floor.offset(7, 12, 7))) {
            BlockEntity be = mc.level.getBlockEntity(p);
            if (be == null) continue;
            List<RenderedMesh.Tri> tris;
            long t0 = System.nanoTime();
            try {
                tris = RenderedMesh.of(be);
                if (!tris.isEmpty()) lines.add("     " + name + ": read again in " + (System.nanoTime() - t0) / 1e6 + " ms");
            } catch (Throwable t) {
                lines.add("     " + name + ": " + be.getType() + " at " + p.toShortString() + " failed: " + t);
                continue;
            }
            var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
            lines.add("     " + name + ": " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()) + " at " + p.toShortString() + ", renderer "
                    + (renderer == null ? "none" : renderer.getClass().getName()) + ", " + tris.size() + " triangles read");
            if (core == null && Structures.taken(be.getBlockState())) core = be;
            if (tris.size() > best.size()) {
                best = tris;
                bestBe = be;
            }
        }
        // a machine drawn only as block models (its core's renderer draws nothing): its core
        if (bestBe == null) bestBe = core;
        if (bestBe == null) {
            // where its blocks are
            int seen = 0;
            for (BlockPos p : BlockPos.betweenClosed(floor.offset(-10, -2, -10), floor.offset(10, 40, 10))) {
                var st = mc.level.getBlockState(p);
                if (!BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString().equals(name)) continue;
                if (seen++ < 2 || com.hbm.blocks.multiblock.MultiblockSurface.foldedCore(st) != null)
                    lines.add("     " + name + ": block at " + p.toShortString() + " " + st + " core=" + (com.hbm.blocks.multiblock.MultiblockSurface.foldedCore(st) != null)
                            + " entity=" + mc.level.getBlockEntity(p));
            }
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (BlockPos p : BlockPos.betweenClosed(floor.offset(-10, -2, -10), floor.offset(10, 40, 10)))
                if (BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString().equals(name)) {
                    minY = Math.min(minY, p.getY());
                    maxY = Math.max(maxY, p.getY());
                }
            lines.add("     " + name + ": " + seen + " blocks of it, y " + minY + ".." + maxY);
            check(false, name + ": drawn by a block entity");
            return;
        }
        Map<String, Integer> all = new TreeMap<>(), windows = new TreeMap<>();
        for (RenderedMesh.Tri t : best) {
            all.merge(RenderedMesh.texName(t.tex()), 1, Integer::sum);
            if (t.tex() instanceof RenderedMesh.Window w) windows.merge(RenderedMesh.texName(t.tex()), 1, Integer::sum);
        }
        lines.add("     " + name + ": " + best.size() + " triangles at " + bestBe.getBlockPos().toShortString() + ", textures " + all);
        boolean continuumNames = all.keySet().stream().filter(k -> k.contains("hbm:")).allMatch(k -> !k.contains("hbm:textures/block/models/"));
        check(continuumNames, name + ": textures named as Continuum names them (hbm:textures/models/...)");
        int n = windows.values().stream().mapToInt(Integer::intValue).sum();
        // the cells bullets meet: the machine's blocks with cells, and its glass faces
        BlockPos corePos = bestBe.getBlockPos();
        int blocks = 0, withCells = 0, glassFaces = 0;
        for (BlockPos p : com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(mc.level, corePos)) {
            blocks++;
            long[] cells = Structures.cells(mc.level, p, mc.level.getBlockState(p));
            if (cells == null) continue;
            withCells++;
            for (ModelSurface.Face f : ModelSurface.faces(cells)) if (f.glass()) glassFaces++;
        }
        check(blocks > 0 && withCells == blocks, name + ": cells of every block taken: " + withCells + " of " + blocks);
        if (WINDOWS[i] != null) check(WINDOWS[i] == (glassFaces > 0), name + ": glass faces in the cells: " + glassFaces);
        else lines.add("     " + name + ": glass faces in the cells: " + glassFaces);
        if (WINDOWS[i] == null) lines.add("     " + name + ": windows " + windows);
        else check(WINDOWS[i] == (n > 0), name + ": " + (WINDOWS[i] ? "windows found" : "no windows") + ", " + n + " triangles " + windows.keySet());
    }

    private static void check(boolean pass, String what) {
        ok &= pass;
        lines.add((pass ? "OK   " : "FAIL ") + what);
    }

    private static void finish() {
        lines.add(ok ? "ALL OK" : "SOME FAILED");
        try {
            Path out = Path.of(REPORT);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, lines);
        } catch (Exception ex) {
            GunFeel.LOGGER.error("[machineglass-probe] report", ex);
        }
        for (String l : lines) GunFeel.LOGGER.info("[machineglass-probe] {}", l);
        done = true;
    }
}
