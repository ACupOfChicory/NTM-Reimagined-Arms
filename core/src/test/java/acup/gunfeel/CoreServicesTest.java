package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CoreServicesTest {
    interface Nobody {}

    @Test
    void withoutAProviderTheFallbackIsGiven() {
        Nobody fallback = new Nobody() {};
        assertSame(fallback, CoreServices.load(Nobody.class, fallback));
    }

    // core's tests have no version: 26.2's service files (src/main/resources) are not on their class path, so what
    // core loads is the fallback, not a ServiceConfigurationError on a 26.2 class missing here
    @Test
    void coresTestsRunOnTheFallbacks() {
        assertThrows(IllegalStateException.class, () -> GameDirs.get().config());
        assertSame(null, getClass().getClassLoader().getResource("META-INF/services/acup.gunfeel.GameDirs"));
    }
}
