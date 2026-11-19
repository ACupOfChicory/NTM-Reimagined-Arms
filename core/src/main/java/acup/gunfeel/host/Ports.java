package acup.gunfeel.host;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What a Minecraft version gives core: one class per version (on 26.2 {@code acup.gunfeel.Wiring}, the version's wiring)
 * named in {@code META-INF/services/acup.gunfeel.host.Ports}, found once ({@link acup.gunfeel.CoreServices}). Core asks
 * it for a port by its interface, each time once, into a {@code static final} field or a system's constructor; the
 * version answers with its single implementation of that interface. Core's own tests run with none: every port is then
 * its fallback.
 */
public interface Ports {
    /** The version's implementation of {@code type}, or null where it has none (core then goes by its fallback). */
    <T> @Nullable T port(Class<T> type);

    /** What this version's NTM can do: a core system whose NTM side is missing here turns itself off. */
    Set<NtmFeature> ntm();
}
