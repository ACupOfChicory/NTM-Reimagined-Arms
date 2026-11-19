package acup.gunfeel;

/**
 * What BalanceKit knows without the game: the shield's numbers (Shield, read from the server's keys) and its most
 * (maxShield, flaskLimit), the super stimpak's heal over time (addHeal, healStep) and an insert's floor on a bullet
 * (insertBullets). BalanceKit extends it on each version with the switches (the mod's balance feature), the effects,
 * the heals' table by creature and the events: its callers name these members through it, BalanceKit.Shield,
 * BalanceKit.maxShield and so on.
 */
public class BalanceKitBase {
    BalanceKitBase() {
    }

    /** NTM's numbers the balance's replace */
    public static final float NTM_MOD = 25F, NTM_FLASK = 5F, NTM_FLASK_LIMIT = 100F;

    /** the shield's numbers */
    public record Shield(float mod, float flask, float cap, int delay, float rate) {
        public static final Shield DEFAULT = new Shield(10F, 2F, 16F, 100, 0.002F);
    }

    static float number(String key, float def, float min, float max) {
        float v = ReaimTimes.number(key, def);
        return Float.isFinite(v) && v >= min && v <= max ? v : def;
    }

    public static Shield shield() {
        Shield d = Shield.DEFAULT;
        return new Shield(number("balance.shield.mod", d.mod(), 0F, 1000F), number("balance.shield.flask", d.flask(), 0F, 1000F),
                number("balance.shield.cap", d.cap(), 0F, 1000F), Math.round(number("balance.shield.delay", d.delay(), 0F, 72000F)),
                number("balance.shield.rate", d.rate(), 0F, 100F));
    }

    /**
     * The most shield: {@code stored} NTM's own count of flasks (5 a flask), {@code mod} what NTM's mod in the chestplate
     * gives (25, 0 without it).
     */
    public static float maxShield(float stored, float mod, Shield s) {
        float flasks = Math.max(0F, stored) / NTM_FLASK * s.flask();
        float fromMod = mod > 0F ? mod / NTM_MOD * s.mod() : 0F;
        return Math.min(s.cap(), flasks + fromMod);
    }

    /** NTM's count of flasks at which one more does nothing: the flasks alone reach the cap */
    public static float flaskLimit(Shield s) {
        if (!(s.flask() > 0F)) return 0F;
        return Math.min(NTM_FLASK_LIMIT, (float) Math.ceil(s.cap() / s.flask()) * NTM_FLASK);
    }

    /** the super stimpak's heal: its HP over this many ticks */
    public static final int STIMPAK_TICKS = 240;

    /** one more heal of {@code amount} on {@code h} (left, ticks): added, or the more of the two ({@code stack} off) */
    public static void addHeal(float[] h, float amount, boolean stack) {
        // without stacking a weaker heal changes nothing (nor stretches what is left over a fresh 12 s)
        if (!stack && amount <= h[0]) return;
        h[0] = stack ? h[0] + amount : amount;
        h[1] = STIMPAK_TICKS;
    }

    /** one tick of the heal: what to heal now, and what is left after it */
    public static float healStep(float[] h) {
        if (!(h[0] > 0F) || !(h[1] > 0F)) return 0F;
        float now = h[0] / h[1];
        h[0] -= now;
        h[1] -= 1F;
        return now;
    }

    /** an insert's share of a bullet's damage it leaves: NTM's, but not under {@code balance.inserts.bullets} */
    public static float insertBullets(float ntm, float floor) {
        return Math.max(ntm, floor);
    }
}
