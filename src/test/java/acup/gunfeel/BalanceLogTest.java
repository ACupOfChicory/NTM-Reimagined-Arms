package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.WeakHashMap;

import org.junit.jupiter.api.Test;

// BalanceLogBase (core) against cc2033b's BalanceLog word for word, the game's reads made parameters (who is a player,
// the magazine, the tick, the sets, the names, the distance) and its entities stand-ins equal by their number, as
// 26.2's Entity is (another object of the same number is the same key): random runs of shots (pellets in one tick,
// reloads, no magazine), hits by the projectile, the direct entity or the bullet being dealt (the shot's own, another
// of the same number, none), fights gone quiet, sweeps, deaths, players leaving, a killer respawned; the same lines,
// the same held() after every step.
class BalanceLogTest {
    /** an entity: equal by its number, as 26.2's Entity */
    static final class Key {
        final int id;

        Key(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.id == id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }

    // ---- cc2033b's BalanceLog, the game's reads given
    static final class Old {
        static final class Gunner {
            long shots, reloads, tick = Long.MIN_VALUE;
            int ammo = -1;
        }

        final Map<Object, Gunner> GUNNERS = Collections.synchronizedMap(new WeakHashMap<>());
        final Map<Object, Long> SHOT = Collections.synchronizedMap(new WeakHashMap<>());

        void shot(Object projectile, Object shooter, boolean player, int ammo, long now) {
            if (!player) return;
            Gunner g = GUNNERS.computeIfAbsent(shooter, s -> new Gunner());
            if (now != g.tick || ammo != g.ammo) {
                if (g.ammo >= 0 && ammo > g.ammo) g.reloads++;
                g.shots++;
                g.ammo = ammo;
                g.tick = now;
            }
            SHOT.put(projectile, g.shots);
        }

        int held() {
            return GUNNERS.size() + SHOT.size() + FIGHTS.size();
        }

        static final class Fight {
            long firstTick, lastTick, shot0, reloads0;
            String pair = "-";
            double tier = Double.NaN;
            final Set<Long> clicks = new HashSet<>(), heads = new HashSet<>();
        }

        final Map<UUID, Map<UUID, Fight>> FIGHTS = Collections.synchronizedMap(new HashMap<>());
        static final long FORGET = 20 * 60;
        int sinceSweep;

        void hit(UUID target, boolean targetPlayer, Object shooterEntity, UUID shooterId, boolean shooterPlayer, Object projectile, Object direct, Object bulletNow,
                long now, String id, double tier, boolean head) {
            if (!targetPlayer || !shooterPlayer) return;
            Object shooter = shooterEntity;
            Long shot = projectile != null ? SHOT.get(projectile) : null;
            if (shot == null) shot = SHOT.get(direct);
            if (shot == null) shot = bulletNow == null ? null : SHOT.get(bulletNow);
            synchronized (FIGHTS) {
                if (++sinceSweep >= 256) {
                    sinceSweep = 0;
                    FIGHTS.values().removeIf(m -> {
                        m.values().removeIf(x -> now - x.lastTick > FORGET);
                        return m.isEmpty();
                    });
                }
                Map<UUID, Fight> of = FIGHTS.computeIfAbsent(target, u -> new HashMap<>());
                Fight f = of.get(shooterId);
                if (f == null || now - f.lastTick > FORGET) {
                    f = new Fight();
                    f.firstTick = now;
                    Gunner g = GUNNERS.get(shooter);
                    f.shot0 = shot != null ? shot : g == null ? 0 : g.shots;
                    f.reloads0 = g == null ? 0 : g.reloads;
                    of.put(shooterId, f);
                }
                f.lastTick = now;
                if (id != null) {
                    f.pair = id;
                    f.tier = tier;
                }
                long click = shot != null ? shot : -now;
                f.clicks.add(click);
                if (head) f.heads.add(click);
            }
        }

        /** the death; the line or null */
        String death(UUID victimId, boolean victimPlayer, Object killer, UUID killerId, boolean killerPlayer, boolean same, boolean on, long now, LocalDateTime time,
                String kName, String vName, String kSet, double kTier, String vSet, double vTier, float distance) {
            if (!victimPlayer) return null;
            Map<UUID, Fight> of = FIGHTS.remove(victimId);
            if (!on || !killerPlayer || same) return null;
            Fight f = of == null ? null : of.get(killerId);
            if (f != null && now - f.lastTick > FORGET) f = null;
            return line(killer, f, now, time, kName, vName, kSet, kTier, vSet, vTier, distance);
        }

