package acup.gunfeel;

/**
 * The game's random numbers for core's code: only what core asks of them. The version passes its own generator through
 * (26.2: GameRandoms.of(RandomSource), often a level's or a mob's), so core's calls are the same calls on the same object
 * and the game's sequence goes on as before; core keeps no generator of its own.
 */
public interface GameRandom {
    /** A seed in the same sequence (26.2: RandomSource.nextLong). */
    long nextLong();
    /** RandomSource.nextInt(bound), in the same sequence as the other draws. */
    int nextInt(int bound);

    /** RandomSource.nextDouble */
    double nextDouble();

    /** RandomSource.nextBoolean */
    boolean nextBoolean();

    /** RandomSource.nextFloat */
    float nextFloat();

    /** RandomSource.nextGaussian (the second of each pair kept by the generator itself) */
    double nextGaussian();
}
