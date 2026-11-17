package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's room after a foe known of (AiBots.fought): a house of stone bricks, a way in a block wide in its south
 * wall; a squad of four south of it, knowing of one foe. Two of another team stand still: the one known of, the other
 * in the house's corner by the wall with the way in, not seen from outside. Rounds by fours: the known one in the
 * house, in line with the way in (shot from outside); the same with the room gone through after it off; the known one
 * out in the open by the house; the known one in a big house (no grenade in after it); the small house with a villager
 * in its far corner (no grenade in after it, the villager spared). Counted: who of the squad came into the house, the
 * hidden one down, grenades in, entries.
 */
final class AiCheckFought {
    static final int X0 = 4800, Z0 = 0, RUN = 1600, ROUNDS = 25, ARMS = 5, SQUAD = 4;
    /** the house: inside x HX0..HX1, z HZ0..HZ1 (small: HX1, HZ0 by the arm), walls round it, its way in at DX in the south wall HZ1 + 1 */
    static final int HX0 = X0 + 10, HX1_SMALL = X0 + 14, HX1_BIG = X0 + 20, HZ0_SMALL = Z0 + 6, HZ0_BIG = Z0 - 2, HZ1 = Z0 + 10, DX = X0 + 12;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private final boolean entryOn = AiEntry.on, fragOn = AiEntry.frag;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> squad = new ArrayList<>(), held = new ArrayList<>();
    /** the villager in the house (arm 4), else null */
    private @org.jspecify.annotations.Nullable Mob villager;
    private final boolean[] inHouse = new boolean[SQUAD];
    private final String[] cameIn = new String[SQUAD];
    private int yields0, entries0, frags0, fragsFought0, noFrag0, places0, firstIn, knownDown, hiddenDown, foughtTicks;
    /** of the squad, one given a place fought in this round (AiBot.fought) */
    private boolean squadPlace;
    /** per round: {arm, of the squad left, in the house, the hidden one down (1), entries, grenades in, no grenade (fought), places, the known one down} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckFought(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private int arm() {
        return round % ARMS;
    }

    /** the big house stands on its own plot this far east: the tactical map of one house built over another lags behind */
    private int ox() {
        return arm() == 3 ? 50 : 0;
    }

    private int hx0() {
        return HX0 + ox();
    }

    private int dx() {
        return DX + ox();
    }

    private int hx1() {
        return (arm() == 3 ? HX1_BIG : HX1_SMALL) + ox();
    }