        String line(Object killer, Fight f, long now, LocalDateTime time, String kName, String vName, String kSet, double kTier, String vSet, double vTier, float distance) {
            Gunner g = GUNNERS.get(killer);
            String pair = f == null ? "-" : f.pair;
            int dot = pair.indexOf('.');
            String gun = dot > 0 ? pair.substring(0, dot) : pair, ammo = dot > 0 ? pair.substring(dot + 1) : "-";
            String ttk = f == null ? "-" : num((now - f.firstTick) / 20.0);
            String shots = f == null || g == null || g.shots < f.shot0 ? "-" : String.valueOf(g.shots - f.shot0 + 1);
            String reloads = f == null || g == null || g.reloads < f.reloads0 || g.shots < f.shot0 ? "-" : String.valueOf(g.reloads - f.reloads0);
            return String.join("\t", time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 19), kName, vName, gun, ammo, f == null ? "-" : num(f.tier),
                    kSet, num(kTier), vSet, num(vTier), num(distance), ttk, shots, f == null ? "-" : String.valueOf(f.clicks.size()),
                    f == null ? "-" : String.valueOf(f.heads.size()), reloads) + "\n";
        }

        static String num(double v) {
            return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "-";
        }

        void logout(UUID id) {
            FIGHTS.remove(id);
        }
    }

    // ---- now: 26.2's BalanceLog's lines around BalanceLogBase
    static void newShot(Object projectile, Object shooter, boolean player, int ammo, long now) {
        if (!player) return;
        BalanceLogBase.shot(projectile, shooter, now, ammo);
    }

    static void newHit(UUID target, boolean targetPlayer, Object shooter, UUID shooterId, boolean shooterPlayer, Object projectile, Object direct, Object bulletNow,
            long now, String id, double tier, boolean head) {
        if (!targetPlayer || !shooterPlayer) return;
        Long shot = projectile != null ? BalanceLogBase.shotOf(projectile) : null;
        if (shot == null) shot = BalanceLogBase.shotOf(direct);
        if (shot == null) shot = bulletNow == null ? null : BalanceLogBase.shotOf(bulletNow);
        BalanceLogBase.count(target, shooterId, shooter, shot, now, id, tier, head);
    }

    static String newDeath(UUID victimId, boolean victimPlayer, Object killer, UUID killerId, boolean killerPlayer, boolean same, boolean on, long now,
            LocalDateTime time, String kName, String vName, String kSet, double kTier, String vSet, double vTier, float distance) {
        if (!victimPlayer) return null;
        Map<UUID, BalanceLogBase.Fight> of = BalanceLogBase.forget(victimId);
        if (!on || !killerPlayer || same) return null;
        BalanceLogBase.Fight f = BalanceLogBase.fight(of, killerId, now);
        return BalanceLogBase.line(time, kName, vName, killer, f, now, kSet, kTier, vSet, vTier, distance);
    }

    private static final String[] PAIRS = {"gun_g3.r556_sp", "gun_spas12.g12", "grenade.he", "nodot", ".lead", "gun_x.a.b", "", null};

    // the core's log is the JVM's one: both runs in one test, one after the other on the same old log, every entity
    // kept to the end (no weak key goes from one map and not the other)
    @Test
    void theSameAsBefore() {
        Old old = new Old();
        List<Object> kept = new ArrayList<>();
        // from tick 0, the clock slow: a hit of no shot (its click -tick) meets the shots' own numbers
        int next = run(old, kept, 1121L, 0, false, 1, 1);
        // then fights gone quiet, to the tick and past it, sweeps
        next = run(old, kept, 20261120L, 1_000_000, true, next, 2);
        // a hit of no shot at tick 3 (click -3) and a hit of shot 3 at that tick: two clicks, not one
        Key shooter = new Key(next++), victim = new Key(next++);
        UUID s = new UUID(3, 0), v = new UUID(3, 1);
        kept.add(shooter);
        kept.add(victim);
        LocalDateTime time = LocalDateTime.of(2026, 11, 20, 13, 0, 0);
        for (int shot = 0; shot < 3; shot++) {
            Key p = new Key(next++);
            kept.add(p);
            old.shot(p, shooter, true, 10 - shot, shot + 1);
            newShot(p, shooter, true, 10 - shot, shot + 1);
            if (shot == 2) {
                old.hit(v, true, shooter, s, true, p, null, null, 3, "gun_g3.r556_sp", 2, true);
                newHit(v, true, shooter, s, true, p, null, null, 3, "gun_g3.r556_sp", 2, true);
            }
        }
        old.hit(v, true, shooter, s, true, null, null, null, 3, null, 0, true);
        newHit(v, true, shooter, s, true, null, null, null, 3, null, 0, true);
        String a = old.death(v, true, shooter, s, true, false, true, 4, time, "s", "v", "-", 1, "-", 2, 5F);
        String b = newDeath(v, true, shooter, s, true, false, true, 4, time, "s", "v", "-", 1, "-", 2, 5F);
        assertEquals(a, b);
        assertEquals("2", a.split("\t")[13], a);
        assertEquals(old.held(), BalanceLogBase.held());
    }

