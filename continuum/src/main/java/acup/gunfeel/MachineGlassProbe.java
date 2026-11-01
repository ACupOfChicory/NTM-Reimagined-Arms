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
// tower's texture is listed whole as empty). The fusion torus has areas listed: its windows are logged. Bullets do not
// meet machines on this node yet (Structures: no machine shapes), so only what is drawn is checked. Report to the file,
// then quits. As of X381 it fails: RenderedMesh reads no triangles from Continuum's Gem renderers (GemBER), and the
// small cooling tower has no block entity; it is for the machine shapes work (MACHINE_MESH) to turn green.
@EventBusSubscriber(modid = "ntmgunfeel", value = Dist.CLIENT)
public final class MachineGlassProbe {
    private static final String REPORT = System.getProperty("ntmgunfeel.machineGlassProbe");
    private static final String[] MACHINES = {"hbm:machine_chemical_plant", "hbm:machine_chemical_factory", "hbm:machine_tower_small", "hbm:fusion_torus"};
    // windows wanted: yes, no, or null (only logged)
    private static final Boolean[] WINDOWS = {true, false, false, null};
    private static final BlockPos[] FLOORS = new BlockPos[MACHINES.length];
    private static final List<String> lines = new ArrayList<>();
    private static int ticks;
    private static volatile boolean placed;
    private static volatile String failure;
    private static boolean ok = true, done;

    private MachineGlassProbe() {
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (REPORT == null || REPORT.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (done) {
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
        for (int i = 0; i < MACHINES.length; i++) probe(mc, i);
        finish();
    }

    private static void place(MinecraftServer server) {
        try {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            com.hbm.blocks.multiblock.BlockMultiblockCore.withoutTeardown(() -> {
                for (int x = -8; x <= 60; x++) for (int z = -8; z <= 24; z++) for (int y = 99; y <= 115; y++)
                    level.setBlock(new BlockPos(x, y, z), (y == 99 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 3);
            });
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

    // the machine's block entities near where it was placed, the one drawn with most triangles
    private static void probe(Minecraft mc, int i) {
        String name = MACHINES[i];
        BlockPos floor = FLOORS[i];
        List<RenderedMesh.Tri> best = List.of();
        BlockEntity bestBe = null;
        for (BlockPos p : BlockPos.betweenClosed(floor.offset(-7, 1, -7), floor.offset(7, 12, 7))) {
            BlockEntity be = mc.level.getBlockEntity(p);
            if (be == null) continue;
            List<RenderedMesh.Tri> tris;
            try {
                tris = RenderedMesh.of(be);
            } catch (Throwable t) {
                lines.add("     " + name + ": " + be.getType() + " at " + p.toShortString() + " failed: " + t);
                continue;
            }
            var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
            lines.add("     " + name + ": " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()) + " at " + p.toShortString() + ", renderer "
                    + (renderer == null ? "none" : renderer.getClass().getName()) + ", " + tris.size() + " triangles read");
            if (tris.size() > best.size()) {
                best = tris;
                bestBe = be;
            }
        }
        if (bestBe == null) {
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
