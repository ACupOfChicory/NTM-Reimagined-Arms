package acup.gunfeel;

import net.minecraft.util.RandomSource;

/** 26.2's generator as core's GameRandom: each call is the same call on it. */
public final class GameRandoms {
    private GameRandoms() {
    }

    public static GameRandom of(RandomSource r) {
        return new GameRandom() {
            @Override
            public double nextDouble() {
                return r.nextDouble();
            }

            @Override
            public boolean nextBoolean() {
                return r.nextBoolean();
            }
        };
    }
}
