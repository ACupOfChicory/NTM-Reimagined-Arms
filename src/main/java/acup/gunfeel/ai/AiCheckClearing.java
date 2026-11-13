package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's search of a house room after room (AiClearing, stage 4.5d): a house of stone bricks with a roof,
 * four rooms in a square with a doorway between each two next to one another and one way in from outside; one of a
 * team hides in a far corner of one of the three rooms past the first and holds there (it does not walk); four of
 * another team outside know only that it was last seen just inside the way in. Rounds by twos, aiClearing off
 * (before) and on, the room it hides in in turn. Counted: whether and when it was found (seen again or hit) and put
 * down, who of the four is left, the rooms the four have been in after a minute and by the end, the times one of
 * them went back into a room it had left, how many of them stood within 4 blocks of the place it was lost at (the
 * mean over the round), the rooms the sweep checked.
 */
final class AiCheckClearing {
    static final int X0 = 900, Z0 = 4, SIZE = 48, RUN = 1500, MINUTE = 1200, ARMS = 2, ROUNDS = 12, SQUAD = 4, ROOMS = 4;
    /** the house: walls at x 918 and 932, z 6 and 20, and across at x 925 and z 13; the way in in the south wall */
    static final int WX0 = 918, WX1 = 932, WZ0 = 6, WZ1 = 20, MID_X = 925, MID_Z = 13, DOOR_X = 921;
    /** the doorways inside: south-west to south-east, south-west to north-west, south-east to north-east, north-west to north-east */
    static final int[][] DOORWAYS = {{MID_X, 17}, {923, MID_Z}, {928, MID_Z}, {MID_X, 9}};
    /** where it hides: the far corner of the south-east, the north-west, the north-east room */
    static final double[][] HIDES = {{930.5, 18.5}, {919.5, 7.5}, {930.5, 7.5}};
    /** where the four think it is: just inside the way in */
    static final double LOST_X = DOOR_X + 0.5, LOST_Z = 18.5;
    static final double[] START_X = {917.5, 921.5, 925.5, 929.5};
    static final double START_Z = 40.5;
    static final int[][] PILLARS = {{916, 30}, {920, 30}, {926, 30}, {930, 30}, {919, 25}, {927, 25}};
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, found, roomsAtMinute, back, near, sweepsBefore, checksBefore;
    private long startTick;
    private Mob hidden;
    private final List<Mob> squad = new ArrayList<>();
    private final int[] in = new int[SQUAD];
    private final boolean[][] been = new boolean[SQUAD][ROOMS];
    /** per round: {arm, hide, found at, down, squad left, ticks, rooms been in after a minute, by the end, went back, near the place x100, rooms checked, sweeps} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckClearing(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    /** the room of the house a place is in: 0 south-west, 1 south-east, 2 north-west, 3 north-east; -1 none */
    private static int room(double x, double z) {
        if (x <= WX0 + 1 || x >= WX1 || z <= WZ0 + 1 || z >= WZ1 || x >= MID_X && x <= MID_X + 1 || z >= MID_Z && z <= MID_Z + 1) return -1;
        return (x > MID_X ? 1 : 0) + (z < MID_Z ? 2 : 0);
    }

