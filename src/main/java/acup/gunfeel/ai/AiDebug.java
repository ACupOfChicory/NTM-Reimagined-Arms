package acup.gunfeel.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * /armsai debug: the players who watch the tactical map. Twice a second each gets the cells around them (the map
 * keeps building them while someone watches) and the AI mobs' thoughts ({@link AiThoughts}); the client draws them
 * ({@code AiDebugClient}). While anyone watches, the soldiers' decision journal runs too.
 */
public final class AiDebug {
    private AiDebug() {
    }

    /** player -> radius across, in blocks */
    private static final Map<UUID, Integer> WATCHERS = new HashMap<>();
    static final int HEIGHT = 6;
    /** player -> {radius across, 1: with the blocks of the rooms' shells} of the buildings' view (/armsai debug rooms) */
    private static final Map<UUID, int[]> ROOMS = new HashMap<>();
    /** the buildings' view: this far up and down, blocks; buildings read an update at most, cells asked about */
    static final int ROOMS_HEIGHT = 12, PLANS = 24, ASKED = 96;
    /** the buildings' view reads a building this far from the cell asked about and so many cells of it at most */
    static final int VIEW_REACH = 64, VIEW_CELLS = 8000;

    /** player -> radius across of the terrain layers' view (/armsai debug terrain); it and the buildings' view share a payload, one at a time */
    private static final Map<UUID, Integer> TERRAIN = new HashMap<>();

    /** the terrain layers' view on (with this radius) or off; whether it is on now */
    public static boolean toggleTerrain(ServerPlayer player, int radius) {
        if (TERRAIN.remove(player.getUUID()) != null) {
            PacketDistributor.sendToPlayer(player, new AiRoomsPayload(player.blockPosition(), new byte[0], java.util.List.of()));
            return false;
        }
        ROOMS.remove(player.getUUID());
        TERRAIN.put(player.getUUID(), radius);
        return true;
    }

    /** the coarse cells of the terrain layers round a place, and over each chunk's middle its numbers */
    static AiRoomsPayload terrain(AiTerrain terrain, BlockPos at, int radius) {
        byte[] marks = new byte[1024 * AiRoomsPayload.MARK];
        int n = 0;
        java.util.List<AiRoomsPayload.Label> labels = new java.util.ArrayList<>();
        int x0 = (at.getX() - radius) & ~3, x1 = at.getX() + radius, z0 = (at.getZ() - radius) & ~3, z1 = at.getZ() + radius;
        for (int z = z0; z <= z1; z += TerrainTile.CELL)
            for (int x = x0; x <= x1; x += TerrainTile.CELL) {
                TerrainTile t = terrain.tile(x, z);
                TerrainDerived d = terrain.derived(x, z);
                if (t == null || d == null) continue;
                int y = t.hi(x, z), dy = y - at.getY();
                if (Math.abs(x - at.getX()) > 120 || Math.abs(z - at.getZ()) > 120 || Math.abs(dy) > 120) continue;
                int far = d.far(x, z), open = d.open(x, z);
                int what = t.wet(x, z) >= 0.5F ? AiRoomsPayload.WET : far >= TerrainDerived.HIGH ? AiRoomsPayload.HIGH
                        : far <= TerrainDerived.HOLLOW ? AiRoomsPayload.HOLLOW : AiRoomsPayload.FLAT;
                if (n * AiRoomsPayload.MARK == marks.length) marks = java.util.Arrays.copyOf(marks, marks.length * 2);
                int i = n++ * AiRoomsPayload.MARK;
                marks[i] = (byte) (x - at.getX());
                marks[i + 1] = (byte) dy;
                marks[i + 2] = (byte) (z - at.getZ());
                marks[i + 3] = AiRoomsPayload.TERRAIN;
                marks[i + 4] = (byte) (Math.min(31, open / 4) | what << 5 | (t.crowned(x, z) >= 0.5F ? 128 : 0));
                // one label a chunk, over the cell at its middle
                if ((x & 15) == 8 && (z & 15) == 8 && labels.size() < AiRoomsPayload.LABELS)
                    labels.add(new AiRoomsPayload.Label(new BlockPos(x, y + 1, z), "sees " + open + "  " + (far >= 0 ? "+" : "") + far
                            + (t.crowned(x, z) > 0 ? "  crowns " + Math.round(t.crowned(x, z) * 100) + "%" : "")
                            + (t.wet(x, z) > 0 ? "  water " + t.water(x, z) : ""), 0xFFFFFFFF));
            }
        return new AiRoomsPayload(at, java.util.Arrays.copyOf(marks, n * AiRoomsPayload.MARK), labels);
    }

