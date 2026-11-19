package acup.gunfeel.nuke;

// The small nuclear cloud's numbers (MiniNukeCloud in 26.2: NTM's cloud, its packet, the waste, the volley's memory):
// its scale by the blast, the shock ring, the waste's reach, the heat its puffs are coloured by
public class MiniNukeCloudBase {
    MiniNukeCloudBase() {
    }

    // torex types of the mod's own, the colours of NTM's types 0 (a bomb) and 1 (the balefire bomb)
    public static final byte MINI = 64, MINI_BALE = 65;
    // NTM's EntityNukeTorex.statFac: scale = yield * 0.01
    static final float SCALE_PER_BLOCK = 0.01F;
    // a muke without a known blast nearby: the Fat Mine's and the standard Fat Man shell's radius
    static final float DEFAULT_SIZE = 10F;
    // the Fat Man's Tiny Tot volley (eight small blasts at once): one cloud the size of a standard shell's
    static final float TINY_VOLLEY_SIZE = 10F;
    // a tiny blast this close in blocks and ticks to the volley's first cloud joins it
    static final double MERGE_RANGE = 16;
    static final long MERGE_TICKS = 2;
    // a blast's radius (ExplosionVNT, NTM's MK5, ExplosionNukeSmall's params) counts for a muke this close, same tick
    static final double HINT_RANGE = 2;

    public static float scale(float size) {
        return Math.max(0.01F, Math.min(5F, size * SCALE_PER_BLOCK));
    }

    // the ticks a small cloud's shock ring grows (1.5 blocks a tick as a bomb's, NTM's bombs: 150 at any size): to
    // three times the blast's radius, where its waste ends (wasteRadius)
    public static int shockTicks(double scale) {
        return (int) Math.max(8, Math.min(150, Math.round(scale / SCALE_PER_BLOCK * WASTE_TIMES / 1.5)));
    }

    // a shock ring puff's width and height at least, blocks: NTM's are the scale times 5 and 2, half a block at a Fat
    // Mine's: specks of smoke on the ground, no ring to see (a bomb's are 5 blocks and more)
    static final float SHOCK_WIDE = 2.5F, SHOCK_HIGH = 1F;

    public static float shockWidth(float ntm) {
        return Math.max(SHOCK_WIDE, ntm);
    }

    public static float shockHeight(float ntm) {
        return Math.max(SHOCK_HIGH, ntm);
    }

    // NTM's shock ring is laid down a tick at a time at 1.5 blocks a tick more, its puffs going out at the scale times
    // 0.25 a tick (still at a small one's) and living up to 400 ticks: under scale 1 the ring left behind fills its
    // disc, smoke all over the ground and no ring to see. A small cloud's puffs ride the ring out at its own pace and
    // live SHOCK_LIFE ticks: a band at its edge, the ground behind it clear
    static final double SHOCK_PACE = 1.5;
    static final int SHOCK_LIFE = 10;

    // the motion NTM's shock puff is given (its own speed: the scale times 0.25) for it to go out SHOCK_PACE a tick
    public static double shockMotion(double scale) {
        return SHOCK_PACE / Math.max(1e-3, scale * 0.25);
    }

    public static int shockLife() {
        return SHOCK_LIFE;
    }

    // the ground laid waste round a small blast out to this many times its radius (within it the crater is bare), and
    // the passes made (the stand)
    static final float WASTE_TIMES = 3F, WASTE_WHOLE = 2F;

    public static float wasteRadius(float blast) {
        return blast * WASTE_TIMES;
    }

    // NTM's bomb at scale 1: its heat at the start (EntityNukeTorex.simulateTick: (int) (50 * s * s))
    static final double BOMB_HEAT = 50;

    // a small cloud's heat at an age, the one its puffs' colour is drawn from (EntityNukeTorex.Cloudlet.updateColor:
    // the colour falls off with the distance squared over the heat). NTM cuts the heat to a whole number, 50 * s * s:
    // under scale 0.142 (a blast of radius 14) none at all, and the cloud is grey from its first tick. Here a bomb's
    // at scale 1, cooling the same way, by the scale squared as the puffs' distances go by the scale: the colours of
    // a bomb's cloud the size of this one
    public static double heat(double scale, int age, int maxAge) {
        double cooled = BOMB_HEAT - Math.pow(BOMB_HEAT * age / Math.max(1, maxAge), 0.6);
        return Math.max(1e-3, scale * scale * cooled);
    }

    // the shock ring's puffs a tick at most (NTM's bombs: 100), 60 at least: fewer and a ring is a spot
    static final int SHOCK_PUFFS = 60;

    public static int shockPuffs(double scale) {
        return (int) Math.max(SHOCK_PUFFS, Math.min(100, Math.round(100 * scale / 0.25)));
    }

    // the smoke the ring leaves lying over the ground behind it (MixinMiniNukeTorex): NTM's own shock puffs, as many a
    // tick as before the ring (a bomb's 100 at scale 0.25, fewer for a smaller cloud)
    public static int smokePuffs(double scale) {
        return (int) Math.max(8, Math.min(100, Math.round(100 * scale / 0.25)));
    }
}
