package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

/**
 * A hurt's source as the balance reads it: the version's own (26.2's DamageSource), passed as it is, its identity
 * the hurt's. Each call reads the source now, the port keeps nothing. The game's tags and NTM's damage types are asked
 * by core's numbers ({@link #is}), each the one call of the game's the version made before: the version names them,
 * so a type NTM renames or drops is the version's to miss (its NTM probe), never a string of core's. One for the whole
 * game, the version's ({@code Ports.port(Hurts.class)}).
 */
public interface Hurts {
    /** {@link #is}'s questions: the game's tags (bypasses_effects, is_fire, is_fall), NTM's damage types */
    int BYPASSES_EFFECTS = 0, FIRE = 1, FALL = 2, NTM_NUCLEAR_BLAST = 3, NTM_DIGAMMA = 4, NTM_RADIATION = 5, NTM_FIRE = 6;

    /** {@link #directKind}'s bits: the direct entity is living; it is NTM's beam (EntityBulletBeamBase) */
    int LIVING = 1, NTM_BEAM = 2;

    /** the source is of the tag or the damage type {@code what} names (26.2: {@code source.is(...)}, one call) */
    boolean is(Object hurt, int what);

    /** what the entity dealing it directly is ({@link #LIVING}, {@link #NTM_BEAM}); 0 for none or another */
    int directKind(Object hurt);

    /** NTM's category of the source (26.2: DamageResistanceHandler.typeToCategory), or null where NTM gives none */
    @Nullable String ntmCategory(Object hurt);

    /**
     * its damage type's registry key as text, {@code "namespace:path"}, the same String object each time for a key; null
     * for a type with no key
     */
    @Nullable String typeName(Object hurt);

    /** where the version gives none (core's own tests): each method throws */
    Hurts MISSING = new Hurts() {
        @Override
        public boolean is(Object hurt, int what) {
            throw new UnsupportedOperationException("no Hurts here");
        }

        @Override
        public int directKind(Object hurt) {
            throw new UnsupportedOperationException("no Hurts here");
        }

        @Override
        public @Nullable String ntmCategory(Object hurt) {
            throw new UnsupportedOperationException("no Hurts here");
        }

        @Override
        public @Nullable String typeName(Object hurt) {
            throw new UnsupportedOperationException("no Hurts here");
        }
    };
}
