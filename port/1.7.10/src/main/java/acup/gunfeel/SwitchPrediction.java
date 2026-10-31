package acup.gunfeel;

// port: new for 1.7.10. The wait for the server's draw from 26.2's InstantSwitch.java (0762086) without the game in it,
// so the tests check it (SwitchPredictionTest): the client started the draw of a gun in a slot; the server's draws for
// that slot and gun, one per config, are skipped once each, within WAIT_MS; anything else ends the wait.
final class SwitchPrediction {
    static final long WAIT_MS = 10000L; // real time: a slow server (tickrate 2) still counts

    private int slot = -1;
    private Object item;
    private long at;
    private boolean[] waiting = new boolean[0];

    synchronized void start(int slot, Object item, int configs, long now) {
        this.slot = slot;
        this.item = item;
        at = now;
        waiting = new boolean[configs];
        java.util.Arrays.fill(waiting, true);
    }

    synchronized void clear() {
        slot = -1;
    }

    // the server's animation for the held gun: false = skip it (the client already started this draw)
    synchronized boolean accept(int slot, Object item, boolean equip, int index, long now) {
        if (this.slot < 0) return true;
        if (!equip) {
            // anything else ends the wait: a later draw from the server is a new one
            this.slot = -1;
            return true;
        }
        if (slot != this.slot || item != this.item || now - at > WAIT_MS) return true;
        if (index < 0 || index >= waiting.length || !waiting[index]) return true;
        waiting[index] = false;
        return false;
    }
}
