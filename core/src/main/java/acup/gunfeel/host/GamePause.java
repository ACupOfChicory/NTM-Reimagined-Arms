package acup.gunfeel.host;

/**
 * Whether the game stands paused now (single player's pause menu): the guns' clock stops with it (tacz.GunTime). The
 * version's client answers; without one (core's tests, a server) nothing is paused.
 */
public interface GamePause {
    boolean paused();
}
