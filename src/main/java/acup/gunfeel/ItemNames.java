package acup.gunfeel;

import acup.gunfeel.host.ItemTable;
import net.minecraft.core.registries.BuiltInRegistries;

/** 26.2's ItemTable: an item's registry id by its number (ItemNumbers), worked out once a number. */
final class ItemNames implements ItemTable {
    /** by number; grown under the lock, read without it (an array once filled in is never changed) */
    private volatile String[] names = new String[64];

    @Override
    public String name(int number) {
        String[] a = names;
        if (number >= 0 && number < a.length) {
            String n = a[number];
            if (n != null) return n;
        }
        return fill(number);
    }

    private synchronized String fill(int number) {
        String[] a = names;
        if (number >= 0 && number < a.length && a[number] != null) return a[number];
        net.minecraft.world.item.Item item = ItemNumbers.item(number);
        if (item == null) throw new IllegalArgumentException("no item has number " + number);
        String n = BuiltInRegistries.ITEM.getKey(item).toString();
        String[] b = java.util.Arrays.copyOf(a, Math.max(a.length, Integer.highestOneBit(number) << 1));
        b[number] = n;
        names = b;
        return n;
    }
}
