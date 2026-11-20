package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.NtmGuns;

// AiRoleBase.base, on NtmGuns.fireTraits's reads, decides as 26.2's AiRole.byFire did on NTM's own (its expression
// copied here as it was, on the same values): every way out of it, the edges of each number among them
class AiRoleBaseTest {
    private static final int[] DELAYS = {Integer.MIN_VALUE, -1, 0, 3, 4, 5, 6, 40, Integer.MAX_VALUE};
    private static final float[] VELOCITIES = {Float.NaN, Float.NEGATIVE_INFINITY, -5F, 0F, 4.99F, Math.nextDown(5F), 5F, 5.01F, 50F,
            Float.POSITIVE_INFINITY};
    private static final int[] CAPACITIES = {Integer.MIN_VALUE, -1, 0, 1, 39, 40, 41, 1000, Integer.MAX_VALUE};

    // AiRole.byFire before the port (2026-11-20), on the values NTM gave it; the magazine's capacity of 40 the default
    private static boolean old(boolean threw, boolean magNull, boolean refire, int delay, boolean bullet, float velocity, boolean laser,
            boolean single, int capacity) {
        if (threw) return false;
        if (magNull || !refire || delay > 4) return false;
        if (!bullet || velocity < 5F || laser) return false;
        int need = Math.max(1, Math.round(40F));
        return !single || capacity >= need;
    }

    // the port as 26.2's implementation answers on those values
    private static NtmGuns port(boolean threw, boolean magNull, boolean refire, int delay, boolean bullet, float velocity, boolean laser,
            boolean single, int capacity) {
        return (NtmGuns) Proxy.newProxyInstance(NtmGuns.class.getClassLoader(), new Class<?>[] {NtmGuns.class}, (p, m, a) -> {
            if (!m.getName().equals("fireTraits")) throw new AssertionError(m.getName());
            float[] out = (float[]) a[2];
            if (threw || magNull) return NtmGuns.NO_ROUNDS;
            out[NtmGuns.T_REFIRE] = refire ? 1F : 0F;
            out[NtmGuns.T_DELAY] = delay;
            out[NtmGuns.T_BULLET] = bullet ? 1F : 0F;
            out[NtmGuns.T_VELOCITY] = bullet ? velocity : 0F;
            out[NtmGuns.T_LASER] = bullet && laser ? 1F : 0F;
            out[NtmGuns.T_SINGLE] = single ? 1F : 0F;
            return single ? capacity : 0;
        });
    }

    @Test
    void baseIsByFire() {
        SplittableRandom r = new SplittableRandom(20261120);
        for (int i = 0; i < 100_000; i++) {
            boolean threw = r.nextInt(10) == 0, magNull = r.nextInt(10) == 0, refire = r.nextInt(4) != 0, bullet = r.nextInt(5) != 0,
                    laser = r.nextInt(5) == 0, single = r.nextBoolean();
            int delay = r.nextInt(3) == 0 ? r.nextInt() : DELAYS[r.nextInt(DELAYS.length)];
            float velocity = r.nextInt(3) == 0 ? (float) r.nextDouble(-10, 100) : VELOCITIES[r.nextInt(VELOCITIES.length)];
            int capacity = r.nextInt(3) == 0 ? r.nextInt() : CAPACITIES[r.nextInt(CAPACITIES.length)];
            String at = threw + " " + magNull + " " + refire + " " + delay + " " + bullet + " " + velocity + " " + laser + " " + single + " " + capacity;
            assertEquals(old(threw, magNull, refire, delay, bullet, velocity, laser, single, capacity),
                    AiRoleBase.base(port(threw, magNull, refire, delay, bullet, velocity, laser, single, capacity), null), at);
        }
    }

    @Test
    void theEdges() {
        // a machine gun: fast, a real bullet, a big magazine or a belt
        assertEquals(true, AiRoleBase.base(port(false, false, true, 4, true, 5F, false, true, 40), null));
        assertEquals(true, AiRoleBase.base(port(false, false, true, 1, true, 30F, false, false, 0), null));
        // each way out
        assertEquals(false, AiRoleBase.base(port(true, false, true, 1, true, 30F, false, false, 0), null), "threw");
        assertEquals(false, AiRoleBase.base(port(false, true, true, 1, true, 30F, false, false, 0), null), "no magazine");
        assertEquals(false, AiRoleBase.base(port(false, false, false, 1, true, 30F, false, false, 0), null), "no refire");
        assertEquals(false, AiRoleBase.base(port(false, false, true, 5, true, 30F, false, false, 0), null), "slow");
        assertEquals(false, AiRoleBase.base(port(false, false, true, 1, false, 30F, false, false, 0), null), "no bullet");
        assertEquals(false, AiRoleBase.base(port(false, false, true, 1, true, Math.nextDown(5F), false, false, 0), null), "slow bullet");
        assertEquals(false, AiRoleBase.base(port(false, false, true, 1, true, 30F, true, false, 0), null), "laser");
        assertEquals(false, AiRoleBase.base(port(false, false, true, 1, true, 30F, false, true, 39), null), "small magazine");
        // NaN is not under 5 (as before)
        assertEquals(true, AiRoleBase.base(port(false, false, true, 1, true, Float.NaN, false, false, 0), null), "NaN");
    }
}
