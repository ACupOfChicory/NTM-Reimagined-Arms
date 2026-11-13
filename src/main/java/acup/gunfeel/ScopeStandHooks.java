package acup.gunfeel;

/** What ScopeSmokeStand reads of the gun model's FOV, and its switch back to NTM's own partial tick there. */
public final class ScopeStandHooks {
    /** -PscopeSmoke: without it the hooks do nothing */
    public static final boolean ON = Boolean.getBoolean("ntmgunfeel.scopeSmoke");
    /** the model's FOV as NTM worked it out last */
    public static volatile float hudFov;
    /** the stand's comparison: the FOV with the last frame's partial tick, as NTM has it */
    public static final boolean oldHudFov = ON && Boolean.getBoolean("ntmgunfeel.scopeOldFov");

    private ScopeStandHooks() {
    }
}
