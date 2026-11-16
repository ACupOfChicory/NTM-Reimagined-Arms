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
 * The AI check's corner: who comes round a corner first. A stone wall three high; south of it a squad of three that
 * has lost its foe behind it; north of it, HOLD blocks back from the wall and in from its east end, the foe, holding
 * the corner (a soldier of the other side that cannot walk: it turns, aims and fires). The squad goes looking for it
 * round the end (AiSoldier.search). Counted each round, at the first sight either way between the holder and a hunter:
 * who saw first and how far apart; how far the hunter's head was turned off the holder (the turn it still had to make);
 * who hit first, how many hunters were hit before their first hit; how near the end of the wall the hunters came before the first sight; the
 * ticks two hunters moved at once (the one moving covered by none). A measure first (notes/ai/plan-corner-search):
 * the bars come with the numbers.
 */
final class AiCheckCorner {
    static final int X0 = 6000, Z0 = 0, SIZE = 48, RUN = 1600, AFTER_SIGHT = 80, SIDE = 3;
    static final int WALL_Z = 20, WALL_X0 = 6010, WALL_X1 = 6030;
    /** the holder this far back from the wall (north), by round, and IN blocks in from its east end */
    static final int[] HOLD = {4, 6, 8, 4, 6, 8};
    static final double IN = 1.5;
    static final double HUNTERS_Z = 12.5;
    static final double[] HUNTERS_X = {6020.5, 6023.5, 6026.5};
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    static final String HUNT = "corner_hunt", HOLDS = "corner_hold";
    /** a hunter's head this far off the holder or more: it had a wide turn to make (the guard: one meeting at most) */
    static final double WIDE_DEG = 45;
    /** the holder saw a hunter this many ticks before any hunter saw it, or more: an edge (a tick or two is at once) */
    static final int LEAD = 5;
    /** a hunter this near the end of the wall (blocks) came round it hugging the wall */
    static final double HUG = 1;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private Mob holder;
    private final List<Mob> hunters = new ArrayList<>();
    private float holderHp;
    private final float[] hunterHp = new float[SIDE];
    /** this round: the tick each side first saw the other (-1 not yet), and the hunter in it */
    private int holderSaw, hunterSaw, sawHunter, holderHit, hunterHit, hitBefore, bothMoving, sightAt;
    private double sawDistance, turnDeg, nearestEnd;
    private boolean[] hunterHitYet = new boolean[SIDE];
    private final List<String> lines = new ArrayList<>();
    /** over the rounds: meetings, the holder first, wide turns, the holder hit first, hunters hit before they hit */
    private int meetings, holderFirst, holderAhead, wide, holderHitFirst, hunterHitFirst, hitBeforeAll, movingAll;
    private double nearestAll = Double.MAX_VALUE, distanceSum;
    /** rounds a hunter came within HUG of the end before the first sight: round it hugging the wall */
    private int hugged;

    AiCheckCorner(AiCheck check, ServerLevel level, int y) {
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
        if (round >= HOLD.length) return true;
        ticks++;
        long now = level.getGameTime();
        Vec3 end = new Vec3(WALL_X1 + 1, y, WALL_Z + 0.5);
        int moving = 0;
        for (int i = 0; i < hunters.size(); i++) {
            Mob h = hunters.get(i);
            if (!h.isAlive()) continue;
            AiBot b = AiBots.get(h);
            boolean searching = b != null && b.brain instanceof AiSoldier s && s.mode() == AiSoldier.Mode.SEARCH;
            if (searching && h.getDeltaMovement().horizontalDistanceSqr() > 0.0004) moving++;
            // how it came round: up to the first sight either way (after it the fight takes them anywhere)
            if (sightAt < 0) nearestEnd = Math.min(nearestEnd, Math.hypot(h.getX() - end.x, h.getZ() - end.z));
            // hit: its health went down since the last tick
            if (h.getHealth() < hunterHp[i] - 0.01F) {
                if (holderHit < 0) holderHit = ticks;
                if (hunterHit < 0 && !hunterHitYet[i]) hitBefore++;
                hunterHitYet[i] = true;
            }
            hunterHp[i] = h.getHealth();
            // a hunter sees the holder
            if (hunterSaw < 0 && b != null && sees(b, holder, now)) {
                hunterSaw = ticks;
                if (sawHunter < 0) note(h);
            }
        }
        if (moving >= 2) bothMoving++;
        if (holder.isAlive() && holder.getHealth() < holderHp - 0.01F && hunterHit < 0) hunterHit = ticks;
        holderHp = holder.getHealth();
        AiBot hb = AiBots.get(holder);
        if (holderSaw < 0 && hb != null)
            for (Mob h : hunters)
                if (h.isAlive() && sees(hb, h, now)) {
                    holderSaw = ticks;
                    if (hunterSaw < 0) note(h);
                    break;
                }
        if (sightAt < 0 && (holderSaw >= 0 || hunterSaw >= 0)) sightAt = ticks;
        if (ticks < RUN && (sightAt < 0 || ticks < sightAt + AFTER_SIGHT)) return false;
        end();
        if (round + 1 < HOLD.length) start(round + 1);
        else {
            round = HOLD.length;
            report();
        }
        return round >= HOLD.length;
    }

