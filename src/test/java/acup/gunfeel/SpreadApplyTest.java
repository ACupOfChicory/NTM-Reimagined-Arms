package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import acup.gunfeel.host.Body;

// core's LivingSpread.apply against 73ca3a5's Spread.apply word for word, the entity's getters made fields of a
// stand-in shooter (each getter's Body method is held to the game's by the entity view stage): the same total and
// logged numbers to the bit, the same reads of the shooter in the same order (Body's, the power armour's share and
// the bloom's), on every gun with spread keys and one with none, every mix of the shooter's flags, paces, aims, NaN.
class SpreadApplyTest {
    /** the shooter as 73ca3a5's apply read it off the game */
    static final class Shooter {
        boolean crawling, crouching, onGround, passenger, climbing, inWater, fallFlying, player, flying, client;
        double kx, ky, kz;
        float armor;
        long time;
        final List<String> reads = new ArrayList<>();
    }

    // ---- 73ca3a5's Spread.apply, inAir and bloom, each getter a field with its read noted
    private static final Map<Shooter, LivingSpread.Bloom> OLD_BLOOM = new HashMap<>();

    static float oldApply(Shooter e, String gun, boolean aim, float spread, float[] log) {
        spread = LivingSpread.choked(gun, aim, spread);
        boolean crawl = read(e, "crawling", e.crawling);
        boolean sneak = !crawl && read(e, "crouching", e.crouching);
        e.reads.add("knownSpeed");
        double mx = e.kx, mz = e.kz;
        float pace = crawl ? 0F : SpreadModel.pace((float) Math.sqrt(mx * mx + mz * mz));
        e.reads.add("armor");
        float armor = e.armor;
        float[] parts = new float[2];
        float mult = LivingSpread.mult(gun, aim, crawl, sneak, pace, armor, parts);
        float live = parts[0], stance = parts[1];
        float air = oldInAir(e) ? LivingSpread.key(gun, "spreadAir", 0F) * armor : 0F;
        float bloom = oldBloom(e, gun) * armor;
        float total = SpreadModel.total(spread, mult, LivingSpread.key(gun, "spreadSpeed", 0F), live, air, bloom, stance);
        log[0] = spread;
        log[1] = pace;
        log[2] = armor;
        log[3] = mult;
        log[4] = bloom;
        log[5] = crawl ? 1F : 0F;
        log[6] = sneak ? 1F : 0F;
        return total;
    }

    static boolean oldInAir(Shooter e) {
        if (read(e, "onGround", e.onGround) || read(e, "passenger", e.passenger) || read(e, "climbing", e.climbing) || read(e, "inWater", e.inWater)
                || read(e, "fallFlying", e.fallFlying)) return false;
        return !read(e, "flying", e.player && e.flying);
    }

    private static float oldBloom(Shooter e, String gun) {
        float perShot = LivingSpread.key(gun, "spreadBloom", 0F);
        if (perShot <= 0F || read(e, "bloom", e.client)) return 0F;
        return OLD_BLOOM.computeIfAbsent(e, k -> new LivingSpread.Bloom()).shot(gun, e.time, perShot);
    }

    private static boolean read(Shooter e, String what, boolean v) {
        e.reads.add(what);
        return v;
    }

    // ---- now: the shooter as a Body (a proxy noting each read), the version's Shooter as Spread's
    private static final Map<Shooter, LivingSpread.Bloom> NEW_BLOOM = new HashMap<>();
    private static final Map<Body, Shooter> OF = new HashMap<>();

