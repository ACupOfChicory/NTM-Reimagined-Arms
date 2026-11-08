package acup.gunfeel.smoke.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeKind;
import acup.gunfeel.smoke.SmokeNet;
import acup.gunfeel.smoke.SmokePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client: this game's copies of the clouds the server tells about. They are stepped every client tick up to their age
 * at the level's game time. A cloud that came late is caught up on a budget of {@link #CATCH_UP} steps a tick for all
 * the clouds together ({@link SmokeClouds#catchUp}); until it has caught up it is not drawn, does not thicken the fog
 * and is not compared. Its hash is kept only at the ages the server sends a check for ({@link SmokeNet#checkDue}) and
 * compared with the server's; a copy that went apart asks for the server's grid.
 */
public final class SmokeClient {
    /** Steps all the clouds together may take in one tick while some catch up. */
    public static final int CATCH_UP = 200;
    /** Steps a cloud takes in one turn of the catch-up. */
    private static final int BURST = 25;
    // hashes kept per cloud: the last four check ages (a check is every CHECK_EVERY ticks)
    private static final int RING = 4;
    private static final Map<Integer, Track> TRACKS = new HashMap<>();
    private static ClientLevel level;
    // counters for the stand: checks that matched, went apart, grids applied, mismatches left alone for an unloaded chunk
    public static int matched, apart, grids, deferred;

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

    /**
     * A cloud to simulate. It comes when the server lets one out and again when this player starts to watch its chunk
     * ({@code ChunkWatchEvent.Sent}, after the chunk itself). The first one can come before the chunk is here: the
     * origin is then a closed cell, nothing comes out of it and the cloud ends at once; the second one, with the chunk
     * loaded, replaces it and is caught up from the cloud's birth. So a cloud born at the edge of the loaded area is
     * not lost, only late.
     */
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
        SmokeClouds all = current();
        SmokeClouds.Cloud c = all == null ? null : all.cloud(p.id());
        if (t == null || c == null) return;
        t.checkAge = p.age();
        t.checkHash = p.hash();
        compare(c, t);
    }

    /** A bullet or a blast stirred a cloud: the copy takes it at the age the server did (a broken one is dropped). */
    public static void stirred(SmokePayloads.Stirred p) {
        SmokeClouds all = clouds();
        SmokeClouds.Cloud c = all == null ? null : all.cloud(p.id());
        // one for an age far past what the cloud can live to is not the server's
        if (c == null || !SmokeClouds.sane(p.stir()) || p.stir().age() > c.grid.age() + 600) return;
        c.stir(p.stir());
    }

    /** A grenade of a cloud's kind burst in it: the copy takes it at the age the server did. */
    public static void fed(SmokePayloads.Fed p) {
        SmokeClouds all = clouds();
        SmokeClouds.Cloud c = all == null ? null : all.cloud(p.id());
        if (c == null || !SmokeClouds.sane(p.feed()) || p.feed().age() > c.grid.age() + 600) return;
        c.stir(p.feed());
    }

    public static void grid(SmokePayloads.Grid p) {
        SmokeClouds all = clouds();
        SmokeClouds.Cloud c = all == null ? null : all.cloud(p.id());
        if (c == null) return;
        try {
            SmokeNet.decode(c.grid, p.data());
            c.stepped();
            grids++;
            Track t = TRACKS.get(p.id());
            if (t != null) {
                java.util.Arrays.fill(t.ages, -1);
                t.checkAge = -1;
            }
        } catch (java.io.IOException ex) {
            // a broken grid: SmokeGrid.read changed nothing, the copy stays as it was and the next check asks again
        }
    }

    private static int slot(int id, int age) {
        return (age + id * 7) / SmokeNet.CHECK_EVERY % RING;
    }

    // the copy's hash at an age the server checks
    private static void remember(Track t, int id, SmokeGrid g) {
        int age = g.age();
        if (!SmokeNet.checkDue(id, age)) return;
        int slot = slot(id, age);
        t.ages[slot] = age;
        t.hashes[slot] = g.hash();
    }

    // the server's check against the copy's hash at that age, once the copy has got there
    private static void compare(SmokeClouds.Cloud c, Track t) {
        if (t.checkAge < 0 || level == null || c.catchingUp(level.getGameTime())) return;
        int slot = slot(c.id, t.checkAge);
        if (t.ages[slot] != t.checkAge) {
            // not there yet, or already so far past that the hash was overwritten: that check is dropped
            if (c.grid.age() > t.checkAge) t.checkAge = -1;
            return;
        }
        boolean same = t.hashes[slot] == t.checkHash;
        t.checkAge = -1;
        if (same) {
            matched++;
            return;
        }
        apart++;
        // At the edge of what this client has loaded the copy differs for good: the server has the chunk next to the
        // cloud and its smoke went in, here that chunk is not loaded, so its cells are closed. A grid from the server
        // would not help (the next check differs again), so no grid is asked for until the chunk is here; then the
        // next check differs once more and asks.
        if (touchesUnloaded(c)) {
            deferred++;
            return;
        }
        long now = level.getGameTime();
        if (now - t.lastAsk < 100 && now >= t.lastAsk) return;
        t.lastAsk = now;
        ClientPacketDistributor.sendToServer(new SmokePayloads.Resync(c.id));
    }

    // whether a chunk the cloud's smoke is in or next to is not loaded here
    private static boolean touchesUnloaded(SmokeClouds.Cloud c) {
        int[] b = c.grid.box();
        if (!c.grid.occupied()) b = new int[] {0, 0, 0, SmokeGrid.SX - 1, SmokeGrid.SY - 1, SmokeGrid.SZ - 1};
        return !level.hasChunksAt(c.baseX() + b[0] - 1, c.baseZ() + b[2] - 1, c.baseX() + b[3] + 1, c.baseZ() + b[5] + 1);
    }

    /** Every client tick. */
    public static void tick() {
        SmokeClouds all = clouds();
        if (all == null || all.isEmpty()) return;
        long t0 = System.nanoTime();
        long time = level.getGameTime();
        all.catchUp(time, CATCH_UP, BURST, (c, g) -> {
            Track t = TRACKS.get(c.id);
            if (t != null) remember(t, c.id, g);
        });
        for (SmokeClouds.Cloud c : all.clouds()) {
            Track t = TRACKS.get(c.id);
            if (t != null && t.checkAge >= 0) compare(c, t);
        }
        for (SmokeClouds.Cloud c : all.reap()) TRACKS.remove(c.id);
        SmokeStats.tick(System.nanoTime() - t0);
    }

    public static void leave() {
        if (level != null) SmokeClouds.forget(level);
        level = null;
        TRACKS.clear();
    }

    // ---- which clouds are drawn (the volume and the slices pick them the same way)

    private record Near(SmokeClouds.Cloud cloud, double distance) {
    }

    /** How far a world point is from a cloud's occupied box (0 inside it). */
    public static double distance(SmokeClouds.Cloud c, Vec3 cam) {
        int[] b = c.grid.box();
        double x = Math.clamp(cam.x, c.baseX() + b[0], c.baseX() + b[3] + 1), y = Math.clamp(cam.y, c.baseY() + b[1], c.baseY() + b[4] + 1),
                z = Math.clamp(cam.z, c.baseZ() + b[2], c.baseZ() + b[5] + 1);
        double dx = cam.x - x, dy = cam.y - y, dz = cam.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The clouds to draw: some smoke in them, caught up, within {@code far} blocks of the camera, the {@code most}
     * nearest, nearest first.
     */
    public static List<SmokeClouds.Cloud> nearest(SmokeClouds all, Vec3 cam, long time, double far, int most) {
        List<Near> near = new ArrayList<>();
        for (SmokeClouds.Cloud c : all.clouds()) {
            if (!c.grid.occupied() || c.catchingUp(time)) continue;
            double d = distance(c, cam);
            if (d <= far) near.add(new Near(c, d));
        }
        near.sort(java.util.Comparator.comparingDouble(Near::distance));
        List<SmokeClouds.Cloud> out = new ArrayList<>(Math.min(most, near.size()));
        for (int i = 0; i < near.size() && i < most; i++) out.add(near.get(i).cloud);
        return out;
    }
}
