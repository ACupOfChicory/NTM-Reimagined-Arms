package acup.gunfeel;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The mod's own number of an item (Body.held), by the Item object, 0 the empty hand's air: the game's raw registry id
 * would not do, NeoForge renumbers the registries when a client joins a server (BlockStates does the same for blocks).
 */
final class ItemNumbers {
    private static final ConcurrentHashMap<Item, Integer> NUMBERS = new ConcurrentHashMap<>();
    /** the items by number (ItemNames), written under add's lock */
    private static final ConcurrentHashMap<Integer, Item> ITEMS = new ConcurrentHashMap<>();
    private static int next = 1;

    static {
        NUMBERS.put(Items.AIR, 0);
        ITEMS.put(0, Items.AIR);
    }

    private ItemNumbers() {
    }

    static int number(Item item) {
        Integer n = NUMBERS.get(item);
        return n != null ? n : add(item);
    }

    private static synchronized int add(Item item) {
        Integer n = NUMBERS.get(item);
        if (n != null) return n;
        int m = next++;
        ITEMS.put(m, item);
        NUMBERS.put(item, m);
        return m;
    }

    /** the item with this number, null for a number none has */
    static Item item(int number) {
        return ITEMS.get(number);
    }
}
