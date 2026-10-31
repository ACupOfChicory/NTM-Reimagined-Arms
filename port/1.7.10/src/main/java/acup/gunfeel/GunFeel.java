package acup.gunfeel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// What both sides share. On 26.2 GunFeel also holds the client's hooks; here they are in GunFeelClient, so the dedicated
// server never loads a class that names client-only NTM or Minecraft classes.
public final class GunFeel {
    // log4j 2.0-beta9 as Minecraft 1.7.10 ships it: the same "{}" placeholders and a trailing Throwable as slf4j
    public static final Logger LOGGER = LogManager.getLogger("ntm-gunfeel");

    private GunFeel() {
    }
}
