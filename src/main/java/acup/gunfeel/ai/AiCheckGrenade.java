package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.grenade.EntityGrenadeUniversal;
import com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling;
import com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze;
import com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell;
import com.hbm.items.weapon.grenade.ItemGrenadeUniversal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's grenades, on a field of their own. First a soldier at a low wall, a foe of the other team rooted 20
 * blocks off behind a stone wall three high, which the soldier saw it go behind: a round with throws off, then three
 * with them on; then twice the foe 10 blocks off behind a wall of its own, a near throw: the soldier (its wall two
 * high now, a fence across the field between them) lies down behind it till the burst. Counted: grenades thrown, where each burst (from the foe, from the soldier, from where it was to burst),
 * blast hits on the foe and on the soldier. Then a mob in the open with a grenade set down a block and a half from it
 * (no one's): three times with running from grenades off, three times on, the third time each with a wall two high
 * three blocks off on its other side. Counted: how far it was from the burst, whether behind a block from it, the
 * blast's damage to it. Then a player 8 blocks off throws NTM's hand grenade at the mob's feet, the mob fighting it: once
 * with running from grenades off, three times on (from a different side each time). Counted the same. Last, two grenades
 * at once: no one's a block and a half from the mob, its own 15.5 blocks off on the other side, just out of its reach,
 * where running from the first leads; then its own set down late, at the place it is running to from the first;
 * counted the blast's damage from its own (none: it runs some other way).
 */
final class AiCheckGrenade {
    static final int X0 = 520, Z0 = 4, SIZE = 44, RUN = 300, DODGE_RUN = 100, FAR_ROUNDS = 4, NEAR_ROUNDS = 2, THROW_ROUNDS = FAR_ROUNDS + NEAR_ROUNDS, DODGE_ROUNDS = 6, PLAYER_ROUNDS = 4, TWO_ROUNDS = 3;
    /** two grenades: its own this far off, on the side away from the one by it */
    static final double OWN_OFF = 15.5;
    /** two grenades, the third round: its own set down this tick of the round, at the place it runs to */
    static final int LATE = 20;
    /** how far off the player throws from */
    static final double PLAYER_OFF = 8;
    /** the foe and its wall three high; the soldier and its low wall */
    static final double FX = 540.5, FZ = 14.5, SX = 540.5, SZ = 35.5;
    static final int WALL_X0 = 537, WALL_X1 = 543, WALL_Z = 17, LOW_X0 = 538, LOW_X1 = 542, LOW_Z = 34;
    /** a near throw's foe and its wall three high */
    static final double NEAR_FZ = 25.5;
    static final int NEAR_WALL_Z = 28, NEAR_FENCE_Z = 31;
    /** the mob that runs */
    static final double DX = 528.5, DZ = 24.5;
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private static AiCheckGrenade counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private Mob soldier, foe, dodger;
    /** the soldier's grenades in the air, with where each was last */
    private final List<EntityGrenadeUniversal> live = new ArrayList<>();
    private final List<Vec3> lastAt = new ArrayList<>();
    private final List<Vec3> plannedAt = new ArrayList<>();
    /** the grenade set down by the runner, where it was last, where the runner was then */
    private EntityGrenadeUniversal set;
    private Vec3 setAt, dodgerAt;
    /** this round: thrown, blast hits on the foe, on the soldier, damage to the runner */
    private int thrown, blastFoe, blastSoldier;
    private float dodgeDamage, ownDamage;
    /** two grenades: its own, where it was */
    private EntityGrenadeUniversal ownSet;
    private Vec3 ownAt;
    /** two grenades: the last place it ran to before the first burst (Long.MIN_VALUE: none) */
    private long lastCell = Long.MIN_VALUE;
    /** two grenades, per round: off the one by it, the damage, the damage from its own, behind a block, off its own */
    private final List<double[]> twoRounds = new ArrayList<>();
    /** throw rounds: {on, thrown, blast on foe, blast on soldier, grenades left, near, it lay down, it ran from a grenade} */
    private final List<int[]> throwRounds = new ArrayList<>();
    /** every burst of the on rounds (the near ones apart): from the foe, from the soldier, from where it was to burst */
    private final List<double[]> bursts = new ArrayList<>(), nearBursts = new ArrayList<>();
    /** this round, the soldier lay down */
    private boolean lay;
    /** runner rounds: {on, distance at the burst, damage, behind a block from it (1)} */
    private final List<double[]> dodgeRounds = new ArrayList<>();
    /** the player's rounds: the same as the runner's; the player, how far from the mob's feet its throw was to burst */
    private final List<double[]> playerRounds = new ArrayList<>();
    private net.neoforged.neoforge.common.util.FakePlayer player;
    private double aimOff = -1;
    private final List<String> trace = new ArrayList<>();

    AiCheckGrenade(AiCheck check, ServerLevel level, int y) {
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
                    AiCheckGrenade c = counting;
                    if (c == null || !e.getSource().is(DamageTypeTags.IS_EXPLOSION)) return;
                    if (c.foe != null && e.getEntity() == c.foe) c.blastFoe++;
                    else if (c.soldier != null && e.getEntity() == c.soldier) c.blastSoldier++;
                    else if (c.dodger != null && e.getEntity() == c.dodger) {
                        c.dodgeDamage += e.getAmount();
                        if (e.getSource().getEntity() == c.dodger) c.ownDamage += e.getAmount();
                    }
                });
                NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
                    AiCheckGrenade c = counting;
                    if (c == null || c.soldier == null || !(e.getEntity() instanceof EntityGrenadeUniversal g) || g.getOwner() != c.soldier) return;
                    c.live.add(g);
                    c.lastAt.add(g.position());
                    AiBot b = AiBots.get(c.soldier);
                    c.plannedAt.add(b != null && b.brain instanceof AiSoldier s && s.planned != null ? s.planned : g.position());
                    c.thrown++;
                });
            }
            listening = true;
            counting = this;
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        return round < THROW_ROUNDS ? throwTick() : dodgeTick();
    }

    private boolean throwTick() {
        if (AiBots.get(soldier) == null || AiBots.get(foe) == null) {
            check.check("grenades: the soldier and the foe are AI mobs", false);
            return finishAll();
        }
        soldier.setHealth(soldier.getMaxHealth());
        foe.setHealth(foe.getMaxHealth());
        boolean near = round >= FAR_ROUNDS;
        lay |= soldier.getPose() == net.minecraft.world.entity.Pose.SWIMMING;
        if (near && ticks % 30 == 0 && trace.size() < 100 && AiBots.get(soldier) instanceof AiBot sb && sb.brain instanceof AiSoldier ss) {
            // why a near throw is not: where it is, what it does, its shield from the foe's place, the throw planned there
            Vec3 at = foe.position();
            AiGrenades.Throw t = ticks == 30 ? AiGrenades.plan(level, soldier, at, false, AiProne.SIZE.height()) : null;
            var c = sb.memory.focus(soldier.getX(), soldier.getZ());
            trace.add(String.format(Locale.ROOT, "r%d t%d: at %.2f %.2f %.2f %s, %s (%s), arrived %s, contact %s, %.1f off the foe; shielded lying from the foe's place %s;"
                    + " a throw planned there %s; prone may %s; grenades %d", round, ticks, soldier.getX(), soldier.getY(), soldier.getZ(), soldier.getPose(), ss.mode(), ss.why.label,
                    sb.mover.arrived(), c == null ? "-" : c.state, soldier.position().distanceTo(at),
                    AiGrenades.shielded(AiBots.voxels(level), soldier.getX(), soldier.getY(), soldier.getZ(), AiProne.SIZE.height(), at),
                    ticks != 30 ? "-" : t == null ? "none" : String.format(Locale.ROOT, "to %.2f %.2f %.2f", t.burst().x, t.burst().y, t.burst().z), AiProne.may(soldier), sb.grenades));
        }
        for (int i = live.size() - 1; i >= 0; i--) {
            EntityGrenadeUniversal g = live.get(i);
            if (!g.isRemoved()) {
                lastAt.set(i, g.position());
                continue;
            }
            Vec3 b = lastAt.get(i);
            (near ? nearBursts : bursts).add(new double[] {b.distanceTo(foe.position()), b.distanceTo(soldier.position()), b.distanceTo(plannedAt.get(i))});
            live.remove(i);
            lastAt.remove(i);
            plannedAt.remove(i);
        }
        if (ticks < RUN) return false;
        AiBot b = AiBots.get(soldier);
        throwRounds.add(new int[] {AiGrenades.on ? 1 : 0, thrown, blastFoe, blastSoldier, b == null ? -1 : b.grenades, near ? 1 : 0, lay ? 1 : 0,
                b != null && b.brain instanceof AiSoldier s ? s.dodges : -1});
        soldier.discard();
        foe.discard();
        soldier = foe = null;
        // one still in the air bursts in no later round
        for (EntityGrenadeUniversal g : live) g.discard();
        live.clear();
        lastAt.clear();
        plannedAt.clear();
        start(round + 1);
        return false;
    }

    private boolean dodgeTick() {
        if (AiBots.get(dodger) == null) {
            check.check("grenades: the runner is an AI mob", false);
            return finishAll();
        }
        boolean two = round >= THROW_ROUNDS + DODGE_ROUNDS + PLAYER_ROUNDS, byPlayer = !two && round >= THROW_ROUNDS + DODGE_ROUNDS;
        if (byPlayer) {
            player.setHealth(player.getMaxHealth());
        }
        if (ticks == 5 && byPlayer) {
            // the player throws it at the mob's feet: the turn whose throw (NTM's own start and speed) bursts nearest them
            Vec3 feet = dodger.position();
            float yaw = (float) Math.toDegrees(Math.atan2(-(feet.x - player.getX()), feet.z - player.getZ()));
            float bestPitch = 0;
            double best = Double.MAX_VALUE;
            var stack = ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, EnumGrenadeFilling.HE, EnumGrenadeFuze.S3);
            for (float p = -60; p <= 30; p += 0.5F) {
                player.snapTo(player.getX(), player.getY(), player.getZ(), yaw, p);
                player.setYHeadRot(yaw);
                EntityGrenadeUniversal g = new EntityGrenadeUniversal(level, player, stack);
                Vec3 v = g.getDeltaMovement();
                Vec3 b = AiGrenades.burst(AiBots.voxels(level), g.getX(), g.getY(), g.getZ(), v.x, v.y, v.z, AiGrenades.FUSE, -1, EnumGrenadeShell.FRAG.getBounce(),
                        level.getMinY());
                if (b != null && b.distanceTo(feet) < best) {
                    best = b.distanceTo(feet);
                    bestPitch = p;
                }
            }
            player.snapTo(player.getX(), player.getY(), player.getZ(), yaw, bestPitch);
            player.setYHeadRot(yaw);
            set = new EntityGrenadeUniversal(level, player, stack);
            level.addFreshEntity(set);
            aimOff = best;
            setAt = set.position();
            dodgerAt = dodger.position();
        } else if (ticks == 5 && two) {
            // no one's a block and a half from it, its own OWN_OFF off the other way (west and east, then north and south)
            int k = round - THROW_ROUNDS - DODGE_ROUNDS - PLAYER_ROUNDS;
            double ax = k % 2 == 0 ? 1 : 0, az = 1 - ax;
            var stack = ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, EnumGrenadeFilling.HE, EnumGrenadeFuze.S3);
            set = new EntityGrenadeUniversal(level, stack);
            set.setPos(dodger.getX() - ax * 1.5, y + 0.1, dodger.getZ() - az * 1.5);
            set.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(set);
            // the third: its own comes later, where it runs to
            if (k < 2) placeOwn(new Vec3(dodger.getX() + ax * OWN_OFF, y + 0.1, dodger.getZ() + az * OWN_OFF));
            setAt = set.position();
            dodgerAt = dodger.position();
        } else if (ticks == LATE && two && ownSet == null) {
            AiBot tb = AiBots.get(dodger);
            long dc = tb != null && tb.brain instanceof AiSoldier s ? s.dodgeCell() : Long.MIN_VALUE;
            placeOwn(dc == Long.MIN_VALUE ? new Vec3(DX + 12, y + 0.1, DZ) : new Vec3(BlockPos.getX(dc) + 0.5, y + 0.1, BlockPos.getZ(dc) + 0.5));
        } else if (ticks == 5) {
            // set down a block and a half from it, still, no one's; a different side each round
            double a = Math.PI * 2 * (round - THROW_ROUNDS) / 3;
            set = new EntityGrenadeUniversal(level, ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, EnumGrenadeFilling.HE, EnumGrenadeFuze.S3));
            set.setPos(dodger.getX() + Math.cos(a) * 1.5, y + 0.1, dodger.getZ() + Math.sin(a) * 1.5);
            set.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(set);
            setAt = set.position();
            dodgerAt = dodger.position();
        }
        if (ownSet != null && !ownSet.isRemoved()) ownAt = ownSet.position();
        // the place it runs to while both are there
        if (two && set != null && !set.isRemoved() && AiBots.get(dodger) instanceof AiBot lb && lb.brain instanceof AiSoldier ls && ls.dodgeCell() != Long.MIN_VALUE) lastCell = ls.dodgeCell();
        if (set != null && !set.isRemoved()) {
            setAt = set.position();
            dodgerAt = dodger.position();
            AiBot tb = AiBots.get(dodger);
            if ((byPlayer || two) && AiGrenades.dodgeOn && ticks % 5 == 0 && tb != null && tb.brain instanceof AiSoldier s && trace.size() < 100) {
                long dc = s.dodgeCell(), g = tb.mover.goal();
                Vec3 pr = AiGrenades.predicted(level, set);
                trace.add(String.format(Locale.ROOT, "r%d t%d: off its own %s, off the grenade %.1f (on the ground %s, v %.2f; to burst %.1f from where it is); %s (%s), dodge cell %s, mover goal %s arrived %s failed %s, speed %.2f; pose %s stance %s lying %s runs %s stoop %s yaw %.0f to the goal %.0f",
                        round, ticks, ownAt == null ? "-" : String.format(Locale.ROOT, "%.1f (dodge cell %s)", ownAt.distanceTo(dodgerAt), s.dodgeCell() == Long.MIN_VALUE ? "-"
                                : String.format(Locale.ROOT, "%.1f off it", ownAt.distanceTo(new Vec3(BlockPos.getX(s.dodgeCell()) + 0.5, BlockPos.getY(s.dodgeCell()), BlockPos.getZ(s.dodgeCell()) + 0.5)))),
                        setAt.distanceTo(dodgerAt), set.onGround(), set.getDeltaMovement().length(), pr == null ? -1 : pr.distanceTo(setAt), s.mode(), s.why.label,
                        dc == Long.MIN_VALUE ? "-" : String.format(Locale.ROOT, "%.1f off it", setAt.distanceTo(new Vec3(BlockPos.getX(dc) + 0.5, BlockPos.getY(dc), BlockPos.getZ(dc) + 0.5))),
                        g == Long.MIN_VALUE ? "-" : String.format(Locale.ROOT, "%.1f off it", setAt.distanceTo(new Vec3(BlockPos.getX(g) + 0.5, BlockPos.getY(g), BlockPos.getZ(g) + 0.5))),
                        tb.mover.arrived(), tb.mover.failed(), dodger.getDeltaMovement().horizontalDistance(), dodger.getPose(), AiPose.of(dodger).stance(), tb.prone.lying(),
                        tb.mover.runs(), tb.stoop.down, dodger.getYRot(), g == Long.MIN_VALUE ? Double.NaN : Math.toDegrees(Math.atan2(-(BlockPos.getX(g) + 0.5 - dodger.getX()),
                                BlockPos.getZ(g) + 0.5 - dodger.getZ()))));
            }
        }
        if (ticks < DODGE_RUN) return false;
        boolean hid = setAt != null && AiGrenades.behind(AiBots.voxels(level), setAt, dodgerAt.x, dodgerAt.y, dodgerAt.z);
        Vec3 lc = lastCell == Long.MIN_VALUE ? null : new Vec3(BlockPos.getX(lastCell) + 0.5, BlockPos.getY(lastCell), BlockPos.getZ(lastCell) + 0.5);
        if (two) twoRounds.add(new double[] {setAt == null ? -1 : setAt.distanceTo(dodgerAt), dodgeDamage, ownDamage, hid ? 1 : 0, ownAt == null ? -1 : ownAt.distanceTo(dodgerAt),
                lc == null || setAt == null ? -1 : lc.distanceTo(setAt), lc == null || ownAt == null ? -1 : lc.distanceTo(ownAt),
                lc != null && setAt != null && AiGrenades.behind(AiBots.voxels(level), setAt, lc.x, lc.y, lc.z) ? 1 : 0});
        else (byPlayer ? playerRounds : dodgeRounds).add(new double[] {AiGrenades.dodgeOn ? 1 : 0, setAt == null ? -1 : setAt.distanceTo(dodgerAt), dodgeDamage, hid ? 1 : 0, aimOff});
        lastCell = Long.MIN_VALUE;
        dodger.discard();
        dodger = null;
        if (player != null) player.discard();
        player = null;
        aimOff = -1;
        if (set != null && !set.isRemoved()) set.discard();
        if (ownSet != null && !ownSet.isRemoved()) ownSet.discard();
        set = ownSet = null;
        setAt = dodgerAt = ownAt = null;
        if (round + 1 >= THROW_ROUNDS + DODGE_ROUNDS + PLAYER_ROUNDS + TWO_ROUNDS) return finishAll();
        start(round + 1);
        return false;
    }

    /** two grenades: its own, still, here */
    private void placeOwn(Vec3 at) {
        ownSet = new EntityGrenadeUniversal(level, dodger, ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, EnumGrenadeFilling.HE, EnumGrenadeFuze.S3));
        ownSet.setPos(at.x, at.y, at.z);
        ownSet.setDeltaMovement(Vec3.ZERO);
        level.addFreshEntity(ownSet);
        ownAt = ownSet.position();
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build(boolean walls) {
        build(walls, false);
    }

    private void build(boolean walls, boolean near) {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                // two deep: a blast's crater is filled again
                set(x, y - 2, z, stone);
                set(x, y - 1, z, stone);
                for (int h = 0; h < 5; h++) set(x, y + h, z, air);
            }
        if (walls) {
            for (int x = WALL_X0; x <= WALL_X1; x++) for (int h = 0; h < 3; h++) set(x, y + h, WALL_Z, stone);
            for (int x = LOW_X0; x <= LOW_X1; x++) set(x, y, LOW_Z, stone);
            if (near) {
                for (int x = WALL_X0; x <= WALL_X1; x++) for (int h = 0; h < 3; h++) set(x, y + h, NEAR_WALL_Z, stone);
                // its wall two high (as high cover as the foe's wall's back, 3 blocks from the foe, which it went off to
                // with a low one), and a fence across the field (no cover: it hides nothing) on the way there
                for (int x = LOW_X0; x <= LOW_X1; x++) set(x, y + 1, LOW_Z, stone);
                BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
                for (int x = X0; x < X0 + SIZE; x++) set(x, y, NEAR_FENCE_Z, fence);
            }
        }
        TacticalMap.of(level).want(X0 + SIZE / 2, y, Z0 + SIZE / 2, SIZE / 2 + 4, 6);
    }

    private Mob spawn(double x, double z, String team, float yaw, int grenades) {
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
        if (bot != null) {
            bot.gunner.ammo.setInfinite();
            bot.grenades = grenades;
        }
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = thrown = blastFoe = blastSoldier = 0;
        lay = false;
        dodgeDamage = ownDamage = 0;
        if (r < THROW_ROUNDS) {
            // the foe rooted where it went behind its wall: where each grenade bursts from it is what is counted
            // the near rounds: running from grenades on, as in a fight (it must not run from its own, nor need to)
            boolean near = r >= FAR_ROUNDS;
            double fz = near ? NEAR_FZ : FZ;
            AiGrenades.on = r > 0;
            AiGrenades.dodgeOn = near;
            build(true, near);
            soldier = spawn(SX, SZ, "grenade_side", 180F, 2);
            foe = spawn(FX, fz, "grenade_foe", 0F, 0);
            var speed = foe.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
            AiBots.refreshTargets(level);
            long now = level.getGameTime();
            AiBot s = AiBots.get(soldier), f = AiBots.get(foe);
            if (s != null) s.memory.seen(foe.getId(), FX, y, fz, now, 0);
            if (f != null) f.memory.seen(soldier.getId(), SX, y, SZ, now, 0);
        } else if (r >= THROW_ROUNDS + DODGE_ROUNDS + PLAYER_ROUNDS) {
            // two grenades: running on
            AiGrenades.on = true;
            AiGrenades.dodgeOn = true;
            build(false);
            dodger = spawn(DX, DZ, "grenade_runner", 0F, 0);
        } else if (r >= THROW_ROUNDS + DODGE_ROUNDS) {
            // the player's: off once, then on; from a different side each time, the mob fighting it
            int k = r - THROW_ROUNDS - DODGE_ROUNDS;
            AiGrenades.on = true;
            AiGrenades.dodgeOn = k > 0;
            build(false);
            dodger = spawn(DX, DZ, "grenade_runner", 0F, 0);
            double a = Math.PI * 2 * k / PLAYER_ROUNDS + 0.4;
            player = new net.neoforged.neoforge.common.util.FakePlayer(level, new com.mojang.authlib.GameProfile(java.util.UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7e00" + k),
                    "armsai_thrower" + k));
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.snapTo(DX + Math.cos(a) * PLAYER_OFF, y, DZ + Math.sin(a) * PLAYER_OFF, 0F, 0F);
            level.addNewPlayer(player);
            AiCheckReact.hittable(player);
            AiBots.refreshTargets(level);
            AiBot d = AiBots.get(dodger);
            if (d != null) d.memory.seen(player.getId(), player.getX(), y, player.getZ(), level.getGameTime(), 0);
        } else {
            AiGrenades.on = true;
            AiGrenades.dodgeOn = r >= THROW_ROUNDS + DODGE_ROUNDS / 2;
            build(false);
            int k = r - THROW_ROUNDS;
            if (k % 3 == 2) {
                // a wall two high and five long three blocks off, across from where the grenade will be
                double a = Math.PI * 2 * k / 3, wx = DX - Math.cos(a) * 3, wz = DZ - Math.sin(a) * 3;
                for (int i = -2; i <= 2; i++) {
                    int bx = (int) Math.floor(wx - Math.sin(a) * i), bz = (int) Math.floor(wz + Math.cos(a) * i);
                    set(bx, y, bz, Blocks.STONE.defaultBlockState());
                    set(bx, y + 1, bz, Blocks.STONE.defaultBlockState());
                }
                TacticalMap.of(level).want((int) DX, y, (int) DZ, 8, 6);
            }
            dodger = spawn(DX, DZ, "grenade_runner", 0F, 0);
        }
    }

    private boolean finishAll() {
        AiGrenades.on = true;
        AiGrenades.dodgeOn = true;
        counting = null;
        for (Mob m : new Mob[] {soldier, foe, dodger}) if (m != null) m.discard();
        for (EntityGrenadeUniversal g : new EntityGrenadeUniversal[] {set, ownSet}) if (g != null && !g.isRemoved()) g.discard();
        if (player != null) player.discard();
        boolean offNone = true, eachThrew = true, finite = true;
        int foeHits = 0, soldierHits = 0;
        boolean nearOk = true;
        for (int i = 0; i < throwRounds.size(); i++) {
            int[] r = throwRounds.get(i);
            check.out(String.format(Locale.ROOT, "     grenades, round %d %s: thrown %d, blast hits on the foe %d, on the soldier %d, grenades left %d%s", i + 1,
                    r[5] == 1 ? "near (10 blocks)" : r[0] == 1 ? "throws on" : "throws off", r[1], r[2], r[3], r[4],
                    r[5] == 1 ? String.format(Locale.ROOT, ", it lay down %s, ran from a grenade %d times", r[6] == 1 ? "yes" : "no", r[7]) : ""));
            if (r[5] == 1) {
                nearOk &= r[1] > 0 && r[1] <= 2 && r[2] > 0 && r[3] == 0 && r[6] == 1 && r[7] == 0;
                continue;
            }
            if (r[0] == 0) offNone &= r[1] == 0;
            else {
                eachThrew &= r[1] > 0;
                finite &= r[1] <= 2 && r[4] == 2 - r[1];
                foeHits += r[2];
            }
            soldierHits += r[3];
        }
        double worstFoe = 0, worstPlan = 0, nearestSoldier = Double.MAX_VALUE, sumFoe = 0, sumPlan = 0;
        for (double[] b : bursts) {
            worstFoe = Math.max(worstFoe, b[0]);
            worstPlan = Math.max(worstPlan, b[2]);
            nearestSoldier = Math.min(nearestSoldier, b[1]);
            sumFoe += b[0];
            sumPlan += b[2];
            check.out(String.format(Locale.ROOT, "       burst %.2f from the foe, %.2f from the soldier, %.2f from where it was to burst", b[0], b[1], b[2]));
        }
        double worstNear = 0, nearestNear = Double.MAX_VALUE;
        for (double[] b : nearBursts) {
            worstNear = Math.max(worstNear, b[0]);
            nearestNear = Math.min(nearestNear, b[1]);
            check.out(String.format(Locale.ROOT, "       near: burst %.2f from the foe, %.2f from the soldier, %.2f from where it was to burst", b[0], b[1], b[2]));
        }
        double[] dist = new double[2], dmg = new double[2];
        int[] n = new int[2];
        boolean eachSafe = true;
        for (double[] d : dodgeRounds) {
            int on = (int) d[0];
            check.out(String.format(Locale.ROOT, "     grenades, a runner, running %s: %.2f from the burst%s, blast damage %.1f", on == 1 ? "on" : "off", d[1],
                    d[3] == 1 ? ", behind a block" : "", d[2]));
            if (on == 1) eachSafe &= d[1] >= AiGrenades.CLEAR - 1 || d[3] == 1;
            dist[on] += d[1];
            dmg[on] += d[2];
            n[on]++;
        }
        double[] pDist = new double[2], pDmg = new double[2];
        int[] pn = new int[2];
        int ran = 0;
        for (double[] d : playerRounds) {
            int on = (int) d[0];
            boolean safe = d[1] >= AiGrenades.CLEAR - 1 || d[3] == 1;
            check.out(String.format(Locale.ROOT, "     grenades, a player's at its feet (thrown to burst %.2f from them), running %s: %.2f from the burst%s, blast damage %.1f", d[4],
                    on == 1 ? "on" : "off", d[1], d[3] == 1 ? ", behind a block" : "", d[2]));
            if (on == 1 && safe) ran++;
            pDist[on] += d[1];
            pDmg[on] += d[2];
            pn[on]++;
        }
        if (throwRounds.size() < THROW_ROUNDS || dodgeRounds.size() < DODGE_ROUNDS || playerRounds.size() < PLAYER_ROUNDS || twoRounds.size() < TWO_ROUNDS) {
            check.check("grenades: all rounds ran", false);
            return true;
        }
        check.out(String.format(Locale.ROOT, "     grenades: %d bursts, from the foe %.2f on average (at most %.2f), from where they were to burst %.2f (at most %.2f),"
                + " the soldier %.2f off at least; a runner: off %.2f from the burst, damage %.1f; on %.2f, damage %.1f", bursts.size(),
                bursts.isEmpty() ? -1 : sumFoe / bursts.size(), worstFoe, bursts.isEmpty() ? -1 : sumPlan / bursts.size(), worstPlan, nearestSoldier,
                dist[0] / n[0], dmg[0] / n[0], dist[1] / n[1], dmg[1] / n[1]));
        check.check("throws off: no grenade", offNone);
        check.check("at a foe gone behind a wall it throws a grenade each round, of the two it has", eachThrew && finite);
        check.check("each grenade bursts within 3 blocks of the foe behind its wall", !bursts.isEmpty() && worstFoe <= AiGrenades.LAND);
        check.check("each bursts within 1.5 blocks of where it was to (NTM's flight, worked out)", !bursts.isEmpty() && worstPlan <= 1.5);
        check.check("the blast hits the foe, never the soldier (15 blocks off at least)", foeHits > 0 && soldierHits == 0 && nearestSoldier >= AiGrenades.SAFE);
        if (!nearOk) for (String t : trace) if (t.startsWith("r" + FAR_ROUNDS) || t.startsWith("r" + (FAR_ROUNDS + 1))) check.out("       " + t);
        check.check("a foe 10 blocks off behind a wall: a near throw each round, bursting within 3 blocks of it, the blast on it; the soldier lies down behind its"
                + " wall, never hit, never runs", nearOk && !nearBursts.isEmpty() && worstNear <= AiGrenades.LAND && nearestNear >= AiGrenades.CLOSE_BURST);
        // the blast hurts out to 15 blocks: 12 off, a fifth of it at the most (standing a block and a half off: 23)
        check.check("a grenade by it: it runs (12 blocks off, or behind a block) and takes a third of the blast standing there would at most",
                n[1] > 0 && n[0] > 0 && eachSafe && dmg[1] <= dmg[0] / 3);
        check.out(String.format(Locale.ROOT, "     grenades, a player's at its feet: ran out of reach %d of %d; off %.2f from the burst, damage %.1f; on %.2f, damage %.1f", ran, pn[1],
                pDist[0] / pn[0], pDmg[0] / pn[0], pDist[1] / pn[1], pDmg[1] / pn[1]));
        boolean playerOk = pn[1] > 0 && pn[0] > 0 && ran == pn[1] && pDmg[1] / pn[1] <= pDmg[0] / pn[0] / 3;
        // what it did meanwhile, every 5 ticks: only when it failed
        if (!playerOk) for (String t : trace) check.out("       " + t);
        check.check("a player's grenade at its feet (it fighting the player): it runs each time and takes a third of the blast it would standing at most", playerOk);
        boolean twoOk = true;
        for (int i = 0; i < twoRounds.size(); i++) {
            double[] d = twoRounds.get(i);
            boolean late = i == 2;
            check.out(String.format(Locale.ROOT, "     grenades, two at once%s: %.2f from the one by it%s, %.2f from its own; blast damage %.1f, of it from its own %.1f;"
                    + " the last place it ran to %.2f from the one by it%s, %.2f from its own", late ? " (its own later, where it ran to)" : "", d[0],
                    d[3] == 1 ? ", behind a block" : "", d[4], d[1], d[2], d[5], d[7] == 1 ? ", behind a block" : "", d[6]));
            // its own later: no time to get out of the first's reach after it has to turn (a mob runs 0.13 a tick); the place it chose is held to
            twoOk &= d[2] == 0 && (late ? (d[5] >= AiGrenades.CLEAR || d[7] == 1) && d[6] >= AiGrenades.OTHER_OWN : d[0] >= AiGrenades.CLEAR - 1 || d[3] == 1);
        }
        if (!twoOk) for (String t : trace) if (Integer.parseInt(t.substring(1, t.indexOf(' '))) >= THROW_ROUNDS + DODGE_ROUNDS + PLAYER_ROUNDS) check.out("       " + t);
        check.check("two grenades: it runs from the one by it (12 blocks off, or behind a block), not into its own's reach", twoOk);
        return true;
    }
}
