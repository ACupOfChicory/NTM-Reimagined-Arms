package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// The living spread in power armour, on a dedicated server (./gradlew runCheckSpread -PnoAssets): a player without
// armour and in each set's chestplate asks NTM for a shot's spread (Lego.calcSpread, through MixinLegoSpread and
// Spread). No armour and T-51 go through every case (standing, walking, sprinting, in the air, aimed, a held-down
// burst, lying), the other sets through standing, walking, sprinting and the air with the G3, three times: the file
// holding powerArmorSpread = 1 (the key of before the table: every set does nothing), the mod's own values (the table
// powerArmorSpread.<set>), and a file with powerArmorSpread = 0.3 and powerArmorSpread.fau = 0.9 (the file's one
// number for every set, a set's own line over it). A tsv goes to the file and the server stops (exit 1 if a check
// failed): standing is the same in any armour; what moving, the air and the bloom add is the set's share of it.
public final class SpreadCheck {
    public static final String PROPERTY = "ntmgunfeel.checkSpread";
    private static final String[] GUNS = {"hbm:gun_g3", "hbm:gun_am180"};
    private static final String[] CASES = {"stand", "walk", "sprint", "jump", "stand aim", "walk aim", "burst aim", "prone", "prone walk"};
    private static final String[] SHORT = {"stand", "walk", "sprint", "jump"};
    private static final String[] SETTINGS = {"1", "mod", "file"};
    // every set the table has, the BJ with wings, and three with no bonus
    private static final String[] SETS = {"hev", "ajr", "ajro", "envsuit", "trenchmaster", "bj", "bj_jetpack", "ncrpa", "rpa", "fau", "dns",
            "security", "steamsuit", "dieselsuit"};
    // the bloom halves every 5 ticks: 100 ticks after a shot and 200 after a burst leave none of it worth a digit (60
    // between single shots: a 4000th of a shot's bloom)
    private static final int BURST = 30, SETTLE = 100, SETTLE_BURST = 200, SETTLE_SHORT = 60;

    private SpreadCheck() {
    }

    // set: "" for no armour
    private record Key(String setting, String gun, String set, String c) {
    }

