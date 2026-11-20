package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmBullets;

class NtmBulletsTest {
    @Test
    void missingThrowsForEveryQuestion() {
        for (Object e : new Object[] {null, new Object()}) {
            assertThrows(UnsupportedOperationException.class, () -> NtmBullets.MISSING.shooter(e));
            assertThrows(UnsupportedOperationException.class, () -> NtmBullets.MISSING.capProjectile(e));
            assertThrows(UnsupportedOperationException.class, () -> NtmBullets.MISSING.pellet(e));
        }
    }

    @Test
    void opaqueEntitiesAndNullableBodiesPassThroughTheContract() {
        Object entity = new Object();
        Body body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class},
                (proxy, method, args) -> { throw new AssertionError("unexpected body read: " + method); });
        List<String> calls = new ArrayList<>();
        NtmBullets fake = new NtmBullets() {
            @Override public Body shooter(Object e) {
                calls.add("shooter");
                return e == entity ? body : null;
            }
            @Override public boolean capProjectile(Object e) {
                calls.add("capProjectile");
                return e == entity;
            }
            @Override public boolean pellet(Object e) {
                calls.add("pellet");
                return e == entity;
            }
        };
        assertSame(body, fake.shooter(entity));
        assertNull(fake.shooter(null));
        assertTrue(fake.capProjectile(entity));
        assertFalse(fake.capProjectile(new Object()));
        assertTrue(fake.pellet(entity));
        assertFalse(fake.pellet(new Object()));
        assertEquals(List.of("shooter", "shooter", "capProjectile", "capProjectile", "pellet", "pellet"), calls);
    }
}
