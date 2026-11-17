package acup.gunfeel.ammo;

/**
 * The nuclear .50's numbers (no Minecraft: the tests drive it). Its blast is NTM's Fat Mine's (BlockLandmineFat) times
 * SCALE, breaking no blocks (owner 2026-11-17); only the AMAT rifles fire it, and it wears each out in a few shots.
 */
public final class NuclearRule {
    private NuclearRule() {
    }

    /** of the Fat Mine's blast */
    public static final float SCALE = 0.8F;
    /** the Fat Mine's: ExplosionVNT's size, the radiation's multiplier, the entity processor's node distance and range */
    public static final float MINE_SIZE = 10F, MINE_RAD = 1.5F, MINE_RANGE_MOD = 1.5F;
    public static final double MINE_NODE = 2.0;
    /** the Fat Mine's sound volume (GUN_MINI_NUKE_EXPLOSION), and how far its cloud is sent */
    public static final float MINE_VOLUME = 25F;

    public static float size() {
        return MINE_SIZE * SCALE;
    }

    public static float rad() {
        return MINE_RAD * SCALE;
    }

    /** the blast's damage of the Fat Mine's {@code mineDamage} (NTM's explosion config, mines' nuke_damage) */
    public static float damage(double mineDamage) {
        return (float) (mineDamage * SCALE);
    }

    /** the rifles that fire it (NTM's ids) and the shots each takes to wear out from new */
    public static int shots(String gunId) {
        return switch (gunId) {
            case "gun_amat" -> 3;
            case "gun_amat_subtlety" -> 6;
            case "gun_amat_penance" -> 10;
            default -> 0;
        };
    }

    /**
     * the wear after a shot: {@code durability / shots} more than {@code wear} (a fixed share of the gun's durability a
     * shot, whatever wear it had), no more than the durability, and all of it once within a hair of it (the float's sum
     * of three thirds)
     */
    public static float wear(float wear, float durability, int shots) {
        if (shots <= 0 || !(durability > 0F)) return wear;
        float w = wear + durability / shots;
        return w >= durability * (1F - 1e-4F) ? durability : w;
    }
}
