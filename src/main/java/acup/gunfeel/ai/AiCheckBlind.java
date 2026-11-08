package acup.gunfeel.ai;

import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's blind fire, on a field of its own: a soldier behind a stone wall one high and five long, a foe of the
 * other team rooted in the open 13 blocks off, both with carbines and endless rounds, each told where the other is.
 * It is pinned down by hand all the while (shots past it pin it: the prone stage). Twice: blind fire off (it rises
 * for its bursts) and on (it stays down and fires over the wall blind). Counted: the ticks it was pinned, its shots
 * then and the blind ones, the ticks it stood up, hits either way (the blind ones' too), and blind shots whose way to
 * the foe a block cut (into a wall: none). Then a narrow wall three high just in front of the foe: none into it.
 */
final class AiCheckBlind {
    static final int X0 = 460, Z0 = 4, SIZE = 40, RUN = 600, WALLED = 300;
    static final int WALL_X0 = 475, WALL_X1 = 479, WALL_Z = 20;
    static final double SX = 477.5, SZ = 21.5, TX = 477.5, TZ = 8.5;
    /** a hit within this many ticks of a blind shot is one of the blind shots' */
    static final int BLIND_HIT = 15;
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private static AiCheckBlind counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    /** 0: blind fire off, 1: on, 2: on with a high wall between them */
    private int phase = -1, ticks;
    private Mob soldier, foe;
    /** this phase: ticks pinned, its shots then, ticks stood pinned, hits on it, its hits (blind), blind shots cut by a block */
    private int pinned, shotsPinned, stood, hitsOn, hitsBy, hitsBlind, cut, blindPose, lastShots;
    /** ticks it was at low cover, pinned: where it would fire blind if it fired at all */
    private int ready;
    private final int[][] results = new int[3][];
    private final List<String> bad = new java.util.ArrayList<>();

    AiCheckBlind(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            if (!listening) {
                NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
                    AiCheckBlind c = counting;
                    if (c == null || c.soldier == null) return;
                    if (e.getEntity() == c.soldier && e.getSource().getEntity() == c.foe) c.hitsOn++;
                    else if (e.getEntity() == c.foe && e.getSource().getEntity() == c.soldier) {
                        c.hitsBy++;
                        AiBot b = AiBots.get(c.soldier);
                        if (b != null && b.gunner.lastBlind != Long.MIN_VALUE && c.level.getGameTime() - b.gunner.lastBlind <= BLIND_HIT) c.hitsBlind++;
                    }
                });
                NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
                    AiCheckBlind c = counting;
                    if (c == null || c.soldier == null || !(e.getEntity() instanceof Projectile p) || p.getOwner() != c.soldier) return;
                    AiBot b = AiBots.get(c.soldier);
                    if (b == null || !b.gunner.blind) return;
                    // a blind shot: its way to the foe's chest past the soldier's own wall, cut by a block?
                    Vec3 from = p.position(), to = c.foe.position().add(0, 1.1, 0);
                    double t = VoxelRay.blocked(AiBots.voxels(c.level), from.x, from.y, from.z, to.x, to.y, to.z, Voxel.PROTECT_MASK);
                    if (t >= 0) {
                        c.cut++;
                        Mob m = c.soldier;
                        Vec3 at = from.add(to.subtract(from).scale(t));
                        if (c.bad.size() < 6) c.bad.add(String.format(Locale.ROOT, "blind shot cut: from %.2f %.2f %.2f to the foe's chest %.2f %.2f %.2f, a block at"
                                + " %.2f %.2f %.2f (%s); soldier at %.2f %.2f %.2f, %s, eye %.2f, yaw %.0f pitch %.0f", from.x, from.y, from.z, to.x, to.y, to.z, at.x, at.y, at.z,
                                c.level.getBlockState(BlockPos.containing(at.add(to.subtract(from).normalize().scale(0.01)))).getBlock().getName().getString(), m.getX(), m.getY(),
                                m.getZ(), m.getPose(), m.getEyeY(), m.getYRot(), m.getXRot()));
                    }
                });
            }
            listening = true;
            counting = this;
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        AiBot bot = AiBots.get(soldier);
        if (bot == null || AiBots.get(foe) == null) {
            check.check("blind fire: the soldier and the foe are AI mobs", false);
            return finishAll();
        }
        soldier.setHealth(soldier.getMaxHealth());
        foe.setHealth(foe.getMaxHealth());
        long now = level.getGameTime();
        // pinned down by hand, all the time: what it does then is counted here (whether shots past it pin it down,
        // the prone stage counts); the foe's shots go on past it all the same
        bot.prone.pin(now);
        int shots = bot.gunner.shots;
        if (bot.prone.pinned(now)) {
            pinned++;
            shotsPinned += shots - lastShots;
            if (soldier.getPose() == Pose.STANDING) stood++;
        }
        lastShots = shots;
        if (AiPose.of(soldier).arms() == AiPose.BLIND) blindPose++;
        if (bot.brain instanceof AiSoldier s && s.spot() != null && bot.mover.arrived() && (s.spot().peek() == s.spot().cell() || !s.spot().hasPeek()))
            ready++;
        if (ticks >= (phase == 2 ? WALLED : RUN)) {
            results[phase] = new int[] {pinned, shotsPinned, bot.gunner.blindShots, stood, hitsOn, hitsBy, hitsBlind, cut, blindPose, ready,
                    bot.brain instanceof AiSoldier s ? s.blindWalled : 0};
            soldier.discard();
            foe.discard();
            soldier = foe = null;
            if (phase == 2) return finishAll();
            start(phase + 1);
        }
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build(boolean walled) {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        for (int x = WALL_X0; x <= WALL_X1; x++) set(x, y, WALL_Z, stone);
        // the walled round: a narrow wall three high just in front of the foe (cover from it stays at the soldier's low
        // wall): the place it knows the foe at is behind that wall now
        if (walled) for (int x = (int) TX - 1; x <= (int) TX + 1; x++)
            for (int h = 0; h < 3; h++) set(x, y + h, (int) TZ + 2, stone);
        TacticalMap.of(level).want((int) SX, y, WALL_Z, 20, 6);
    }

    private Mob spawn(double x, double z, String team, float yaw) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(GUN));
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

    private void start(int p) {
        phase = p;
        ticks = pinned = shotsPinned = stood = hitsOn = hitsBy = hitsBlind = cut = blindPose = lastShots = ready = 0;
        AiSoldier.blindOn = p >= 1;
        build(p == 2);
        soldier = spawn(SX, SZ, "blind_soldier", 180F);
        foe = spawn(TX, TZ, "blind_foe", 0F);
        // the foe stays where it is: it pins the soldier down from there
        var speed = foe.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        AiBot s = AiBots.get(soldier), f = AiBots.get(foe);
        if (s != null) s.memory.seen(foe.getId(), TX, y, TZ, now, 0);
        if (f != null) f.memory.seen(soldier.getId(), SX, y, SZ, now, 0);
    }

    private boolean finishAll() {
        AiSoldier.blindOn = true;
        counting = null;
        if (soldier != null) soldier.discard();
        if (foe != null) foe.discard();
        if (results[0] == null || results[1] == null || results[2] == null) {
            check.check("blind fire: all rounds ran", false);
            return true;
        }
        String[] names = {"blind fire off", "blind fire on", "on, a high wall between them"};
        for (int i = 0; i < 3; i++) {
            int[] r = results[i];
            check.out(String.format(Locale.ROOT, "     blind, %s: pinned %d ticks, shots then %d (blind %d), stood up pinned %d ticks; hit by the foe %d times,"
                    + " hit it %d (blind %d); blind shots a block cut %d; ticks in the blind pose %d; at low cover %d ticks; held back for a wall %d ticks", names[i], r[0], r[1],
                    r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], r[10]));
        }
        int[] off = results[0], on = results[1], walled = results[2];
        for (String b : bad) check.out("       " + b);
        check.check("blind fire off: no blind shot", off[2] == 0);
        check.check("pinned behind low cover it fires blind over it, in the blind pose", on[2] > 0 && on[8] > 0);
        // staying down it keeps firing: 0.6 of the shots it let go rising for them at least (stand runs: 0.8 to 1.0)
        check.check("pinned, staying down it keeps firing (blind fire: 0.6 of the shots rising at least)",
                on[0] > 0 && off[0] > 0 && (double) on[1] / on[0] >= 0.6 * off[1] / off[0]);
        check.check("pinned, it stands up less with blind fire than without", on[0] > 0 && off[0] > 0 && (double) on[3] / on[0] < (double) off[3] / off[0]);
        check.check("no blind shot into a wall (at its low wall, the foe behind a high one: held back, none at all)", on[7] == 0 && walled[10] > 0 && walled[2] == 0);
        return true;
    }
}
