package acup.gunfeel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.HurtNow;
import acup.gunfeel.host.Worlds;

/**
 * The fight log without the game (BalanceLog on each version reads the magazine, the source and the distance).
 * A shooter and a projectile are the version's entities as
 * they are, keys of weak maps by their own equals (on 26.2 and 1.7.10 the entity's number), never wrapped: a wrapper
 * would key them by identity. A fight is by the players' UUIDs.
 */
class BalanceLogBase {
    BalanceLogBase() {
    }

    static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");
    static final String FILE = "logs/ntmgunfeel-fights.log";
    static final String HEADER = "# time\tkiller\tvictim\tgun\tammo\tpair tier\tkiller set\tkiller tier\tvictim set\tvictim tier\tdistance\tttk s\tshots\thits\thead hits\treloads\n";

    /** The version's reads and output; no fight state. */
    interface Reads {
        /** Magazine left (26.2: first receiver's magazine.getAmount with the player's inventory). */
        int ammo(Body player, Object gun);
        /** Causing entity (26.2: EntityBody.of(DamageSource.getEntity), null for none). */
        @Nullable Body cause(Object source);
        /** Direct entity (26.2: DamageSource.getDirectEntity). */
        @Nullable Object direct(Object source);
        /** Impact bullet (26.2: ExplosiveDamage.bulletNow). */
        @Nullable Object bulletNow();
        /** Raw map key (26.2: EntityBody.entity). */
        Object raw(Body b);
        /** Entity distance, float widened (26.2: Entity.distanceTo). */
        double distance(Body killer, Body victim);
        /** Log switch (26.2: BalanceLog.on). */
        boolean on();
        /** Wall clock (26.2: LocalDateTime.now). */
        LocalDateTime time();
        /** Append the line (26.2: BalanceLogBase.write). */
        void write(String line);
        /** Failed kill (26.2: LOGGER.warn). */
        void failed(RuntimeException ex);
        /** Live hurt port (26.2: EuphImmunityBase.NowPort.NOW). */
        HurtNow hurt();
        /** Balance supplier (26.2: BalanceOn.ON). */
        java.util.function.BooleanSupplier balanceOn();
    }

    static void shot(Object projectile, @Nullable Body shooter, Object gun, Reads reads) {
        if (shooter == null || !shooter.player()) return;
        int ammo = reads.ammo(shooter, gun);
        shot(projectile, reads.raw(shooter), Worlds.of(shooter.level()).gameTime(), ammo);
    }

    static void hit(@Nullable Body target, Object source, @Nullable Object projectile, String id, double tier, boolean head, Reads reads) {
        if (target == null || !target.player()) return;
        Body shooter = reads.cause(source);
        if (shooter == null || !shooter.player()) return;
        Long shot = projectile != null ? shotOf(projectile) : null;
        if (shot == null) shot = shotOf(reads.direct(source));
        if (shot == null) shot = reads.bulletNow() == null ? null : shotOf(reads.bulletNow());
        count(target.uuid(), shooter.uuid(), reads.raw(shooter), shot, Worlds.of(target.level()).gameTime(), id, tier, head);
    }

    static void death(Body victim, java.util.function.Supplier<Object> source, Reads reads) {
        death(victim, source, reads, BalanceArmorBase::setOfWorn);
    }

    /** The same with live armor slots supplied for core's tests. */
    static void death(Body victim, java.util.function.Supplier<Object> source, Reads reads,
            java.util.function.BiFunction<Body, Integer, String> worn) {
        if (!victim.player() || Worlds.of(victim.level()).client()) return;
        Map<UUID, Fight> of = forget(victim.uuid());
        if (!reads.on()) return;
        Body killer = reads.cause(source.get());
        if (killer == null || !killer.player() || killer == victim) return;
        try {
            long now = Worlds.of(victim.level()).gameTime();
            Fight f = fight(of, killer.uuid(), now);
            String killerSet = set(killer, reads, worn);
            double killerTier = tier(killer, reads, worn);
            String victimSet = set(victim, reads, worn);
            double victimTier = tier(victim, reads, worn);
            reads.write(line(reads.time(), killer.profileName(), victim.profileName(), reads.raw(killer), f, now,
                    killerSet, killerTier, victimSet, victimTier, reads.distance(killer, victim)));
        } catch (RuntimeException ex) {
            reads.failed(ex);
        }
    }

    static void logout(Body player) {
        forget(player.uuid());
    }

    private static String set(Body who, Reads reads, java.util.function.BiFunction<Body, Integer, String> worn) {
        var a = BalanceFinalBase.armorOf(who, BalanceFinalBase.state(), reads.hurt(), reads.balanceOn(), worn);
        return a == null ? "-" : a.kind();
    }

    private static double tier(Body who, Reads reads, java.util.function.BiFunction<Body, Integer, String> worn) {
        var a = BalanceFinalBase.armorOf(who, BalanceFinalBase.state(), reads.hurt(), reads.balanceOn(), worn);
        return a == null ? Double.NaN : a.tier();
    }

    // ---------------------------------------------------------------- a shot

    /** a shooter's shots so far and its reloads (a magazine fuller than after its last shot) */
    static final class Gunner {
        long shots, reloads, tick = Long.MIN_VALUE;
        int ammo = -1;
    }