    private int roomsBeen() {
        int n = 0;
        for (int r = 0; r < ROOMS; r++) {
            boolean any = false;
            for (int i = 0; i < SQUAD; i++) any |= been[i][r];
            if (any) n++;
        }
        return n;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        int left = 0;
        long now = level.getGameTime();
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            if (!m.isAlive()) continue;
            left++;
            int r = room(m.getX(), m.getZ());
            if (r >= 0 && r != in[i]) {
                if (been[i][r] && found < 0) back++;
                been[i][r] = true;
                in[i] = r;
            }
            double dx = m.getX() - LOST_X, dz = m.getZ() - LOST_Z;
            if (dx * dx + dz * dz < 16) near++;
            AiBot b = AiBots.get(m);
            TargetMemory.Contact c = b == null ? null : b.memory.contact(hidden.getId());
            if (found < 0 && c != null && c.state == TargetMemory.State.VISIBLE && c.seenTick > startTick + 10) found = ticks;
        }
        if (found < 0 && (!hidden.isAlive() || hidden.getHealth() < hidden.getMaxHealth())) found = ticks;
        if (ticks == MINUTE) roomsAtMinute = roomsBeen();
        if (ticks < RUN && left > 0 && hidden.isAlive()) return false;
        if (ticks < MINUTE) roomsAtMinute = roomsBeen();
        results.add(new int[] {round % ARMS, round / ARMS % HIDES.length, found, hidden.isAlive() ? 0 : 1, left, ticks, roomsAtMinute, roomsBeen(), back, near * 100 / ticks,
                AiClearing.checks - checksBefore, AiClearing.sweeps - sweepsBefore});
        for (Mob m : squad) m.discard();
        hidden.discard();
        squad.clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), brick = Blocks.STONE_BRICKS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                boolean in = x >= WX0 && x <= WX1 && z >= WZ0 && z <= WZ1;
                boolean wall = in && (x == WX0 || x == WX1 || z == WZ0 || z == WZ1 || x == MID_X || z == MID_Z);
                for (int h = 0; h < 3; h++) set(x, y + h, z, wall ? brick : air);
                set(x, y + 3, z, in ? brick : air);
                set(x, y + 4, z, air);
            }
        for (int[] p : PILLARS) {
            set(p[0], y, p[1], stone);
            set(p[0], y + 1, p[1], stone);
        }
        set(DOOR_X, y, WZ1, air);
        set(DOOR_X, y + 1, WZ1, air);
        for (int[] d : DOORWAYS) {
            set(d[0], y, d[1], air);
            set(d[0], y + 1, d[1], air);
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
        if (bot != null) bot.gunner.ammo.setInfinite();
        // the one in the house holds there: it turns, crouches and fires where it stands
        if (team.equals("clear_in")) {
            var speed = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
        }
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        found = -1;
        roomsAtMinute = back = near = 0;
        java.util.Arrays.fill(in, -1);
        for (boolean[] b : been) java.util.Arrays.fill(b, false);
        AiClearing.on = r % ARMS == 1;
        AiEntry.on = true;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        AiEntry.forget(level);
        AiClearing.forget(level);
        build();
        double[] hide = HIDES[r / ARMS % HIDES.length];
        // its back to the corner, facing the middle of its room
        hidden = spawn(hide[0], hide[1], "clear_in", hide[0] > MID_X ? (hide[1] > MID_Z ? 135F : 45F) : -45F);
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(START_X[i], START_Z, "clear_out", 180F));
        AiBots.refreshTargets(level);
        sweepsBefore = AiClearing.sweeps;
        checksBefore = AiClearing.checks;
        startTick = level.getGameTime();
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b != null) b.memory.seen(hidden.getId(), LOST_X, y, LOST_Z, startTick, 0);
        }
    }

    private boolean finish() {
        AiClearing.on = true;
        String[] names = {"aiClearing off (before)", "aiClearing on"}, hides = {"south-east", "north-west", "north-east"};
        double[][] sum = new double[ARMS][12];
        int[] n = new int[ARMS], foundIn = new int[ARMS];
        double[] foundAt = new double[ARMS];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            n[r[0]]++;
            for (int k = 3; k < r.length; k++) sum[r[0]][k] += r[k];
            if (r[2] >= 0) {
                foundIn[r[0]]++;
                foundAt[r[0]] += r[2];
            }
            check.out(String.format(Locale.ROOT, "     clearing, round %d, hid in the %s room, %s: found %s, %s; of the squad %d of %d left, %d ticks; rooms been in %d after a minute, %d by the end;"
                    + " went back into a room %d times; near the place it was lost %.2f of them (mean); rooms checked %d, sweeps %d", i + 1, hides[r[1]], names[r[0]],
                    r[2] < 0 ? "never" : "at tick " + r[2], r[3] == 1 ? "put down" : "not put down", r[4], SQUAD, r[5], r[6], r[7], r[8], r[9] / 100.0, r[10], r[11]));
        }
        for (int a = 0; a < ARMS; a++)
            check.out(String.format(Locale.ROOT, "     clearing, %s, %d rounds: found in %d, at tick %.0f (mean of those); put down in %.0f; of the squad %.0f of %d left; mean %.0f ticks;"
                    + " rooms been in %.1f after a minute, %.1f by the end; went back into a room %.1f times; near the place it was lost %.2f; rooms checked %.1f", names[a], n[a], foundIn[a],
                    foundIn[a] == 0 ? 0 : foundAt[a] / foundIn[a], sum[a][3], sum[a][4], n[a] * SQUAD, sum[a][5] / Math.max(1, n[a]), sum[a][6] / Math.max(1, n[a]),
                    sum[a][7] / Math.max(1, n[a]), sum[a][8] / Math.max(1, n[a]), sum[a][9] / 100 / Math.max(1, n[a]), sum[a][10] / Math.max(1, n[a])));
        check.check("clearing: all rounds ran", results.size() == ROUNDS);
        return true;
    }
}
