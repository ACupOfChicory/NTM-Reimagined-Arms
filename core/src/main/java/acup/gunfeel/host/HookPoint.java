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

    // ---- arms and recoil
    /** NTM drew the gun in first person (its frames and parts read meanwhile): the arms are placed (ArmPoses) and drawn. */
    ARMS_DRAW,
    /** NTM's pistol is placed at the hip or aimed in first person: the hold's place (PistolPlace). */
    PISTOL_PLACE,
    /** A gun fired on the client (NTM set its recoil): the camera's and the gun model's kicks start (CameraKick, ModelKick). */
    RECOIL_SHOT,
    /** A frame begins: the camera turns by the kicks in flight (CameraKick). */
    RECOIL_FRAME,
    /** The gun model is drawn: its kick moves it (ModelKick). */
    RECOIL_MODEL,
}
