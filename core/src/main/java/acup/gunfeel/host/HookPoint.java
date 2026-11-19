package acup.gunfeel.host;

/**
 * Where the game has to call core in: each version wires every point to its events or mixins, and marks the method
 * the game calls there with {@link Hook}. A point a version forgot leaves the system that needs it silently off; its
 * test ({@code HookPointContract}) fails instead. A system that comes to core adds its points here.
 */
public enum HookPoint {
    // ---- smoke and gases
    /** A server level's tick ended: its clouds step and send their changes. */
    SMOKE_SERVER_TICK,
    /** A grenade, round or shell let smoke or gas out on the server. */
    SMOKE_BURST,
    /** A client tick ended: the client's copies of the clouds step. */
    SMOKE_CLIENT_TICK,
    /** The server's smoke messages came to the client (a cloud born, stirred, fed, ended, checked). */
    SMOKE_PACKETS,
    /** The client left the server: its clouds go. */
    SMOKE_CLIENT_LEFT,
    /** A frame begins: the fog at the camera eases towards the smoke's. */
    SMOKE_FRAME,
    /** The game sets its fog and its colour and draws the screen's overlays: thickened, tinted in smoke. */
    SMOKE_FOG,
    /** The world is drawn: the smoke's passes (after the opaque blocks, after the weather, at the end, its geometry). */
    SMOKE_DRAW,
    /** A mob looks for its target: smoke in between hides it. */
    SMOKE_SIGHT,
    /** A server level's tick ended: the gases act on the living and the ground, NTM's clouds are taken over. */
    GAS_SERVER_TICK,
    /** A living thing comes into a level, would heal, drinks milk: the gases' hold on it. */
    GAS_LIVING,
    /** A level unloads, a player leaves: what the gases kept for them goes. */
    GAS_FORGET,

    // ---- water
    /** A bullet's tick: its path through liquid before it moves, the block and the mobs it may hit cut where the water
     * stopped or turned it, then its speed, its stop, skip or sinking at the end. */
    WATER_BULLET_TICK,
    /** A bullet hits or touches something: an impact fuze in water, a blast going off under water. */
    WATER_IMPACT,
    /** An explosion: in or over water it is told to the players near, its push and harm reach mobs in the same water. */
    WATER_BLAST,
    /** The server's word of a blast at water came to the client. */
    WATER_PACKETS,
    /** A client tick ended: the splashes, bubbles and rings step. */
    WATER_CLIENT_TICK,
    /** The client left the server: its splashes, rings and sunk rounds go. */
    WATER_CLIENT_LEFT,
    /** A thing came into the client's level: a grenade or rocket shot under water gets its gas bubble. */
    WATER_JOINED,
    /** The world's geometry is handed in: the rings on the surface and the rounds lying under water. */
    WATER_DRAW,
    /** A gun's muzzle flash is drawn: none under water. */
    WATER_MUZZLE,
}
