package acup.gunfeel.host;

import acup.gunfeel.BlockFacts;

/**
 * The game's block states as numbers, and what core asks of a state: one table for the whole game (every level, both
 * sides), the version's ({@code Ports.port(BlockTable.class)}); a level's blocks by these numbers: {@link WorldView}.
 * <ul>
 * <li>A number stays the same state's for the whole run of the game, whatever the game does to its own numbers (26.2
 * renumbers its states when a client joins a server).</li>
 * <li>It lives only inside this process: it follows the order the states were first met in, so it differs from run
 * to run and between a client and its server. It never goes into a packet, a file, a trace or the test log; core
 * writes the state's {@link #key} there.</li>
 * <li>{@link #AIR}, 0, is the plain air block's default state. Cave air and void air have numbers of their own: core
 * tells air by its facts, not by 0.</li>
 * </ul>
 */
public interface BlockTable {
    /** the plain air block's default state */
    int AIR = 0;

    /** what a bullet's rules need of the state (its block's id, classes, sound, hardness, tags...) */
    BlockFacts facts(int state);

    /** the state as text: its block's id, then its properties sorted by name, {@code "id[p=v,q=w]"}; the shapes' key */
    String key(int state);

    /** the state is air as the game tells it (its block's: plain, cave and void air, a mod's own), not only {@link #AIR} */
    boolean air(int state);
}