    static Body body(Shooter e) {
        Body b = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, args) -> {
            String n = m.getName();
            if (n.equals("hashCode")) return System.identityHashCode(p);
            if (n.equals("equals")) return p == args[0];
            e.reads.add(n);
            return switch (n) {
                case "crawling" -> e.crawling;
                case "crouching" -> e.crouching;
                case "onGround" -> e.onGround;
                case "passenger" -> e.passenger;
                case "climbing" -> e.climbing;
                case "inWater" -> e.inWater;
                case "fallFlying" -> e.fallFlying;
                case "flying" -> e.player && e.flying;
                case "knownSpeed" -> {
                    double[] out = (double[]) args[0];
                    out[0] = e.kx;
                    out[1] = e.ky;
                    out[2] = e.kz;
                    yield null;
                }
                default -> throw new AssertionError("not read by the spread: " + n);
            };
        });
        OF.put(b, e);
        return b;
    }

    static final LivingSpread.Shooter SHOOTER = new LivingSpread.Shooter() {
        @Override
        public float armor(Body b, String gun) {
            Shooter e = OF.get(b);
            e.reads.add("armor");
            return e.armor;
        }

        @Override
        public float bloom(Body b, String gun, float perShot) {
            Shooter e = OF.get(b);
            e.reads.add("bloom");
            if (e.client) return 0F;
            return NEW_BLOOM.computeIfAbsent(e, k -> new LivingSpread.Bloom()).shot(gun, e.time, perShot);
        }
    };

    private static List<String> guns() {
        TreeSet<String> guns = new TreeSet<>();
        TreeSet<String> keys = new TreeSet<>(ReaimTimes.values().keySet());
        keys.addAll(BalanceKeys.ARMS.keySet());
        for (String k : keys) {
            int i = k.lastIndexOf(".spread");
            if (i > 0) guns.add(k.substring(0, i));
        }
        List<String> out = new ArrayList<>(guns);
        out.add("hbm:no_such_gun");
        return out;
    }

    private static double speed(SplittableRandom r) {
        return switch (r.nextInt(6)) {
            case 0 -> 0;
            case 1 -> r.nextDouble(-0.05, 0.05);
            case 2 -> r.nextDouble(-0.4, 0.4);
            case 3 -> r.nextDouble(-1.5, 1.5);
            case 4 -> r.nextInt(40) == 0 ? Double.NaN : 0.2;
            default -> -0.0;
        };
    }

    @Test
    void theSameAsBefore() {
        List<String> guns = guns();
        assertTrue(guns.size() > 5, "guns with spread keys: " + guns);
        SplittableRandom r = new SplittableRandom(1120L);
        float[] a = new float[7], b = new float[7];
        int airs = 0, blooms = 0, n = 0;
        for (String gun : guns) {
            Shooter old = new Shooter(), now = new Shooter();
            Body body = body(now);
            long time = r.nextLong(0, 1_000_000);
            for (int i = 0; i < 4_000; i++) {
                time += switch (r.nextInt(4)) {
                    case 0 -> 0;
                    case 1 -> 1 + r.nextInt(4);
                    case 2 -> r.nextInt(20);
                    default -> r.nextInt(200);
                };
                // every flag of the shooter by its own bits, now and then all of them
                int flags = r.nextInt(4) == 0 ? r.nextInt(1 << 10) : r.nextInt(1 << 10) & r.nextInt(1 << 10) & r.nextInt(1 << 10);
                double kx = speed(r), ky = speed(r), kz = speed(r);
                float armor = r.nextInt(3) == 0 ? (float) r.nextDouble(0, 1.5) : r.nextInt(30) == 0 ? Float.NaN : 1F;
                for (Shooter s : new Shooter[] {old, now}) {
                    s.crawling = (flags & 1) != 0;
                    s.crouching = (flags & 2) != 0;
                    s.onGround = (flags & 4) != 0;
                    s.passenger = (flags & 8) != 0;
                    s.climbing = (flags & 16) != 0;
                    s.inWater = (flags & 32) != 0;
                    s.fallFlying = (flags & 64) != 0;
                    s.player = (flags & 128) != 0;
                    s.flying = (flags & 256) != 0;
                    s.client = (flags & 512) != 0;
                    s.kx = kx;
                    s.ky = ky;
                    s.kz = kz;
                    s.armor = armor;
                    s.time = time;
                    s.reads.clear();
                }
                boolean aim = r.nextBoolean();
                float spread = r.nextInt(5) == 0 ? 0F : r.nextInt(50) == 0 ? Float.NaN : (float) r.nextDouble(0, 0.2);
                float ot = oldApply(old, gun, aim, spread, a);
                float nt = LivingSpread.apply(body, gun, aim, spread, SHOOTER, b);
                String at = gun + " #" + i + " flags " + Integer.toBinaryString(flags) + " aim " + aim + " speed " + kx + "," + kz + " armor " + armor + " spread " + spread;
                assertEquals(Float.floatToRawIntBits(ot), Float.floatToRawIntBits(nt), at);
                for (int k = 0; k < 7; k++) assertEquals(Float.floatToRawIntBits(a[k]), Float.floatToRawIntBits(b[k]), at + " log " + k);
                assertEquals(old.reads, now.reads, at);
                if (old.reads.contains("flying")) airs++;
                if (old.reads.contains("bloom")) blooms++;
                n++;
            }
        }
        assertTrue(airs > n / 20 && blooms > n / 20, "airs " + airs + " blooms " + blooms + " of " + n);
    }

    @Test
    void noBloomAtZero(@TempDir Path config) {
        // spreadBloom = 0 (a server's "no bloom"; the shipped keys have none at 0): the version's bloom is not asked.
        // The keys come as a server's (ServerSettings); the shapes that follow them read the jar's here, no config folder
        ShapeTable.load0(config);
        ServerSettings.useRemote(new ServerSettings.Snapshot("", Map.of("test:no_bloom.spreadBloom", "0"), Map.of(), ""));
        try {
            assertEquals(0F, LivingSpread.key("test:no_bloom", "spreadBloom", 0F));
            float[] a = new float[7], b = new float[7];
            for (int shot = 0; shot < 4; shot++) {
                Shooter old = new Shooter(), now = new Shooter();
                old.time = now.time = shot * 3L;
                float ot = oldApply(old, "test:no_bloom", shot % 2 == 0, 0.05F, a);
                float nt = LivingSpread.apply(body(now), "test:no_bloom", shot % 2 == 0, 0.05F, SHOOTER, b);
                assertEquals(Float.floatToRawIntBits(ot), Float.floatToRawIntBits(nt));
                assertEquals(old.reads, now.reads);
                assertTrue(!now.reads.contains("bloom") && now.reads.contains("armor"), now.reads.toString());
                assertEquals(0F, b[4]);
            }
        } finally {
            ServerSettings.useRemote(null);
        }
    }

    @Test
    void inAirTheSame() {
        for (int flags = 0; flags < 1 << 9; flags++) {
            Shooter s = new Shooter();
            s.onGround = (flags & 4) != 0;
            s.passenger = (flags & 8) != 0;
            s.climbing = (flags & 16) != 0;
            s.inWater = (flags & 32) != 0;
            s.fallFlying = (flags & 64) != 0;
            s.player = (flags & 128) != 0;
            s.flying = (flags & 256) != 0;
            Shooter t = new Shooter();
            t.onGround = s.onGround;
            t.passenger = s.passenger;
            t.climbing = s.climbing;
            t.inWater = s.inWater;
            t.fallFlying = s.fallFlying;
            t.player = s.player;
            t.flying = s.flying;
            assertEquals(oldInAir(s), LivingSpread.inAir(body(t)), "flags " + flags);
            assertEquals(s.reads, t.reads, "flags " + flags);
        }
    }
}
