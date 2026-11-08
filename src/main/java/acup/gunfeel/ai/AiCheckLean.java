package acup.gunfeel.ai;

import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
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
 * The AI check's lean round a corner, on a field of its own: a soldier behind a stone wall two high and five long, a
 * foe of the other team rooted in the open 20 blocks off, both with carbines and endless rounds, each told where the
 * other is. Twice: looking out with a step aside (as before) and leaning round the corner (AiLean). Out at its peek,
 * how much of the soldier the foe sees (head, chest, legs: three rays from the foe's eye); hits either way, and on
 * the soldier while it leans (its head out past the corner, hit through the boxes it puts with no client); and that
 * no shot of the soldier's starts inside a block.
 */
final class AiCheckLean {
    static final int X0 = 340, Z0 = 4, SIZE = 40, RUN = 600;
    static final int WALL_X0 = 355, WALL_X1 = 359, WALL_Z = 20;
    static final double SX = 357.5, SZ = 26.5, TX = 357.5, TZ = 6.5;
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private static AiCheckLean counting;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int phase = -1, ticks;
    private Mob soldier, foe;
    /** this phase: ticks out at its peek, parts of it the foe saw then (a sum), hits on it (leaned), hits on the foe */
    private int out, seen, hitsOn, hitsOnLeaned, hitsBy, shots, inBlock;
    private final int[][] results = new int[2][];
    /** the first shots that started inside a block: where, and the soldier then */
    private final List<String> bad = new java.util.ArrayList<>();
    /** leaned all the way once: its head out of its upright hitbox, and a shot through the head hits it (its boxes) */
    private boolean probed, headOut, headHit;
    private String headWhere = "-";

    AiCheckLean(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
                AiCheckLean c = counting;
                if (c == null || c.soldier == null) return;
                if (e.getEntity() == c.soldier && e.getSource().getEntity() == c.foe) {
                    c.hitsOn++;
                    if (AiLean.frac(c.soldier) != 0F) c.hitsOnLeaned++;
                } else if (e.getEntity() == c.foe && e.getSource().getEntity() == c.soldier) c.hitsBy++;
            });
            NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
                AiCheckLean c = counting;
                if (c == null || c.soldier == null || !(e.getEntity() instanceof Projectile p) || p.getOwner() != c.soldier
                        || p instanceof com.hbm.entity.grenade.EntityGrenadeUniversal) return;
                c.shots++;
                BlockPos at = BlockPos.containing(p.position());
                if (!c.level.getBlockState(at).getCollisionShape(c.level, at).isEmpty()) {
                    c.inBlock++;
                    Mob m = c.soldier;
                    AiPose pose = AiPose.of(m);
                    if (c.bad.size() < 6) c.bad.add(String.format(Locale.ROOT, "shot at %.2f %.2f %.2f; soldier at %.2f %.2f %.2f, eye %s, yaw %.0f body %.0f head %.0f pitch %.0f,"
                            + " lean %d->%d at %d (now %d, %.2f), %s", p.getX(), p.getY(), p.getZ(), m.getX(), m.getY(), m.getZ(), AiLean.eye(m), m.getYRot(), m.yBodyRot,
                            m.getYHeadRot(), m.getXRot(), pose.prevLean(), pose.lean(), pose.leanTick(), c.level.getGameTime(), AiLean.frac(m),
                            m.getPose()));
                }
            });
            counting = this;
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        AiBot bot = AiBots.get(soldier), other = AiBots.get(foe);
        if (bot == null || other == null) {
            check.check("the lean: the soldier and the foe are AI mobs", false);
            return finishAll();
        }
        soldier.setHealth(soldier.getMaxHealth());
        foe.setHealth(foe.getMaxHealth());
        if (phase == 1 && !probed && Math.abs(AiLean.frac(soldier)) == 1F) {
            // its head's middle, leaned: out past its upright hitbox; a short shot down through it hits the boxes
            probed = true;
            Vec3 head = AiLean.point(soldier, 1.75);
            // its upright box from its size (getBoundingBox is the model's when it has boxes: MixinEntityModelBox)
            headOut = !soldier.getDimensions(soldier.getPose()).makeBoundingBox(soldier.position()).contains(head);
            headWhere = String.format(Locale.ROOT, "%.2f across from its middle, its hitbox %.2f wide", Math.hypot(head.x - soldier.getX(), head.z - soldier.getZ()),
                    soldier.getBbWidth());
            Vec3 a = head.add(0, 0.2, 0), b = head.add(0, -0.2, 0);
            headHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, (net.minecraft.world.entity.Entity) null, a, b,
                    new net.minecraft.world.phys.AABB(a, b).inflate(0.01), e -> e == soldier, 0F) != null;
        }
        if (bot.brain instanceof AiSoldier s && s.mode() == AiSoldier.Mode.PEEK && bot.mover.arrived()) {
            out++;
            seen += partsSeen();
        }
        if (ticks >= RUN) {
            int leans = bot.brain instanceof AiSoldier s ? s.leans : 0, steps = bot.brain instanceof AiSoldier s ? s.steps : 0;
            results[phase] = new int[] {out, seen, hitsOn, hitsOnLeaned, hitsBy, shots, inBlock, leans, steps};
            soldier.discard();
            foe.discard();
            soldier = foe = null;
            if (phase == 1) return finishAll();
            start(phase + 1);
        }
        return false;
    }

    /** the parts of the soldier (head, chest, legs) in plain sight from the foe's eye, where its lean has them */
    private int partsSeen() {
        Vec3 eye = AiLean.eye(foe);
        double h = soldier.getBbHeight();
        int n = 0;
        for (double part : new double[] {0.9, 0.55, 0.2}) {
            Vec3 p = AiLean.point(soldier, h * part);
            if (VoxelRay.clear(AiBots.voxels(level), eye.x, eye.y, eye.z, p.x, p.y, p.z, Voxel.SIGHT)) n++;
        }
        return n;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        for (int x = WALL_X0; x <= WALL_X1; x++) {
            set(x, y, WALL_Z, stone);
            set(x, y + 1, WALL_Z, stone);
        }
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
        ticks = out = seen = hitsOn = hitsOnLeaned = hitsBy = shots = inBlock = 0;
        AiLean.on = p == 1;
        build();
        soldier = spawn(SX, SZ, "lean_soldier", 180F);
        foe = spawn(TX, TZ, "lean_foe", 0F);
        // the foe stays where it is: the soldier's looks out are what is counted, not a hunt round the wall
        var speed = foe.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        AiBot s = AiBots.get(soldier), f = AiBots.get(foe);
        if (s != null) s.memory.heard(foe.getX(), foe.getY(), foe.getZ(), now, true, false);
        if (f != null) f.memory.heard(soldier.getX(), soldier.getY(), soldier.getZ(), now, true, false);
    }

    private boolean finishAll() {
        AiLean.on = true;
        counting = null;
        if (soldier != null) soldier.discard();
        if (foe != null) foe.discard();
        if (results[0] == null || results[1] == null) {
            check.check("the lean: both rounds ran", false);
            return true;
        }
        int[] st = results[0], le = results[1];
        String[] names = {"stepping out", "leaning"};
        for (int i = 0; i < 2; i++) {
            int[] r = results[i];
            check.out(String.format(Locale.ROOT, "     lean, %s (looks out: %d leaning, %d stepping): out %d ticks, the foe saw %.2f parts of it a tick out;"
                    + " hit by the foe %d times (%d leaned), hit the foe %d times; %d shots, %d of them from inside a block", names[i], r[7], r[8], r[0],
                    r[0] == 0 ? 0 : (double) r[1] / r[0], r[2], r[3], r[4], r[5], r[6]));
        }
        for (String b : bad) check.out("       " + b);
        check.check("stepping out (lean off) it never leans", st[7] == 0 && st[8] > 0);
        check.check("it leans round the corner to look out, more often than it steps out", le[7] > 0 && le[7] >= le[8]);
        check.check("leaning, the foe sees less of it out at its peek than stepping out",
                st[0] > 0 && le[0] > 0 && (double) le[1] / le[0] < (double) st[1] / st[0]);
        check.check("leaning, it fires round the corner and hits", le[5] > 0 && le[4] > 0);
        check.check("no shot of its starts inside a block, stepping or leaning", st[6] == 0 && le[6] == 0);
        check.out("     leaned all the way, its head " + headWhere + ": out of its hitbox " + headOut + ", a shot through it hits " + headHit);
        check.check("leaned, its head is out of its upright hitbox and a shot through it hits (its own boxes, no client)", probed && headOut && headHit);
        return true;
    }
}
