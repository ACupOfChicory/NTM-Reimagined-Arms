package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class CoreServicesTest {
    interface Nobody {}

    @Test
    void withoutAProviderTheFallbackIsGiven() {
        Nobody fallback = new Nobody() {};
        assertSame(fallback, CoreServices.load(Nobody.class, fallback));
    }
}