    private static final Map<Object, Gunner> GUNNERS = Collections.synchronizedMap(new WeakHashMap<>());
    /** the shot (its number) a projectile is of */
    private static final Map<Object, Long> SHOT = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * a player's shot: {@code projectile} made by {@code shooter} at game tick {@code now}, its magazine {@code ammo}
     * after it (-1: not known); its number on the projectile, a reload before it counted
     */
    static void shot(Object projectile, Object shooter, long now, int ammo) {
        Gunner g = GUNNERS.computeIfAbsent(shooter, s -> new Gunner());
        // the projectiles of one click (a shotgun's pellets) are made in the same tick and leave the magazine as it was: one shot
        if (now != g.tick || ammo != g.ammo) {
            if (g.ammo >= 0 && ammo > g.ammo) g.reloads++;
            g.shots++;
            g.ammo = ammo;
            g.tick = now;
        }
        SHOT.put(projectile, g.shots);
    }

    /** the shot a projectile is of, or null */
    static @Nullable Long shotOf(@Nullable Object projectile) {
        return SHOT.get(projectile);
    }

    /** the stand: what the log holds now (shooters, projectiles, fights), to see that a shot does no work with it off */
    static int held() {
        return GUNNERS.size() + SHOT.size() + FIGHTS.size();
    }

    // ---------------------------------------------------------------- a hit

    /** a fight of one shooter on one target: since its first hit */
    static final class Fight {
        long firstTick, lastTick, shot0, reloads0;
        String pair = "-";
        double tier = Double.NaN;
        final Set<Long> clicks = new HashSet<>(), heads = new HashSet<>();
    }

    /** by the target's id: its shooters' fights */
    private static final Map<UUID, Map<UUID, Fight>> FIGHTS = Collections.synchronizedMap(new HashMap<>());
    /** a fight with no hit for this long starts again (ticks), and is no more the kill's */
    static final long FORGET = 20 * 60;
    /** hits since the last sweep of the fights gone quiet (a target that lived or left) */
    private static int sinceSweep;

    /**
     * a player's hit on the player {@code target} at game tick {@code now}, of the shot {@code shot} (null: none known)
     * of the pair {@code id} (null: the pair kept) at {@code tier}: counted to the fight of {@code shooter} (its UUID
     * {@code shooterId})
     */
    static void count(UUID target, UUID shooterId, Object shooter, @Nullable Long shot, long now, @Nullable String id, double tier, boolean head) {
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

    // ---------------------------------------------------------------- a death

    /** a player died or left: the fights on them, forgotten (null: none) */
    static @Nullable Map<UUID, Fight> forget(UUID player) {
        return FIGHTS.remove(player);
    }

    /** the killer's fight among a dead one's ({@link #forget}) at game tick {@code now}: null when none or gone quiet */
    static @Nullable Fight fight(@Nullable Map<UUID, Fight> of, UUID killer, long now) {
        Fight f = of == null ? null : of.get(killer);
        // a fight gone quiet long ago is not this kill's (the kill came by some other way)
        if (f != null && now - f.lastTick > FORGET) f = null;
        return f;
    }

    /**
     * the kill's line at {@code time}: {@code killer} the killer's entity (its shots), its fight {@code f} (null: none),
     * the sets and tiers of both and the distance as the version read them
     */
    static String line(LocalDateTime time, String killerName, String victimName, Object killer, @Nullable Fight f, long now, String killerSet, double killerTier,
            String victimSet, double victimTier, double distance) {
        Gunner g = GUNNERS.get(killer);
        String pair = f == null ? "-" : f.pair;
        int dot = pair.indexOf('.');
        String gun = dot > 0 ? pair.substring(0, dot) : pair, ammo = dot > 0 ? pair.substring(dot + 1) : "-";
        String ttk = f == null ? "-" : num((now - f.firstTick) / 20.0);
        // a killer respawned since the first hit counts its shots anew: then they are not known
        String shots = f == null || g == null || g.shots < f.shot0 ? "-" : String.valueOf(g.shots - f.shot0 + 1);
        String reloads = f == null || g == null || g.reloads < f.reloads0 || g.shots < f.shot0 ? "-" : String.valueOf(g.reloads - f.reloads0);
        return String.join("\t", time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 19), killerName, victimName, gun, ammo, f == null ? "-" : num(f.tier),
                killerSet, num(killerTier), victimSet, num(victimTier), num(distance), ttk, shots, f == null ? "-" : String.valueOf(f.clicks.size()),
                f == null ? "-" : String.valueOf(f.heads.size()), reloads) + "\n";
    }

    static String num(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "-";
    }

    /** balance.final.log.size, MB: the file's ceiling (then it is moved to .1 and begun again) */
    static long ceiling() {
        float v = ReaimTimes.number("balance.final.log.size", 4F);
        return (long) ((Float.isFinite(v) && v > 0F ? Math.min(v, 1024F) : 4F) * 1024 * 1024);
    }

    static synchronized void write(String line) {
        Path p = Path.of(FILE);
        try {
            Files.createDirectories(p.toAbsolutePath().getParent());
            if (Files.exists(p) && Files.size(p) + line.length() > ceiling()) Files.move(p, p.resolveSibling(p.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            if (!Files.exists(p)) Files.writeString(p, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(p, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("The fight log: could not write {}", p, e);
        }
    }
}