    /** b knows the mob is there, seen this tick or the last few (noticed, not a glimpse), and since the round began (not the memory it was given) */
    private boolean sees(AiBot b, Mob m, long now) {
        TargetMemory.Contact c = b.memory.contact(m.getId());
        return c != null && c.state == TargetMemory.State.VISIBLE && c.seenTick > startedAt && now - c.seenTick <= 4;
    }

    /** the meeting, from the hunter in it: how far from the holder, and how far its head was off it */
    private void note(Mob h) {
        sawHunter = hunters.indexOf(h);
        sawDistance = h.distanceTo(holder);
        float to = (float) Math.toDegrees(Math.atan2(holder.getZ() - h.getZ(), holder.getX() - h.getX())) - 90F;
        turnDeg = Math.abs(Mth.wrapDegrees(to - h.getYHeadRot()));
    }

    /** the game tick the round began: what it was given to remember is not a sight */
    private long startedAt;

    private void start(int r) {
        round = r;
        ticks = 0;
        holderSaw = hunterSaw = sawHunter = holderHit = hunterHit = sightAt = -1;
        hitBefore = bothMoving = 0;
        sawDistance = turnDeg = 0;
        nearestEnd = Double.MAX_VALUE;
        hunterHitYet = new boolean[SIDE];
        AiSquads.forget(level);
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        for (int x = WALL_X0; x <= WALL_X1; x++)
            for (int h = 0; h < 3; h++) set(x, y + h, WALL_Z, stone);
        TacticalMap.of(level).want(X0 + SIZE / 2, y, Z0 + SIZE / 2, SIZE / 2 + 4, 6);
        double hx = WALL_X1 + 1 - IN, hz = WALL_Z + 0.5 + HOLD[r];
        // the holder faces the corner it holds
        float toCorner = (float) Math.toDegrees(Math.atan2(WALL_Z + 0.5 - hz, WALL_X1 + 1 - hx)) - 90F;
        holder = spawn(hx, hz, HOLDS, toCorner);
        var speed = holder.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        hunters.clear();
        for (int i = 0; i < SIDE; i++) hunters.add(spawn(HUNTERS_X[i], HUNTERS_Z, HUNT, 0F));
        AiBots.refreshTargets(level);
        // each side saw the other a moment ago, then the wall: lost behind it
        long now = level.getGameTime();
        startedAt = now;
        AiBot hb = AiBots.get(holder);
        for (Mob h : hunters) {
            AiBot b = AiBots.get(h);
            if (b != null) b.memory.seen(holder.getId(), holder.getX(), holder.getY(), holder.getZ(), now, 0);
            if (hb != null) hb.memory.seen(h.getId(), h.getX(), h.getY(), h.getZ(), now, 0);
        }
        holderHp = holder.getHealth();
        for (int i = 0; i < SIDE; i++) hunterHp[i] = hunters.get(i).getHealth();
    }