    /** the buildings' view on (with this radius) or off; whether it is on now */
    public static boolean toggleRooms(ServerPlayer player, int radius) {
        if (ROOMS.remove(player.getUUID()) != null) {
            PacketDistributor.sendToPlayer(player, new AiRoomsPayload(player.blockPosition(), new byte[0], java.util.List.of()));
            return false;
        }
        TERRAIN.remove(player.getUUID());
        ROOMS.put(player.getUUID(), new int[] {radius, 0});
        return true;
    }

    /** the blocks of the rooms' shells shown or not (the buildings' view turned on for it); whether they are shown now */
    public static boolean toggleWalls(ServerPlayer player) {
        TERRAIN.remove(player.getUUID());
        int[] v = ROOMS.computeIfAbsent(player.getUUID(), u -> new int[] {24, 0});
        v[1] ^= 1;
        return v[1] != 0;
    }

    /** a player's buildings' view: its radius, or -1 when it is off */
    static int roomsRadius(ServerPlayer player) {
        int[] v = ROOMS.get(player.getUUID());
        return v == null ? -1 : v[0];
    }

    /** the buildings read round a place: their plans, and the cells under a roof that are no building's */
    record Buildings(java.util.List<AiRooms.Plan> plans, it.unimi.dsi.fastutil.longs.LongOpenHashSet wild) {
    }

    static Buildings buildings(TacticalMap map, BlockPos at, int radius) {
        return buildings(map, at.getX() - radius, at.getX() + radius, at.getY() - ROOMS_HEIGHT, at.getY() + ROOMS_HEIGHT, at.getZ() - radius, at.getZ() + radius, PLANS, ASKED);
    }

