package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * The tactical map on a real server (a dev run: ./gradlew runCheckAiMap -PnoAssets). -Dntmgunfeel.checkAiMap=&lt;file&gt;:
 * once the world is up, the server builds a yard high in the air (a stone floor, a stone wall two high, a concrete
 * wall one high, glass, leaves), has the map build its cells on the background threads (the map is ticked by hand,
 * the server's own ticks wait), checks the cover of cells by the walls, breaks a hole in the wall and blows up a
 * corner and checks the cells follow, with the time it all took. One line per check to the file; the server stops
 * (exit 1 if a check failed).
 */
public final class AiMapCheck {
    public static final String PROPERTY = "ntmgunfeel.checkAiMap";
    private static final int Y = 100, X0 = 8, Z0 = 8, SIZE = 32;

    private AiMapCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> {
            List<String> out = new ArrayList<>();
            int code;
            try {
                code = new AiMapCheck().run(out, e.getServer()) ? 0 : 1;
            } catch (Throwable t) {
                out.add("FAIL crashed: " + t);
                for (StackTraceElement s : t.getStackTrace()) out.add("    at " + s);
                code = 1;
            }
            finish(file, out, code);
        });
    }

    static void finish(String file, List<String> out, int code) {
        try {
            java.nio.file.Path f = java.nio.file.Path.of(file);
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            ex.printStackTrace();
            code = 1;
        }
        out.forEach(System.out::println);
        Runtime.getRuntime().halt(code);
    }

    private List<String> out;
    private boolean ok = true;
    private ServerLevel level;
    private TacticalMap map;

    private boolean run(List<String> out, MinecraftServer server) throws Exception {
        this.out = out;
        level = server.overworld();
        BlockState stone = Blocks.STONE.defaultBlockState(), concrete = Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY).defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) set(x, Y - 1, z, stone);
        // a stone wall two high across the yard at z = 24, a concrete one one high at z = 32
        for (int x = X0 + 2; x < X0 + SIZE - 2; x++) {
            set(x, Y, 24, stone);
            set(x, Y + 1, 24, stone);
            set(x, Y, 32, concrete);
        }
        // glass and leaves two high, side by side at z = 14
        for (int y = Y; y < Y + 2; y++) {
            set(12, y, 14, Blocks.GLASS.defaultBlockState());
            set(16, y, 14, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        }

        map = TacticalMap.of(level);
        long t0 = System.nanoTime();
        map.want(X0 + SIZE / 2, Y, Z0 + SIZE / 2, SIZE / 2 + 2, 8);
        int ticks = settle();
        out.add(String.format(Locale.ROOT, "     first build: %d ticks, %.1f ms; %s", ticks, (System.nanoTime() - t0) / 1e6, map.info()));
        check("map built", map.publishedCount() > 0 && map.queued() == 0 && map.inFlight() == 0);

        int north = cover(15, Y, 23), south = cover(15, Y, 25);
        check("stone wall: high hard cover south of the cell north of it",
                SectionCells.height(north, Dirs.S) == 2 && SectionCells.hard(north, Dirs.S));
        check("stone wall: high hard cover north of the cell south of it",
                SectionCells.height(south, Dirs.N) == 2 && SectionCells.hard(south, Dirs.N));
        check("stone wall: no cover away from it", SectionCells.height(north, Dirs.N) == 0);
        check("a cell on top of the wall", has(15, Y + 2, 24));
        check("no cell in the wall", !has(15, Y, 24));
        int low = cover(15, Y, 31);
        check("concrete wall one high: low hard cover", SectionCells.height(low, Dirs.S) == 1 && SectionCells.hard(low, Dirs.S));
        check("glass hides nothing", SectionCells.height(cover(12, Y, 13), Dirs.S) == 0);
        int leaves = cover(16, Y, 13);
        check("leaves hide but do not stop bullets", SectionCells.height(leaves, Dirs.S) == 2 && !SectionCells.hard(leaves, Dirs.S));
        int cells = 0;
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) if (has(x, Y, z)) cells++;
        out.add("     cells on the yard floor: " + cells);
        check("cells on the floor of the yard", cells > SIZE * SIZE - 3 * SIZE);

        // a hole in the wall: the cells follow
        long changesBefore = map.changes;
        t0 = System.nanoTime();
        set(15, Y, 24, Blocks.AIR.defaultBlockState());
        set(15, Y + 1, 24, Blocks.AIR.defaultBlockState());
        check("a broken block makes its section stale", map.cellsAt(15, Y, 24) != null && !map.fresh(map.cellsAt(15, Y, 24)));
        map.want(15, Y, 24, 8, 4);
        ticks = settle();
        out.add(String.format(Locale.ROOT, "     hole: %d block changes seen, rebuilt in %d ticks, %.1f ms",
                map.changes - changesBefore, ticks, (System.nanoTime() - t0) / 1e6));
        check("a cell in the hole", has(15, Y, 24));
        check("the cell before the hole lost its cover south", SectionCells.height(cover(15, Y, 23), Dirs.S) == 0);
        int beside = cover(14, Y, 23);
        check("its neighbour keeps its wall south, the hole south-east is open",
                SectionCells.height(beside, Dirs.S) == 2 && SectionCells.height(beside, Dirs.SE) == 0);
        check("a cell beside the wall is fresh again", map.fresh(map.cellsAt(15, Y, 23)));

        // a change that does not touch what the AI reads (stone to cobblestone): nothing to rebuild
        changesBefore = map.changes;
        set(20, Y + 1, 24, Blocks.COBBLESTONE.defaultBlockState());
        check("stone to cobblestone changes nothing for the AI", map.changes == changesBefore);

        // an explosion at the end of the wall
        changesBefore = map.changes;
        long builds = map.builds, snaps = map.snapshots;
        t0 = System.nanoTime();
        level.explode(null, X0 + SIZE - 6, Y + 1, 24, 6F, Level.ExplosionInteraction.BLOCK);
        long blastNanos = System.nanoTime() - t0;
        ticks = settle();
        out.add(String.format(Locale.ROOT, "     explosion: %.1f ms for the blast itself, %d block changes seen, %d snapshots, %d builds, settled in %d ticks, %.1f ms",
                blastNanos / 1e6, map.changes - changesBefore, map.snapshots - snaps, map.builds - builds, ticks, (System.nanoTime() - t0) / 1e6));
        check("the explosion was seen", map.changes > changesBefore);
        check("every section fresh after the explosion", allFresh());

        // a snapshot once every block state met is classified: what a changed section costs the server thread
        long n0 = map.snapshots, t1 = map.snapshotNanos;
        for (int i = 0; i < 20; i++) {
            set(X0 + 1 + i, Y, Z0 + 1, Blocks.STONE.defaultBlockState());
            map.want(X0 + 1 + i, Y, Z0 + 1, 2, 2);
            settle();
            set(X0 + 1 + i, Y, Z0 + 1, Blocks.AIR.defaultBlockState());
            settle();
        }
        double warm = (map.snapshotNanos - t1) / 1000.0 / Math.max(1, map.snapshots - n0);
        out.add(String.format(Locale.ROOT, "     warm snapshots: %d, avg %.1f us", map.snapshots - n0, warm));
        check("a warm snapshot under 100 us", warm < 100);

        // what /armsai debug sends: through the network codec and back
        AiCellsPayload p = AiDebug.cells(map, new BlockPos(20, Y, 20), 16);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        AiCellsPayload.CODEC.encode(buf, p);
        int bytes = buf.readableBytes();
        AiCellsPayload back = AiCellsPayload.CODEC.decode(buf);
        out.add("     debug view radius 16: " + p.count() + " cells, " + bytes + " bytes");
        check("debug cells survive the codec", p.count() > 100 && back.count() == p.count()
                && java.util.Arrays.equals(back.cover(), p.cover()) && java.util.Arrays.equals(back.offsets(), p.offsets()));
        out.add("     " + map.info());
        out.add(ok ? "ALL OK" : "FAILED");
        return ok;
    }

    private void set(int x, int y, int z, BlockState st) {
        level.setBlock(new BlockPos(x, y, z), st, Block.UPDATE_CLIENTS);
    }

    /** ticks the map by hand until nothing is queued or building, 10 s at most */
    private int settle() throws InterruptedException {
        int ticks = 0;
        long end = System.nanoTime() + 10_000_000_000L;
        do {
            map.tick();
            ticks++;
            if (map.queued() == 0 && map.inFlight() == 0) break;
            Thread.sleep(2);
        } while (System.nanoTime() < end);
        return ticks;
    }

    private boolean allFresh() {
        boolean all = true;
        for (int x = X0; x < X0 + SIZE; x += 16)
            for (int z = Z0; z < Z0 + SIZE; z += 16) {
                SectionCells c = map.cellsAt(x, Y, z);
                if (c == null || !map.fresh(c)) all = false;
            }
        return all;
    }

    private boolean has(int x, int y, int z) {
        SectionCells c = map.cellsAt(x, y, z);
        return c != null && c.find(x & 15, y & 15, z & 15) >= 0;
    }

    private int cover(int x, int y, int z) {
        SectionCells c = map.cellsAt(x, y, z);
        int i = c == null ? -1 : c.find(x & 15, y & 15, z & 15);
        if (i < 0) {
            check("a cell at " + x + " " + y + " " + z, false);
            return 0;
        }
        return c.cover(i);
    }

    private void check(String what, boolean good) {
        out.add((good ? "OK   " : "FAIL ") + what);
        ok &= good;
    }
}