    private void end() {
        boolean met = sightAt >= 0;
        String first = !met ? "none" : holderSaw >= 0 && (hunterSaw < 0 || holderSaw < hunterSaw) ? "holder"
                : hunterSaw >= 0 && (holderSaw < 0 || hunterSaw < holderSaw) ? "hunter" : "both";
        String hitFirst = holderHit < 0 && hunterHit < 0 ? "none" : hunterHit < 0 || holderHit >= 0 && holderHit < hunterHit ? "holder" : holderHit < 0 || hunterHit < holderHit ? "hunter" : "both";
        lines.add(String.format(Locale.ROOT, "     corner, round %d (held %d back): met at tick %d; first saw %s (holder %d, hunter %d), %.1f blocks apart, the hunter's head %.0f degrees off;"
                        + " first hit by %s (holder's %d, hunters' %d), hunters hit before theirs %d; nearest a hunter came to the end before the first sight %.1f; two hunters moving at once %d ticks",
                round + 1, HOLD[round], sightAt, first, holderSaw, hunterSaw, sawDistance, turnDeg, hitFirst, holderHit, hunterHit, hitBefore, nearestEnd, bothMoving));
        if (met) {
            meetings++;
            distanceSum += sawDistance;
            if (first.equals("holder")) holderFirst++;
            if (holderSaw >= 0 && (hunterSaw < 0 || hunterSaw - holderSaw > LEAD)) holderAhead++;
            if (turnDeg >= WIDE_DEG) wide++;
        }
        if (hitFirst.equals("holder")) holderHitFirst++;
        if (hitFirst.equals("hunter")) hunterHitFirst++;
        hitBeforeAll += hitBefore;
        movingAll += bothMoving;
        nearestAll = Math.min(nearestAll, nearestEnd);
        if (nearestEnd <= HUG) hugged++;
        holder.discard();
        for (Mob h : hunters) h.discard();
        hunters.clear();
    }

    private void report() {
        for (String l : lines) check.out(l);
        check.out(String.format(Locale.ROOT, "     corner: met %d of %d rounds; the holder saw first %d of them, %.1f blocks apart on average; the hunter's head %.0f degrees or more off %d;"
                        + " the holder hit first %d rounds; hunters hit before theirs %d; nearest to the end before the first sight %.1f, within %.0f block of it in %d rounds; two hunters moving at once %d ticks",
                meetings, HOLD.length, holderFirst, meetings == 0 ? 0 : distanceSum / meetings, WIDE_DEG, wide, holderHitFirst, hitBeforeAll, nearestAll, HUG, hugged, movingAll));
        check.check(String.format(Locale.ROOT, "corner: the hunters come round the end and meet the holder in half the rounds at least (%d of %d)", meetings, HOLD.length),
                2 * meetings >= HOLD.length);
        // a guard: a hunter comes round with its gun on what the end opens, seen as soon as it sees (2026-11-16: the
        // holder first 2 to 4 of 6, by a tick or two, never more than 5; heads 0 to 10 degrees off; the hunters hit
        // first 41 of 46 rounds, all 23 met further than 10 blocks off; the holder's 5 all close, the hunter by the end)
        check.check(String.format(Locale.ROOT, "corner: the holder sees a hunter more than %d ticks before it is seen in a third of the meetings at most (%d of %d; first at all %d)", LEAD, holderAhead, meetings, holderFirst),
                3 * holderAhead <= meetings);
        check.check(String.format(Locale.ROOT, "corner: a hunter's head is %.0f degrees or more off the holder in one meeting at most (%d)", WIDE_DEG, wide), wide <= 1);
        check.check(String.format(Locale.ROOT, "corner: the hunters hit first no less often than the holder (%d against %d)", hunterHitFirst, holderHitFirst),
                hunterHitFirst >= holderHitFirst);
    }

    private Mob spawn(double x, double z, String team, float yaw) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(RIFLE));
        Mob m = made.get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        m.setHealth(1000);
        AiTeams.join(level, m, team);
        AiBot bot = AiBots.get(m);
        if (bot != null) bot.gunner.ammo.setInfinite();
        return m;
    }

    private void set(int x, int yy, int z, BlockState s) {
        level.setBlock(new BlockPos(x, yy, z), s, Block.UPDATE_CLIENTS);
    }
}