    /** the same in a box, so many buildings and cells asked about at most */
    static Buildings buildings(TacticalMap map, int x0, int x1, int y0, int y1, int z0, int z1, int most, int ask) {
        java.util.List<AiRooms.Plan> plans = new java.util.ArrayList<>();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet wild = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(), covered = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        int asked = 0;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++)
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++)
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    SectionCells c = map.cells(SectionPos.asLong(sx, sy, sz));
                    if (c == null) continue;
                    for (int i = 0; i < c.count(); i++) {
                        if ((c.flags(i) & SectionCells.ROOF) == 0) continue;
                        int x = (sx << 4) + c.x(i), y = (sy << 4) + c.y(i), z = (sz << 4) + c.z(i);
                        if (x < x0 || x > x1 || y < y0 || y > y1 || z < z0 || z > z1) continue;
                        long cell = BlockPos.asLong(x, y, z);
                        if (covered.contains(cell) || wild.contains(cell) || plans.size() >= most || asked >= ask) continue;
                        asked++;
                        // a whole building at once: read farther than the AI reads round a target (AiRooms.REACH, MAX_CELLS)
                        AiRooms.Plan p = AiRooms.of(map, cell, wild, VIEW_REACH, VIEW_CELLS);
                        if (p == null) {
                            wild.add(cell);
                            continue;
                        }
                        plans.add(p);
                        covered.addAll(p.roomCells().keySet());
                        for (AiRooms.Way w : p.ways) for (long wc : w.cells) covered.add(wc);
                        covered.add(cell);
                    }
                }
        return new Buildings(plans, wild);
    }

    /** the buildings' view of a place: marks and labels (AiRoomsPayload) */
    static AiRoomsPayload rooms(TacticalMap map, BlockPos at, int radius, boolean walls) {
        Buildings b = buildings(map, at, radius);
        Marks m = new Marks(at, radius);
        java.util.List<AiRoomsPayload.Label> labels = new java.util.ArrayList<>();
        int number = 0;
        for (AiRooms.Plan p : b.plans) {
            int first = number;
            for (var e : p.roomCells().long2IntEntrySet()) m.add(e.getLongKey(), AiRoomsPayload.ROOM, (first + e.getIntValue()) % 240);
            for (int r = 0; r < p.rooms(); r++) {
                if (labels.size() < AiRoomsPayload.LABELS && m.in(p.mid[r]))
                    labels.add(new AiRoomsPayload.Label(BlockPos.of(p.mid[r]), (first + r + 1) + (p.kind[r] == AiRooms.CORRIDOR ? " corridor " : " room ") + p.size[r], 0xFFFFFFFF));
            }
            number += p.rooms();
            for (AiRooms.Way w : p.ways) {
                int value = (w.door ? 1 : 0) | (w.b == AiRooms.OUTSIDE ? 2 : 0) | (w.sill ? 4 : 0);
                // an open side of a roof is a row of the room's own cells: its marks go over theirs
                for (long c : w.cells) m.put(c, AiRoomsPayload.WAY, value);
                for (long c : w.besideA) m.add2(c, AiRoomsPayload.BESIDE, 1);
                for (long c : w.besideB) m.add2(c, AiRoomsPayload.BESIDE, 0);
            }
            for (long c : p.stairs) m.add2(c, AiRoomsPayload.STAIRS, 0);
            for (long c : p.ladders) m.add2(c, AiRoomsPayload.STAIRS, 1);
            for (AiRooms.Window w : p.windows) m.add2(w.block(), AiRoomsPayload.WINDOW, w.dir() | (w.glazed() ? 8 : 0));
            if (walls) {
                for (var e : p.roomCells().long2IntEntrySet()) {
                    long c = e.getLongKey();
                    int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
                    m.wall(map, x, y - 1, z);
                    for (int up = 2; up < 2 + CellBuilder.ROOF_REACH; up++) {
                        int v = map.voxel(x, y + up, z);
                        if (!Voxel.open(v) || Voxel.sight(v)) {
                            m.wall(map, x, y + up, z);
                            break;
                        }
                    }
                    for (int d = 0; d < 8; d += 2)
                        for (int h = 0; h < 2; h++)
                            if (!Voxel.open(map.voxel(x + Dirs.DX[d], y + h, z + Dirs.DZ[d]))) m.wall(map, x + Dirs.DX[d], y + h, z + Dirs.DZ[d]);
                }
            }
        }
        for (long c : b.wild) m.add(c, AiRoomsPayload.WILD, 0);
        return new AiRoomsPayload(at, m.bytes(), labels);
    }

    /** the marks of a view being put together: a room's or a wild cell once a place, the rest over them */
    private static final class Marks {
        final BlockPos at;
        final int radius;
        byte[] out = new byte[AiRoomsPayload.MARK * 1024];
        int n;
        final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap floor = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet walls = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        Marks(BlockPos at, int radius) {
            this.at = at;
            this.radius = radius;
            floor.defaultReturnValue(-1);
        }

        boolean in(long cell) {
            return Math.abs(BlockPos.getX(cell) - at.getX()) <= radius && Math.abs(BlockPos.getZ(cell) - at.getZ()) <= radius
                    && Math.abs(BlockPos.getY(cell) - at.getY()) <= ROOMS_HEIGHT + 4;
        }

        /** a floor's mark, the first to come to a cell */
        void add(long cell, byte kind, int value) {
            if (floor.containsKey(cell)) return;
            int i = add2(cell, kind, value);
            if (i >= 0) floor.put(cell, i);
        }

        /** a floor's mark that takes the place of the one a cell has */
        void put(long cell, byte kind, int value) {
            int i = floor.get(cell);
            if (i < 0) {
                i = add2(cell, kind, value);
                if (i >= 0) floor.put(cell, i);
                return;
            }
            out[i * AiRoomsPayload.MARK + 3] = kind;
            out[i * AiRoomsPayload.MARK + 4] = (byte) value;
        }

        /** a mark over whatever else is there; its index, or -1 if it is out of the view */
        int add2(long cell, byte kind, int value) {
            if (!in(cell) || n >= AiRoomsPayload.LIMIT) return -1;
            if ((n + 1) * AiRoomsPayload.MARK > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
            int o = n * AiRoomsPayload.MARK;
            out[o] = (byte) (BlockPos.getX(cell) - at.getX());
            out[o + 1] = (byte) (BlockPos.getY(cell) - at.getY());
            out[o + 2] = (byte) (BlockPos.getZ(cell) - at.getZ());
            out[o + 3] = kind;
            out[o + 4] = (byte) value;
            return n++;
        }

        void wall(TacticalMap map, int x, int y, int z) {
            long b = BlockPos.asLong(x, y, z);
            if (walls.add(b)) add2(b, AiRoomsPayload.WALL, map.kind(x, y, z));
        }

        byte[] bytes() {
            return java.util.Arrays.copyOf(out, n * AiRoomsPayload.MARK);
        }
    }

    /** what the buildings' view shows round a place, in words: for the chat (the first line) and the debug log (all of it) */
    static java.util.List<String> dump(TacticalMap map, BlockPos at, int radius) {
        return dump(buildings(map, at, radius), "within " + radius + " blocks of " + at.toShortString());
    }

    static java.util.List<String> dump(Buildings b, String where) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int rooms = 0, corridors = 0, ways = 0, doors = 0, outs = 0, sills = 0, windows = 0, glazed = 0, stairs = 0, ladders = 0, number = 0;
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int pi = 0; pi < b.plans.size(); pi++) {
            AiRooms.Plan p = b.plans.get(pi);
            lines.add(String.format(java.util.Locale.ROOT, "building %d: rooms %d, ways %d, windows %d, stairs %d, ladders %d%s", pi + 1, p.rooms(), p.ways.length, p.windows.length,
                    p.stairs.length, p.ladders.length, p.cut ? " (read in part: it goes on past the AI's reach)" : ""));
            for (int r = 0; r < p.rooms(); r++) {
                int win = 0;
                for (AiRooms.Window w : p.windows) if (w.room() == r) win++;
                boolean corridor = p.kind[r] == AiRooms.CORRIDOR;
                if (corridor) corridors++;
                else rooms++;
                lines.add(String.format(java.util.Locale.ROOT, "  %d %s: %d cells, middle %s, ways %d, windows %d", number + r + 1, corridor ? "corridor" : "room", p.size[r],
                        BlockPos.of(p.mid[r]).toShortString(), p.waysOf(r).size(), win));
            }
            for (AiRooms.Way w : p.ways) {
                ways++;
                if (w.door) doors++;
                if (w.b == AiRooms.OUTSIDE) outs++;
                if (w.sill) sills++;
                lines.add(String.format(java.util.Locale.ROOT, "  way at %s to the %s: from %d to %s, %d wide%s, cells beside it %d and %d", BlockPos.of(w.cells[0]).toShortString(), Dirs.NAMES[w.dir],
                        number + w.a + 1, w.b == AiRooms.OUTSIDE ? "outside" : String.valueOf(number + w.b + 1), w.width, w.door ? ", a door" : w.sill ? ", over a sill (a window climbed through)" : "", w.besideA.length, w.besideB.length));
            }
            for (AiRooms.Window w : p.windows) {
                windows++;
                if (w.glazed()) glazed++;
                lines.add(String.format(java.util.Locale.ROOT, "  window at %s, room %d, to the %s%s", BlockPos.of(w.block()).toShortString(), number + w.room() + 1, Dirs.NAMES[w.dir()],
                        w.glazed() ? ", glazed" : ""));
            }
            stairs += p.stairs.length;
            ladders += p.ladders.length;
            number += p.rooms();
        }
        out.add(String.format(java.util.Locale.ROOT, "buildings %d %s: rooms %d, corridors %d, ways %d (doors %d, out from under the roof %d, over a sill %d), windows not walked through %d (glazed %d),"
                + " stair cells %d, ladder cells %d, cells under a roof that are no building's %d", b.plans.size(), where, rooms, corridors, ways, doors, outs, sills, windows,
                glazed, stairs, ladders, b.wild.size()));
        out.addAll(lines);
        return out;
    }

    public static boolean toggle(ServerPlayer player, int radius) {
        if (WATCHERS.remove(player.getUUID()) != null) {
            PacketDistributor.sendToPlayer(player, new AiCellsPayload(player.blockPosition(), new byte[0], new int[0], new byte[0]));
            PacketDistributor.sendToPlayer(player, new AiBotsPayload(java.util.List.of()));
            return false;
        }
        WATCHERS.put(player.getUUID(), radius);
        return true;
    }

    public static void forget(UUID player) {
        WATCHERS.remove(player);
        ROOMS.remove(player);
        TERRAIN.remove(player);
    }

    /** someone watches */
    static boolean watching() {
        return !WATCHERS.isEmpty();
    }

    public static void tick(ServerLevel level) {
        if (!TERRAIN.isEmpty() && level.getGameTime() % 20 == 7)
            for (ServerPlayer p : level.players()) {
                Integer radius = TERRAIN.get(p.getUUID());
                if (radius == null) continue;
                AiTerrain terrain = AiTerrain.of(level);
                BlockPos at = p.blockPosition();
                terrain.want(at.getX(), at.getZ());
                PacketDistributor.sendToPlayer(p, terrain(terrain, at, radius));
            }
        if (!ROOMS.isEmpty() && level.getGameTime() % 20 == 5)
            for (ServerPlayer p : level.players()) {
                int[] v = ROOMS.get(p.getUUID());
                if (v == null) continue;
                TacticalMap map = TacticalMap.of(level);
                BlockPos at = p.blockPosition();
                map.want(at.getX(), at.getY(), at.getZ(), v[0] + 16, ROOMS_HEIGHT + 8);
                PacketDistributor.sendToPlayer(p, rooms(map, at, v[0], v[1] != 0));
            }
        if (WATCHERS.isEmpty() || level.getGameTime() % 10 != 0) return;
        for (ServerPlayer p : level.players()) {
            Integer radius = WATCHERS.get(p.getUUID());
            if (radius == null) continue;
            TacticalMap map = TacticalMap.of(level);
            BlockPos at = p.blockPosition();
            map.want(at.getX(), at.getY(), at.getZ(), radius + 16, HEIGHT + 16);
            PacketDistributor.sendToPlayer(p, cells(map, at, radius));
            PacketDistributor.sendToPlayer(p, bots(level, at, radius + 32));
        }
    }

    static AiBotsPayload bots(ServerLevel level, BlockPos at, int radius) {
        java.util.List<AiBotsPayload.Bot> out = new java.util.ArrayList<>();
        for (AiBot b : AiBots.all(level)) {
            if (out.size() >= AiBotsPayload.LIMIT) break;
            if (b.mob.blockPosition().distManhattan(at) > radius * 2) continue;
            String text = !b.active() ? "asleep  " + AiThoughts.hp(b.mob) : b.brain instanceof AiSoldier s ? s.describe(b) : "driven  " + AiThoughts.hp(b.mob);
            net.minecraft.world.phys.Vec3 w = b.brain instanceof AiSoldier s ? s.watch() : null;
            CoverQuery.Spot spot = b.brain instanceof AiSoldier s ? s.spot() : null;
            out.add(new AiBotsPayload.Bot(b.mob.getId(), text, w == null ? Double.NaN : w.x, w == null ? Double.NaN : w.y,
                    w == null ? Double.NaN : w.z, spot == null ? Long.MIN_VALUE : spot.cell(), spot == null ? Long.MIN_VALUE : spot.peek(),
                    b.mover.path()));
        }
        return new AiBotsPayload(out);
    }

    static AiCellsPayload cells(TacticalMap map, BlockPos at, int radius) {
        int n = 0;
        byte[] offsets = new byte[3 * 1024];
        int[] cover = new int[1024];
        byte[] flags = new byte[1024];
        int x0 = at.getX() - radius, x1 = at.getX() + radius, z0 = at.getZ() - radius, z1 = at.getZ() + radius;
        int y0 = at.getY() - HEIGHT, y1 = at.getY() + HEIGHT;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++)
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++)
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    SectionCells c = map.cells(SectionPos.asLong(sx, sy, sz));
                    if (c == null) continue;
                    for (int i = 0; i < c.count(); i++) {
                        int x = (sx << 4) + c.x(i), y = (sy << 4) + c.y(i), z = (sz << 4) + c.z(i);
                        if (x < x0 || x > x1 || y < y0 || y > y1 || z < z0 || z > z1) continue;
                        if (n == AiCellsPayload.LIMIT) break;
                        if (n == cover.length) {
                            offsets = java.util.Arrays.copyOf(offsets, n * 6);
                            cover = java.util.Arrays.copyOf(cover, n * 2);
                            flags = java.util.Arrays.copyOf(flags, n * 2);
                        }
                        offsets[3 * n] = (byte) (x - at.getX());
                        offsets[3 * n + 1] = (byte) (y - at.getY());
                        offsets[3 * n + 2] = (byte) (z - at.getZ());
                        cover[n] = c.cover(i);
                        flags[n] = (byte) c.flags(i);
                        n++;
                    }
                }
        return new AiCellsPayload(at, java.util.Arrays.copyOf(offsets, 3 * n), java.util.Arrays.copyOf(cover, n),
                java.util.Arrays.copyOf(flags, n));
    }
}
