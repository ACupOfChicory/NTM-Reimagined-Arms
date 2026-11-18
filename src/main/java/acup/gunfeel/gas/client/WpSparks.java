package acup.gunfeel.gas.client;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeKind;
import acup.gunfeel.smoke.client.SmokeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client: the sparks off the phosphorus burning on the ground under a white phosphorus hand grenade's cloud
 * ({@link SmokeKind#WP}), as NTM's hazes threw them: each grenade of the cloud {@link #PER_TICK} a tick for
 * {@link #TICKS} ticks, a lava spark on the ground within 7 blocks of a point a normal 4 blocks about its burst. The
 * ground is the first top under the burst (NTM took the column's top, a roof's too). NTM's flames off its fire on the
 * ground are NTM's own. The rounds' and shells' clouds ({@link SmokeKind#WP_SHELL}) throw none: NTM's had no hazes.
 */
public final class WpSparks {
    /** Ticks a grenade's sparks go on: NTM's haze lives 600 to 700. */
    public static final int TICKS = 650;
    /** Sparks a tick a grenade: NTM's three hazes, one each. */
    public static final int PER_TICK = 3;
    /** How far above and below the burst the ground is looked for. */
    private static final int UP = 2, DOWN = 8;
    private static final RandomSource RANDOM = RandomSource.create();

    private WpSparks() {
    }

    /** Every client tick. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        SmokeClouds all = SmokeClient.current();
        if (all == null || all.isEmpty()) return;
        long time = level.getGameTime();
        for (SmokeClouds.Cloud c : all.clouds()) {
            if (c.kind() != SmokeKind.WP || c.catchingUp(time)) continue;
            int n = burning(c.grid) * PER_TICK;
            for (int i = 0; i < n; i++) spark(level, c.origin);
        }
    }

    /** The grenades of a cloud whose sparks still go: its own for {@link #TICKS} ticks, and each feed's from its start. */
    public static int burning(SmokeGrid g) {
        int age = g.age(), n = age < TICKS ? 1 : 0;
        for (int i = 0; i < g.feeds(); i++) {
            int from = g.feedFrom(i);
            if (age >= from && age < from + TICKS) n++;
        }
        return n;
    }

    private static void spark(ClientLevel level, BlockPos burst) {
        int x = (int) Math.floor(burst.getX() + 0.5 + RANDOM.nextGaussian() * 4) + RANDOM.nextInt(15) - 7;
        int z = (int) Math.floor(burst.getZ() + 0.5 + RANDOM.nextGaussian() * 4) + RANDOM.nextInt(15) - 7;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, burst.getY() + UP, z);
        boolean open = !blocks(level, p);
        for (int k = 0; k < UP + DOWN; k++) {
            p.move(0, -1, 0);
            boolean below = blocks(level, p);
            if (open && below) {
                level.addParticle(ParticleTypes.LAVA, x + RANDOM.nextDouble(), p.getY() + 1.1, z + RANDOM.nextDouble(), 0, 0, 0);
                return;
            }
            open = !below;
        }
    }

    // what NTM's column top stops at: a block that blocks movement, or a liquid
    private static boolean blocks(ClientLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.blocksMotion() || !s.getFluidState().isEmpty();
    }
}
