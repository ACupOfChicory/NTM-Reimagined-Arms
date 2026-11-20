package acup.gunfeel;

import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.host.RoundTable;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 26.2's RoundTable: a round's name by its number (RoundNumbers), BalanceGuns.ammoName, kept once its ammo item is in
 * the registry (a name worked out before, config_&lt;id&gt; or what an item not yet registered gives, is not kept: it is
 * worked out again next time).
 */
final class RoundNames implements RoundTable {
    /** by number; grown under the lock, read without it (an array once filled in is never changed) */
    private volatile String[] names = new String[64];

    @Override
    public String name(int round) {
        String[] a = names;
        if (round >= 0 && round < a.length) {
            String n = a[round];
            if (n != null) return n;
        }
        return fill(round);
    }

    private synchronized String fill(int round) {
        String[] a = names;
        if (round >= 0 && round < a.length && a[round] != null) return a[round];
        BulletConfig b = RoundNumbers.round(round);
        if (b == null) throw new IllegalArgumentException("no round has number " + round);
        String n = BalanceGuns.ammoName(b);
        if (!registered(b)) return n;
        String[] c = java.util.Arrays.copyOf(a, Math.max(a.length, Integer.highestOneBit(round) << 1));
        c[round] = n;
        names = c;
        return n;
    }

    // its ammo item is one the registry has: the name ammoName gave is the item's path (config_<id> otherwise, or air's)
    private static boolean registered(BulletConfig b) {
        if (b.ammoItem == null) return false;
        try {
            var s = b.ammoStack();
            return s != null && !s.isEmpty() && BuiltInRegistries.ITEM.getResourceKey(s.getItem()).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
