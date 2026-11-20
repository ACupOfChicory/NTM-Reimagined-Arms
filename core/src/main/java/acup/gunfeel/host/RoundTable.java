package acup.gunfeel.host;

/**
 * NTM's rounds (its BulletConfig) by the mod's own numbers, as {@link NtmGuns#rounds} gives them: one table for the
 * whole game, the version's. A number stays the same round's for the whole run, lives only inside this process and
 * never goes into a packet, a file or a trace: core writes the round's {@link #name} there.
 */
public interface RoundTable {
    /**
     * The round's name as the balance file and the commands write it (BalanceGuns.ammoName: its ammo item's path, without
     * ammo_standard_ or ammo_; config_&lt;NTM's id&gt; for a round with no ammo item); the same String object each time
     * once its ammo item is in the registry; a number no round has throws.
     */
    String name(int round);

    /** where the version gives none (core's own tests): each method throws */
    RoundTable MISSING = round -> {
        throw new UnsupportedOperationException("no RoundTable here");
    };
}
