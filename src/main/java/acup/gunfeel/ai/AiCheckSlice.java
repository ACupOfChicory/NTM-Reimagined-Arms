package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's opening looked into from round it before going in (AiEntry's slicing): a house of stone bricks seven
 * by seven, its way in two blocks wide in the middle of its south wall, open ground before it; a squad of four south
 * of it, knowing of one foe. Two of another team stand still: the one known of in the house's far corner, the other by
 * the side wall near the wall with the way in, not seen from straight before it, only from round it. Rounds by fours: the
 * slicing on and off, with a grenade each and with none. Counted: of the squad fallen by the way in, the one in the
 * corner known of (or down) before any of the squad came in, slicings.
 */
final class AiCheckSlice {
    static final int X0 = 5600, Z0 = 0, RUN = 1200, ROUNDS = 40, ARMS = 4, SQUAD = 4;
    /**
     * the house: inside x HX0..HX1, z HZ0..HZ1, walls round it, its way in two wide at DX, DX + 1 in the south wall HZ1 + 1
     * (one a block wide in a wall a block thick shows a cone too thin for any by the side wall: seen from round it only
     * along a line through the jambs' corners, grazing)
     */
    static final int HX0 = X0 + 10, HX1 = X0 + 16, HZ0 = Z0 + 4, HZ1 = Z0 + 10, DX = X0 + 13;
    /** a fall this near the way in (outside or in) is one by it */
    static final double BY_WAY_IN = 3.5;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private final boolean entryOn = AiEntry.on, fragOn = AiEntry.frag, sliceOn = AiEntry.slice;
    private boolean started;
    private final int stackMoved0 = AiEntry.stackMoved, stackSeen0 = AiEntry.stackSeen;
    private final int goes0 = AiEntry.goes;
    private final long goTicks0 = AiEntry.goTicks;
    private int round = -1, ticks;
    private final List<Mob> squad = new ArrayList<>(), held = new ArrayList<>();
    private final boolean[] in = new boolean[SQUAD], fell = new boolean[SQUAD];
    private int firstIn, atWayIn, cornerAtIn, slices0, sliceSeen0, slicePoints0, entries0, frags0, knownDown, hiddenDown;
    /** per round: {arm, left, fallen by the way in, the corner one as the first came in (0 down, 1 known of, 2 not, -1 none in), the hidden one down, the known one down, sliced, entries, grenades in, foes seen slicing} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckSlice(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private int arm() {
        return round % ARMS;
    }

    /** arms 0 and 2: the slicing on; 0 and 1: a grenade each */
    private boolean slicing() {
        return arm() % 2 == 0;
    }

