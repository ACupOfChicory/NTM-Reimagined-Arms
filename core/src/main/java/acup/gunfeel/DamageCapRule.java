package acup.gunfeel;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The damage cap's rule, without the game (DamageCap applies it): what one shooter may take off one target.
 *
 * <ul>
 * <li>one click (every projectile one shooter fired in one game tick: a shotgun's pellets, SPAS-12's double shot, the
 * double barrel's two barrels, Liberator's four rounds, both akimbo guns) takes at most {@code click} of the target's
 * max health, {@code head} when the hit is on the head, {@code sniperHead} when it is on the head and the gun waits
 * {@code sniperDelay} ticks or more between shots;</li>
 * <li>any {@code windowTicks} ticks in a row take at most {@code window} of it (a sniper's head click its own cap,
 * when that is more).</li>
 * </ul>
 * What counts is what the target loses: health, absorption and NTM's shield together. A shield's share taken over the
 * cap is given back (the {@code shield} of {@link #take}).
 */
public final class DamageCapRule {
    /** the shares of max health and the ticks; {@code sniperDelay} is used by DamageCap, kept here with the rest */
    public record Limits(float click, float head, float window, int windowTicks, float sniperHead, int sniperDelay) {
        public static final Limits DEFAULT = new Limits(0.6F, 0.8F, 0.8F, 14, 1.0F, 25);

        /** the cap of one click, as a share of max health */
        public float clickCap(boolean onHead, boolean sniper) {
            return onHead ? (sniper ? sniperHead : head) : click;
        }
    }

    /** how long a click's damage is kept to add its late pellets to (a held client hit, a slow shell's blast) */
    public static final int KEEP_TICKS = 200;

    private record Taken(long tick, long click, float amount) {
    }

    /** what one shooter took off one target lately */
    public static final class Ledger {
        private final ArrayDeque<Taken> taken = new ArrayDeque<>();
        private long last = Long.MIN_VALUE;

        /** nothing kept from the last {@link #KEEP_TICKS} ticks before {@code now} */
        public boolean idle(long now) {
            return last == Long.MIN_VALUE || now - last >= KEEP_TICKS;
        }

        private void prune(long now) {
            for (Iterator<Taken> it = taken.iterator(); it.hasNext();) if (now - it.next().tick() >= KEEP_TICKS) it.remove();
        }

        /** taken by the click fired at {@code click} */
        public float ofClick(long click) {
            float sum = 0;
            for (Taken t : taken) if (t.click() == click) sum += t.amount();
            return sum;
        }

        /** taken in the {@code ticks} ticks up to {@code now}, this one too */
        public float inWindow(long now, int ticks) {
            float sum = 0;
            for (Taken t : taken) if (now - t.tick() < ticks) sum += t.amount();
            return sum;
        }
    }

    /** what a hit leaves: the damage to health (and absorption), and the shield to give back */
    public record Cut(float damage, float shield) {
    }

    private DamageCapRule() {
    }

    /**
     * One hit. {@code now}: the game tick; {@code click}: the tick its projectile was fired; {@code maxHealth}: the
     * target's; {@code shield}: what NTM's shield took of it already; {@code damage}: what is left for absorption and
     * health. The hit is written down as taken.
     */
    public static Cut take(Ledger ledger, Limits limits, long now, long click, boolean onHead, boolean sniper, float maxHealth,
            float shield, float damage) {
        ledger.prune(now);
        float clickCap = limits.clickCap(onHead, sniper);
        float windowCap = Math.max(limits.window(), clickCap);
        float room = Math.min(clickCap * maxHealth - ledger.ofClick(click), windowCap * maxHealth - ledger.inWindow(now, limits.windowTicks()));
        room = Math.max(0F, room);
        float s = Math.max(0F, shield), d = Math.max(0F, damage);
        // the shield went first: it keeps its share, the rest of the room is health's
        float keptShield = Math.min(s, room);
        float keptDamage = Math.min(d, room - keptShield);
        float total = keptShield + keptDamage;
        if (total > 0F) ledger.taken.addLast(new Taken(now, click, total));
        ledger.last = now;
        return new Cut(keptDamage, s - keptShield);
    }
}
