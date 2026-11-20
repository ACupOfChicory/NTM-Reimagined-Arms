package acup.gunfeel.host;

/**
 * The game's entity types by the mod's own numbers (one an entity type, the same while the game runs; not the
 * registry's, which a server renumbers), and what the bullet rules ask of a type: one table for the whole game, the
 * version's ({@code Ports.port(EntityTypeTable.class)}). A number lives only inside this process and never goes into
 * a packet, a file or a trace: core writes the type's {@link #name} there.
 */
public interface EntityTypeTable {
    /**
     * The type's registry id as text, {@code "namespace:path"} (26.2: BuiltInRegistries.ENTITY_TYPE.getKey(type)
     * .toString()), the same String object each time for a number; a number no type has throws.
     */
    String name(int type);

    /**
     * The type is in the entity type tag {@code tag} ({@code "namespace:path"}, no '#'); a tag name that is no id
     * (26.2: Identifier.tryParse null) is in none. One question to the game a call, as an entity's own {@code is(tag)}.
     */
    boolean tagged(int type, String tag);

    /** where the version gives none (core's own tests): each method throws */
    EntityTypeTable MISSING = new EntityTypeTable() {
        @Override
        public String name(int type) {
            throw new UnsupportedOperationException("no EntityTypeTable here");
        }

        @Override
        public boolean tagged(int type, String tag) {
            throw new UnsupportedOperationException("no EntityTypeTable here");
        }
    };
}
