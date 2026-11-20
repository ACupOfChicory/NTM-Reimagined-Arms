package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Every version reads live identities, accepts null questions and fails loudly without a catalog. */
public abstract class AmmoCatalogContract {
    protected abstract AmmoCatalog catalog();

    /** Duplex 5.56, Duplex 7.62, rubber, nuclear, shock, SP 5.56, SP 7.62, slug, SP .50. */
    protected abstract Object[] rounds();

    @Test
    void roundIdentitiesAreLiveAndUnwrapped() {
        Object[] r = rounds();
        AmmoCatalog c = catalog();
        assertSame(r[0], c.duplex556());
        assertSame(r[1], c.duplex762());
        assertSame(r[4], c.shock());
        assertSame(r[5], c.sp556());
        assertSame(r[6], c.sp762());
        assertSame(r[7], c.slug());
        assertSame(r[8], c.sp50());
        for (int i = 0; i < r.length; i++) {
            assertSame(r[0], c.duplex556());
            if (i == 2) assertTrue(c.rubber(r[i]));
            else assertFalse(c.rubber(r[i]));
            if (i == 3) assertTrue(c.nuclear(r[i]));
            else assertFalse(c.nuclear(r[i]));
        }
        assertFalse(c.rubber(null));
        assertFalse(c.nuclear(null));
    }

    @Test
    void missingThrowsOnEveryOperation() {
        AmmoCatalog c = AmmoCatalog.MISSING;
        assertThrows(UnsupportedOperationException.class, c::duplex556);
        assertThrows(UnsupportedOperationException.class, c::duplex762);
        assertThrows(UnsupportedOperationException.class, () -> c.rubber(null));
        assertThrows(UnsupportedOperationException.class, c::shock);
        assertThrows(UnsupportedOperationException.class, () -> c.nuclear(null));
        assertThrows(UnsupportedOperationException.class, c::sp556);
        assertThrows(UnsupportedOperationException.class, c::sp762);
        assertThrows(UnsupportedOperationException.class, c::slug);
        assertThrows(UnsupportedOperationException.class, c::sp50);
    }
}
