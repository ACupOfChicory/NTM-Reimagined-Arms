package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.HurtNow;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.host.Worlds;

/** 5bac642's log on live fakes against core: all state, TSV bytes and reads after each step. */
class BalanceLogStep1Test {
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 20, 14, 15, 16, 999_999_999);
    private static final Object GUN = new Object();

    /** Entity equality is the game's number, while Body identity belongs to the entity object. */
    static final class Fake {
        final int id;
        UUID uuid;
        final String name;
        final boolean player;
        final String kit;
        final List<String> calls;
        final Object level;
        Body body;

        Fake(int id, boolean player, String kit, Fixture f) {
            this.id = id;
            this.uuid = new UUID(42, id);
            this.name = new String("name" + id);
            this.player = player;
            this.kit = kit;
            this.calls = f.calls;
            this.level = f.level;
            body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
                case "player" -> isPlayer();
                case "uuid" -> uuid();
                case "profileName" -> name();
                case "level" -> level();
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                default -> throw new AssertionError(m.getName());
            });
        }

        boolean isPlayer() { calls.add("player:" + id); return player; }
        UUID uuid() { calls.add("uuid:" + id); return uuid; }
        String name() { calls.add("name:" + id); return player ? name : null; }
        Object level() { calls.add("level:" + id); return level; }
        @Override public boolean equals(Object o) { return o instanceof Fake f && id == f.id; }
        @Override public int hashCode() { return id; }
    }

    record Source(Fake killer, Object direct) {}

    static final class Fixture implements BalanceLogBase.Reads {
        final List<String> calls = new ArrayList<>(), lines = new ArrayList<>();
        final Object level = new Object();
        final Map<Body, Fake> bodies = new IdentityHashMap<>();
        final Legacy old = new Legacy();
        final WorldView world;
        final HurtNow hurt;
        final Fake killer = fake(1, true, "dns"), victim = fake(2, true, "hev"), bot = fake(3, false, null);
        long tick;
        boolean enabled = true, client;
        int ammo = 9, bulletCalls, fail;
        Object bullet, secondBullet;
        boolean changingBullet;
        final BiFunction<Body, Integer, String> worn = (b, slot) -> {
            Fake f = bodies.get(b);
            calls.add("worn:" + f.id + ":" + slot);
            if (fail == 1) throw new IllegalStateException("armor");
            return f.kit;
        };
        final BooleanSupplier balanceOn = () -> { calls.add("balanceOn"); return true; };

        Fixture() {
            world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class}, (p, m, a) -> switch (m.getName()) {
                case "gameTime" -> { calls.add("tick"); if (fail == 2) throw new IllegalStateException("tick"); yield tick; }
                case "client" -> { calls.add("client"); yield client; }
                default -> throw new AssertionError(m.getName());
            });
            hurt = (HurtNow) Proxy.newProxyInstance(HurtNow.class.getClassLoader(), new Class<?>[] {HurtNow.class}, (p, m, a) -> {
                if (!m.getName().equals("corrosion")) throw new AssertionError(m.getName());
                calls.add("corrosion:" + bodies.get(a[0]).id);
                return 0.375F;
            });
            worlds().put(level, world);
        }

        Fake fake(int id, boolean player, String kit) {
            Fake f = new Fake(id, player, kit, this);
            bodies.put(f.body, f);
            return f;
        }

        @Override public int ammo(Body b, Object gun) { assertSame(GUN, gun); calls.add("ammo:" + bodies.get(b).id); return ammo; }
        @Override public Body cause(Object s) { calls.add("cause"); Fake f = ((Source) s).killer(); return f == null ? null : f.body; }
        @Override public Object direct(Object s) { calls.add("direct"); return ((Source) s).direct(); }
        @Override public Object bulletNow() { calls.add("bullet"); return changingBullet && bulletCalls++ > 0 ? secondBullet : bullet; }
        @Override public Object raw(Body b) { return bodies.get(b); }
        @Override public double distance(Body k, Body v) {
            calls.add("distance:" + bodies.get(k).id + ":" + bodies.get(v).id);
            if (fail == 3) throw new IllegalStateException("distance");
            if (fail == 5) throw new LinkageError("distance");
            return (double) (float) Math.sqrt(123.456789);
        }
        @Override public boolean on() { calls.add("on"); return enabled; }
        @Override public LocalDateTime time() { calls.add("time"); return TIME; }
        @Override public void write(String line) { calls.add("write"); if (fail == 4) throw new IllegalStateException("write"); lines.add(line); }
        @Override public void failed(RuntimeException e) { calls.add("failed:" + e.getMessage()); }
        @Override public HurtNow hurt() { calls.add("hurtPort"); return hurt; }
        @Override public BooleanSupplier balanceOn() { calls.add("balancePort"); return balanceOn; }

        Object source(Source s) { calls.add("eventSource"); return s; }

        void oldShot(Object p, Fake s) {
            if (s == null || !s.isPlayer()) return;
            int left = ammo(s.body, GUN);
            s.level();
            old.shot(p, s, world.gameTime(), left);
        }

        void oldHit(Fake target, Source source, Object p, String id, double tier, boolean head) {
            if (target == null || !target.isPlayer()) return;
            Body owner = cause(source);
            if (owner == null || !bodies.get(owner).isPlayer()) return;
            Fake shooter = bodies.get(owner);
            Long shot = p != null ? old.shotOf(p) : null;
            if (shot == null) shot = old.shotOf(direct(source));
            if (shot == null) shot = bulletNow() == null ? null : old.shotOf(bulletNow());
            UUID t = target.uuid(), s = shooter.uuid();
            target.level();
            old.count(t, s, shooter, shot, world.gameTime(), id, tier, head);
        }

        void oldDeath(Fake v, Source s) {
            if (!v.isPlayer()) return;
            v.level();
            if (world.client()) return;
            Map<UUID, Legacy.Fight> of = old.forget(v.uuid());
            if (!on()) return;
            Body owner = cause(source(s));
            if (owner == null) return;
            Fake k = bodies.get(owner);
            if (!k.isPlayer() || k == v) return;
            try {
                v.level();
                long now = world.gameTime();
                Legacy.Fight f = old.fight(of, k.uuid(), now);
                String killerSet = oldSet(k);
                double killerTier = oldTier(k);
                String victimSet = oldSet(v);
                double victimTier = oldTier(v);
                write(old.line(time(), k.name(), v.name(), k, f, now, killerSet, killerTier, victimSet, victimTier, distance(k.body, v.body)));
            } catch (RuntimeException ex) {
                failed(ex);
            }
        }

        String oldSet(Fake who) {
            var a = BalanceFinalBase.armorOf(who.body, BalanceFinalBase.state(), hurt(), balanceOn(), worn);
            return a == null ? "-" : a.kind();
        }

        double oldTier(Fake who) {
            var a = BalanceFinalBase.armorOf(who.body, BalanceFinalBase.state(), hurt(), balanceOn(), worn);
            return a == null ? Double.NaN : a.tier();
        }

        void step(String label, Runnable before, Runnable after) {
            calls.clear();
            lines.clear();
            bulletCalls = 0;
            Throwable oldError = thrown(before);
            List<String> expectedCalls = List.copyOf(calls), expectedLines = List.copyOf(lines);
            calls.clear();
            lines.clear();
            bulletCalls = 0;
            Throwable newError = thrown(after);
            assertEquals(oldError == null ? null : oldError.getClass(), newError == null ? null : newError.getClass(), label);
            assertEquals(expectedCalls, calls, label + " reads");
            assertEquals(expectedLines, lines, label + " TSV");
            assertEquals(snapshot(Legacy.class, old), snapshot(BalanceLogBase.class, null), label + " state");
        }

        void shot(Object p, Fake s) {
            step("shot", () -> oldShot(p, s), () -> BalanceLogBase.shot(p, s == null ? null : s.body, GUN, this));
        }

        void hit(Fake v, Fake k, Object p, Object direct, String id, double tier, boolean head) {
            Source s = new Source(k, direct);
            step("hit", () -> oldHit(v, s, p, id, tier, head),
                    () -> BalanceLogBase.hit(v == null ? null : v.body, s, p, id, tier, head, this));
        }

        void death(Fake v, Fake k) {
            Source s = new Source(k, null);
            step("death", () -> oldDeath(v, s),
                    () -> BalanceLogBase.death(v.body, () -> source(s), this, worn));
        }

        void logout(Fake p) {
            step("logout", () -> old.forget(p.uuid()), () -> BalanceLogBase.logout(p.body));
        }
    }

    private static Throwable thrown(Runnable action) {
        try { action.run(); return null; } catch (RuntimeException | LinkageError e) { return e; }
    }

    @SuppressWarnings("unchecked")
    private static LevelMap<WorldView> worlds() { return (LevelMap<WorldView>) field(Worlds.class, null, "WORLDS"); }

    private static Object field(Class<?> type, Object instance, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(instance);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Map<Object, Object> snapshot(Class<?> type, Object instance) {
        Map<Object, Object> result = new HashMap<>();
        for (String name : List.of("GUNNERS", "SHOT", "FIGHTS"))
            result.put(name, values((Map<?, ?>) field(type, instance, name)));
        result.put("sinceSweep", field(type, instance, "sinceSweep"));
        return result;
    }

    private static Map<Object, Object> values(Map<?, ?> map) {
        Map<Object, Object> out = new HashMap<>();
        map.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) out.put(key, values(nested));
            else if (value instanceof Long) out.put(key, value);
            else {
                Map<String, Object> fields = new HashMap<>();
                for (Field f : value.getClass().getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(value);
                        fields.put(f.getName(), v instanceof Set<?> s ? Set.copyOf(s)
                                : v instanceof Double d ? Double.doubleToRawLongBits(d) : v);
                    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                }
                out.put(key, fields);
            }
        });
        return out;
    }

    private static void clear() {
        for (String name : List.of("GUNNERS", "SHOT", "FIGHTS")) ((Map<?, ?>) field(BalanceLogBase.class, null, name)).clear();
        try {
            Field f = BalanceLogBase.class.getDeclaredField("sinceSweep");
            f.setAccessible(true);
            f.setInt(null, 0);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void run(Consumer<Fixture> test) {
        clear();
        Fixture f = new Fixture();
        try { test.accept(f); }
        finally { clear(); Worlds.forget(f.level); }
    }

    @Test
    void playersPelletsReloadsFallbacksAndExactTsv() {
        run(f -> {
            Fake p = f.fake(10, false, null), twin = f.fake(10, false, null), second = f.fake(11, false, null);
            f.shot(p, f.bot);
            f.shot(p, null);
            f.shot(p, f.killer);
            f.shot(second, f.killer);
            assertEquals(1L, ((BalanceLogBase.Gunner) ((Map<?, ?>) field(BalanceLogBase.class, null, "GUNNERS")).get(f.killer)).shots);
            f.hit(f.bot, f.killer, p, null, "g.a", 1, false);
            f.hit(null, f.killer, p, null, "g.a", 1, false);
            f.hit(f.victim, f.bot, p, null, "g.a", 1, false);
            f.hit(f.victim, null, p, null, "g.a", 1, false);
            f.hit(f.victim, f.killer, twin, null, "g.a.b", 1.25, true);
            f.tick = 20;
            f.ammo = 8;
            f.shot(second, f.killer);
            f.hit(f.victim, f.killer, null, second, null, 99, false);
            f.tick = 40;
            f.ammo = 30;
            f.shot(p, f.killer);
            f.bullet = p;
            f.hit(f.victim, f.killer, null, null, "g.a.b", 1.25, true);
            f.death(f.victim, f.killer);
            String expected = "2026-11-20T14:15:16\tname1\tname2\tg\ta.b\t1.25\tdns\t%s\thev\t%s\t11.11\t2.00\t3\t3\t2\t1\n";
            String[] cells = f.lines.getFirst().split("\t");
            assertEquals(expected.formatted(cells[7], cells[9]), f.lines.getFirst());
            f.changingBullet = true;
            f.secondBullet = second;
            f.hit(f.victim, f.killer, null, null, ".lead", Double.NaN, false);
            assertEquals(2, Collections.frequency(f.calls, "bullet"));
            f.bullet = null;
            f.hit(f.victim, f.killer, null, null, "nodot", 0, true);
            assertEquals(1, Collections.frequency(f.calls, "bullet"));
            f.ammo = -1;
            f.shot(second, f.killer);
            f.logout(f.victim);
        });
    }

    @Test
    void deathsLogoutDisabledClientAndSelfKill() {
        run(f -> {
            for (Fake k : Arrays.asList(f.killer, f.bot, null, f.victim)) {
                f.hit(f.victim, f.killer, null, null, "g.a", 1, true);
                f.death(f.victim, k);
                assertTrue(((Map<?, ?>) field(BalanceLogBase.class, null, "FIGHTS")).isEmpty());
            }
            f.death(f.bot, f.killer);
            f.enabled = false;
            f.hit(f.victim, f.killer, null, null, null, 0, false);
            f.death(f.victim, f.killer);
            assertFalse(f.calls.contains("cause"));
            f.enabled = true;
            f.client = true;
            f.hit(f.victim, f.killer, null, null, null, 0, false);
            f.death(f.victim, f.killer);
            assertFalse(((Map<?, ?>) field(BalanceLogBase.class, null, "FIGHTS")).isEmpty());
            f.client = false;
            f.logout(f.victim);
            f.death(f.victim, f.killer);
            assertTrue(f.lines.getFirst().contains("\t-\t-\t-\t"));
        });
    }

    @Test
    void ttlAtBoundaryAfterBoundaryAndSweep() {
        run(f -> {
            f.hit(f.victim, f.killer, null, null, "g.a", 2, false);
            f.tick = 1200;
            f.death(f.victim, f.killer);
            assertEquals("60.00", f.lines.getFirst().split("\t")[11]);
            f.hit(f.victim, f.killer, null, null, "g.a", 2, false);
            f.tick += 1201;
            f.death(f.victim, f.killer);
            assertEquals("-", f.lines.getFirst().split("\t")[11]);
            f.tick = 10000;
            f.hit(f.victim, f.killer, null, null, "g.a", 2, false);
            f.tick += 1200;
            f.hit(f.victim, f.killer, null, null, null, 0, false);
            f.tick += 1201;
            f.hit(f.victim, f.killer, null, null, null, 0, false);
            Fake quiet = f.fake(77, true, null);
            f.hit(quiet, f.killer, null, null, "stale", 0, false);
            f.tick += 1201;
            for (int i = 0; i < 256; i++) f.hit(f.victim, f.killer, null, null, "g.a", 1, false);
            assertFalse(((Map<?, ?>) field(BalanceLogBase.class, null, "FIGHTS")).containsKey(quiet.uuid));
        });
    }

    @Test
    void exceptionBoundariesAndRawKeys() {
        run(f -> {
            Fake p = f.fake(10, false, null);
            f.shot(p, f.killer);
            Fake sameNumber = f.fake(1, true, null);
            f.tick++;
            f.shot(p, sameNumber);
            assertEquals(1, ((Map<?, ?>) field(BalanceLogBase.class, null, "GUNNERS")).size());
            Fake equalVictim = f.fake(f.victim.id, true, null);
            f.death(f.victim, equalVictim);
            assertEquals(1, f.lines.size());
            for (int fail = 1; fail <= 5; fail++) {
                f.hit(f.victim, f.killer, p, null, "g.a", 2, true);
                f.fail = fail;
                f.death(f.victim, f.killer);
                if (fail < 5) assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("failed:")));
                else assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("failed:")));
                f.fail = 0;
            }
        });
    }

    @Test
    void deterministicMixedStepsAndRespawn() {
        run(f -> {
            SplittableRandom random = new SplittableRandom(5_642);
            List<Fake> kept = new ArrayList<>(List.of(f.killer, f.victim, f.bot));
            List<Fake> players = new ArrayList<>(List.of(f.killer, f.victim, f.fake(4, true, null)));
            for (int i = 0; i < 2500; i++) {
                Fake k = players.get(random.nextInt(players.size())), v = players.get(random.nextInt(players.size()));
                Fake p = f.fake(100 + i, false, null);
                kept.add(p);
                f.bullet = random.nextBoolean() ? p : null;
                switch (random.nextInt(6)) {
                    case 0 -> { f.ammo = random.nextInt(-1, 31); f.shot(p, k); }
                    case 1, 2 -> f.hit(v, k, random.nextBoolean() ? p : null, p, random.nextBoolean() ? "g.a" : null, 0.25, random.nextBoolean());
                    case 3 -> f.death(v, k);
                    case 4 -> f.logout(v);
                    default -> f.tick += random.nextInt(1202);
                }
                if (i == 1000) {
                    Fake respawn = f.fake(9000, true, null);
                    respawn.uuid = players.get(0).uuid;
                    players.set(0, respawn);
                    kept.add(respawn);
                }
            }
            assertFalse(kept.isEmpty());
        });
    }

    /** The old state and calculations, copied from HEAD without changing their operations. */
    static final class Legacy {
        // ---------------------------------------------------------------- a shot

        /** a shooter's shots so far and its reloads (a magazine fuller than after its last shot) */
        static final class Gunner {
            long shots, reloads, tick = Long.MIN_VALUE;
            int ammo = -1;
        }

        private final Map<Object, Gunner> GUNNERS = Collections.synchronizedMap(new WeakHashMap<>());
        /** the shot (its number) a projectile is of */
        private final Map<Object, Long> SHOT = Collections.synchronizedMap(new WeakHashMap<>());

        /**
         * a player's shot: {@code projectile} made by {@code shooter} at game tick {@code now}, its magazine {@code ammo}
         * after it (-1: not known); its number on the projectile, a reload before it counted
         */
        void shot(Object projectile, Object shooter, long now, int ammo) {
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
        @Nullable Long shotOf(@Nullable Object projectile) {
            return SHOT.get(projectile);
        }

        /** the stand: what the log holds now (shooters, projectiles, fights), to see that a shot does no work with it off */
        int held() {
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
        private final Map<UUID, Map<UUID, Fight>> FIGHTS = Collections.synchronizedMap(new HashMap<>());
        /** a fight with no hit for this long starts again (ticks), and is no more the kill's */
        static final long FORGET = 20 * 60;
        /** hits since the last sweep of the fights gone quiet (a target that lived or left) */
        private int sinceSweep;

        /**
         * a player's hit on the player {@code target} at game tick {@code now}, of the shot {@code shot} (null: none known)
         * of the pair {@code id} (null: the pair kept) at {@code tier}: counted to the fight of {@code shooter} (its UUID
         * {@code shooterId})
         */
        void count(UUID target, UUID shooterId, Object shooter, @Nullable Long shot, long now, @Nullable String id, double tier, boolean head) {
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
        @Nullable Map<UUID, Fight> forget(UUID player) {
            return FIGHTS.remove(player);
        }

        /** the killer's fight among a dead one's ({@link #forget}) at game tick {@code now}: null when none or gone quiet */
        @Nullable Fight fight(@Nullable Map<UUID, Fight> of, UUID killer, long now) {
            Fight f = of == null ? null : of.get(killer);
            // a fight gone quiet long ago is not this kill's (the kill came by some other way)
            if (f != null && now - f.lastTick > FORGET) f = null;
            return f;
        }

        /**
         * the kill's line at {@code time}: {@code killer} the killer's entity (its shots), its fight {@code f} (null: none),
         * the sets and tiers of both and the distance as the version read them
         */
        String line(LocalDateTime time, String killerName, String victimName, Object killer, @Nullable Fight f, long now, String killerSet, double killerTier,
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

        String num(double v) {
            return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "-";
        }


    }
}

