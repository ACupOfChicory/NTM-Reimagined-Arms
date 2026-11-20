package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.util.RandomSource;

import org.junit.jupiter.api.Test;

// core's GameRandom over the game's generator is that generator: from one seed, the numbers through it are the
// game's own, call for call, whatever is asked in turn
class GameRandomsTest {
    @Test
    void theGamesNumbersInItsSequence() {
        RandomSource game = RandomSource.create(20261120L), ours = RandomSource.create(20261120L);
        GameRandom r = GameRandoms.of(ours);
        GameRandom other = GameRandoms.of(ours);
        assertEquals(game.nextInt(41), r.nextInt(41));
        assertEquals(game.nextInt(41), other.nextInt(41));
        assertEquals(game.nextInt(41), r.nextInt(41));
        for (int i = 0; i < 10_000; i++) {
            assertEquals(game.nextLong(), r.nextLong());
            switch (i % 3) {
                case 0 -> assertEquals(Double.doubleToRawLongBits(game.nextDouble()), Double.doubleToRawLongBits(r.nextDouble()));
                case 1 -> assertEquals(game.nextBoolean(), r.nextBoolean());
                default -> assertEquals(Float.floatToRawIntBits(game.nextFloat()), Float.floatToRawIntBits(r.nextFloat()));
            }
        }
    }
}
