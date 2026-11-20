package acup.gunfeel.host;

/**
 * NTM's armour suits as the balance reads them (EuphImmunity): whether a body wears a suit's full set as NTM counts it
 * (its own check, each time: it reads the pieces' charge), and whether an item is a powered NTM armour piece (its kind,
 * the same for the item while the game runs). One for the whole game, the version's ({@code Ports.port(ArmorSuits.class)}).
 */
public interface ArmorSuits {
    /** {@link #fullSet}'s suits: NTM's DNS, DIGAMMA (FAU's), EUPHEMIUM */
    int DNS = 0, DIGAMMA = 1, EUPHEMIUM = 2;

    /** {@code b} wears the full set of {@code suit} as NTM's own check has it (26.2: ArmorSuitEffects.hasFullSet, one call); living */
    boolean fullSet(Body b, int suit);

    /** the item numbered {@code item} (Body.worn's) is an NTM armour piece with power (26.2: ModArmorItem, maxPower() > 0) */
    boolean powered(int item);

    /** where the version gives none (core's own tests): each method throws */
    ArmorSuits MISSING = new ArmorSuits() {
        @Override
        public boolean fullSet(Body b, int suit) {
            throw new UnsupportedOperationException("no ArmorSuits here");
        }

        @Override
        public boolean powered(int item) {
            throw new UnsupportedOperationException("no ArmorSuits here");
        }
    };
}
