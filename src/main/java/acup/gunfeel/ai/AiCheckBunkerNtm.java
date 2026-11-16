package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's NTM bunker (stage 6 T8, a case to look at, not a drill): NTM's own underground bunker (structure
 * hbm:bunker, its pieces branching corridors and rooms off a hub under a hatch) placed by the game's /place structure;
 * what the AI reads of it (the building at the hub, its rooms, corridors, ways, ladders, slits); two of a team in the hub,
 * a squad of four of another on the ground over it knowing where they are. Counted: who of the squad got down under the
 * ground and when, who is left of either side, what each of the squad was doing at the end. Checked only that it was
 * placed, read as a building with no slit (AiBunker) and that nothing in the AI threw. As of T8 the squad does not get in:
 * the way down is a steel trapdoor over a ladder sixty deep, and the AI opens no trapdoor.
 */
final class AiCheckBunkerNtm {
    static final int X0 = 4300, Z0 = 0, REACH = 40, RUN = 2400, SQUAD = 4, INSIDE = 2;
    /** the stone's top (grass), where NTM's hatch comes out in a world of hills */
    static final int TOP = 63;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started, placed;
    private int ticks, faults0, firstDown = -1, downs;
    private long hub = Long.MIN_VALUE, hatch = Long.MIN_VALUE;
    private int ground;
    private final List<Mob> squad = new ArrayList<>(), inside = new ArrayList<>();
    private final boolean[] under = new boolean[SQUAD];

