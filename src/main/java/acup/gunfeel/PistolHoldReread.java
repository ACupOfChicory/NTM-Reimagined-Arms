package acup.gunfeel;

/** The settings file read again (Config, core): the pistol's hip defaults measured against the old values go. */
public final class PistolHoldReread implements Config.Reread {
    @Override
    public void reread() {
        PistolHold.clearHipDefaults();
    }
}
