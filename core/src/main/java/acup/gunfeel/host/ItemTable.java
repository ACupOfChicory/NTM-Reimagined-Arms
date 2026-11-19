package acup.gunfeel.host;

/**
 * The game's items by the numbers {@link Body#held} gives: one table for the whole game, the version's
 * ({@code Ports.port(ItemTable.class)}). A number stays the same item's for the whole run, lives only inside this
 * process and never goes into a packet, a file or a trace: core writes the item's {@link #name} there.
 */
public interface ItemTable {
    /** the empty hand's (air) */
    int AIR = 0;

    /**
     * The item's registry id as text, {@code "namespace:path"} (26.2: BuiltInRegistries.ITEM.getKey(item).toString()),
     * the same String object each time for a number; a number no item has throws.
     */
    String name(int number);

    /** where the version gives none (core's own tests): each method throws */
    ItemTable MISSING = number -> {
        throw new UnsupportedOperationException("no ItemTable here");
    };
}
