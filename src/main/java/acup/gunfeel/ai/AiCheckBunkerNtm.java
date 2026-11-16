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
 * placed, read as a building with no slit (AiBunker) and that nothing in the AI threw. The way down is a steel trapdoor
 * over a ladder sixty deep: ROUNDS rounds on the one bunker, the way down a shaft (AiHatch) off and on by turns;
 * checked that with it the squad gets under the ground and loses no more than without (the hub taken is counted).
 */
final class AiCheckBunkerNtm {
    static final int X0 = 4300, Z0 = 0, REACH = 40, RUN = 2400, SQUAD = 4, INSIDE = 2;
    /** rounds on the one bunker, the way down a shaft (AiHatch) off and on by turns */
    static final int ROUNDS = 10;
    /** a soldier's legs in the shaft every few ticks (looking into a climb) */
    private static final boolean TRACE = false;
    /** the stone's top (grass), where NTM's hatch comes out in a world of hills */
    static final int TOP = 63;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started, placed;
    private int ticks, faults0, firstDown = -1, downs, round, roundAt;
    /** per round, off ones and on ones: got under the ground, the hub taken, of the squad left, rounds */
    private final int[][] sums = new int[2][4];
    private final boolean hatchOn = AiHatch.on;
    private long hub = Long.MIN_VALUE, hatch = Long.MIN_VALUE;
    private int ground;
    private final int[] hatch0 = {AiHatch.begun, AiHatch.frags, AiHatch.noFrag, AiHatch.deepNoFrag, AiHatch.down};
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
        int rt = ticks - roundAt;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            if (!m.isAlive() || under[i]) continue;
            if (m.getY() < ground - 2) {
                under[i] = true;
                downs++;
                if (firstDown < 0) firstDown = rt;
            }
        }
        if (TRACE && round == 1 && rt % 10 == 0 && rt < 1600)
            for (Mob m : squad)
                if (m.isAlive() && m.getY() < ground - 1 && m.getY() > ground - 58)
                    check.out(String.format(Locale.ROOT, "     bunkerntm, tick %d: in the shaft %d at %.2f %.2f %.2f v %.2f %.2f %.2f climb %s hcol %s ground %s pose %s crouch %s nav %s legs %s", rt, m.getId(), m.getX(), m.getY(),
                            m.getZ(), m.getDeltaMovement().x, m.getDeltaMovement().y, m.getDeltaMovement().z, m.onClimbable(), m.horizontalCollision, m.onGround(), m.getPose(), m.isShiftKeyDown(),
                            m.getNavigation().isDone() ? "done" : "on " + m.getNavigation().getPath().getNextNodePos().toShortString(), AiBots.get(m) instanceof AiBot b ? b.mover.state() : ""));
        if (rt % 400 == 0 && rt > 0) check.out(String.format(Locale.ROOT, "     bunkerntm %d, tick %d:%s", round, rt, where()));
        boolean anyIn = inside.stream().anyMatch(Mob::isAlive), anyOut = squad.stream().anyMatch(Mob::isAlive);
        if (rt < RUN && anyIn && anyOut) return false;
        endRound(rt);
        if (++round < ROUNDS) {
            spawn();
            return false;
        }
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
        StringBuilder odd = new StringBuilder();
        for (int yy = BlockPos.getY(foot); yy <= BlockPos.getY(top); yy++) {
            BlockState b = level.getBlockState(new BlockPos(BlockPos.getX(top), yy, BlockPos.getZ(top)));
            if (!(b.getBlock() instanceof LadderBlock) && !BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath().contains("ladder")) odd.append(' ').append(yy).append('=').append(b);
        }
        check.out("     bunkerntm: in the shaft but its ladder:" + odd);
        BlockState hs = level.getBlockState(new BlockPos(BlockPos.getX(top), BlockPos.getY(top) + 1, BlockPos.getZ(top)));
        check.out("     bunkerntm: the hatch " + hs + " class " + hs.getBlock().getClass().getName() + (hs.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock t
                ? " a TrapDoorBlock, by hand " + AiDoors.byHand(hs) + " type " + typeOf(t) : "") + " tag trapdoors " + hs.is(net.minecraft.tags.BlockTags.TRAPDOORS) + " voxel " + BlockClasses.describe(BlockClasses.classify(hs))
                + "; a ladder " + BlockClasses.describe(BlockClasses.classify(level.getBlockState(new BlockPos(BlockPos.getX(top), BlockPos.getY(top), BlockPos.getZ(top))))));
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

    private static String typeOf(net.minecraft.world.level.block.TrapDoorBlock t) {
        var bt = ((acup.gunfeel.mixin.mc.TrapDoorAccess) t).gunfeel$type();
        return bt.name() + " byHand " + bt.canOpenByHand();
    }

    private void spawn() {
        roundAt = ticks;
        AiHatch.on = hatchOn && round % 2 == 1;
        double hx = BlockPos.getX(hub) + 0.5, hy = BlockPos.getY(hub), hz = BlockPos.getZ(hub) + 0.5;
        for (int i = 0; i < INSIDE; i++) {
            Mob m = AiSpawn.spawn(level, new Vec3(hx, hy, hz), AiSpawn.Kind.DUMMY, 1, null).get(0);
            m.setPos(hx + (i == 0 ? 0 : 1), hy, hz);
            AiTeams.join(level, m, "ntmbunker_in" + round);
            // they hold the hub (up the ladder they went at the squad, and the fight was in the shaft)
            var speed = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
            inside.add(m);
        }
        double sx = BlockPos.getX(hatch) + 12.5, sz = BlockPos.getZ(hatch) + 0.5;
        int sy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(sx), (int) Math.floor(sz));
        for (int i = 0; i < SQUAD; i++) {
            Mob m = AiSpawn.spawn(level, new Vec3(sx, sy, sz + i * 2 - 3), AiSpawn.Kind.DUMMY, 1, null).get(0);
            AiTeams.join(level, m, "ntmbunker_out" + round);
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
        if (round == 0) check.out(String.format(Locale.ROOT, "     bunkerntm: %d in the hub at %.1f %.1f %.1f, %d of the squad on the ground at %.1f %d %.1f", INSIDE, hx, hy, hz, SQUAD, sx, sy, sz));
    }

    private String where() {
        StringBuilder where = new StringBuilder();
        for (Mob m : squad)
            where.append(String.format(Locale.ROOT, " %s %.1f %.1f %.1f %s;", m.isAlive() ? "up" : "down", m.getX(), m.getY(), m.getZ(),
                    AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why + (b.mover.goal() == Long.MIN_VALUE ? "" : " to " + BlockPos.of(b.mover.goal()).toShortString())
                            + (b.mover.failed() ? " failed" : "") + (b.mover.noWay() ? " no way" : "") + " [" + b.mover.state() + "]"
                            + " contacts " + b.memory.contacts().size() : ""));
        where.append(" | op ").append(AiHatch.describe(level, "ntmbunker_out" + round)).append(" | in:");
        for (Mob m : inside)
            where.append(String.format(Locale.ROOT, " %s %.1f %.1f %.1f %s;", m.isAlive() ? "up" : "down", m.getX(), m.getY(), m.getZ(),
                    AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why + " contacts " + b.memory.contacts().size() : ""));
        where.append(String.format(Locale.ROOT, " | sweeps %d checks %d", AiClearing.sweeps, AiClearing.checks));
        return where.toString();
    }

    /** a round over: counted, its mobs taken away, the hatch shut again */
    private void endRound(int rt) {
        long left = squad.stream().filter(Mob::isAlive).count(), in = inside.stream().filter(Mob::isAlive).count();
        int k = AiHatch.on ? 1 : 0;
        sums[k][0] += downs;
        sums[k][1] += in == 0 ? 1 : 0;
        sums[k][2] += (int) left;
        sums[k][3]++;
        check.out(String.format(Locale.ROOT, "     bunkerntm %d (%s): %d ticks; of the squad %d of %d left, %d got under the ground (the first at tick %d); in the hub %d of %d left;%s", round,
                AiHatch.on ? "on" : "off", rt, left, squad.size(), downs, firstDown, in, inside.size(), where()));
        for (Mob m : squad) m.discard();
        for (Mob m : inside) m.discard();
        squad.clear();
        inside.clear();
        for (int i = 0; i < SQUAD; i++) under[i] = false;
        downs = 0;
        firstDown = -1;
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new net.minecraft.world.phys.AABB(X0 - REACH, level.getMinY(), Z0 - REACH, X0 + REACH, TOP + 20, Z0 + REACH),
                e -> e instanceof com.hbm.entity.grenade.EntityGrenadeUniversal || e instanceof net.minecraft.world.entity.item.ItemEntity)) e.discard();
        BlockPos h = BlockPos.of(hatch).above();
        BlockState hs = level.getBlockState(h);
        if (hs.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock) level.setBlock(h, hs.setValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN, false), 3);
        AiHatch.forget(level);
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
        AiHatch.on = hatchOn;
        check.out(String.format(Locale.ROOT, "     bunkerntm: rounds off: got under %d, the hub taken %d, of the squad left %d, of %d rounds; on: got under %d, the hub taken %d, of the squad left %d, of %d rounds",
                sums[0][0], sums[0][1], sums[0][2], sums[0][3], sums[1][0], sums[1][1], sums[1][2], sums[1][3]));
        check.out(String.format(Locale.ROOT, "     bunkerntm: ways down a shaft begun %d, grenades down %d, none %d (too deep %d), down the ladder %d; none found: not under %d, no cell %d, no way %d, no shaft %d", AiHatch.begun - hatch0[0],
                AiHatch.frags - hatch0[1], AiHatch.noFrag - hatch0[2], AiHatch.deepNoFrag - hatch0[3], AiHatch.down - hatch0[4], AiHatch.none[0], AiHatch.none[1], AiHatch.none[2], AiHatch.none[3]));
        if (hatchOn && sums[1][3] > 0) {
            check.check(String.format(Locale.ROOT, "bunkerntm: with the way down a shaft the squad gets under the ground (%d of %d, before %d)", sums[1][0], SQUAD * sums[1][3], sums[0][0]),
                    sums[1][0] * 4 >= SQUAD * sums[1][3] * 3);
            // (the hub taken is counted, not checked: two holding the shaft's foot shoot most going down it either way)
            check.check(String.format(Locale.ROOT, "bunkerntm: with it no more of the squad lost (%d left, %d before)", sums[1][2], sums[0][2]), sums[1][2] >= sums[0][2]);
        }
        check.check("bunkerntm: nothing in the AI threw on it (" + (AiRooms.faults - faults0) + ")", AiRooms.faults == faults0);
        for (Mob m : squad) m.discard();
        for (Mob m : inside) m.discard();
        return true;
    }
}
