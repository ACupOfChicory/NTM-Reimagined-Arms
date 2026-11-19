package acup.gunfeel.compat;

import acup.gunfeel.host.HostVersions;

/** The versions for core (HostVersions) on 26.2: Compat's, read each time (Compat fills them when the mods are listed). */
public final class CompatVersions implements HostVersions {
    @Override
    public String mod() {
        return Compat.modVersion();
    }

    @Override
    public String ntm() {
        return Compat.hbmVersion();
    }
}
