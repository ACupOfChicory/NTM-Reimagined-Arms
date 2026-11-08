package acup.gunfeel.ai;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The AI check's head with no one to fight: AI mobs by a player in creative (whom they neither see nor hear). Six
 * rounds on a field of its own: FBI agents and dummies by a player in creative from the start, FBI agents and
 * dummies that fought the player in survival (in the open, 10 blocks off) till the player went to creative, and FBI
 * agents and dummies by a player in creative with cows and chickens wandering round them. Counted
 * on the server, tick by tick, with no one to fight (the whole round; after the switch): how far its head turned a
 * tick, on average and at most, the ticks it turned more than 20 degrees, and how often it turned back the other way
 * (both ways 5 degrees or more), and in what modes.
 */
final class AiCheckIdle {
    static final int X0 = 580, Z0 = 4, SIZE = 40, RUN = 500, FOUGHT = 100, AFTER = 600;
    static final double PX = 600.5, PZ = 14.5, MX = 600.5, MZ = 24.5;
    static final int MOBS = 2;
    /** a tick's turn this far is a jerk, degrees; a turn back after one this far is a reversal */
    static final float JERK = 20F, BACK = 5F;
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private record Round(String name, AiSpawn.Kind kind, boolean fought, boolean animals) {
        Round(String name, AiSpawn.Kind kind, boolean fought) {
            this(name, kind, fought, false);
        }
    }

    private static final List<Round> ROUNDS = List.of(new Round("FBI agents, a player in creative by them", AiSpawn.Kind.FBI, false),
            new Round("dummies, a player in creative by them", AiSpawn.Kind.DUMMY, false),
            new Round("FBI agents that fought the player, gone to creative", AiSpawn.Kind.FBI, true),
            new Round("dummies that fought the player, gone to creative", AiSpawn.Kind.DUMMY, true),
            new Round("FBI agents, a player in creative, cows and chickens about", AiSpawn.Kind.FBI, false, true),
            new Round("dummies, a player in creative, cows and chickens about", AiSpawn.Kind.DUMMY, false, true));
    private final List<net.minecraft.world.entity.Entity> animals = new java.util.ArrayList<>();

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int round = -1, ticks;
    private FakePlayer player;
    private List<Mob> mobs = List.of();
    private float[] last, lastTurn;
    /** this round: ticks counted, turn sum, the most in a tick, jerks, reversals; pitch sum */
    private long counted, jerks, reversals;
    private double sum, most, pitchSum;
    private float[] lastPitch;
    private final Map<String, double[]> byMode = new TreeMap<>();
    /** per round: {ticks, mean, most, jerks, reversals, mean pitch} */
    private final double[][] results = new double[ROUNDS.size()][];
    private final String[] modes = new String[ROUNDS.size()];