    private boolean grenades() {
        return arm() < 2;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = (X0 - 4) >> 4; cx <= (X0 + 40) >> 4; cx++)
                for (int cz = (Z0 - 10) >> 4; cz <= (Z0 + 34) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            if (!m.isAlive()) {
                if (!fell[i]) {
                    fell[i] = true;
                    if (byWayIn(m)) atWayIn++;
                    check.out(String.format(Locale.ROOT, "     slice %d, fell at %d: %.1f %.1f, by %s", round + 1, ticks, m.getX(), m.getZ(), by(m)));
                }
                continue;
            }
            if (!in[i] && m.getX() >= HX0 && m.getX() <= HX1 + 1 && m.getZ() >= HZ0 && m.getZ() <= HZ1 + 1) {
                in[i] = true;
                if (firstIn < 0) {
                    firstIn = ticks;
                    cornerAtIn = corner();
                }
            }
        }
        if (knownDown < 0 && !held.get(0).isAlive()) knownDown = ticks;
        if (hiddenDown < 0 && !held.get(1).isAlive()) hiddenDown = ticks;
        boolean anyOut = squad.stream().anyMatch(Mob::isAlive), anyIn = held.stream().anyMatch(Mob::isAlive);
        if (ticks < RUN && anyOut && anyIn) return false;
        end();
        if (round + 1 < ROUNDS) {
            start(round + 1);
            return false;
        }
        return finish();
    }

    private boolean byWayIn(Mob m) {
        return Math.hypot(m.getX() - DX - 1, m.getZ() - HZ1 - 1.5) <= BY_WAY_IN;
    }

    private String by(Mob m) {
        var e = m.getLastHurtByMob();
        return e == null ? "?" : e == held.get(0) ? "the known one" : e == held.get(1) ? "the one in the corner" : squad.contains(e) ? "one of its own" : e.getType().toShortString();
    }

    /** the one in the corner: 0 down, 1 known of by one of the squad, 2 not */
    private int corner() {
        Mob h = held.get(1);
        if (!h.isAlive()) return 0;
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            TargetMemory.Contact c = b == null || !m.isAlive() ? null : b.memory.contact(h.getId());
            if (c != null && c.known()) return 1;
        }
        return 2;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), brick = Blocks.STONE_BRICKS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0 - 4; x <= X0 + 40; x++)
            for (int z = Z0 - 10; z <= Z0 + 34; z++) {
                set(x, y - 1, z, stone);
                boolean house = x >= HX0 - 1 && x <= HX1 + 1 && z >= HZ0 - 1 && z <= HZ1 + 1;
                boolean wall = house && (x == HX0 - 1 || x == HX1 + 1 || z == HZ0 - 1 || z == HZ1 + 1);
                for (int h = 0; h < 3; h++) set(x, y + h, z, wall ? brick : air);
                set(x, y + 3, z, house ? brick : air);
                set(x, y + 4, z, air);
            }
        for (int x = DX; x <= DX + 1; x++) {
            set(x, y, HZ1 + 1, air);
            set(x, y + 1, HZ1 + 1, air);
        }
        TacticalMap.of(level).want(X0 + 18, y, Z0 + 12, 26, 6);
    }

    private Mob spawn(double x, double z, String team, boolean still) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(AiCheckEntry.GUN)).get(0);
        m.setPos(x, y, z);
        AiTeams.join(level, m, team);
        AiBot b = AiBots.get(m);
        if (b != null) {
            b.gunner.ammo.setInfinite();
            b.grenades = still || !grenades() ? 0 : Math.max(b.grenades, 1);
            b.smokes = 0;
        }
        if (still) {
            var speed = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
        }
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        firstIn = cornerAtIn = knownDown = hiddenDown = -1;
        atWayIn = 0;
        java.util.Arrays.fill(in, false);
        java.util.Arrays.fill(fell, false);
        AiCheck.clearShots(level);
        AiEntry.on = true;
        AiEntry.frag = true;
        AiEntry.slice = sliceOn && slicing();
        AiSquads.on = true;
        AiSquads.forget(level);
        AiEntry.forget(level);
        AiClearing.forget(level);
        build();
        slices0 = AiEntry.slices;
        sliceSeen0 = AiEntry.sliceSeen;
        slicePoints0 = AiEntry.slicePoints;
        entries0 = AiEntry.entries;
        frags0 = AiEntry.frags;
        held.clear();
        squad.clear();
        // the known one in the far corner; the other by the side wall, two in from the wall with the way in: not seen
        // from straight before it, seen from round it (flush in the corner by that wall it is seen from none outside:
        // the first in take that one, their guns on it)
        held.add(spawn(HX1 + 0.5, HZ0 + 0.5, "slice_in" + r, true));
        held.add(spawn(HX0 + 0.5, HZ1 - 1.5, "slice_in" + r, true));
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(DX - 2.5 + i * 1.5, HZ1 + 16.5, "slice_out" + r, false));
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        Mob foe = held.get(0);
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b != null) b.memory.seen(foe.getId(), foe.getX(), foe.getY(), foe.getZ(), now, 0);
        }
    }

    private void end() {
        int left = (int) squad.stream().filter(Mob::isAlive).count();
        int[] res = {arm(), left, atWayIn, cornerAtIn, hiddenDown >= 0 ? 1 : 0, knownDown >= 0 ? 1 : 0, AiEntry.slices - slices0 > 0 ? 1 : 0, AiEntry.entries - entries0,
                AiEntry.frags - frags0, AiEntry.sliceSeen - sliceSeen0};
        results.add(res);
        check.out(String.format(Locale.ROOT, "     slice %d (%s): %d ticks; of the squad %d of %d left, fallen by the way in %d, the first in at %d (the one in the corner then %s);"
                + " the known one down at %d, the one in the corner at %d; slicings %d (places %d, foes seen %d), entries %d, grenades in %d; fates %s",
                round + 1, NAMES[arm()], ticks, left, SQUAD, atWayIn, firstIn, CORNER[cornerAtIn + 1], knownDown, hiddenDown, AiEntry.slices - slices0,
                AiEntry.slicePoints - slicePoints0, res[9], res[7], res[8], AiEntry.fates));
        AiEntry.fates.clear();
        for (Mob m : squad) m.discard();
        for (Mob m : held) m.discard();
        for (var e : level.getEntitiesOfClass(Entity.class, new AABB(X0 - 4, y - 2, Z0 - 10, X0 + 40, y + 20, Z0 + 34),
                e -> e instanceof com.hbm.entity.grenade.EntityGrenadeUniversal || e instanceof net.minecraft.world.entity.item.ItemEntity)) e.discard();
    }

    static final String[] NAMES = {"slicing on, a grenade each", "slicing off, a grenade each", "slicing on, no grenade", "slicing off, no grenade"};
    static final String[] CORNER = {"none in", "down", "known of", "not known of"};

    private boolean finish() {
        check.out(String.format(Locale.ROOT, "     slice: places beside the way in moved out of sight of the room %d, left in it %d", AiEntry.stackMoved - stackMoved0, AiEntry.stackSeen - stackSeen0));
        check.out(String.format(Locale.ROOT, "     slice: entries all gone in %%d, ticks from in to all in their places %%.1f a time", AiEntry.goes - goes0, AiEntry.goes == goes0 ? 0.0 : (AiEntry.goTicks - goTicks0) / (double) (AiEntry.goes - goes0)));
        AiEntry.on = entryOn;
        AiEntry.frag = fragOn;
        AiEntry.slice = sliceOn;
        AiEntry.forget(level);
        AiClearing.forget(level);
        // by arm: rounds, left, fallen by the way in, rounds with one in, the corner one down or known of as the first came
        // in, not known of then, sliced, the hidden one down, the known one down
        int[][] s = new int[ARMS][9];
        for (int[] r : results) {
            int[] a = s[r[0]];
            a[0]++;
            a[1] += r[1];
            a[2] += r[2];
            // (the one in the corner down with none of them gone in: taken from outside, looked in at)
            if (r[3] >= 0 || r[4] == 1) a[3]++;
            if (r[3] == 0 || r[3] == 1 || r[3] < 0 && r[4] == 1) a[4]++;
            if (r[3] == 2) a[5]++;
            a[6] += r[6];
            a[7] += r[4];
            a[8] += r[5];
        }
        for (int k = 0; k < ARMS; k++)
            check.out(String.format(Locale.ROOT, "     slice: %s: %d rounds, of the squad left %d, fallen by the way in %d; rounds gone in or the one in the corner down %d, it down or known of before any came in %d, not known of as they came in %d; sliced %d; the one in the corner down %d, the known one %d",
                    NAMES[k], s[k][0], s[k][1], s[k][2], s[k][3], s[k][4], s[k][5], s[k][6], s[k][7], s[k][8]));
        if (!sliceOn) return true;
        int[] on = s[0], off = s[1], onBare = s[2], offBare = s[3];
        // (the owner's ask: the corner by the wall with the way in looked into from round it, before going in)
        check.check(String.format(Locale.ROOT, "slice: the opening looked into from round it, the one by the side wall down or known of before any goes in, in most rounds (with a grenade %d of %d, with none %d of %d)",
                on[4], on[3], onBare[4], onBare[3]), (on[4] + onBare[4]) * 3 >= (on[3] + onBare[3]) * 2);
        // (no check: most fall by the way in as they stack beside it, before any slicing, to the one by the side wall;
        // beside an opening two wide one is seen from across the room, flush with the wall as one may be)
        check.out(String.format(Locale.ROOT, "     slice: of the squad fallen by the way in, with the slicing %d, without it %d; and as they came in the one by the side wall known of with it %d, without %d",
                on[2] + onBare[2], off[2] + offBare[2], on[4] + onBare[4], off[4] + offBare[4]));
        // (with it off as well as on: the one by the side wall is seen from round the opening, so more with it)
        check.check(String.format(Locale.ROOT, "slice: the one by the side wall down or known of before any goes in, more often with the slicing than without it (with %d, without %d)",
                on[4] + onBare[4], off[4] + offBare[4]), on[4] + onBare[4] > off[4] + offBare[4]);
        return true;
    }
}
