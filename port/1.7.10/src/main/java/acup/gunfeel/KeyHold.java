package acup.gunfeel;

// port: new for 1.7.10. The tap-or-hold of 26.2's AmmoLockKey.tick (0762086) without the game in it, so the tests
// drive it with made-up times (KeyHoldTest): a tap (let go before HOLD_MS) locks or unlocks, a hold opens the radial
// menu, letting go of a held key closes it without choosing.
final class KeyHold {
    static final long HOLD_MS = 250;

    enum Action {
        NONE, OPEN, CLOSE, TAP
    }

    private boolean wasDown, held;
    private long downAt;

    // one client tick: the key down now (and no screen open), a gun in hand, the menu open; what to do
    Action tick(boolean down, boolean gun, boolean menuOpen, long now) {
        Action out = Action.NONE;
        if (down && !wasDown) {
            downAt = now;
            held = false;
        }
        if (down && !held && now - downAt >= HOLD_MS) {
            held = true;
            if (gun) out = Action.OPEN;
        }
        if (!down && wasDown) {
            if (menuOpen) out = Action.CLOSE; // let go without a click: nothing changes
            else if (!held && gun) out = Action.TAP;
        }
        wasDown = down;
        return out;
    }
}
