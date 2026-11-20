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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The AI check's medic (AiMedic, stage 5 R4a). Rounds by turns, aiMedic off (before) and on:
 * <ul>
 * <li>quiet: four soldiers and a medic in a row, no one to fight; two of them hurt to HURT of their health, and a
 * player of their side (a fake one, in survival) hurt as much 10 blocks off. QUIET ticks. Counted: their health at
 * the end, as a share, and the tick each was treated;</li>
 * <li>fights: the same five, two of them hurt, with low walls 3.5 blocks before them, against THEIRS 50 blocks off
 * with such walls before them (not every one behind one, either side), all with LONGER times their health; AFTER
 * ticks more once one side is gone (the medic goes round the hurt after a fight). FIGHTS_OFF with aiMedic off,
 * then by turns on with aiMedicCover off and on with it on (R4a+, care under fire), EACH of either. Counted: who is
 * left, kits used (and in the fight itself), runs, the hurt off to cover, whether the medic lived.</li>
 * </ul>
 */
final class AiCheckMedic {
    static final int X0 = 1210, Z0 = 0, W = 40, L = 72, QUIET = 600, FIGHT = 1600, AFTER = 400, QUIETS = 4, THEIRS = 4;
    /**
     * fights with aiMedic off (it uses none), then on with aiMedicCover off and on by turns, EACH of either:
     * in about half the fights the medic uses no kit, three of each failed one run in four
     */
    static final int FIGHTS_OFF = 1, EACH = 6, FIGHTS = FIGHTS_OFF + 2 * EACH, ROUNDS = QUIETS + FIGHTS;
    static final float HURT = 0.3F, LONGER = 3F;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    static final double THEIR_Z = 10.5, OUR_Z = 60.5;
    static final int[][] LOW = {{1218, 14, 1222}, {1227, 14, 1231}, {1236, 14, 1240}, {1218, 57, 1222}, {1227, 57, 1231}, {1236, 57, 1240}};

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, over = -1;
    private final List<Mob> ours = new ArrayList<>(), theirs = new ArrayList<>(), hurt = new ArrayList<>();
    private Mob medic;
    private FakePlayer player;
    private Item rifle;
    /** the tick each hurt one (the two soldiers, the player) was first above HURT and a little, -1: not yet */
    private final int[] treatedAt = new int[3];
    private int treatedBefore, runsBefore, coverBefore, kitsInFight, medicShots0, medicShotsFight;
    /**
     * per round: {on, fight, ours left, theirs left, ticks, kits on others, runs, medic alive, health of the two *100,
     * of the player *100, treated at x3, mode (0 off, 1 on, 2 on with cover), kits in the fight, to cover}
     */
    private final List<int[]> results = new ArrayList<>();

