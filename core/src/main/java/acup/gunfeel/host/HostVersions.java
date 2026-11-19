package acup.gunfeel.host;

import acup.gunfeel.CoreServices;

/**
 * The versions on this game, as the mod's log and the test log write them: {@link #mod()} the version of this mod,
 * {@link #ntm()} NTM's (also the label the jar's machine shapes are made with, ShapePack). Read each time it is asked:
 * the version knows them once the loader has listed the mods, which may be after core's classes are set up, so nobody
 * keeps the strings. On 26.2 {@code acup.gunfeel.compat.CompatVersions} gives Compat's. Without a version (core's own
 * tests) both are "".
 */
public interface HostVersions {
    /** this mod's version, "" while not known */
    String mod();

    /** NTM's version, "" while not known or without NTM */
    String ntm();

    /** The version's versions. */
    static HostVersions get() {
        return HostVersionsFound.VERSIONS;
    }
}

/** Looked up once, the first time a version is asked for. */
final class HostVersionsFound {
    static final HostVersions VERSIONS = CoreServices.load(HostVersions.class, new HostVersions() {
        @Override
        public String mod() {
            return "";
        }

        @Override
        public String ntm() {
            return "";
        }
    });

    private HostVersionsFound() {
    }
}
