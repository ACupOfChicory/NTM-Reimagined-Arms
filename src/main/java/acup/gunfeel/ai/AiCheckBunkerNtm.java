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
 * checked that none hangs halfway down the shaft and that some get to its foot in every round (how many, down by the
 * drill, the hub taken and those lost are counted). Last a round up: the squad in the hub, a foe on the ground, checked
 * that all of it comes up the shaft through the steel hatch.
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
    /** AiHatch.down as the round began; the on rounds some went down the ladder in */
    private int hatchDown0, ladderRounds;
    /** AiHatch's held, smoked down, held off as the round began */
    private int[] held0 = new int[3];
    /** the way down on with smoke, without: rounds, of the squad left, the hub taken, held, smoke down, held off, some at the foot */
    private final int[][] arms = new int[2][8];

    /** an on round with a smoke grenade each: the first of every two on rounds (1, 5, 9) */
    private boolean smokeRound() {
        return round % 4 == 1;
    }
    /** off rounds and on some of the squad got to the shaft's foot in */
    private final int[] footRounds = new int[2];
    /** of the squad left in the shaft halfway down as a round ran out (hung on the ladder), off rounds and on */
    private final int[] hung = new int[2];
    /** hung: in the shaft as the round runs out, and no more than a block from where it was this many ticks before */
    static final int STILL = 100;
    private final double[] yLate = new double[SQUAD];
    /** the stands: of the squad still on its way down the shaft as a round ran out (one after another, it takes long) */
    private int climbingAtEnd;
    /**
     * the shaft's foot cleared, off rounds and on: ticks of the squad in its column within 2*SQUAD of its floor once one
     * got there, and of the squad there as a round ran out (still a while: none coming down) hanging on the ladder or
     * standing on the floor under one of its own in the shaft
     */
    private final int[] columnTicks = new int[2], columnAtEnd = new int[2];
    /** the ladder's lowest rung (the shaft's floor) */
    private long ladderFoot = Long.MIN_VALUE;
    /** per round, off ones and on ones: got under the ground, the hub taken, of the squad left, rounds */
    private final int[][] sums = new int[2][4];
    private final boolean hatchOn = AiHatch.on;
    private long hub = Long.MIN_VALUE, hatch = Long.MIN_VALUE;
    private int ground;
    private final int[] hatch0 = {AiHatch.begun, AiHatch.frags, AiHatch.noFrag, AiHatch.deepNoFrag, AiHatch.down};
    private final List<Mob> squad = new ArrayList<>(), inside = new ArrayList<>();
    private final boolean[] under = new boolean[SQUAD];
    /** fallen this round, and of those in the shaft (under the ground, over the foot's stack): fallen going down */
    private final boolean[] fell = new boolean[SQUAD];
    private int fellInShaft;

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
        // (where each was a while before the round runs out: one still climbing down then is on its way, not hung)
        if (rt == RUN - STILL) for (int i = 0; i < squad.size() && i < yLate.length; i++) yLate[i] = squad.get(i).getY();
        for (int i = 0; i < squad.size() && i < SQUAD; i++) {
            Mob m = squad.get(i);
            if (!m.isAlive() && !fell[i]) {
                fell[i] = true;
                if (m.getY() < ground - 2 && m.getY() >= BlockPos.getY(hub) + 2) fellInShaft++;
            }
        }
        if (firstDown >= 0)
            for (Mob m : squad)
                if (inColumn(m)) columnTicks[AiHatch.on ? 1 : 0]++;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            if (!m.isAlive() || under[i]) continue;
            // (at the shaft's foot: halfway down it is no getting under, they hung there)
            if (m.getY() < BlockPos.getY(hub) + 2) {
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
        if (round == ROUNDS) return upTick(rt);
        boolean anyIn = inside.stream().anyMatch(Mob::isAlive), anyOut = squad.stream().anyMatch(Mob::isAlive);
        if (rt < RUN && anyIn && anyOut) return false;
        endRound(rt);
        if (++round < ROUNDS) {
            spawn();
            return false;
        }
        spawnUp();
        return false;
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
        ladderFoot = foot;
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

    /**
     * The cells round the hub one of the squad stands free in (a floor under it, nothing in its box), nearest first:
     * the hub is cramped, a place a block aside of it may be in a wall (one put there choked to death, the stands)
     */
    private List<Vec3> freeByHub() {
        List<Vec3> out = new ArrayList<>();
        int bx = BlockPos.getX(hub), by = BlockPos.getY(hub), bz = BlockPos.getZ(hub);
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos feet = new BlockPos(bx + dx, by + dy, bz + dz);
                    if (level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) continue;
                    // (not in the ladder's column: the next of its own would hang on the rungs over it)
                    if (level.getBlockState(feet).is(net.minecraft.tags.BlockTags.CLIMBABLE)) continue;
                    double x = feet.getX() + 0.5, z = feet.getZ() + 0.5;
                    if (!level.noCollision(new net.minecraft.world.phys.AABB(x - 0.3, feet.getY(), z - 0.3, x + 0.3, feet.getY() + 1.95, z + 0.3))) continue;
                    out.add(new Vec3(x, feet.getY(), z));
                }
        Vec3 c = new Vec3(bx + 0.5, by, bz + 0.5);
        out.sort(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(c)));
        return out;
    }

    /** one of a team put at the k-th free cell by the hub (or the hub, none free); null if none could be made there */
    private @org.jspecify.annotations.Nullable Mob atHub(List<Vec3> free, int k, String team) {
        Vec3 at = free.isEmpty() ? new Vec3(BlockPos.getX(hub) + 0.5, BlockPos.getY(hub), BlockPos.getZ(hub) + 0.5) : free.get(Math.min(k, free.size() - 1));
        List<Mob> made = AiSpawn.spawn(level, at, AiSpawn.Kind.DUMMY, 1, null);
        if (made.isEmpty()) {
            check.out(String.format(Locale.ROOT, "     bunkerntm: none could be made by the hub at %.1f %.1f %.1f (%d free cells)", at.x, at.y, at.z, free.size()));
            return null;
        }
        Mob m = made.get(0);
        if (!free.isEmpty()) m.setPos(at.x, at.y, at.z);
        AiTeams.join(level, m, team);
        return m;
    }

    private void spawn() {
        roundAt = ticks;
        hatchDown0 = AiHatch.down;
        held0 = new int[] {AiHatch.held, AiHatch.smokedDown, AiHatch.contained};
        AiHatch.on = hatchOn && round % 2 == 1;
        List<Vec3> free = freeByHub();
        for (int i = 0; i < INSIDE; i++) {
            Mob m = atHub(free, i, "ntmbunker_in" + round);
            if (m == null) continue;
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
            if (b != null) {
                b.grenades = Math.max(b.grenades, 1);
                // the way down on: a smoke grenade each in some rounds, none in the others (the foot held: smoke down, or hold)
                b.smokes = smokeRound() ? 1 : 0;
            }
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
        double hx = BlockPos.getX(hub) + 0.5, hy = BlockPos.getY(hub), hz = BlockPos.getZ(hub) + 0.5;
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

    /** alive in the shaft's column at its foot or over it on the ladder within 2*SQUAD of its floor */
    private boolean inColumn(Mob m) {
        return m.isAlive() && ladderFoot != Long.MIN_VALUE && m.getBlockX() == BlockPos.getX(ladderFoot) && m.getBlockZ() == BlockPos.getZ(ladderFoot)
                && m.getY() < BlockPos.getY(ladderFoot) + 2 * SQUAD;
    }

    /** hanging on the ladder over the shaft's floor, or standing on it with one of its own over it in the shaft (in the way down) */
    private boolean inTheWay(Mob m) {
        if (!m.onGround()) return true;
        for (Mob o : squad)
            if (o != m && o.isAlive() && o.getBlockX() == m.getBlockX() && o.getBlockZ() == m.getBlockZ() && o.getY() > m.getY() + 1 && o.getY() < ground - 1) return true;
        return false;
    }

    /** rounds some of the squad got to the shaft's foot in, and of them those nobody fell in (the stands: a fight a block apart, 1600 ticks of nobody firing) */
    private int atFoot, noLoss;

    /** a round over: counted, its mobs taken away, the hatch shut again */
    private void endRound(int rt) {
        long left = squad.stream().filter(Mob::isAlive).count(), in = inside.stream().filter(Mob::isAlive).count();
        int k = AiHatch.on ? 1 : 0;
        sums[k][0] += downs;
        sums[k][1] += in == 0 ? 1 : 0;
        sums[k][2] += (int) left;
        sums[k][3]++;
        if (rt >= RUN)
            for (int i = 0; i < squad.size(); i++) {
                Mob m = squad.get(i);
                // (over the squad stacked at the foot: waiting on the ladder over its own holding the foot is no hanging)
                if (m.isAlive() && m.getY() >= BlockPos.getY(hub) + 2 * SQUAD && m.getY() < ground - 2) {
                    if (i < yLate.length && Math.abs(m.getY() - yLate[i]) > 1) climbingAtEnd++;
                    else hung[k]++;
                }
                if (inColumn(m) && i < yLate.length && Math.abs(m.getY() - yLate[i]) <= 1 && inTheWay(m)) columnAtEnd[k]++;
            }
        if (k == 1 && AiHatch.down > hatchDown0) ladderRounds++;
        if (downs > 0) footRounds[k]++;
        // the fight at the foot, close: a round some got down in and nobody fell on either side
        if (downs > 0) {
            atFoot++;
            if (left == squad.size() && in == inside.size()) noLoss++;
        }
        if (k == 1) {
            int[] a = arms[smokeRound() ? 0 : 1];
            a[0]++;
            a[1] += (int) left;
            a[2] += in == 0 ? 1 : 0;
            a[3] += AiHatch.held - held0[0];
            a[4] += AiHatch.smokedDown - held0[1];
            a[5] += AiHatch.contained - held0[2];
            a[6] += downs > 0 ? 1 : 0;
            a[7] += fellInShaft;
            check.out(String.format(Locale.ROOT, "     bunkerntm %d: %s; the foot held %d (why %s), smoke down %d (first %d all told), held off %d; fallen in the shaft %d; the smoker's ticks %s; ways round looked for on a map not built yet %d, none %s", round, smokeRound() ? "a smoke each" : "no smoke",
                    AiHatch.held - held0[0], java.util.Arrays.toString(AiHatch.heldWhy), AiHatch.smokedDown - held0[1], AiHatch.smokeFirst, AiHatch.contained - held0[2], fellInShaft, java.util.Arrays.toString(AiHatch.smokerTicks), AiHatch.bypassMissing, java.util.Arrays.toString(AiHatch.bypassNone)));
        }
        check.out(String.format(Locale.ROOT, "     bunkerntm %d (%s): %d ticks; of the squad %d of %d left, %d got to the shaft's foot (the first at tick %d); in the hub %d of %d left;%s", round,
                AiHatch.on ? "on" : "off", rt, left, squad.size(), downs, firstDown, in, inside.size(), where()));
        for (Mob m : squad) m.discard();
        for (Mob m : inside) m.discard();
        squad.clear();
        inside.clear();
        for (int i = 0; i < SQUAD; i++) under[i] = false;
        java.util.Arrays.fill(fell, false);
        fellInShaft = 0;
        downs = 0;
        firstDown = -1;
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new net.minecraft.world.phys.AABB(X0 - REACH, level.getMinY(), Z0 - REACH, X0 + REACH, TOP + 20, Z0 + REACH),
                e -> e instanceof com.hbm.entity.grenade.EntityGrenadeUniversal || e instanceof net.minecraft.world.entity.item.ItemEntity
                        // (bullets still in flight from the round's last shots: one would hit the next round's squad put at the hub)
                        || e instanceof com.hbm.entity.projectile.EntityBulletBaseMK4
                        || e instanceof net.minecraft.world.entity.projectile.Projectile)) e.discard();
        BlockPos h = BlockPos.of(hatch).above();
        BlockState hs = level.getBlockState(h);
        if (hs.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock) level.setBlock(h, hs.setValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN, false), 3);
        AiHatch.forget(level);
    }

    /** the last round: the squad up the shaft from the hub, through the steel hatch, to a foe on the ground; its ticks */
    static final int UP_RUN = 1600;
    private Mob upFoe;
    private int upAt = -1;
    private final boolean[] upped = new boolean[SQUAD];

    private void spawnUp() {
        AiHatch.wayUpFails.clear();
        roundAt = ticks;
        // (on: the foe on the ground is a still target, no grenade comes down; the way up a shaft is AiHatch's)
        AiHatch.on = hatchOn;
        AiHatch.forget(level);
        double fx = BlockPos.getX(hatch) + 10.5, fz = BlockPos.getZ(hatch) + 0.5;
        int fy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(fx), (int) Math.floor(fz));
        // a still target on the ground (no AI of its own: it does not come down to them)
        upFoe = net.minecraft.world.entity.EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        upFoe.setNoAi(true);
        upFoe.addTag("armsai_target");
        upFoe.setInvulnerable(true);
        upFoe.setPos(fx, fy, fz);
        level.addFreshEntity(upFoe);
        AiTeams.join(level, upFoe, "ntmbunker_inup");
        List<Vec3> free = freeByHub();
        for (int i = 0; i < SQUAD; i++) {
            Mob m = atHub(free, i, "ntmbunker_outup");
            if (m == null) continue;
            check.out(String.format(Locale.ROOT, "       up trace: #%d put at %.2f %.2f %.2f, health %.1f, in a wall %s, on rungs %s", m.getId(), m.getX(), m.getY(), m.getZ(), m.getHealth(), m.isInWall(),
                    m.onClimbable()));
            upHealth[squad.size()] = m.getHealth();
            squad.add(m);
            upped[i] = false;
            upDownTold[i] = false;
        }
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        for (Mob m : squad) if (AiBots.get(m) instanceof AiBot b) b.memory.seen(upFoe.getId(), upFoe.getX(), upFoe.getY(), upFoe.getZ(), now, 0);
    }

    /** the trace of the way up: each of the squad every UP_TRACE ticks, and how one fell (its fall and what hurt it) */
    static final int UP_TRACE = 50;
    private final float[] upHealth = new float[SQUAD];
    private final float[] upFall = new float[SQUAD];
    private final boolean[] upDownTold = new boolean[SQUAD];

    private String upOne(Mob m) {
        if (!(AiBots.get(m) instanceof AiBot b) || !(b.brain instanceof AiSoldier so)) return "-";
        TargetMemory.Contact c = upFoe == null ? null : b.memory.contact(upFoe.getId());
        long now = level.getGameTime();
        return String.format(Locale.ROOT, "#%d %.1f %.1f %.1f %s/%s climb %s hunt %s foe %s", m.getId(), m.getX(), m.getY(), m.getZ(), so.mode(), so.why, m.onClimbable(),
                upFoe == null ? "-" : AiSquads.mayHunt(b, upFoe.getId()), c == null ? "none" : c.state + " lost " + (now - c.lostTick) + " ago");
    }

    private boolean upTick(int rt) {
        int up = 0;
        if (rt % UP_TRACE == 0) {
            StringBuilder sb = new StringBuilder();
            for (Mob m : squad) if (m.isAlive()) sb.append(' ').append(upOne(m)).append(';');
            check.out(String.format(Locale.ROOT, "       up trace, tick %d:%s", rt, sb));
        }
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            float h = m.isAlive() ? m.getHealth() : 0;
            if (h < upHealth[i] && !upDownTold[i]) {
                var by = m.getLastDamageSource();
                check.out(String.format(Locale.ROOT, "       up trace, tick %d: #%d hurt %.1f (left %.1f) at %.2f %.2f %.2f by %s (%s), its fall %.1f, climbing %s", rt, m.getId(), upHealth[i] - h, h,
                        m.getX(), m.getY(), m.getZ(), by == null ? "?" : by.type().msgId(), by == null || by.getEntity() == null ? "-" : by.getEntity().getType().toShortString(), upFall[i],
                        m.onClimbable()));
                if (h <= 0) {
                    upDownTold[i] = true;
                    check.out(String.format(Locale.ROOT, "       up trace, tick %d: #%d down; direct %s, source at %s", rt, m.getId(), by == null || by.getDirectEntity() == null ? "-" : by.getDirectEntity().getType().toShortString(),
                            by == null || by.getSourcePosition() == null ? "-" : by.getSourcePosition().toString()));
                }
            }
            upHealth[i] = h;
            upFall[i] = (float) m.fallDistance;
            if (!upped[i] && m.isAlive() && m.getY() >= ground - 0.1) upped[i] = true;
            if (upped[i]) up++;
        }
        if (up == SQUAD && upAt < 0) upAt = rt;
        if (rt % 400 == 0 && rt > 0) check.out(String.format(Locale.ROOT, "     bunkerntm up, tick %d:%s", rt, where()));
        if (rt < UP_RUN && up < SQUAD) return false;
        check.out(String.format(Locale.ROOT, "     bunkerntm up: %d of %d up the shaft through the steel hatch by tick %d (all at %d);%s", up, SQUAD, rt, upAt, where()));
        check.out(String.format(Locale.ROOT, "     bunkerntm up: walks to the way up that failed %d%s", AiHatch.wayUpFails.size(), AiHatch.wayUpFails.isEmpty() ? "" : "; " + String.join("; ", AiHatch.wayUpFails)));
        check.check(String.format(Locale.ROOT, "bunkerntm: the squad comes up the sixty-deep shaft through NTM's steel hatch, all of it (%d of %d in %d ticks)", up, SQUAD, UP_RUN), up == SQUAD);
        for (Mob m : squad) m.discard();
        squad.clear();
        upFoe.discard();
        AiHatch.on = hatchOn;
        return finish();
    }

    private boolean finish() {
        StringBuilder where = new StringBuilder();
        for (Mob m : squad)
            where.append(String.format(Locale.ROOT, " %s %.1f %.1f %.1f %s;", m.isAlive() ? "up" : "down", m.getX(), m.getY(), m.getZ(),
                    AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why + " goal " + (b.mover.goal() == Long.MIN_VALUE ? "-"
                            : BlockPos.getX(b.mover.goal()) + " " + BlockPos.getY(b.mover.goal()) + " " + BlockPos.getZ(b.mover.goal())) + (b.mover.failed() ? " failed" : "") + (b.mover.noWay() ? " no way" : "")
                            + " contacts " + b.memory.contacts().size() : ""));
        check.out(String.format(Locale.ROOT, "     bunkerntm: %d ticks; of the squad %d of %d left, %d got to the shaft's foot (the first at tick %d); in the hub %d of %d left; the squad at the end:%s",
                ticks, squad.stream().filter(Mob::isAlive).count(), squad.size(), downs, firstDown, inside.stream().filter(Mob::isAlive).count(), inside.size(), where));
        AiHatch.on = hatchOn;
        check.out(String.format(Locale.ROOT, "     bunkerntm: at the foot, close: of %d rounds some got down in, nobody fell in %d", atFoot, noLoss));
        check.out(String.format(Locale.ROOT, "     bunkerntm: rounds off: got under %d, the hub taken %d, of the squad left %d, of %d rounds; on: got under %d, the hub taken %d, of the squad left %d, of %d rounds",
                sums[0][0], sums[0][1], sums[0][2], sums[0][3], sums[1][0], sums[1][1], sums[1][2], sums[1][3]));
        check.out(String.format(Locale.ROOT, "     bunkerntm: ways down a shaft begun %d, grenades down %d, none %d (too deep %d), down the ladder %d; none found: not under %d, no cell %d, no way %d, no shaft %d", AiHatch.begun - hatch0[0],
                AiHatch.frags - hatch0[1], AiHatch.noFrag - hatch0[2], AiHatch.deepNoFrag - hatch0[3], AiHatch.down - hatch0[4], AiHatch.none[0], AiHatch.none[1], AiHatch.none[2], AiHatch.none[3]));
        if (hatchOn && sums[1][3] > 0) {
            // (got to the foot and lost are counted, not checked: off it goes down the ladder too, and either way the
            // two holding the foot shoot some going down; checked is what was wrong: hanging halfway, no drill down)
            check.out(String.format(Locale.ROOT, "     bunkerntm: with the way down a shaft to the shaft's foot %d of %d (before %d); of the squad left %d (%d before)", sums[1][0], SQUAD * sums[1][3], sums[0][0],
                    sums[1][2], sums[0][2]));
            check.check(String.format(Locale.ROOT, "bunkerntm: none hangs halfway down the shaft as a round runs out (%d, %d before; on its way down still %d)", hung[1], hung[0], climbingAtEnd), hung[0] == 0 && hung[1] == 0);
            // (down, they go on off the ladder: one standing in the column kept the next hanging on the ladder over it)
            check.out(String.format(Locale.ROOT, "     bunkerntm: in the shaft's column at its foot once one got there, ticks of the squad: on %d, off %d", columnTicks[1], columnTicks[0]));
            check.check(String.format(Locale.ROOT, "bunkerntm: the shaft's foot cleared: none of the squad stands in its column at the foot or hangs over it as a round runs out (on %d, off %d)",
                    columnAtEnd[1], columnAtEnd[0]), columnAtEnd[0] == 0 && columnAtEnd[1] == 0);
            // (down by the drill itself counted: one shot off the ladder, or slow under fire, goes on as it may, uncounted)
            check.out(String.format(Locale.ROOT, "     bunkerntm: with it down the ladder by the drill in %d of %d rounds", ladderRounds, sums[1][3]));
            // (with the way down off, under fire from the hub up the shaft they come off the ladder at its top, X1309: so
            // the bunker placed with its hub under the shaft; the drill goes down whatever it sees)
            int[] sm = arms[0], no = arms[1];
            check.out(String.format(Locale.ROOT, "     bunkerntm: the way down on, with smoke: %d rounds, of the squad left %d, the hub taken %d, held %d, smoke down %d, held off %d, some at the foot %d;"
                    + " without: %d rounds, left %d, the hub taken %d, held %d, held off %d; off: %d rounds, left %d, the hub taken %d", sm[0], sm[1], sm[2], sm[3], sm[4], sm[5], sm[6],
                    no[0], no[1], no[2], no[3], no[5], sums[0][3], sums[0][2], sums[0][1]));
            // (the foot held: with smoke they go down under it, so some get there; without, none more goes down)
            check.check(String.format(Locale.ROOT, "bunkerntm: with the way down a shaft and smoke some get to its foot in every round (%d of %d)", sm[6], sm[0]), sm[6] == sm[0]);
            // (sixty deep, too deep for a frag: the smoke goes down first)
            check.check(String.format(Locale.ROOT, "bunkerntm: smoke goes down the shaft in every round with smoke (%d in %d rounds)", sm[4], sm[0]), sm[4] >= sm[0]);
            // (fallen going down the shaft: one up at the hatch shot by a foe come up the ladder is no going down)
            check.check(String.format(Locale.ROOT, "bunkerntm: the foot held with no smoke, none more goes down: fallen in the shaft %d in %d rounds, at most one a round (of the squad lost %d all told)",
                    no[7], no[0], SQUAD * no[0] - no[1]), no[7] <= no[0]);
            // (the hub taken is counted, not checked: two holding the shaft's foot shoot most going down it either way)
        }
        check.check("bunkerntm: nothing in the AI threw on it (" + (AiRooms.faults - faults0) + ")", AiRooms.faults == faults0);
        for (Mob m : squad) m.discard();
        for (Mob m : inside) m.discard();
        return true;
    }
}
