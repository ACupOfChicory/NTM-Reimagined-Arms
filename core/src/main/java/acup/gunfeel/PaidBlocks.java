package acup.gunfeel;

import java.util.function.LongConsumer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The blocks a bullet already paid for piercing (PierceWalk), kept between its ticks: one it ended a tick inside of
 * is not paid for again. A block by its place packed as {@link PosLong#block}. Whoever reads it all takes no order.
 */
public final class PaidBlocks {
    private final LongOpenHashSet paid = new LongOpenHashSet();

    public boolean contains(long pos) {
        return paid.contains(pos);
    }

    public void add(long pos) {
        paid.add(pos);
    }

    /** every block in it, in no order */
    public void forEach(LongConsumer each) {
        paid.forEach(each);
    }

    public int size() {
        return paid.size();
    }
}