    // one tick's work; a shot is one call of NTM's spread, at most one a tick (the bloom counts a tick's first call)
    private static final List<Runnable> STEPS = new ArrayList<>();
    private static final Map<Key, Float> SPREAD = new HashMap<>();
    private static final Map<Key, Float> PACE = new HashMap<>();
    private static final Map<Key, Boolean> CRAWL = new HashMap<>();
    private static final List<String> LINES = new ArrayList<>();
    private static boolean ok = true;

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, started -> {
            ServerLevel level = started.getServer().overworld();
            FakePlayer p = FakePlayerFactory.getMinecraft(level);
            p.setPos(0.5, 200, 0.5);
            plan(p);
            int[] at = {0};
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
                if (at[0] < 0) return;
                try {
                    if (at[0] < STEPS.size()) {
                        STEPS.get(at[0]++).run();
                        return;
                    }
                    at[0] = -1;
                    report();
                    LINES.add(ok ? "ALL OK" : "SOME FAILED");
                    BulletCheck.finish(file, LINES, ok ? 0 : 1);
                } catch (Throwable t) {
                    at[0] = -1;
                    LINES.add("FAIL\tcrashed: " + t);
                    for (StackTraceElement s : t.getStackTrace()) LINES.add("    at " + s);
                    BulletCheck.finish(file, LINES, 1);
                }
            });
        });
    }

    private static String chest(String set) {
        return set.equals("bj_jetpack") ? "hbm:bj_plate_jetpack" : "hbm:" + set + "_plate";
    }

    /** the share the check expects for the set under the setting */
    static float expected(String setting, String set) {
        String s = set.equals("bj_jetpack") ? "bj" : set;
        Float table = PowerArmor.SPREAD.get(s);
        if (table == null) return 1F; // Security, the steam and diesel suits
        return switch (setting) {
            case "1" -> 1F;
            case "file" -> s.equals("fau") ? 0.9F : 0.3F;
            default -> table;
        };
    }

    private static void plan(FakePlayer p) {
        for (String setting : SETTINGS) {
            STEPS.add(() -> settings(setting));
            wait(30); // the server's file is looked at once a second
            STEPS.add(() -> LINES.add("#\tsetting " + setting + ": powerArmorSpread = " + ReaimTimes.number("powerArmorSpread", 1F)
                    + ", in the file " + ReaimTimes.inFile("powerArmorSpread")));
            for (String gun : GUNS) for (String set : new String[] {"", "t51"}) cases(p, setting, gun, set, CASES, SETTLE);
            for (String set : SETS) cases(p, setting, GUNS[0], set, SHORT, SETTLE_SHORT);
        }
    }

    private static void cases(FakePlayer p, String setting, String gun, String set, String[] cases, int settle) {
        STEPS.add(() -> p.setItemSlot(EquipmentSlot.CHEST, set.isEmpty() ? ItemStack.EMPTY : new ItemStack(item(chest(set)))));
        for (String c : cases) {
            Key k = new Key(setting, gun, set, c);
            ItemStack stack = new ItemStack(item(gun));
            int shots = c.startsWith("burst") ? BURST : 1;
            int every = Math.max(1, ((ItemGunBaseNT) stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0].getDelayAfterFire(stack));
            for (int i = 0; i < shots; i++) {
                STEPS.add(() -> shot(p, stack, k));
                if (i < shots - 1) wait(every - 1);
            }
            wait(shots > 1 ? SETTLE_BURST : settle); // the bloom gone before the next case
        }
    }

    private static void wait(int ticks) {
        for (int i = 0; i < ticks; i++) STEPS.add(() -> {
        });
    }

    private static void settings(String setting) {
        try {
            Files.writeString(FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE), switch (setting) {
                case "1" -> "powerArmorSpread = 1\n";
                case "file" -> "powerArmorSpread = 0.3\npowerArmorSpread.fau = 0.9\n";
                default -> "# the mod's own values\n";
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static net.minecraft.world.item.Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static void shot(FakePlayer p, ItemStack stack, Key k) {
        String c = k.c();
        // lying down (the prone key: vanilla's crawling pose) and crawling along at a third of the walking speed
        p.setPose(c.startsWith("prone") ? Pose.SWIMMING : Pose.STANDING);
        float speed = c.startsWith("walk") ? SpreadModel.WALK : c.equals("sprint") ? SpreadModel.SPRINT
                : c.equals("prone walk") ? SpreadModel.WALK / 3F : 0F;
        p.setSprinting(c.equals("sprint"));
        p.setKnownMovement(new Vec3(0, 0, speed));
        p.setDeltaMovement(new Vec3(0, 0, speed));
        p.setOnGround(!c.equals("jump"));
        var gun = (ItemGunBaseNT) stack.getItem();
        var config = gun.getConfig(stack, 0);
        Receiver r = config.getReceivers(stack)[0];
        var ctx = new ItemGunBaseNT.LambdaContext(config, p, p.getInventory(), 0);
        BulletConfig bullet = (BulletConfig) r.getMagazine(stack).getType(stack, p.getInventory());
        float s = Lego.calcSpread(ctx, stack, r, bullet, false, 0, c.endsWith("aim"));
        SPREAD.put(k, s); // a burst keeps its last shot
        var mv = p.getKnownSpeed();
        PACE.put(k, SpreadModel.pace((float) Math.sqrt(mv.x * mv.x + mv.z * mv.z)));
        CRAWL.put(k, p.isVisuallyCrawling());
        p.setPose(Pose.STANDING);
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }

    private static String deg(float spread) {
        return String.format(Locale.ROOT, "%.2f", Math.toDegrees(Math.atan(spread)));
    }

    private static void check(boolean pass, String what) {
        if (!pass) ok = false;
        LINES.add((pass ? "OK" : "FAIL") + "\t" + what);
    }

    private static void report() {
        LINES.add(String.join("\t", "setting", "gun", "set", "case", "pace", "none", "armour", "none°", "armour°", "armour extra / none extra", "expected"));
        for (String setting : SETTINGS) for (String gun : GUNS) for (String set : all()) for (String c : CASES) {
            Float armour = SPREAD.get(new Key(setting, gun, set, c));
            if (armour == null) continue;
            float none = SPREAD.get(new Key(setting, gun, "", c));
            // what the case adds over standing (aimed cases over standing aimed): moving, the air, the bloom
            String base = c.endsWith("aim") ? "stand aim" : c.startsWith("prone") ? "prone" : "stand";
            float b0 = SPREAD.get(new Key(setting, gun, "", base)), b1 = SPREAD.get(new Key(setting, gun, set, base));
            float extraNone = none - b0, extraArmour = armour - b1;
            String ratio = extraNone > 1e-6F ? String.format(Locale.ROOT, "%.3f", extraArmour / extraNone) : "-";
            LINES.add(String.join("\t", setting, gun, set, c, String.format(Locale.ROOT, "%.3f", PACE.get(new Key(setting, gun, "", c))),
                    f(none), f(armour), deg(none), deg(armour), ratio, String.format(Locale.ROOT, "%.2f", expected(setting, set))));
        }
        float k = ReaimTimes.number("powerArmorSpread", 1F); // the mod's own value, the file now holds the third setting's
        check(Math.abs(k - 0.3F) < 1e-6F, "the file's powerArmorSpread is read: " + k);
        for (String gun : GUNS) for (String c : CASES) {
            float pace = PACE.get(new Key("mod", gun, "", c));
            check(c.startsWith("walk") || c.equals("sprint") ? pace > 0.9F : c.equals("prone walk") ? pace > 0.2F : pace == 0F,
                    gun + " " + c + ": pace " + pace);
            check(CRAWL.get(new Key("mod", gun, "t51", c)) == c.startsWith("prone"), gun + " " + c + ": lying " + c.startsWith("prone"));
            float none1 = SPREAD.get(new Key("1", gun, "", c)), none = SPREAD.get(new Key("mod", gun, "", c));
            check(Math.abs(none - none1) < 1e-6F, gun + " " + c + ": without armour the setting changes nothing (" + f(none) + ")");
        }
        for (String setting : SETTINGS) for (String gun : GUNS) for (String set : all()) for (String c : CASES) {
            if (set.isEmpty()) continue;
            Float armour = SPREAD.get(new Key(setting, gun, set, c));
            if (armour == null) continue;
            float want = expected(setting, set);
            String what = setting + " " + gun + " " + set + " " + c;
            float none = SPREAD.get(new Key(setting, gun, "", c));
            float b = SPREAD.get(new Key(setting, gun, "", c.endsWith("aim") ? "stand aim" : "stand"));
            if (c.startsWith("prone")) {
                // lying, crawling along adds nothing, in the armour or not; lying is spreadCrawl of standing
                float stand = SPREAD.get(new Key(setting, gun, "", "stand"));
                float crawl = ReaimTimes.number(gun + ".spreadCrawl", ReaimTimes.number("spreadCrawl", 1F));
                check(Math.abs(armour - none) < 1e-6F && Math.abs(none - stand * crawl) < 1e-6F,
                        what + ": " + f(armour) + " = none " + f(none) + " = stand " + f(stand) + " x " + crawl);
            } else if (c.startsWith("stand")) check(Math.abs(armour - none) < 1e-6F, what + ": " + f(armour) + " = none " + f(none));
            else check(none - b > 1e-3F && Math.abs(armour - (b + want * (none - b))) < 1e-5F,
                    what + ": " + f(armour) + " = stand " + f(b) + " + " + want + " of " + f(none - b));
        }
    }

    private static List<String> all() {
        List<String> out = new ArrayList<>(List.of("", "t51"));
        out.addAll(List.of(SETS));
        return out;
    }
}
