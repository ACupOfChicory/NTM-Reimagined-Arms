package acup.gunfeel.gl54;

import acup.gunfeel.ReaimTimes;

// ntmgunfeel-server.properties: gl54 = true (the default). Off, the GL-54 is in the game (it can be given and held, a
// world keeps it) but does nothing: no shot, no reload; the grenade it fired meanwhile flies and falls as a dud.
public final class Gl54Server {
    public static final String KEY = "gl54";

    private Gl54Server() {
    }

    public static boolean enabled() {
        return ReaimTimes.flag(KEY, true);
    }
}
