package acup.gunfeel;

// The living spread's arithmetic, in NTM's spread units (a shot leans off by spread × a normal random per axis, 0.025
// is about 1.4°). No game classes here, so the tests check it; Spread feeds it the shooter's state and the settings.
//
// spread = NTM's spread × body-state multipliers + speed + air + bloom
//  - speed: the walking multiplier (spreadMove) grows with the shooter's ground speed, full at walking speed, and on
//    top of it spreadSpeed is added per walking speed, so even a gun with no spread of its own loses accuracy moving;
//  - air: spreadAir is added while the shooter is off the ground (jumping, falling);
//  - bloom: every shot adds spreadBloom, at most spreadBloomMax, and it halves every spreadBloomHalfLife seconds,
//    so a long burst spreads out and single shots or short bursts stay accurate.
public final class SpreadModel {
    // a player's walking and sprinting speed on flat ground, blocks per tick
    public static final float WALK = 0.216F, SPRINT = 0.281F;
    // slower than this is standing (drifting, turning on the spot)
    public static final float STILL = 0.02F;

    private SpreadModel() {
    }

    // how far into "moving" the shooter is: 0 standing, 1 walking, about 1.3 sprinting, never more than sprinting
    public static float pace(float speed) {
        if (speed < STILL) return 0F;
        return Math.min(speed, SPRINT * 1.25F) / WALK;
    }

    // the walking multiplier at that pace: 1 standing, spreadMove walking, past it when sprinting
    public static float moveMult(float spreadMove, float pace) {
        return 1F + (spreadMove - 1F) * pace;
    }

    public static float speedAdd(float spreadSpeed, float pace) {
        return Math.max(0F, spreadSpeed) * pace;
    }

    // the whole shot: NTM's spread times the state multipliers, the speed and air parts added, bloom held down by stance
    public static float total(float ntm, float mult, float spreadSpeed, float pace, float air, float bloom, float stance) {
        return Math.max(0F, ntm * Math.max(0F, mult) + speedAdd(spreadSpeed, pace) + Math.max(0F, air) + bloom * stance);
    }

    // what is left of a bloom after that many ticks
    public static float decay(float bloom, long ticks, float halfLifeSeconds) {
        if (ticks <= 0) return bloom;
        if (halfLifeSeconds <= 0) return 0F;
        return bloom * (float) Math.pow(0.5, ticks / (halfLifeSeconds * 20.0));
    }

    // the bloom a shot leaves: what is left of the last one plus this shot's, capped
    public static float afterShot(float left, float perShot, float max) {
        return Math.min(Math.max(0F, max), left + Math.max(0F, perShot));
    }

    // the bloom a shot meets in a burst held down long enough, one shot every delayTicks: the last one has decayed and
    // a new one is added, until it settles (or hits the cap)
    public static float steadyBloom(int delayTicks, float perShot, float max, float halfLifeSeconds) {
        float b = 0F;
        for (int i = 0; i < 400; i++) b = afterShot(decay(b, Math.max(1, delayTicks), halfLifeSeconds), perShot, max);
        return decay(b, Math.max(1, delayTicks), halfLifeSeconds);
    }
}
