package acup.gunfeel.gl54.frag;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.gl54.GrenadeKind;

// What a fragment grenade throws when it goes off (design.md, "Осколки airburst и зажигательной: кольцо + конус"): a cone
// along the flight (the nose and the base) and a ring around the plane across it (the side spray of a cylindrical body),
// both at once. No game classes here, so the numbers are tested without the game.
//   cone: `cone` fragments inside a cone of `coneDegrees` (the full angle) round the flight direction;
//   ring: `ring` fragments in a band of RING_DEGREES either side of the plane across the flight;
//   damage: one fragment's; threshold/piercing: NTM's armour threshold negation and armour piercing share of it;
//   igniteTicks: how long a fragment sets the creature it hits on fire.
public record FragSpec(GrenadeKind kind, double coneDegrees, int cone, int ring, float damage, float threshold, float piercing,
        int igniteTicks) {
    // how far a fragment flies, blocks
    public static final double RANGE = 6.0;
    // the ring's band either side of the plane across the flight, degrees
    public static final double RING_DEGREES = 15.0;
    // the most rays one burst may cast
    public static final int MAX_RAYS = 160;

    // airburst: does not beat NTM's armour thresholds (against armour there is HE)
    public static final FragSpec AIRBURST = new FragSpec(GrenadeKind.AIRBURST, 65.0, 40, 120, 1.25F, 0F, 0F, 0);
    // armour-piercing airburst: a narrower cone, NTM's 5.56 AP threshold negation and armour piercing
    public static final FragSpec AP = new FragSpec(GrenadeKind.AP, 30.0, 20, 60, 1.25F, 10F, 0.15F, 0);
    // incendiary: weak fragments that set what they hit on fire for 4 s
    public static final FragSpec INCENDIARY = new FragSpec(GrenadeKind.INCENDIARY, 65.0, 20, 60, 0.75F, 0F, 0F, 80);

    public int rays() {
        return cone + ring;
    }

    public boolean armourPiercing() {
        return threshold > 0F || piercing > 0F;
    }

    // the spec of a fragment kind; null for the others (HE, smoke, the gases)
    public static @Nullable FragSpec of(@Nullable GrenadeKind kind) {
        if (kind == null) return null;
        return switch (kind) {
            case AIRBURST -> AIRBURST;
            case AP -> AP;
            case INCENDIARY -> INCENDIARY;
            default -> null;
        };
    }
}
