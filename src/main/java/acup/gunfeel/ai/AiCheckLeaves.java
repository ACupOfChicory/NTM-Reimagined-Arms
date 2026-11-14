package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's leaves and grass (T3, the woods): what a look sees through them. A plain of grass in the air with
 * lanes 20 blocks long side by side: in each a wall of leaves three high across the lane, 0 to 10 blocks thick; then
 * one with no grass, one with short grass all along it, one with tall grass. A watcher (a mob) at one end looks at a
 * player at the other standing, crouching, lying, running (in the open): whether it sees it, in how many ticks it
 * notices it ({@link Senses#look}). Then a soldier with a rifle and a foe 24 blocks off behind 2 blocks of leaves, and
 * behind 10: whether it comes to know the foe, when, its shots and hits through the leaves.
 */
final class AiCheckLeaves {
    static final int X0 = 2400, Z0 = 0, SX = 64, SZ = 40;
    /** the lanes: the watcher at z WATCH, the target at z WATCH + LENGTH; leaves from z WALL on */
    static final int LENGTH = 20, WATCH = 2, WALL = 8, THICKEST = 10;
    static final int OPEN_X = X0 + 30, SHORT_X = X0 + 32, TALL_X = X0 + 34;
    /** the live rounds: the soldier at LIVE_Z, the foe 24 further, the leaves between; ticks a round */
    static final int LIVE_X = X0 + 44, LIVE_Z = 6, LIVE_FAR = 24, LIVE_WALL = 16, RUN = 300;
    static final int[] LIVE_LEAVES = {2, 10};
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private static AiCheckLeaves counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int ticks, round = -1;
    private Mob soldier, foe, watcher;
    private int knownAt, shotsBefore, shots, hits;
    private final List<String> lines = new ArrayList<>();
    /** notice ticks through k leaves: standing, crouching, lying */
    private final int[][] leaves = new int[THICKEST + 1][3];
    /** open, short grass, tall grass: standing, crouching, lying, running */
    private final int[][] grass = new int[3][4];
    private final int[][] live = new int[LIVE_LEAVES.length][3];

    AiCheckLeaves(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (ticks == 0) {
            for (int cx = (X0 >> 4) - 1; cx <= (X0 + SX) >> 4; cx++)
                for (int cz = (Z0 >> 4) - 1; cz <= (Z0 + SZ) >> 4; cz++) level.setChunkForced(cx, cz, true);
            if (!listening) NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
                AiCheckLeaves c = counting;
                if (c != null && c.foe != null && e.getEntity() == c.foe && e.getSource().getEntity() == c.soldier) c.hits++;
            });
            listening = true;
            counting = this;
            AiBots.awake = true;
            build();
        }
        ticks++;
        if (ticks == 5) {
            looks();
            start(0);
            return false;
        }
        if (round < 0) return false;
        foe.setHealth(foe.getMaxHealth());
        soldier.setHealth(soldier.getMaxHealth());
        AiBot b = AiBots.get(soldier);
        if (b != null) {
            TargetMemory.Contact c = b.memory.contact(foe.getId());
            if (knownAt < 0 && c != null && c.known()) knownAt = ticks - 5 - round * RUN;
            shots += b.gunner.shots - shotsBefore;
            shotsBefore = b.gunner.shots;
        }
        if (ticks - 5 - round * RUN >= RUN) {
            live[round] = new int[] {knownAt, shots, hits};
            lines.add(String.format(Locale.ROOT, "     leaves, a soldier and a foe %d off behind %d blocks of leaves: knows it %s, shots %d, hits %d", LIVE_FAR,
                    LIVE_LEAVES[round], knownAt < 0 ? "never" : "after " + knownAt + " ticks", shots, hits));
            soldier.discard();
            foe.discard();
            if (round + 1 >= LIVE_LEAVES.length) return finish();
            start(round + 1);
        }
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private void build() {
        BlockState ground = Blocks.GRASS_BLOCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState(),
                leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int x = X0; x < X0 + SX; x++)
            for (int z = Z0; z < Z0 + SZ; z++) {
                set(x, y - 1, z, ground);
                for (int h = 0; h < 5; h++) set(x, y + h, z, air);
            }
        for (int k = 1; k <= THICKEST; k++)
            for (int d = 0; d < k; d++)
                for (int h = 0; h < 3; h++) set(lane(k), y + h, Z0 + WALL + d, leaf);
        BlockState low = Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER),
                high = Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
        // grass from in front of the watcher to under the target
        for (int z = Z0 + WATCH + 2; z <= Z0 + WATCH + LENGTH; z++) {
            set(SHORT_X, y, z, Blocks.SHORT_GRASS.defaultBlockState());
            set(TALL_X, y, z, low);
            set(TALL_X, y + 1, z, high);
        }
        for (int d = 0; d < 10; d++)
            for (int x = LIVE_X - 4; x <= LIVE_X + 4; x++)
                for (int h = 0; h < 3; h++) set(x, y + h, Z0 + LIVE_WALL + d, d < LIVE_LEAVES[0] ? leaf : air);
    }

    private static int lane(int k) {
        return X0 + 2 + 2 * k;
    }

    private Mob dummy(double x, double z, float yaw) {
        var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy"));
        Mob e = (Mob) type.create(level, EntitySpawnReason.COMMAND);
        e.setPos(x, y, z);
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
        e.setXRot(0);
        level.addFreshEntity(e);
        return e;
    }

    /** the ticks the watcher takes to notice the target here, as it stands: -1, not seen */
    private int look(LivingEntity target, double x, Pose pose, boolean run) {
        watcher.setPos(x + 0.5, y, Z0 + WATCH + 0.5);
        watcher.setYRot(0);
        watcher.setYHeadRot(0);
        watcher.setXRot(0);
        target.setPos(x + 0.5, y, Z0 + WATCH + LENGTH + 0.5);
        target.setPose(pose);
        target.setSprinting(run);
        return Senses.look(AiBots.voxels(level), null, watcher, target, AiProfile.SOLDIER);
    }

    private void looks() {
        watcher = dummy(X0 + 0.5, Z0 + 0.5, 0);
        LivingEntity p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-b2b2b2b2b2b2"), "armsai_leaves"));
        Pose[] poses = {Pose.STANDING, Pose.CROUCHING, Pose.SWIMMING};
        StringBuilder s = new StringBuilder("     leaves, ticks to notice a player 20 off through k blocks of leaves (standing / crouching / lying; -: not seen):");
        for (int k = 0; k <= THICKEST; k++) {
            for (int i = 0; i < 3; i++) leaves[k][i] = look(p, lane(k), poses[i], false);
            s.append(String.format(Locale.ROOT, " %d: %s/%s/%s", k, n(leaves[k][0]), n(leaves[k][1]), n(leaves[k][2])));
        }
        lines.add(s.toString());
        int[] xs = {OPEN_X, SHORT_X, TALL_X};
        String[] names = {"no grass", "short grass", "tall grass"};
        s = new StringBuilder("     leaves, the same in grass (standing / crouching / lying / running):");
        for (int g = 0; g < 3; g++) {
            for (int i = 0; i < 3; i++) grass[g][i] = look(p, xs[g], poses[i], false);
            grass[g][3] = look(p, xs[g], Pose.STANDING, true);
            s.append(String.format(Locale.ROOT, " %s %s/%s/%s/%s;", names[g], n(grass[g][0]), n(grass[g][1]), n(grass[g][2]), n(grass[g][3])));
        }
        lines.add(s.toString());
        p.setPose(Pose.STANDING);
        p.setSprinting(false);
        watcher.discard();
    }

    private static String n(int t) {
        return t < 0 ? "-" : Integer.toString(t);
    }

    private Mob soldier(double x, double z, String team, float yaw) {
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

    private void start(int r) {
        round = r;
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), air = Blocks.AIR.defaultBlockState();
        for (int d = 0; d < 10; d++)
            for (int x = LIVE_X - 4; x <= LIVE_X + 4; x++)
                for (int h = 0; h < 3; h++) set(x, y + h, Z0 + LIVE_WALL + d, d < LIVE_LEAVES[r] ? leaf : air);
        AiSquads.on = false;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        knownAt = -1;
        shots = 0;
        hits = 0;
        soldier = soldier(LIVE_X + 0.5, Z0 + LIVE_Z + 0.5, "leaves_side", 0F);
        foe = dummy(LIVE_X + 0.5, Z0 + LIVE_Z + LIVE_FAR + 0.5, 180F);
        foe.addTag(AiBots.TARGET_TAG);
        AiTeams.join(level, foe, "leaves_foe");
        var hp = foe.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        foe.setHealth(1000);
        var speed = foe.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        AiBots.refreshTargets(level);
        AiBot b = AiBots.get(soldier);
        shotsBefore = b == null ? 0 : b.gunner.shots;
    }

    private boolean finish() {
        counting = null;
        AiSquads.on = true;
        for (String s : lines) check.out(s);
        boolean thicker = true;
        for (int k = 1; k <= 8; k++) thicker &= leaves[k][0] >= leaves[k - 1][0] && leaves[k][0] > 0;
        check.check("leaves: a player standing is seen through 0 to 8 blocks of leaves, noticed later the thicker (" + leaves[1][0] + " ticks through 1, "
                + leaves[8][0] + " through 8, " + leaves[0][0] + " in the open)", thicker && leaves[0][0] > 0 && leaves[1][0] > leaves[0][0]);
        check.check("leaves: not seen through 9 or 10 blocks of leaves, whatever its bearing", max(leaves[9]) < 0 && max(leaves[10]) < 0);
        check.check("leaves: lying is noticed later than crouching, crouching later than standing, running sooner (" + grass[0][2] + "/" + grass[0][1] + "/"
                + grass[0][0] + "/" + grass[0][3] + ")", grass[0][2] > grass[0][1] && grass[0][1] > grass[0][0] && grass[0][3] < grass[0][0] && grass[0][3] > 0);
        check.check("leaves: short grass hides little: standing in it noticed within a fifth more than in the open (" + grass[1][0] + ", " + grass[0][0] + ")",
                grass[1][0] > 0 && grass[1][0] <= grass[0][0] * 1.2);
        check.check("leaves: lying in tall grass is seen, later than lying in the open (" + grass[2][2] + ", " + grass[0][2] + ")",
                grass[2][2] > grass[0][2] && grass[0][2] > 0);
        check.check("leaves: a soldier comes to know a foe behind 2 blocks of leaves and hits it through them (known after " + live[0][0] + " ticks, shots "
                + live[0][1] + ", hits " + live[0][2] + ")", live[0][0] >= 0 && live[0][2] > 0);
        check.check("leaves: a foe behind 10 blocks of leaves it never knows of, never fires at (" + live[1][0] + ", shots " + live[1][1] + ")",
                live[1][0] < 0 && live[1][1] == 0);
        return true;
    }

    private static int max(int[] a) {
        int m = Integer.MIN_VALUE;
        for (int v : a) m = Math.max(m, v);
        return m;
    }
}
