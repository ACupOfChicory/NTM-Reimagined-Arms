package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import it.unimi.dsi.fastutil.longs.Long2CharOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The AI check on a world brought from elsewhere (./gradlew runCheckAi -PaiWorld=<folder> -PaiWorldAt=x,y,z,radius[,up]):
 * the stand's server opens that world (a copy: the game brings it up to this version as it loads), no stage of the
 * check runs and no block is set; the chunks round the place are loaded, the tactical map reads them, and the
 * buildings the AI reads there (AiRooms, as /armsai debug rooms shows them) are written out in numbers and as a plan of
 * each floor in letters. The world is saved as the server stops.
 */
final class AiCheckWorld {
    private AiCheckWorld() {
    }

    static final String PROPERTY = "ntmgunfeel.checkAiWorld";

    static void start(MinecraftServer server, String spec, String file) {
        String[] a = spec.split(",");
        int x = Integer.parseInt(a[0].trim()), y = Integer.parseInt(a[1].trim()), z = Integer.parseInt(a[2].trim());
        int radius = a.length > 3 ? Integer.parseInt(a[3].trim()) : 64, up = a.length > 4 ? Integer.parseInt(a[4].trim()) : 24;
        ServerLevel level = server.overworld();
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++)
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) level.setChunkForced(cx, cz, true);
        AiBots.awake = true;
        int[] t = {0, 0};
        boolean[] done = {false};
        List<String> out = new ArrayList<>();
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, ev -> {
            if (done[0]) return;
            try {
                TacticalMap map = TacticalMap.of(level);
                if (t[0]++ % 20 == 0) map.want(x, y, z, radius + 16, up + 16);
                // read: nothing queued or building for a second, or two minutes gone
                t[1] = map.queued() > 0 || map.inFlight() > 0 ? 0 : t[1] + 1;
                if (t[0] < 100 || t[1] < 20 && t[0] < 2400) return;
                done[0] = true;
                out.add(String.format(Locale.ROOT, "     the world round %d %d %d, %d blocks each way, %d up and down, read in %d ticks; %s", x, y, z, radius, up, t[0], map.info()));
                AiDebug.Buildings b = AiDebug.buildings(map, x - radius, x + radius, y - up, y + up, z - radius, z + radius, 400, 4000);
                for (String line : AiDebug.dump(b, String.format(Locale.ROOT, "within %d blocks of %d %d %d", radius, x, y, z))) out.add("     " + line);
                floors(map, b, out);
                out.add("ALL OK");
            } catch (Throwable e) {
                out.add("FAIL crashed: " + e);
                for (StackTraceElement s : e.getStackTrace()) out.add("    at " + s);
                done[0] = true;
            }
            try {
                java.nio.file.Path f = java.nio.file.Path.of(file);
                java.nio.file.Files.createDirectories(f.getParent());
                java.nio.file.Files.write(f, out, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                e.printStackTrace();
            }
            // stopped as a server stops: the world saved, none of its chunks kept loaded
            for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++)
                for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) level.setChunkForced(cx, cz, false);
            server.halt(false);
        });
    }

    /**
     * A plan of each floor in letters: a room's cells its number's last letter (1..9, a..z, then again), '+' a way, 'D'
     * a way with a door, 'w' a way over a sill (a window climbed through), '/' a stair, 'H' a ladder, 'x' under a roof and no building's, '.' a cell under the sky,
     * 'W' a window and 'G' a glazed one (the wall's block), '#' any other block at head height, ' ' nothing.
     */
    static void floors(TacticalMap map, AiDebug.Buildings b, List<String> out) {
        Long2CharOpenHashMap at = new Long2CharOpenHashMap();
        TreeMap<Integer, int[]> levels = new TreeMap<>(); // y -> {cells, x0, x1, z0, z1}
        String digits = "123456789abcdefghijklmnopqrstuvwxyz";
        int number = 0;
        for (AiRooms.Plan p : b.plans()) {
            for (var e : p.roomCells().long2IntEntrySet()) {
                long c = e.getLongKey();
                at.put(c, digits.charAt((number + e.getIntValue()) % digits.length()));
                int[] l = levels.computeIfAbsent(BlockPos.getY(c), k -> new int[] {0, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE});
                l[0]++;
                l[1] = Math.min(l[1], BlockPos.getX(c));
                l[2] = Math.max(l[2], BlockPos.getX(c));
                l[3] = Math.min(l[3], BlockPos.getZ(c));
                l[4] = Math.max(l[4], BlockPos.getZ(c));
            }
            number += p.rooms();
            for (AiRooms.Way w : p.ways)
                for (long c : w.cells) {
                    at.put(c, w.door ? 'D' : w.sill ? 'w' : '+');
                    // a way over a sill stands a step over the floor it leads from: drawn on that floor's plan too
                    if (w.sill) at.putIfAbsent(BlockPos.asLong(BlockPos.getX(c), BlockPos.getY(c) - 1, BlockPos.getZ(c)), 'w');
                }
            for (long c : p.stairs) at.put(c, '/');
            for (long c : p.ladders) at.put(c, 'H');
            // a window is the wall's block at head height: put at its feet's level, where the floor's plan is drawn
            for (AiRooms.Window w : p.windows) at.put(BlockPos.asLong(BlockPos.getX(w.block()), BlockPos.getY(w.block()) - 1, BlockPos.getZ(w.block())), w.glazed() ? 'G' : 'W');
        }
        for (long c : b.wild()) at.putIfAbsent(c, 'x');
        for (var e : levels.entrySet()) {
            int y = e.getKey();
            int[] l = e.getValue();
            if (l[0] < 12) continue;
            out.add(String.format(Locale.ROOT, "     floor y %d: %d cells of rooms, x %d..%d (left to right), z %d..%d (top to bottom, north up)", y, l[0], l[1] - 2, l[2] + 2, l[3] - 2, l[4] + 2));
            for (int z = l[3] - 2; z <= l[4] + 2; z++) {
                StringBuilder row = new StringBuilder("     |");
                for (int x = l[1] - 2; x <= l[2] + 2; x++) {
                    long c = BlockPos.asLong(x, y, z);
                    char ch = at.get(c);
                    if (ch == 0) {
                        SectionCells s = map.cells(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
                        if (s != null && s.find(x & 15, y & 15, z & 15) >= 0) ch = '.';
                        else ch = Voxel.open(map.voxel(x, y + 1, z)) ? ' ' : '#';
                    }
                    row.append(ch);
                }
                out.add(row.append('|').toString());
            }
        }
    }
}