    AiCheckMedic(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private boolean fight() {
        return round >= QUIETS;
    }

    /** 0: aiMedic off; 1: on, aiMedicCover off; 2: both on (quiet rounds: 0 and 2 by turns) */
    private int mode() {
        return fight() ? round - QUIETS < FIGHTS_OFF ? 0 : (round - QUIETS - FIGHTS_OFF) % 2 + 1 : round % 2 * 2;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + W) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + L) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            rifle = BuiltInRegistries.ITEM.getValue(RIFLE);
            start(0);
            return false;
        }
        ticks++;
        for (int i = 0; i < 3; i++) {
            LivingEntity e = i < 2 ? hurt.get(i) : player;
            if (e != null && treatedAt[i] < 0 && e.isAlive() && e.getHealth() > (HURT + 0.1F) * e.getMaxHealth()) treatedAt[i] = ticks;
        }
        int left = 0, foes = 0;
        for (Mob m : ours) if (m.isAlive()) left++;
        for (Mob m : theirs) if (m.isAlive()) foes++;
        if (fight() && over < 0 && (ticks >= FIGHT || left == 0 || foes == 0)) {
            over = ticks;
            kitsInFight = AiMedic.treated - treatedBefore;
            medicShotsFight = AiBots.get(medic) instanceof AiBot mb ? mb.gunner.shots - medicShots0 : 0;
        }
        if (fight() ? over < 0 || ticks < over + AFTER && left > 0 : ticks < QUIET) return false;
        int two = 0;
        for (Mob m : hurt) two += m.isAlive() ? Math.round(100 * m.getHealth() / m.getMaxHealth()) : 0;
        int pl = player == null ? -1 : Math.round(100 * player.getHealth() / player.getMaxHealth());
        results.add(new int[] {mode() > 0 ? 1 : 0, fight() ? 1 : 0, left, foes, ticks, AiMedic.treated - treatedBefore, AiMedic.runs - runsBefore, medic.isAlive() ? 1 : 0,
                two / 2, pl, treatedAt[0], treatedAt[1], treatedAt[2], mode(), fight() ? kitsInFight : 0, AiMedic.toCover - coverBefore, fight() ? medicShotsFight : 0});
        for (Mob m : ours) m.discard();
        for (Mob m : theirs) m.discard();
        ours.clear();
        theirs.clear();
        hurt.clear();
        if (player != null) {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            player = null;
        }
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + W; x++)
            for (int z = Z0; z < Z0 + L; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 8; h++) set(x, y + h, z, air);
            }
        for (int[] w : LOW) for (int x = w[0]; x <= w[2]; x++) set(x, y, w[1], stone);
        TacticalMap.of(level).want(X0 + W / 2, y, Z0 + L / 2, L / 2 + 4, 8);
    }

    private Mob spawn(double x, double z, String team, float yaw) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, rifle).get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiTeams.join(level, m, team);
        // a fight: all as hard to kill by LONGER (both sides alike), the fight longer, the medic's time in it
        if (fight()) {
            var h = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (h != null) h.setBaseValue(h.getBaseValue() * LONGER);
            m.setHealth(m.getMaxHealth());
        }
        AiBot b = AiBots.get(m);
        if (b != null) b.gunner.ammo.set(b.gunner.capacity() * 4);
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        over = -1;
        java.util.Arrays.fill(treatedAt, -1);
        AiMedic.on = mode() > 0;
        AiMedic.cover = mode() == 2;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        build();
        for (int i = 0; i < 4; i++) ours.add(spawn(1219.5 + i * 6 + (i >= 2 ? 6 : 0), Z0 + OUR_Z, "med_a", 180F));
        medic = spawn(1231.5, Z0 + OUR_Z + 3, "med_a", 180F);
        ours.add(medic);
        AiBot mb = AiBots.get(medic);
        if (mb != null) {
            AiRoles.assign(mb, AiRole.MEDIC);
            mb.medic = null;
            mb.mob.getPersistentData().remove(AiMedic.KITS_TAG);
        }
        hurt.add(ours.get(0));
        hurt.add(ours.get(3));
        for (Mob m : hurt) m.setHealth(HURT * m.getMaxHealth());
        if (fight()) {
            for (int i = 0; i < THEIRS; i++) theirs.add(spawn(1219.5 + i * 6, Z0 + THEIR_Z, "med_b", 0F));
        } else {
            player = new FakePlayer(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7e0" + String.format(Locale.ROOT, "%02d", r)), "armsai_medic" + r));
            player.setGameMode(GameType.SURVIVAL);
            player.snapTo(1231.5, y, Z0 + OUR_Z + 9, 0F, 0F);
            level.addNewPlayer(player);
            AiCheckReact.hittable(player);
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20);
            player.setHealth(HURT * player.getMaxHealth());
            var board = level.getScoreboard();
            board.addPlayerToTeam(player.getScoreboardName(), board.getPlayerTeam("med_a"));
        }
        treatedBefore = AiMedic.treated;
        runsBefore = AiMedic.runs;
        coverBefore = AiMedic.toCover;
        kitsInFight = 0;
        medicShotsFight = 0;
        medicShots0 = AiBots.get(medic) instanceof AiBot mb0 ? mb0.gunner.shots : 0;
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        for (Mob a : ours) for (Mob b : theirs) {
            AiBot ba = AiBots.get(a), bb = AiBots.get(b);
            if (ba != null) ba.memory.seen(b.getId(), b.getX(), b.getY(), b.getZ(), now, 0);
            if (bb != null) bb.memory.seen(a.getId(), a.getX(), a.getY(), a.getZ(), now, 0);
        }
    }

    private boolean finish() {
        AiMedic.on = true;
        AiMedic.cover = true;
        String[] names = {"aiMedic off (before)", "aiMedic on, aiMedicCover off", "aiMedic on"};
        // [mode][fight]: rounds, ours left, theirs left, kits, runs, medic alive, the two's health, the player's, treated (of the three),
        // kits in the fight, to cover
        double[][][] sum = new double[3][2][12];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            double[] s = sum[r[13]][r[1]];
            s[0]++;
            s[1] += r[2];
            s[2] += r[3];
            s[3] += r[5];
            s[4] += r[6];
            s[5] += r[7];
            s[6] += r[8];
            s[7] += Math.max(0, r[9]);
            for (int k = 10; k < 13; k++) if (r[k] >= 0) s[8]++;
            s[9] += r[14];
            s[10] += r[15];
            s[11] += r[16];
            check.out(String.format(Locale.ROOT, "     medic, round %d, %s, %s: left %d of ours, %d of theirs, %d ticks; kits on others %d (%d in the fight), runs %d,"
                    + " to cover %d, the medic %s, its shots in the fight %d; the two hurt at %d%% of their health, the player at %d%%; treated at ticks %d, %d, %d", i + 1, r[1] == 1 ? "a fight" : "quiet",
                    names[r[13]], r[2], r[3], r[4], r[5], r[14], r[6], r[15], r[7] == 1 ? "alive" : "down", r[16], r[8], r[9], r[10], r[11], r[12]));
        }
        for (int m = 0; m < 3; m++)
            for (int f = 0; f < 2; f++) {
                double[] s = sum[m][f];
                if (s[0] == 0) continue;
                check.out(String.format(Locale.ROOT, "     medic, %s, %s, %.0f rounds: left %.0f of ours of %.0f, %.0f of theirs of %.0f; kits on others %.0f (%.0f in the fight),"
                        + " runs %.0f, to cover %.0f; the medic alive in %.0f; the two hurt at %.0f%% on average%s", f == 1 ? "fights" : "quiet", names[m], s[0], s[1],
                        s[0] * 5, s[2], f == 1 ? s[0] * THEIRS : 0, s[3], s[9], s[4], s[10], s[5], s[6] / s[0],
                        f == 1 ? "" : String.format(Locale.ROOT, ", the player at %.0f%%; treated %.0f of %.0f", s[7] / s[0], s[8], s[0] * 3)));
            }
        check.out("     " + AiMedic.info());
        double[] qOn = sum[2][0], qOff = sum[0][0], fOff = sum[0][1], fPlain = sum[1][1], fCover = sum[2][1];
        check.check("medic: all rounds ran", results.size() == ROUNDS);
        check.check(String.format(Locale.ROOT, "medic: with no one to fight it treats the two hurt and the player of its side (%.0f of %.0f treated; the two at %.0f%%, the player at %.0f%%;"
                + " off: %.0f treated)", qOn[8], qOn[0] * 3, qOn[0] == 0 ? 0 : qOn[6] / qOn[0], qOn[0] == 0 ? 0 : qOn[7] / qOn[0], qOff[3]),
                qOn[0] > 0 && qOn[8] == qOn[0] * 3 && qOff[3] == 0);
        check.check(String.format(Locale.ROOT, "medic: in the fights it treats the hurt, with aiMedicCover too (%.0f kits on others, %.0f with it, %.0f off)", fPlain[3], fCover[3],
                fOff[3]), fPlain[3] > 0 && fCover[3] > 0 && fOff[3] == 0);
        check.check(String.format(Locale.ROOT, "medic: the hurt go to cover for it with aiMedicCover (%.0f times on, %.0f off, %.0f with no medic)", fCover[10], fPlain[10], fOff[10]),
                fCover[10] > 0 && fPlain[10] == 0 && fOff[10] == 0);
        // R4a+ (X1115): while its squad fights, the medic fights too, and treats them after the fight
        check.check(String.format(Locale.ROOT, "medic: in the fights it fires with the squad while they see the foe (%.0f shots in %.0f fights with aiMedicCover, %.0f without)",
                fCover[11], fCover[0], fPlain[11]), fCover[11] > 0);
        check.check(String.format(Locale.ROOT, "medic: with aiMedicCover it treats the hurt after the fight (%.0f kits after, %.0f in it, of %.0f fights)", fCover[3] - fCover[9], fCover[9], fCover[0]),
                fCover[3] - fCover[9] > 0);
        return true;
    }
}