    AiCheckBunkerNtm(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = (X0 - REACH) >> 4; cx <= (X0 + REACH) >> 4; cx++)
                for (int cz = (Z0 - REACH) >> 4; cz <= (Z0 + REACH) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            faults0 = AiRooms.faults;
            // the stand's world is flat and low: stone up to where NTM puts the bunker's hatch, its rooms dug out of it
            // as in a world of hills (grass on top)
            var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            var grass = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int x = X0 - REACH; x <= X0 + REACH; x++)
                for (int z = Z0 - REACH; z <= Z0 + REACH; z++)
                    for (int yy = level.getMinY() + 1; yy <= TOP; yy++) level.setBlock(p.set(x, yy, z), yy == TOP ? grass : stone, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            return false;
        }
        if (ticks++ == 0) {
            var server = level.getServer();
            String cmd = String.format(Locale.ROOT, "place structure hbm:bunker %d %d %d", X0, y, Z0);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level), cmd);
            check.out("     bunkerntm: /" + cmd);
            return false;
        }
        if (!placed) {
            if (ticks == 40) find();
            if (ticks < 140) return false;
            placed = true;
            if (hub != Long.MIN_VALUE) read();
            if (hub == Long.MIN_VALUE) return finish();
            spawn();
            return false;
        }
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            if (!m.isAlive() || under[i]) continue;
            if (m.getY() < ground - 2) {
                under[i] = true;
                downs++;
                if (firstDown < 0) firstDown = ticks;
                check.out(String.format(Locale.ROOT, "     bunkerntm, tick %d: one of the squad under the ground at %.1f %.1f %.1f", ticks, m.getX(), m.getY(), m.getZ()));
            }
        }
        boolean anyIn = inside.stream().anyMatch(Mob::isAlive), anyOut = squad.stream().anyMatch(Mob::isAlive);
        if (ticks < RUN && anyIn && anyOut) return false;
        return finish();
    }

    /** the structure's blocks round the place by kind, its ladders' top (the hatch) and foot (the hub); the map asked for there */
    private void find() {
        Map<String, Integer> kinds = new TreeMap<>();
        long top = Long.MIN_VALUE, foot = Long.MIN_VALUE;
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, X0, Z0);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = X0 - REACH; x <= X0 + REACH; x++)
            for (int z = Z0 - REACH; z <= Z0 + REACH; z++)
                for (int yy = level.getMinY(); yy < y + 20; yy++) {
                    BlockState st = level.getBlockState(p.set(x, yy, z));
                    var id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
                    if (!id.getNamespace().equals("hbm")) {
                        if (!(st.getBlock() instanceof LadderBlock)) continue;
                    } else kinds.merge(id.getPath(), 1, Integer::sum);
                    lowest = Math.min(lowest, yy);
                    highest = Math.max(highest, yy);
                    if (st.getBlock() instanceof LadderBlock || id.getPath().contains("ladder")) {
                        kinds.merge("(ladder) " + id, 1, Integer::sum);
                        if (top == Long.MIN_VALUE || yy > BlockPos.getY(top)) top = BlockPos.asLong(x, yy, z);
                        if (foot == Long.MIN_VALUE || yy < BlockPos.getY(foot)) foot = BlockPos.asLong(x, yy, z);
                    }
                }
        check.out(String.format(Locale.ROOT, "     bunkerntm: the ground at %d; NTM blocks from y %d to %d: %s", ground, lowest, highest, kinds));
        check.check("bunkerntm: the bunker is placed (NTM blocks round the place)", !kinds.isEmpty());
        if (foot == Long.MIN_VALUE) {
            check.out("     bunkerntm: no ladder found");
            return;
        }
        hatch = top;
        StringBuilder col = new StringBuilder();
        for (int yy = BlockPos.getY(top) - 1; yy <= BlockPos.getY(top) + 3; yy++)
            col.append(' ').append(yy).append('=').append(BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(BlockPos.getX(top), yy, BlockPos.getZ(top))).getBlock()).getPath());
        check.out("     bunkerntm: over the ladder:" + col);
        check.out(String.format(Locale.ROOT, "     bunkerntm: a ladder from %d %d %d down to %d %d %d", BlockPos.getX(top), BlockPos.getY(top), BlockPos.getZ(top), BlockPos.getX(foot),
                BlockPos.getY(foot), BlockPos.getZ(foot)));
        // the hub: a cell to stand on at the ladder's foot
        TacticalMap map = TacticalMap.of(level);
        map.want(X0, BlockPos.getY(foot), Z0, REACH, 8);
        int fx = BlockPos.getX(foot), fy = BlockPos.getY(foot), fz = BlockPos.getZ(foot);
        for (int d = 0; d <= 3 && hub == Long.MIN_VALUE; d++)
            for (int dx = -d; dx <= d && hub == Long.MIN_VALUE; dx++)
                for (int dz = -d; dz <= d && hub == Long.MIN_VALUE; dz++)
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos c = new BlockPos(fx + dx, fy + dy, fz + dz);
                        if (level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir() && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), net.minecraft.core.Direction.UP)) {
                            hub = c.asLong();
                            break;
                        }
                    }
        if (hub == Long.MIN_VALUE) check.out("     bunkerntm: no cell to stand on at the ladder's foot");
    }

    /** what the AI reads at the hub, the map built there by now */
    private void read() {
        TacticalMap map = TacticalMap.of(level);
        AiRooms.Plan plan = AiRooms.of(map, hub);
        if (plan == null) {
            check.out("     bunkerntm: the hub is no building to the AI (not under a roof, or the wild's)");
            check.check("bunkerntm: the hub is read as a building", false);
            return;
        }
        int corridors = 0;
        for (int i = 0; i < plan.rooms(); i++) if (plan.kind[i] == AiRooms.CORRIDOR) corridors++;
        int doors = 0;
        for (AiRooms.Way w : plan.ways) if (w.door) doors++;
        List<AiBunker.Slit> slits = AiBunker.slits(map, plan);
        check.out(String.format(Locale.ROOT, "     bunkerntm: the building at the hub: %d rooms (%d corridors), %d ways (%d with a door), %d ladders' cells, %d stairs' cells, %d windows, %d slits%s; read cut %s",
                plan.rooms(), corridors, plan.ways.length, doors, plan.ladders.length, plan.stairs.length, plan.windows.length, slits.size(), slits.isEmpty() ? "" : " " + slits, plan.cut));
        check.check("bunkerntm: the hub is read as a building", true);
        check.check("bunkerntm: under the ground no slit (its holes look into other rooms): " + slits.size(), slits.isEmpty());
    }

    private void spawn() {
        double hx = BlockPos.getX(hub) + 0.5, hy = BlockPos.getY(hub), hz = BlockPos.getZ(hub) + 0.5;
        for (int i = 0; i < INSIDE; i++) {
            Mob m = AiSpawn.spawn(level, new Vec3(hx, hy, hz), AiSpawn.Kind.DUMMY, 1, null).get(0);
            m.setPos(hx + (i == 0 ? 0 : 1), hy, hz);
            AiTeams.join(level, m, "ntmbunker_in");
            inside.add(m);
        }
        double sx = BlockPos.getX(hatch) + 12.5, sz = BlockPos.getZ(hatch) + 0.5;
        int sy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(sx), (int) Math.floor(sz));
        for (int i = 0; i < SQUAD; i++) {
            Mob m = AiSpawn.spawn(level, new Vec3(sx, sy, sz + i * 2 - 3), AiSpawn.Kind.DUMMY, 1, null).get(0);
            AiTeams.join(level, m, "ntmbunker_out");
            AiBot b = AiBots.get(m);
            if (b != null) b.grenades = Math.max(b.grenades, 1);
            squad.add(m);
        }
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        for (Mob m : squad)
            for (Mob o : inside) {
                AiBot b = AiBots.get(m), ob = AiBots.get(o);
                if (b != null) b.memory.seen(o.getId(), o.getX(), o.getY(), o.getZ(), now, 0);
                if (ob != null) ob.memory.seen(m.getId(), m.getX(), m.getY(), m.getZ(), now, 0);
            }
        check.out(String.format(Locale.ROOT, "     bunkerntm: %d in the hub at %.1f %.1f %.1f, %d of the squad on the ground at %.1f %d %.1f", INSIDE, hx, hy, hz, SQUAD, sx, sy, sz));
    }

    private boolean finish() {
        StringBuilder where = new StringBuilder();
        for (Mob m : squad)
            where.append(String.format(Locale.ROOT, " %s %.1f %.1f %.1f %s;", m.isAlive() ? "up" : "down", m.getX(), m.getY(), m.getZ(),
                    AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why + " goal " + (b.mover.goal() == Long.MIN_VALUE ? "-"
                            : BlockPos.getX(b.mover.goal()) + " " + BlockPos.getY(b.mover.goal()) + " " + BlockPos.getZ(b.mover.goal())) + (b.mover.failed() ? " failed" : "") + (b.mover.noWay() ? " no way" : "")
                            + " contacts " + b.memory.contacts().size() : ""));
        check.out(String.format(Locale.ROOT, "     bunkerntm: %d ticks; of the squad %d of %d left, %d got under the ground (the first at tick %d); in the hub %d of %d left; the squad at the end:%s",
                ticks, squad.stream().filter(Mob::isAlive).count(), squad.size(), downs, firstDown, inside.stream().filter(Mob::isAlive).count(), inside.size(), where));
        check.check("bunkerntm: nothing in the AI threw on it (" + (AiRooms.faults - faults0) + ")", AiRooms.faults == faults0);
        for (Mob m : squad) m.discard();
        for (Mob m : inside) m.discard();
        return true;
    }
}
