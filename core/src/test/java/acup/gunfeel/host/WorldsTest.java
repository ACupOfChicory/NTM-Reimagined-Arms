package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

class WorldsTest {
    @Test
    void oneViewPerLevelUntilForgotten() {
        Object a = new Object(), b = new Object();
        int[] asks = {0};
        Worlds.Port port = level -> {
            asks[0]++;
            return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class},
                    (p, m, args) -> { throw new UnsupportedOperationException(m.getName()); });
        };
        try {
            WorldView first = Worlds.of(port, a);
            assertSame(first, Worlds.of(port, a));
            assertEquals(1, asks[0]);
            WorldView other = Worlds.of(port, b);
            assertNotSame(first, other);
            assertSame(other, Worlds.of(port, b));
            assertEquals(2, asks[0]);
            Worlds.forget(a);
            WorldView next = Worlds.of(port, a);
            assertNotSame(first, next);
            assertSame(next, Worlds.of(port, a));
            assertEquals(3, asks[0]);
            assertSame(other, Worlds.of(port, b));
            assertEquals(3, asks[0]);
        } finally {
            Worlds.forget(a);
            Worlds.forget(b);
        }
    }
}