    private int hz0() {
        return arm() == 3 ? HZ0_BIG : HZ0_SMALL;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = (X0 - 4) >> 4; cx <= (X0 + 90) >> 4; cx++)
                for (int cz = (Z0 - 10) >> 4; cz <= (Z0 + 34) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            if (!m.isAlive() || inHouse[i]) continue;
            if (m.getX() >= hx0() && m.getX() <= hx1() + 1 && m.getZ() >= hz0() && m.getZ() <= HZ1 + 1) {
                inHouse[i] = true;
                if (firstIn < 0) firstIn = ticks;
                AiBot b = AiBots.get(m);
                cameIn[i] = ticks + " " + (b != null && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why : "?");
            }
        }
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b == null || !m.isAlive()) continue;
            // (a place given for the known one: the hidden one fallen in the house later is a foe known of there too)
            if (b.fought != null && b.fought.id == held.get(0).getId()) squadPlace = true;
            if (b.brain instanceof AiSoldier so && so.why == AiThoughts.Why.FOUGHT) foughtTicks++;
        }
        if (knownDown < 0 && !held.get(0).isAlive()) knownDown = ticks;
        if (hiddenDown < 0 && !held.get(1).isAlive()) hiddenDown = ticks;
        if (ticks % 400 == 0) check.out(String.format(Locale.ROOT, "     fought %d, tick %d:%s", round + 1, ticks, where()));
        boolean anyOut = squad.stream().anyMatch(Mob::isAlive), anyIn = held.stream().anyMatch(Mob::isAlive);
        if (ticks < RUN && anyOut && anyIn) return false;
        end();
        if (round + 1 < ROUNDS) {
            start(round + 1);
            return false;
        }
        return finish();
    }

    private String where() {
        StringBuilder w = new StringBuilder();
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            long g = b == null ? Long.MIN_VALUE : b.mover.goal();
            w.append(String.format(Locale.ROOT, " %s %.1f %.1f %s%s;", m.isAlive() ? "up" : "down", m.getX(), m.getZ(),
                    b != null && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why : "",
                    g == Long.MIN_VALUE ? "" : String.format(Locale.ROOT, " to %d %d %d%s%s", BlockPos.getX(g), BlockPos.getY(g) - y, BlockPos.getZ(g),
                            b.mover.arrived() ? " there" : "", b.mover.failed() ? " failed" : "")));
        }
        return w.toString();
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), brick = Blocks.STONE_BRICKS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0 - 4 + ox(); x <= X0 + 40 + ox(); x++)
            for (int z = Z0 - 10; z <= Z0 + 34; z++) {
                set(x, y - 1, z, stone);
                boolean in = x >= hx0() - 1 && x <= hx1() + 1 && z >= hz0() - 1 && z <= HZ1 + 1;
                boolean wall = in && (x == hx0() - 1 || x == hx1() + 1 || z == hz0() - 1 || z == HZ1 + 1);
                for (int h = 0; h < 3; h++) set(x, y + h, z, wall ? brick : air);
                set(x, y + 3, z, in ? brick : air);
                set(x, y + 4, z, air);
            }
        set(dx(), y, HZ1 + 1, air);
        set(dx(), y + 1, HZ1 + 1, air);
        TacticalMap.of(level).want(X0 + 18 + ox(), y, Z0 + 12, 26, 6);
    }

    private Mob spawn(double x, double z, String team, boolean still) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(AiCheckEntry.GUN)).get(0);
        m.setPos(x, y, z);
        AiTeams.join(level, m, team);
        AiBot b = AiBots.get(m);
        if (b != null) {
            b.gunner.ammo.setInfinite();
            b.grenades = still ? 0 : Math.max(b.grenades, 1);
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
        firstIn = knownDown = hiddenDown = -1;
        foughtTicks = 0;
        squadPlace = false;
        java.util.Arrays.fill(inHouse, false);
        java.util.Arrays.fill(cameIn, "-");
        AiCheck.clearShots(level);
        AiEntry.on = true;
        AiEntry.frag = true;
        AiBots.foughtOn = arm() != 1;
        AiSquads.on = true;
        AiSquads.forget(level);
        AiEntry.forget(level);
        AiClearing.forget(level);
        build();
        entries0 = AiEntry.entries;
        frags0 = AiEntry.frags;
        fragsFought0 = AiEntry.fragsFought;
        if (r == 0) yields0 = AiSoldier.sweepYields;
        noFrag0 = AiEntry.noFragFought;
        places0 = AiBots.foughtPlaces;
        held.clear();
        squad.clear();
        // the known one: in line with the way in, three in; out in the open by the house (arm 2)
        held.add(arm() == 2 ? spawn(hx0() - 4.5, HZ1 + 5.5, "fought_in" + r, true) : spawn(dx() + 0.5, HZ1 - 2.5, "fought_in" + r, true));
        // the hidden one: the corner by the wall with the way in, not seen from outside
        held.add(spawn(hx0() + 0.5, HZ1 + 0.5, "fought_in" + r, true));
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(dx() - 2.5 + i * 1.5, HZ1 + 16.5, "fought_out" + r, false));
        // a villager in the house's far corner, still (arm 4): one the grenade after a fight is kept from
        villager = null;
        if (arm() == 4) {
            Mob v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
            if (v != null) {
                v.setPos(hx1() + 0.5, y, hz0() + 0.5);
                v.setNoAi(true);
                level.addFreshEntity(v);
                villager = v;
            }
        }
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        Mob foe = held.get(0);
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b != null) b.memory.seen(foe.getId(), foe.getX(), foe.getY(), foe.getZ(), now, 0);
        }
    }

    private void end() {
        int left = (int) squad.stream().filter(Mob::isAlive).count(), in = 0;
        for (boolean b : inHouse) if (b) in++;
        int[] res = {arm(), left, in, held.get(1).isAlive() ? 0 : 1, AiEntry.entries - entries0, AiEntry.frags - frags0, AiEntry.noFragFought - noFrag0,
                AiBots.foughtPlaces - places0, held.get(0).isAlive() ? 0 : 1, squadPlace ? 1 : 0, foughtTicks > 0 ? 1 : 0, villager != null && villager.isAlive() ? 1 : 0, AiEntry.fragsFought - fragsFought0,
                // (going through it, the known one down a while before the round's end, none in the house by then: held up)
                foughtTicks > 0 && knownDown >= 0 && ticks > knownDown + LATE && (firstIn < 0 || firstIn > knownDown + LATE) ? 1 : 0};
        results.add(res);
        check.out(String.format(Locale.ROOT, "     fought %d (%s): %d ticks; of the squad %d of %d left, in the house %d %s (the first at %d); the known one down at %d, the hidden one at %d;"
                + " places %d (the squad's %b), its ticks going through %d, entries %d, grenades in %d, no grenade (fought) %d; fates %s;%s",
                round + 1, NAMES[arm()], ticks, left, SQUAD, in, java.util.Arrays.toString(cameIn), firstIn, knownDown, hiddenDown, res[7], squadPlace, foughtTicks, res[4], res[5], res[6], AiEntry.fates, where()));
        AiEntry.fates.clear();
        for (Mob m : squad) m.discard();
        for (Mob m : held) m.discard();
        if (villager != null) villager.discard();
        for (var e : level.getEntitiesOfClass(Entity.class, new AABB(X0 - 4 + ox(), y - 2, Z0 - 10, X0 + 40 + ox(), y + 20, Z0 + 34),
                e -> e instanceof com.hbm.entity.grenade.EntityGrenadeUniversal || e instanceof net.minecraft.world.entity.item.ItemEntity)) e.discard();
    }

    /** a squad going through a house after the known one is in it this long after it fell at most, ticks */
    static final int LATE = 600;

    static final String[] NAMES = {"on, in the house", "off, in the house", "on, in the open", "on, a big house", "on, a villager in the house"};

    private boolean finish() {
        AiEntry.on = entryOn;
        AiEntry.frag = fragOn;
        AiBots.foughtOn = true;
        AiEntry.forget(level);
        AiClearing.forget(level);
        // by arm: rounds, the known one down, rounds with one in the house, the hidden one down, rounds with a grenade in,
        // with no grenade (fought), places, the squad's place, going through, the villager alive
        int[][] s = new int[ARMS][11];
        int late = 0;
        for (int[] r : results) {
            if (r[1] > 0) late += r[13];
            int[] a = s[r[0]];
            a[0]++;
            // (the villager alive: every round counts)
            a[9] += r[11];
            // (rounds the squad was all put down in tell nothing of what it does after)
            if (r[1] == 0) continue;
            a[1] += r[8];
            a[2] += r[2] > 0 ? 1 : 0;
            a[3] += r[3];
            a[4] += r[5] > 0 ? 1 : 0;
            a[5] += r[6] > 0 ? 1 : 0;
            a[6] += r[7] > 0 ? 1 : 0;
            a[7] += r[9];
            a[8] += r[10];
            a[10] += r[12] > 0 ? 1 : 0;
        }
        for (int k = 0; k < ARMS; k++)
            check.out(String.format(Locale.ROOT, "     fought: %s: %d rounds, the known one down %d, rounds with one in the house %d, the hidden one down %d, rounds with a grenade in %d, with no grenade (fought) %d, with a place to go through %d",
                    NAMES[k], s[k][0], s[k][1], s[k][2], s[k][3], s[k][4], s[k][5], s[k][6]));
        int[] on = s[0], off = s[1], open = s[2], big = s[3], vil = s[4];
        // (the rounds counted are those the known one was put down in: the rest tell nothing of after it)
        // (the grenade may go in, not must: one of the squad by the way in, in its blast, holds it back)
        // (in by the old drill too: the known one down by an entry's grenade, those of it go on in after it)
        check.check(String.format(Locale.ROOT, "fought: the known one down in the house, the squad goes into the house after it in every such round (%d of %d; going through it %d), the hidden one down in more than half (%d)",
                on[2], on[1], on[8], on[3]), on[1] > 0 && on[2] >= on[1] && on[3] * 2 > on[1]);
        check.out(String.format(Locale.ROOT, "     fought: a small house fought in, rounds with a grenade in %d of %d", on[4], on[1]));
        // (one may still look in after a sound: AiSoldier's INVESTIGATE, not a going through)
        check.check(String.format(Locale.ROOT, "fought: with it off, no house gone through after the known one, the hidden one down in fewer rounds than with it on (going through %d of %d; the hidden one down %d, on %d)",
                off[8], off[1], off[3], on[3]), off[1] > 0 && off[7] == 0 && off[8] == 0 && off[3] < on[3]);
        // (the house is cover by it: in it so is no going through it; the squad given no place, none going through one)
        check.check(String.format(Locale.ROOT, "fought: the known one down in the open, the squad given no house to go through (rounds with one %d, going through %d of %d; in the house as cover %d)", open[7], open[8],
                open[1], open[2]), open[1] > 0 && open[7] == 0 && open[8] == 0);
        // (an entry under way already as the known one fell, or after the hidden one heard, is the old drill's: its
        // grenade is no fought room's, counted apart)
        check.check(String.format(Locale.ROOT, "fought: a big house fought in, gone into after it, no grenade in as a room fought in (in %d, going through %d, grenades as fought in %d, by the old drill %d, of %d)", big[2], big[8],
                big[10], big[4] - big[10], big[1]), big[1] > 0 && big[2] >= big[1] && big[10] == 0);
        // (a villager in the room: the grenade a room fought in takes is kept from it; they go in all the same)
        // (nor by the drill after a foe known alive: the villager is spared by any entry's grenade)
        check.check(String.format(Locale.ROOT, "fought: a villager in the house fought in, gone into after it, no grenade in at all, the villager alive in every round (in %d, going through %d, grenades as fought in %d, by the old drill %d, the villager alive %d of %d)",
                vil[2], vil[8], vil[10], vil[4] - vil[10], vil[9], vil[0]), vil[1] > 0 && vil[2] >= vil[1] && vil[4] == 0 && vil[9] == vil[0]);
        check.check(String.format(Locale.ROOT, "fought: going through a house after the known one, the squad is in it within %d ticks of its fall in every round (late %d; steps back at a doorway %d)",
                LATE, late, AiSoldier.sweepYields - yields0), late == 0);
        return true;
    }
}
