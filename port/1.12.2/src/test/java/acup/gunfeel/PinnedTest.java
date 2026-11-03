package acup.gunfeel;

import java.util.List;

// What the shared 26.2 tests take from 26.2's PinnedTest: pin() here checks against 26.2's pins (the root's
// src/test/pinned) and never rewrites them, the pins belong to 26.2.
final class PinnedTest {
    private PinnedTest() {
    }

    static String num(float f) {
        return PinnedPortTest.num(f);
    }

    static void pin(String name, List<String> now) {
        PinnedPortTest.compare(name, now);
    }
}