    private static int run(Old old, List<Object> kept, long seed, long start, boolean quietToo, int next, int round) {
        SplittableRandom r = new SplittableRandom(seed);
        int players = 5;
        // a player's entity now (respawned: another object, another number)
        List<Key> alive = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            Key k = new Key(next++);
            alive.add(k);
            kept.add(k);
            ids.add(new UUID(round, i));
        }
        List<Key> projectiles = new ArrayList<>();
        long now = start;
        int lines = 0, nulls = 0, quiet = 0, deaths = 0, sameId = 0;
        LocalDateTime time = LocalDateTime.of(2026, 11, 20, 12, 0, 0);
        for (int step = 0; step < 200_000; step++) {
            int kind = r.nextInt(20);
            if (kind < 7) {
                // a shot: pellets of one click in the same tick, the magazine down, up (a reload), none
                int s = r.nextInt(players);
                Key p = new Key(next++);
                projectiles.add(p);
                kept.add(p);
                int ammo = r.nextInt(8) == 0 ? -1 : r.nextInt(30);
                boolean player = r.nextInt(15) != 0;
                old.shot(p, alive.get(s), player, ammo, now);
                newShot(p, alive.get(s), player, ammo, now);
            } else if (kind < 15) {
                int s = r.nextInt(players), t = r.nextInt(players);
                Object proj = null, direct = null, bullet = null;
                if (!projectiles.isEmpty()) {
                    Key p = projectiles.get(r.nextInt(projectiles.size()));
                    // the projectile itself, or another object of its number (equal), or one of no shot
                    Object pick = switch (r.nextInt(4)) {
                        case 0 -> p;
                        case 1 -> {
                            sameId++;
                            Key twin = new Key(p.id);
                            kept.add(twin);
                            yield twin;
                        }
                        case 2 -> new Key(-1 - r.nextInt(5)); // no projectile has a number below 0
                        default -> null;
                    };
                    switch (r.nextInt(3)) {
                        case 0 -> proj = pick;
                        case 1 -> direct = pick;
                        default -> bullet = pick;
                    }
                }
                String pair = PAIRS[r.nextInt(PAIRS.length)];
                double tier = r.nextInt(10) == 0 ? Double.NaN : r.nextInt(5) * 0.5;
                boolean head = r.nextInt(4) == 0, tp = r.nextInt(20) != 0, sp = r.nextInt(20) != 0;
                old.hit(ids.get(t), tp, alive.get(s), ids.get(s), sp, proj, direct, bullet, now, pair, tier, head);
                newHit(ids.get(t), tp, alive.get(s), ids.get(s), sp, proj, direct, bullet, now, pair, tier, head);
            } else if (kind < 17) {
                int v = r.nextInt(players), k = r.nextInt(players);
                boolean vp = r.nextInt(20) != 0, kp = r.nextInt(20) != 0, on = r.nextInt(10) != 0;
                String kSet = r.nextBoolean() ? "-" : "set" + r.nextInt(4), vSet = r.nextBoolean() ? "-" : "set" + r.nextInt(4);
                double kTier = r.nextInt(5) == 0 ? Double.NaN : r.nextDouble(0, 6), vTier = r.nextInt(5) == 0 ? Double.NaN : r.nextDouble(0, 6);
                float distance = r.nextInt(30) == 0 ? Float.NaN : (float) r.nextDouble(0, 200);
                String a = old.death(ids.get(v), vp, alive.get(k), ids.get(k), kp, v == k, on, now, time, "p" + k, "p" + v, kSet, kTier, vSet, vTier, distance);
                String b = newDeath(ids.get(v), vp, alive.get(k), ids.get(k), kp, v == k, on, now, time, "p" + k, "p" + v, kSet, kTier, vSet, vTier, distance);
                assertEquals(a, b, "death at step " + step);
                deaths++;
                if (a == null) nulls++;
                else {
                    lines++;
                    if (a.split("\t")[11].equals("-")) quiet++;
                }
                // the dead one respawns now and then: another object, another number
                if (vp && r.nextInt(3) == 0) {
                    Key k2 = new Key(next++);
                    alive.set(v, k2);
                    kept.add(k2);
                }
            } else if (kind < 18) {
                UUID id = ids.get(r.nextInt(players));
                old.logout(id);
                BalanceLogBase.forget(id);
            } else {
                // time: a tick, a few seconds, past FORGET
                // time: a tick, a few seconds, FORGET to the tick, past it
                now += !quietToo ? r.nextInt(3) : switch (r.nextInt(6)) {
                    case 0 -> 1;
                    case 1 -> r.nextInt(100);
                    case 2 -> r.nextInt(600);
                    case 3 -> Old.FORGET;
                    case 4 -> Old.FORGET + 1;
                    default -> Old.FORGET + r.nextInt(400);
                };
                time = time.plusSeconds(r.nextInt(30));
            }
            assertEquals(old.held(), BalanceLogBase.held(), "held at step " + step);
        }
        assertTrue(lines > 1000 && nulls > 100 && (quiet > 100 || !quietToo) && sameId > 1000, "lines " + lines + " none " + nulls + " quiet " + quiet + " same number " + sameId + " of deaths " + deaths);
        return next;
    }

    @Test
    void numAndCeiling() {
        assertEquals("-", BalanceLogBase.num(Double.NaN));
        assertEquals("1.25", BalanceLogBase.num(1.25));
        assertEquals("-", BalanceLogBase.num(Double.POSITIVE_INFINITY));
    }
}
