package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's bunker (AiBunker, stage 6 T8): a stone plain, a box of stone bricks with a roof, its front wall two thick with a
 * slit one block high at head height facing south, a doorway in its back wall. One of a team inside at the slit (it does
 * not walk) fires out of it; a squad of four of another, a grenade each, south of it in front of the slit, knows where
 * it is, starting behind a low wall; stone pillars on the flanks for cover. The one inside is hurt by no shot from further off than {@link #REACH}
 * (a slit a head high: from afar it is no target; the bunker is taken from near it, or by a grenade in). Rounds by twos, aiBunker off (before) and on. Counted: the squad's mob
 * ticks in the slit's wedge (the stand's own geometry, the same both ways), who of the squad is left, whether the one
 * inside went down, when and by what, grenades in through the slit, the squad hurt by a blast.
 */
final class AiCheckBunker {
    static final int X0 = 4100, Z0 = 0, SIZE = 64, RUN = 1600, ROUNDS = 8, SQUAD = 4;
    /** the box: walls at x BX0 and BX1, z BZ0 (back) and BZ1..BZ1+1 (the front, two thick); inside x BX0+1..BX1-1, z BZ0+1..BZ1-1 */
    static final int BX0 = 4127, BX1 = 4132, BZ0 = 9, BZ1 = 14, SLIT_X = 4129, DOOR_X = 4130;
    static final double[] START_X = {4123.5, 4127.5, 4131.5, 4135.5};
    static final double START_Z = 44.5;
    static final int START_WALL_Z = 42, START_WALL_X0 = 4121, START_WALL_X1 = 4137;
    static final int[][] PILLARS = {{4118, 24}, {4141, 24}, {4115, 32}, {4144, 32}, {4120, 18}, {4139, 18}};
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    /** the slit's wedge as the stand reads it: its outer face's middle, its half width, its widening a block */
    static final double FACE_X = SLIT_X + 0.5, FACE_Z = BZ1 + 2, HALF = 0.5, TAN = 0.5;
    /** the one inside is hurt by no shot from further off than this, blocks */
    static final double REACH = 6;
    /** the wedge counted this near the slit: further off the base of fire sees into the slit by design, to fire into it */
    static final double CLOSE = 12;
    private static AiCheckBunker counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, inWedge, downAt, ownBlast, fragsBefore, begunBefore, noThrowBefore;
    private String downBy = "-";
    private int slitsRead = -1;
    private boolean command;
    private int commandSlits = -1;
    private float insideHealth;
    private final java.util.Map<Integer, Vec3> nades = new java.util.HashMap<>();
    private Mob inside;
    private final List<Mob> squad = new ArrayList<>();
    private float[] health = new float[SQUAD];
    /** per round: {on, squad left, ticks in the wedge, the one inside down at (-1 never), by a blast, grenades in, throws begun, stepped out and threw none, the squad hurt by a blast, ticks} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckBunker(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    /** whether a place is in the slit's wedge: south of the wall, within the widening lines */
    static boolean wedge(double x, double z) {
        double along = z - FACE_Z;
        return along >= 0 && Math.abs(x - FACE_X) <= HALF + along * TAN + AiBunker.MARGIN;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            if (!listening) net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent.class, e -> {
                AiCheckBunker c = counting;
                if (c == null || e.getEntity() != c.inside) return;
                var direct = e.getSource().getDirectEntity();
                var by = e.getSource().getEntity();
                if (direct != null && direct.getType().toShortString().contains("bullet") && (by == null || by.distanceTo(c.inside) > REACH)) e.setCanceled(true);
            });
            listening = true;
            counting = this;
            start(0);
            return false;
        }
        ticks++;
        // what the AI reads of the box, once
        if (ticks == 40 && round == 0) {
            long cell = BlockPos.asLong(SLIT_X, y, BZ1 - 1);
            AiRooms.Plan plan = AiRooms.of(TacticalMap.of(level), cell);
            List<AiBunker.Slit> slits = plan == null ? List.of() : AiBunker.slits(TacticalMap.of(level), plan);
            check.out(String.format(Locale.ROOT, "     bunker, the box as read: %s; windows %d; slits %s", plan == null ? "no building" : plan.rooms() + " rooms, the cell's " + plan.room(cell),
                    plan == null ? 0 : plan.windows.length, slits));
            slitsRead = slits.size();
        }
        // the grenades on the field: where each burst (its last place), the stands' own log
        java.util.Set<Integer> now = new java.util.HashSet<>();
        for (var e : level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(X0, y - 4, Z0, X0 + SIZE, y + 20, Z0 + SIZE),
                e -> e.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("grenade"))) {
            now.add(e.getId());
            if (!nades.containsKey(e.getId())) check.out(String.format(Locale.ROOT, "     bunker, round %d, tick %d: a grenade %d out at %.1f %.1f %.1f", round + 1, ticks, e.getId(), e.getX(), e.getY(), e.getZ()));
            nades.put(e.getId(), e.position());
        }
        for (var it = nades.entrySet().iterator(); it.hasNext();) {
            var en = it.next();
            if (now.contains(en.getKey())) continue;
            check.out(String.format(Locale.ROOT, "     bunker, round %d, tick %d: a grenade %d gone at %.1f %.1f %.1f", round + 1, ticks, en.getKey(), en.getValue().x, en.getValue().y, en.getValue().z));
            it.remove();
        }
        int left = 0;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            float h = m.isAlive() ? m.getHealth() : 0;
            if (h < health[i]) {
                var by = m.getLastDamageSource();
                if (by == null || by.getDirectEntity() == null || !by.getDirectEntity().getType().toShortString().contains("bullet")) {
                    ownBlast++;
                    check.out(String.format(Locale.ROOT, "     bunker, round %d, tick %d: one of the squad hurt %.1f by %s at %.1f %.1f %.1f%s", round + 1, ticks, health[i] - h,
                            by == null ? "?" : by.type().msgId() + (by.getDirectEntity() == null ? "" : " " + by.getDirectEntity().getType().toShortString()), m.getX(), m.getY(), m.getZ(),
                            AiBots.get(m) != null && AiBunker.throwing(AiBots.get(m)) ? " (the thrower)" : "") + "; the last grenade " + AiBunker.lastBurst);
                }
            }
            if (h <= 0 && health[i] > 0) {
                String what = AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why : "?";
                check.out(String.format(Locale.ROOT, "     bunker, round %d, tick %d: one of the squad down at %.1f %.1f, %s, %s the wedge", round + 1, ticks, m.getX(), m.getZ(), what,
                        wedge(m.getX(), m.getZ()) ? "in" : "out of"));
            }
            health[i] = h;
            if (!m.isAlive()) continue;
            left++;
            // the thrower's step out before the slit to throw is the drill's own, not counted
            if (wedge(m.getX(), m.getZ()) && m.getZ() - FACE_Z <= CLOSE && !(AiBots.get(m) instanceof AiBot b && AiBunker.throwing(b))) inWedge++;
        }
        float ih = inside.isAlive() ? inside.getHealth() : 0;
        if (ih < insideHealth) {
            var by = inside.getLastDamageSource();
            if (by != null && !(by.getDirectEntity() != null && by.getDirectEntity().getType().toShortString().contains("bullet")))
                check.out(String.format(Locale.ROOT, "     bunker, round %d, tick %d: the one inside hurt %.1f of %.1f by %s", round + 1, ticks, insideHealth - ih, insideHealth, by.type().msgId()));
        }
        insideHealth = ih;
        if (downAt < 0 && !inside.isAlive()) {
            downAt = ticks;
            var src = inside.getLastDamageSource();
            downBy = src == null ? "?" : src.type().msgId() + (src.getDirectEntity() == null ? "" : " (" + src.getDirectEntity().getType().toShortString() + ")");
        }
        if (command && ticks == 40) {
            long cell = BlockPos.asLong(Mth.floor(inside.getX()), Mth.floor(inside.getY()), Mth.floor(inside.getZ()));
            AiRooms.Plan plan = AiRooms.of(TacticalMap.of(level), cell);
            commandSlits = plan == null ? 0 : AiBunker.slits(TacticalMap.of(level), plan).size();
        }
        // (the command's round runs till the bunker is read, the one inside down sooner or not)
        if (ticks < RUN && (left > 0 && inside.isAlive() || command && ticks <= 40)) return false;
        boolean blast = downAt >= 0 && !downBy.contains("bullet");
        results.add(new int[] {round % 2, left, inWedge, downAt, blast ? 1 : 0, AiBunker.frags - fragsBefore, AiBunker.begun - begunBefore, AiBunker.noThrow - noThrowBefore, ownBlast, ticks});
        StringBuilder where = new StringBuilder();
        for (Mob m : squad)
            if (m.isAlive() && AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so)
                where.append(String.format(Locale.ROOT, " %.1f %.1f %s/%s;", m.getX(), m.getZ(), so.mode(), so.why));
        check.out(String.format(Locale.ROOT, "     bunker, round %d, at the end:%s the one inside %s by %s; throws lost so far: down %d, too long %d, no way %d, the foe gone %d; last %s", round + 1, where,
                downAt < 0 ? "up" : "down at tick " + downAt, downBy, AiBunker.lost[0], AiBunker.lost[1], AiBunker.lost[2], AiBunker.lost[3], AiBunker.lastSide));
        for (Mob m : squad) m.discard();
        inside.discard();
        squad.clear();
        if (round + 1 >= ROUNDS) {
            if (command) return finish();
            startCommand();
            return false;
        }
        start(round + 1);
        return false;
    }

    /** the last round: the bunker and both sides made by /armsai bunker (AiCommand.bunker), facing north */
    private void startCommand() {
        command = true;
        round = ROUNDS;
        ticks = inWedge = ownBlast = 0;
        downAt = -1;
        downBy = "-";
        AiBunker.on = true;
        AiSquads.forget(level);
        AiEntry.forget(level);
        AiBunker.forget(level);
        AiCheck.clearShots(level);
        build();
        // clear the field of the box and the pillars: the command's bunker stands in it
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) for (int h = 0; h < 6; h++) set(x, y + h, z, air);
        var made = AiCommand.bunker(level, new Vec3(X0 + SIZE / 2 + 0.5, y, Z0 + SIZE - 8.5), net.minecraft.core.Direction.NORTH, SQUAD);
        inside = made[0].get(0);
        squad.addAll(made[1]);
        // as the owner has it: the one inside hurt by any shot
        counting = null;
        insideHealth = inside.getHealth();
        for (int i = 0; i < SQUAD; i++) health[i] = squad.get(i).getHealth();
        fragsBefore = AiBunker.frags;
        begunBefore = AiBunker.begun;
        noThrowBefore = AiBunker.noThrow;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build() {
        // the box of stone bricks: natural stone under a roof is a cave to the AI, not a building
        BlockState stone = Blocks.STONE.defaultBlockState(), brick = Blocks.STONE_BRICKS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                boolean box = x >= BX0 && x <= BX1 && z >= BZ0 && z <= BZ1 + 1;
                boolean wall = box && (x == BX0 || x == BX1 || z == BZ0 || z >= BZ1);
                for (int h = 0; h < 3; h++) set(x, y + h, z, wall ? brick : air);
                set(x, y + 3, z, box ? brick : air);
                for (int h = 4; h < 6; h++) set(x, y + h, z, air);
            }
        // the slit, through the front wall's two blocks at head height; the doorway in the back
        set(SLIT_X, y + 1, BZ1, air);
        set(SLIT_X, y + 1, BZ1 + 1, air);
        set(DOOR_X, y, BZ0, air);
        set(DOOR_X, y + 1, BZ0, air);
        // the squad's start behind a low wall: out in the open the one inside picks it off from afar before it moves
        for (int x = START_WALL_X0; x <= START_WALL_X1; x++) {
            set(x, y, START_WALL_Z, stone);
            set(x, y + 1, START_WALL_Z, stone);
        }
        for (int[] p : PILLARS) {
            set(p[0], y, p[1], stone);
            set(p[0], y + 1, p[1], stone);
        }
        TacticalMap.of(level).want(X0 + SIZE / 2, y, Z0 + SIZE / 2, SIZE / 2 + 4, 6);
    }

    private Mob spawn(double x, double z, String team, float yaw) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(GUN));
        Mob m = made.get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiTeams.join(level, m, team);
        AiBot bot = AiBots.get(m);
        if (bot != null) {
            bot.gunner.ammo.setInfinite();
            if (team.equals("bunker_out")) bot.grenades = 1;
        }
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = inWedge = ownBlast = 0;
        downAt = -1;
        downBy = "-";
        AiBunker.on = r % 2 == 1;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        AiEntry.forget(level);
        AiBunker.forget(level);
        build();
        AiCheck.clearShots(level);
        inside = spawn(SLIT_X + 0.5, BZ1 - 0.5, "bunker_in", 0F);
        insideHealth = inside.getHealth();
        var speed = inside.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(START_X[i], START_Z, "bunker_out", 180F));
        AiBots.refreshTargets(level);
        for (int i = 0; i < SQUAD; i++) health[i] = squad.get(i).getHealth();
        fragsBefore = AiBunker.frags;
        begunBefore = AiBunker.begun;
        noThrowBefore = AiBunker.noThrow;
        long now = level.getGameTime();
        AiBot ib = AiBots.get(inside);
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b != null) b.memory.seen(inside.getId(), inside.getX(), inside.getY(), inside.getZ(), now, 0);
            if (ib != null) ib.memory.seen(m.getId(), m.getX(), m.getY(), m.getZ(), now, 0);
        }
    }

    private boolean finish() {
        AiBunker.on = true;
        counting = null;
        int[] cmd = results.remove(results.size() - 1);
        check.out(String.format(Locale.ROOT, "     bunker, /armsai bunker: of the squad %d of %d left, the one inside %s; grenades in %d, throws begun %d; %d ticks", cmd[1], SQUAD,
                cmd[3] < 0 ? "up" : "down at tick " + cmd[3] + (cmd[4] > 0 ? " by a blast" : ""), cmd[5], cmd[6], cmd[9]));
        // (by the drill: a throw begun at the slit; the one inside shot through it from afar at once is no drill the owner sees)
        check.check(String.format(Locale.ROOT, "bunker: /armsai bunker makes a bunker with one slit, the squad takes it by the drill (slits %d; throws begun %d, %d grenades in, the one inside %s)", commandSlits, cmd[6], cmd[5],
                cmd[3] < 0 ? "up" : "down"), commandSlits == 1 && cmd[3] >= 0 && cmd[6] > 0);
        String[] names = {"aiBunker off (before)", "aiBunker on"};
        double[][] sum = new double[2][10];
        int[] n = new int[2], down = new int[2];
        double[] downTicks = new double[2];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            n[r[0]]++;
            for (int k = 1; k < r.length; k++) sum[r[0]][k] += r[k];
            if (r[3] >= 0) {
                down[r[0]]++;
                downTicks[r[0]] += r[3];
            }
            check.out(String.format(Locale.ROOT, "     bunker, round %d, %s: of the squad %d of %d left, %d mob ticks in the slit's wedge, the one inside %s%s; grenades in through the slit %d,"
                    + " throws begun %d, stepped out and threw none %d; the squad hurt by a blast %d; %d ticks", i + 1, names[r[0]], r[1], SQUAD, r[2], r[3] < 0 ? "up" : "down at tick " + r[3],
                    r[4] > 0 ? " by a blast" : "", r[5], r[6], r[7], r[8], r[9]));
        }
        for (int a = 0; a < 2; a++)
            check.out(String.format(Locale.ROOT, "     bunker, %s, %d rounds: the one inside down in %d (%.0f ticks on average); of the squad %.0f of %d left; %.0f mob ticks in the wedge a round;"
                    + " grenades in %.0f, throws begun %.0f, none thrown %.0f; the squad hurt by a blast %.0f", names[a], n[a], down[a], down[a] == 0 ? 0 : downTicks[a] / down[a], sum[a][1],
                    n[a] * SQUAD, n[a] == 0 ? 0 : sum[a][2] / n[a], sum[a][5], sum[a][6], sum[a][7], sum[a][8]));
        check.check("bunker: all rounds ran", results.size() == ROUNDS);
        check.check(String.format(Locale.ROOT, "bunker: the slit is read as one (%d)", slitsRead), slitsRead == 1);
        check.check(String.format(Locale.ROOT, "bunker: with aiBunker the squad keeps out of the slit's wedge near it, 12 blocks, but the thrower's step out (%.0f mob ticks in it a round, %.0f before; no more)",
                n[1] == 0 ? 0 : sum[1][2] / n[1], n[0] == 0 ? 0 : sum[0][2] / n[0]), n[1] > 0 && sum[1][2] <= sum[0][2]);
        check.check(String.format(Locale.ROOT, "bunker: with aiBunker a grenade goes in through the slit in most rounds (%.0f of %d)", sum[1][5], n[1]), sum[1][5] * 2 > n[1]);
        check.check(String.format(Locale.ROOT, "bunker: with aiBunker the one inside is put down in more rounds, or sooner (%d, %.0f ticks; %d, %.0f before)", down[1],
                down[1] == 0 ? 0 : downTicks[1] / down[1], down[0], down[0] == 0 ? 0 : downTicks[0] / down[0]),
                down[1] > down[0] || down[1] == down[0] && down[1] > 0 && downTicks[1] / down[1] < downTicks[0] / down[0]);
        check.check(String.format(Locale.ROOT, "bunker: with aiBunker no more of the squad lost (%.0f left, %.0f before)", sum[1][1], sum[0][1]), sum[1][1] >= sum[0][1]);
        check.check(String.format(Locale.ROOT, "bunker: a grenade in through the slit hurts none of the squad (%.0f)", sum[1][8]), sum[1][8] == 0);
        return true;
    }
}
