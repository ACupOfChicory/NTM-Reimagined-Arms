package acup.gunfeel.smoke.client;

import java.util.HashMap;
import java.util.Map;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import acup.gunfeel.smoke.SmokeNet;
import acup.gunfeel.smoke.SmokePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client: this game's copies of the clouds the server tells about. They are stepped every client tick up to their age
 * at the level's game time (a cloud that came late is caught up, at most {@link #CATCH_UP} steps a tick), their hash is
 * kept for the last ticks and compared with the server's checks; a copy that went apart asks for the server's grid.
 */
public final class SmokeClient {
    /** Steps a cloud may take in one tick while it catches up. */
    public static final int CATCH_UP = 200;
    private static final int RING = 64;
    private static final Map<Integer, Track> TRACKS = new HashMap<>();
    private static ClientLevel level;
    // counters for the stand: checks that matched, went apart, grids applied
    public static int matched, apart, grids;

    private static final class Track {
        final long[] hashes = new long[RING];
        final int[] ages = new int[RING];
        int checkAge = -1;
        long checkHash;
        long lastAsk = Long.MIN_VALUE;

        Track() {
            java.util.Arrays.fill(ages, -1);
        }
    }

    private SmokeClient() {
    }

    private static SmokeClouds clouds() {
        ClientLevel now = Minecraft.getInstance().level;
        if (now != level) {
            if (level != null) SmokeClouds.forget(level);
            TRACKS.clear();
            level = now;
        }
        return now == null ? null : SmokeClouds.of(now);
    }

    public static SmokeClouds current() {
        ClientLevel now = Minecraft.getInstance().level;
        return now == null || now != level ? null : SmokeClouds.get(now);
    }

    public static void spawn(SmokePayloads.Spawn p) {
        SmokeClouds all = clouds();
        if (all == null) return;
        // a cloud past the limit pushes the oldest out, as on the server
        all.add(p.id(), p.origin(), SmokeKind.byId(p.kind()), p.seed(), p.dirX(), p.dirZ(), p.scale(), p.born(), gone -> TRACKS.remove(gone.id));
        TRACKS.put(p.id(), new Track());
    }

    public static void end(SmokePayloads.End p) {
        SmokeClouds all = clouds();
        if (all != null) all.remove(p.id());
        TRACKS.remove(p.id());
    }

    public static void check(SmokePayloads.Check p) {
        Track t = TRACKS.get(p.id());
        if (t == null) return;
        t.checkAge = p.age();
        t.checkHash = p.hash();
        compare(p.id(), t);
    }

    public static void grid(SmokePayloads.Grid p) {
        SmokeClouds all = clouds();
        SmokeClouds.Cloud c = all == null ? null : all.cloud(p.id());
        if (c == null) return;
        try {
            SmokeNet.decode(c.grid, p.data());
            grids++;
            Track t = TRACKS.get(p.id());
            if (t != null) {
                java.util.Arrays.fill(t.ages, -1);
                t.checkAge = -1;
                remember(t, c);
            }
        } catch (java.io.IOException ex) {
            // a broken grid: the copy stays as it was
        }
    }

    private static void remember(Track t, SmokeClouds.Cloud c) {
        int slot = c.grid.age() % RING;
        t.ages[slot] = c.grid.age();
        t.hashes[slot] = c.grid.hash();
    }

    // the server's check against the copy's hash at that age, once the copy has got there
    private static void compare(int id, Track t) {
        if (t.checkAge < 0) return;
        int slot = t.checkAge % RING;
        if (t.ages[slot] != t.checkAge) return;
        boolean same = t.hashes[slot] == t.checkHash;
        t.checkAge = -1;
        if (same) {
            matched++;
            return;
        }
        apart++;
        long now = level == null ? 0 : level.getGameTime();
        if (now - t.lastAsk < 100 && now >= t.lastAsk) return;
        t.lastAsk = now;
        ClientPacketDistributor.sendToServer(new SmokePayloads.Resync(id));
    }

    /** Every client tick. */
    public static void tick() {
        SmokeClouds all = clouds();
        if (all == null || all.isEmpty()) return;
        long t0 = System.nanoTime();
        long time = level.getGameTime();
        for (SmokeClouds.Cloud c : all.clouds()) {
            Track t = TRACKS.get(c.id);
            int before = c.grid.age();
            all.step(c, time, CATCH_UP, t == null ? null : g -> {
                int slot = g.age() % RING;
                t.ages[slot] = g.age();
                t.hashes[slot] = g.hash();
            });
            if (t != null && c.grid.age() != before) compare(c.id, t);
        }
        for (SmokeClouds.Cloud c : all.reap()) TRACKS.remove(c.id);
        SmokeStats.tick(System.nanoTime() - t0);
    }

    public static void leave() {
        if (level != null) SmokeClouds.forget(level);
        level = null;
        TRACKS.clear();
    }
}
