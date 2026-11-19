package acup.gunfeel;

/**
 * An explosive round's damage without the game: how its hit is split between the contact (the round striking a
 * creature) and the blast, and which of NTM's rounds are a launcher's (by the ammo item's path in NTM's namespace).
 * ExplosiveDamage binds a blast to the bullet whose impact set it off and deals the damage; ExplosiveAmmo reads the
 * ammo item of a bullet config.
 */
public final class ExplosiveRule {
    private ExplosiveRule() {}
    /** the shares of the round's damage: its contact on the creature it struck, the blast by area */
    static final float CONTACT=.6F, BLAST=.4F;
    /** the blast's share of an explosive bullet's damage, out of NTM's {@code original} */
    public static float area(float original){return original*BLAST;}
    /** the blast's share of {@code area}, and on the creature struck ({@code direct}) the contact's share of {@code base} */
    static float total(float area,float base,float multiplier,boolean direct){return area*BLAST+(direct?base*CONTACT*multiplier:0);}
    /** the round's own base damage on whom: half on the one who set it off */
    static float onSelf(boolean self){return self?.5F:1;}
    /** a launcher's round (a 40 mm shell, a rocket) by its ammo item's path in NTM's namespace */
    static boolean heavyPath(String path) {
        return path.startsWith("ammo_standard_g40_") || path.startsWith("ammo_standard_rocket_");
    }
    /** a rocket (Panzerschreck, Stinger, Quadro, the missile launcher), not a 40 mm shell */
    static boolean rocketPath(String path) {
        return path.startsWith("ammo_standard_rocket_");
    }
    /** the Jeremy turret's 240 mm shells (hbm:ammo_shell, ammo_shell_*) */
    static boolean shellPath(String path) {
        return path.equals("ammo_shell") || path.startsWith("ammo_shell_");
    }
}