    AiCheckIdle(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (round < 0) {
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        Round r = ROUNDS.get(round);
        player.snapTo(PX, y, PZ, 0F, 0F);
        player.setHealth(player.getMaxHealth());
        for (Mob m : mobs) m.setHealth(m.getMaxHealth());
        if (r.fought && ticks == FOUGHT) player.setGameMode(GameType.CREATIVE);
        boolean counting = !r.fought || ticks > FOUGHT;
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            float head = m.getYHeadRot(), pitch = m.getXRot();
            if (counting) {
                float d = Mth.wrapDegrees(head - last[i]);
                float a = Math.abs(d);
                counted++;
                sum += a;
                most = Math.max(most, a);
                pitchSum += Math.abs(pitch - lastPitch[i]);
                if (a > JERK) jerks++;
                if (a >= BACK && Math.abs(lastTurn[i]) >= BACK && Math.signum(d) != Math.signum(lastTurn[i])) reversals++;
                lastTurn[i] = d;
                AiBot b = AiBots.get(m);
                String mode = b != null && b.brain instanceof AiSoldier s ? s.mode().name() : "-";
                double[] t = byMode.computeIfAbsent(mode, k -> new double[3]);
                t[0]++;
                t[1] += a;
                t[2] = Math.max(t[2], a);
            }
            last[i] = head;
            lastPitch[i] = pitch;
        }
        if (ticks >= (r.fought ? FOUGHT + AFTER : RUN)) {
            results[round] = new double[] {counted, counted == 0 ? 0 : sum / counted, most, jerks, reversals, counted == 0 ? 0 : pitchSum / counted};
            StringBuilder sb = new StringBuilder();
            for (var e : byMode.entrySet())
                sb.append(String.format(Locale.ROOT, " %s %d ticks %.1f/tick (most %.0f)", e.getKey(), (long) e.getValue()[0], e.getValue()[1] / e.getValue()[0], e.getValue()[2]));
            modes[round] = sb.toString();
            for (Mob m : mobs) m.discard();
            for (var a : animals) a.discard();
            animals.clear();
            player.discard();
            if (round + 1 >= ROUNDS.size()) return finish();
            start(round + 1);
        }
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        counted = jerks = reversals = 0;
        sum = most = pitchSum = 0;
        byMode.clear();
        Round rd = ROUNDS.get(r);
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        TacticalMap.of(level).want((int) MX, y, (int) MZ, 20, 6);
        player = new FakePlayer(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7d00" + r), "armsai_idle" + r));
        player.setGameMode(rd.fought ? GameType.SURVIVAL : GameType.CREATIVE);
        player.snapTo(PX, y, PZ, 0F, 0F);
        level.addNewPlayer(player);
        AiCheckReact.hittable(player);
        mobs = AiSpawn.spawn(level, new Vec3(MX, y, MZ), rd.kind, MOBS, BuiltInRegistries.ITEM.getValue(GUN));
        for (Mob m : mobs) {
            AiBot b = AiBots.get(m);
            if (b != null) {
                b.gunner.ammo.setInfinite();
                b.grenades = 0;
            }
        }
        if (rd.animals) for (int i = 0; i < 6; i++) {
            // wandering round them, 4 to 12 blocks off: their steps and calls
            var type = i % 2 == 0 ? net.minecraft.world.entity.EntityTypes.COW : net.minecraft.world.entity.EntityTypes.CHICKEN;
            var a = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (a == null) continue;
            double ang = Math.PI * 2 * i / 6, far = 4 + i * 1.5;
            a.snapTo(MX + Math.cos(ang) * far, y, MZ + Math.sin(ang) * far, 0F, 0F);
            level.addFreshEntity(a);
            animals.add(a);
        }
        AiBots.refreshTargets(level);
        last = new float[mobs.size()];
        lastTurn = new float[mobs.size()];
        lastPitch = new float[mobs.size()];
        for (int i = 0; i < mobs.size(); i++) {
            last[i] = mobs.get(i).getYHeadRot();
            lastPitch[i] = mobs.get(i).getXRot();
        }
    }

    private boolean finish() {
        double worstMean = 0, worstMost = 0;
        long jerkAll = 0, backAll = 0;
        for (int i = 0; i < ROUNDS.size(); i++) {
            double[] r = results[i];
            if (r == null) {
                check.check("the idle head: all rounds ran", false);
                return true;
            }
            check.out(String.format(Locale.ROOT, "     idle head, %s: %d mob-ticks, head turned %.2f degrees a tick on average, %.0f at most; %d ticks over %.0f,"
                    + " %d turns back; pitch %.2f a tick; by mode:%s", ROUNDS.get(i).name, (long) r[0], r[1], r[2], (long) r[3], JERK, (long) r[4], r[5], modes[i]));
            worstMean = Math.max(worstMean, r[1]);
            worstMost = Math.max(worstMost, r[2]);
            jerkAll += (long) r[3];
            backAll += (long) r[4];
        }
        check.check("with no one to fight its head turns 2 degrees a tick at most on average, never over 20 in a tick, never back and forth",
                worstMean <= 2 && worstMost <= JERK && jerkAll == 0 && backAll == 0);
        return true;
    }
}
