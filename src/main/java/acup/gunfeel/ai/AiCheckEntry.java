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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's way into a building (AiEntry, stage 4.5c): two of one team in a house of stone bricks with a roof and one way
 * in, four of another outside who know where they are. Rounds by twos, aiEntry off (before) and on; the first half
 * with an open doorway, the second with a shut wooden door. Counted: who is left, when the house was cleared, how
 * many got in and when the first did, those of the four that fell in the doorway or just before it, grenades thrown,
 * entries begun. Those in the house stand in its corners by the wall with the way in, not seen from outside, and hold it
 * (they do not walk).
 */
final class AiCheckEntry {
    static final int X0 = 840, Z0 = 4, SIZE = 48, RUN = 1200, ROUNDS = 12, SQUAD = 4, INSIDE = 2;
    /** the house: walls at x 859 and 869, z 10 and 18; the way in in the south wall */
    static final int WX0 = 859, WX1 = 869, WZ0 = 10, WZ1 = 18, DOOR_X = 864;
    /** in the corners by the wall with the way in: not seen from outside */
    static final double[][] DEFENDERS = {{860.5, 16.5}, {868.5, 16.5}};
    static final double[] START_X = {858.5, 862.5, 866.5, 870.5};
    static final double START_Z = 40.5;
    /** low blocks in the house and pillars before it */
    static final int[][] LOW = {{862, 13}, {866, 13}}, PILLARS = {{857, 30}, {861, 30}, {867, 30}, {871, 30}, {860, 24}, {868, 24}};
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, firstIn, entriesBefore;
    private final List<Mob> squad = new ArrayList<>(), inside = new ArrayList<>();
    private final boolean[] gotIn = new boolean[SQUAD], atDoor = new boolean[SQUAD], fellAtDoor = new boolean[SQUAD];
    private final int[] threw = new int[SQUAD];
    private final java.util.Set<Integer> noted = new java.util.HashSet<>();
    /** per round: {aiEntry, door, defenders left, squad left, ticks, got in, first in at, fell at the doorway, grenades, entries} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckEntry(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
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
        int left = 0, foes = 0;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            boolean door = Math.abs(m.getX() - (DOOR_X + 0.5)) < 1.5 && m.getZ() > WZ1 - 0.5 && m.getZ() < WZ1 + 3;
            if (!m.isAlive()) {
                if (atDoor[i]) fellAtDoor[i] = true;
                atDoor[i] = false;
                continue;
            }
            left++;
            atDoor[i] = door;
            if (AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so) threw[i] = so.thrown;
            if (!gotIn[i] && m.getX() > WX0 + 1 && m.getX() < WX1 && m.getZ() > WZ0 + 1 && m.getZ() < WZ1) {
                gotIn[i] = true;
                if (firstIn < 0) firstIn = ticks;
            }
        }
        for (Mob m : inside) {
            if (m.isAlive()) foes++;
            else if (noted.add(m.getId())) {
                var src = m.getLastDamageSource();
                check.out(String.format(Locale.ROOT, "     entry, round %d, tick %d: one in the house down at %.1f %.1f %.1f, by %s (%s) from %s", round + 1, ticks, m.getX(), m.getY() - y, m.getZ(),
                        src == null ? "?" : src.type().msgId(), src == null || src.getDirectEntity() == null ? "-" : src.getDirectEntity().getType().toShortString(),
                        src == null || src.getEntity() == null ? "-" : String.format(Locale.ROOT, "%.1f %.1f", src.getEntity().getX(), src.getEntity().getZ())));
            }
        }
        if (ticks < RUN && left > 0 && foes > 0) return false;
        int in = 0, fell = 0, thrown = 0;
        for (int i = 0; i < SQUAD; i++) {
            if (gotIn[i]) in++;
            if (fellAtDoor[i]) fell++;
        }
        for (int t : threw) thrown += t;
        results.add(new int[] {round % 2, round >= ROUNDS / 2 ? 1 : 0, foes, left, ticks, in, firstIn, fell, thrown, AiEntry.entries - entriesBefore});
        for (Mob m : squad) m.discard();
        for (Mob m : inside) m.discard();
        squad.clear();
        inside.clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build(boolean door) {
        BlockState stone = Blocks.STONE.defaultBlockState(), brick = Blocks.STONE_BRICKS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                boolean in = x >= WX0 && x <= WX1 && z >= WZ0 && z <= WZ1, wall = in && (x == WX0 || x == WX1 || z == WZ0 || z == WZ1);
                for (int h = 0; h < 3; h++) set(x, y + h, z, wall ? brick : air);
                set(x, y + 3, z, in ? brick : air);
                set(x, y + 4, z, air);
            }
        for (int[] p : LOW) set(p[0], y, p[1], stone);
        for (int[] p : PILLARS) {
            set(p[0], y, p[1], stone);
            set(p[0], y + 1, p[1], stone);
        }
        if (door) {
            BlockState d = Blocks.OAK_DOOR.defaultBlockState();
            set(DOOR_X, y, WZ1, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            set(DOOR_X, y + 1, WZ1, d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        } else {
            set(DOOR_X, y, WZ1, air);
            set(DOOR_X, y + 1, WZ1, air);
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
        // those in the house hold it: they turn, crouch and fire where they stand
        if (team.equals("entry_in")) {
            var speed = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
        }
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        firstIn = -1;
        java.util.Arrays.fill(gotIn, false);
        java.util.Arrays.fill(atDoor, false);
        java.util.Arrays.fill(fellAtDoor, false);
        AiEntry.on = r % 2 == 1;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        AiEntry.forget(level);
        build(r >= ROUNDS / 2);
        for (double[] p : DEFENDERS) inside.add(spawn(p[0], p[1], "entry_in", 0F));
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(START_X[i], START_Z, "entry_out", 180F));
        AiBots.refreshTargets(level);
        java.util.Arrays.fill(threw, 0);
        entriesBefore = AiEntry.entries;
        long now = level.getGameTime();
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b == null) continue;
            for (Mob f : inside) {
                b.memory.seen(f.getId(), f.getX(), f.getY(), f.getZ(), now, 0);
                AiBot fb = AiBots.get(f);
                if (fb != null) fb.memory.seen(m.getId(), m.getX(), m.getY(), m.getZ(), now, 0);
            }
        }
    }

    private boolean finish() {
        AiEntry.on = true;
        String[] names = {"aiEntry off (before)", "aiEntry on"}, ways = {"an open doorway", "a shut door"};
        double[][] sum = new double[2][10];
        int[] n = new int[2], cleared = new int[2];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            n[r[0]]++;
            for (int k = 2; k < r.length; k++) sum[r[0]][k] += r[k];
            if (r[2] == 0) cleared[r[0]]++;
            check.out(String.format(Locale.ROOT, "     entry, round %d, %s, %s: in the house %d of %d left, of the squad %d of %d, %d ticks; got in %d, the first %s;"
                    + " fell in the doorway %d; grenades %d; entries %d", i + 1, ways[r[1]], names[r[0]], r[2], INSIDE, r[3], SQUAD, r[4], r[5],
                    r[6] < 0 ? "never" : "at tick " + r[6], r[7], r[8], r[9]));
        }
        for (int on = 0; on < 2; on++)
            check.out(String.format(Locale.ROOT, "     entry, %s, %d rounds: the house cleared in %d; left in the house %.0f of %d, of the squad %.0f of %d; mean %.0f ticks;"
                    + " got in %.0f, fell in the doorway %.0f; grenades %.0f; entries %.0f", names[on], n[on], cleared[on], sum[on][2],
                    n[on] * INSIDE, sum[on][3], n[on] * SQUAD, n[on] == 0 ? 0 : sum[on][4] / n[on], sum[on][5], sum[on][7], sum[on][8], sum[on][9]));
        check.check("entry: all rounds ran", results.size() == ROUNDS);
        return true;
    }
}
