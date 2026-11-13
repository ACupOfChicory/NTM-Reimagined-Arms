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
// holding powerArmorSpread = 1 (the key of before the table: now only for a power armour chestplate with no line, the
// table stays), the mod's own values (the table powerArmorSpread.<set>), and a file with powerArmorSpread = 0.3 and
// powerArmorSpread.fau = 0.9 (a set's own line over the table). The recoil's share (powerArmorRecoil, the same keys)
// by the same settings. A tsv goes to the file and the server stops (exit 1 if a check failed): standing is the same
// in any armour; what moving, the air and the bloom add is the set's share of it.
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
            StandSprint.start(started.getServer());
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
                    StandSprint.stop(level.getServer());
                    report();
                    LINES.add(ok ? "ALL OK" : "SOME FAILED");
                    BulletCheck.finish(file, LINES, ok ? 0 : 1);
                } catch (Throwable t) {
                    at[0] = -1;
                    StandSprint.stop(level.getServer());
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
        Float table = PowerArmor.TIERS.get(s);
        if (table == null) return 1F; // Security, the steam and diesel suits
        // the old one number is for a chestplate with no line only: every set here has one
        return setting.equals("file") && s.equals("fau") ? 0.9F : table;
    }

    private static void plan(FakePlayer p) {
        // the choke first: it runs under the mod's own values, and the check at the end reads the last setting's file
        choke(p);
        for (String setting : SETTINGS) {
            STEPS.add(() -> settings(setting));
            wait(30); // the server's file is looked at once a second
            STEPS.add(() -> LINES.add("#\tsetting " + setting + ": powerArmorSpread = " + ReaimTimes.number("powerArmorSpread", 1F)
                    + ", in the file " + ReaimTimes.inFile("powerArmorSpread")));
            STEPS.add(() -> recoil(p, setting));
            for (String gun : GUNS) for (String set : new String[] {"", "t51"}) cases(p, setting, gun, set, CASES, SETTLE);
            for (String set : SETS) cases(p, setting, GUNS[0], set, SHORT, SETTLE_SHORT);
        }
    }

    /** Fable №3: the shotguns given the choke (spreadAimKnee, spreadAimOver in the mod's file; the SPAS-12 spreadHipKnee, spreadHipOver) */
    static final String[] CHOKED = {"gun_maresleg", "gun_maresleg_broken", "gun_spas12", "gun_autoshotgun", "gun_autoshotgun_sexy", "gun_autoshotgun_heretic",
            "gun_double_barrel", "gun_double_barrel_sacred_dragon"};
    /** the guns and ammo of the choke's check: the nine and some others (whose spread is to stay X942's) */
    private static final String[][] CHOKE_CASES = {{"gun_spas12", "g12"}, {"gun_spas12", "g12_flechette"}, {"gun_spas12", "g12_magnum"}, {"gun_spas12", "g12_slug"},
            {"gun_maresleg", "g12"}, {"gun_maresleg_broken", "g12"}, {"gun_maresleg_akimbo", "g12"}, {"gun_autoshotgun", "g12"}, {"gun_autoshotgun", "g12_flechette"},
            {"gun_autoshotgun", "g12_magnum"}, {"gun_autoshotgun_sexy", "g12"}, {"gun_autoshotgun_heretic", "g10"}, {"gun_double_barrel", "g10"}, {"gun_double_barrel_sacred_dragon", "g10"},
            {"gun_autoshotgun_shredder", "g12"}, {"gun_g3", "r556_fmj"}, {"gun_minigun", "r762_fmj"}, {"gun_uzi", "p9_fmj"}, {"gun_amat", "bmg50_fmj"}};
    private static final List<String> CHOKE_LINES = new ArrayList<>();

    /**
     * The choke (the mod's own values, no armour): standing from the hip and standing aimed, a shot each after the
     * bloom is gone. From the hip: NTM's spread times spreadStand (X942); aimed: the knee and the share past it of
     * NTM's aimed spread (a gun with no knee: X942's); a gun with no aim key (an akimbo, the SPAS-12) aimed is from
     * the hip; the SPAS-12's from the hip by spreadHipKnee (it has no aim). The Maresleg and the auto-shotgun with shot
     * aimed 0.021 ± 0.001, the SPAS-12 from the hip the same.
     */
    private static void choke(FakePlayer p) {
        STEPS.add(() -> settings("mod"));
        wait(30);
        STEPS.add(() -> p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY));
        for (String[] gc : CHOKE_CASES) {
            for (String c : new String[] {"stand", "stand aim"}) {
                STEPS.add(() -> {
                    String gun = "hbm:" + gc[0];
                    ItemStack stack = new ItemStack(item(gun));
                    var g = (ItemGunBaseNT) stack.getItem();
                    BulletConfig ammo = null;
                    for (Receiver r : g.getConfig(stack, 0).getReceivers(stack))
                        for (BulletConfig b : AmmoLock.accepted(r.getMagazine(stack))) if (BalanceGuns.ammoName(b).equals(gc[1])) ammo = b;
                    if (ammo == null) {
                        check(false, "choke: " + gun + " no ammo " + gc[1]);
                        return;
                    }
                    Receiver r = g.getConfig(stack, 0).getReceivers(stack)[0];
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    com.hbm.items.weapon.sedna.mags.IMagazine mag = r.getMagazine(stack);
                    mag.setType(stack, ammo);
                    mag.setAmount(stack, 1);
                    boolean aim = c.endsWith("aim"), canAim = BalanceAim.key(stack) != BalanceAim.Key.NONE, effAim = aim && canAim;
                    // a gun with no aim key "aimed" is fired from the hip, as in the game
                    Key k = new Key(aim && !canAim ? "choke-noaim" : "choke", gun + " " + gc[1], "", effAim || !aim ? c : "stand");
                    shot(p, stack, k);
                    float base = r.getInnateSpread(stack) + ammo.spread * r.getAmmoSpread(stack), hip = r.getHipfireSpread(stack);
                    float stand = ReaimTimes.number(gun + ".spreadStand", ReaimTimes.number("spreadStand", 1F));
                    float aimMult = ReaimTimes.number(gun + ".spreadAim", ReaimTimes.number("spreadAim", 1F));
                    float knee = ReaimTimes.number(gun + ".spreadAimKnee", ReaimTimes.number("spreadAimKnee", 0F));
                    float over = ReaimTimes.number(gun + ".spreadAimOver", ReaimTimes.number("spreadAimOver", 1F));
                    float hipKnee = ReaimTimes.number(gun + ".spreadHipKnee", ReaimTimes.number("spreadHipKnee", 0F));
                    float hipOver = ReaimTimes.number(gun + ".spreadHipOver", ReaimTimes.number("spreadHipOver", 1F));
                    float got = SPREAD.get(k);
                    float x942 = effAim ? base * stand * aimMult : (base + hip) * stand;
                    float want = effAim ? SpreadModel.choke(base, true, knee, over) * stand * aimMult : SpreadModel.choke(base + hip, true, hipKnee, hipOver) * stand;
                    String what = c + (aim && !canAim ? " (no aim key: from the hip)" : "");
                    CHOKE_LINES.add(String.join("\t", "choke", gun, gc[1], what, f(base), f(hip), f(effAim ? knee : hipKnee), f(effAim ? over : hipOver), f(x942), f(want), f(got)));
                    boolean listed = java.util.Arrays.asList(CHOKED).contains(gc[0]);
                    check(Math.abs(got - want) < 1e-5F, "choke " + gun + " " + gc[1] + " " + what + ": " + f(got) + " (want " + f(want) + ", X942 " + f(x942) + ")");
                    boolean spas = gc[0].equals("gun_spas12");
                    if ((!effAim || !listed) && !spas) check(Math.abs(got - x942) < 1e-5F, "choke " + gun + " " + gc[1] + " " + what + ": as X942 " + f(x942));
                    // the SPAS-12 (no aim): its choke from the hip, shot 0.021 ± 0.001 (owner 2026-11-13)
                    if (spas && gc[1].equals("g12")) check(Math.abs(got - 0.021F) <= 0.001F, "choke " + gun + " g12 from the hip 0.021 ± 0.001: " + f(got));
                    if (effAim && gc[1].equals("g12") && (gc[0].equals("gun_maresleg") || gc[0].equals("gun_autoshotgun")))
                        check(Math.abs(got - 0.021F) <= 0.001F, "choke " + gun + " g12 aimed 0.021 ± 0.001: " + f(got));
                });
                wait(SETTLE_SHORT);
            }
        }
    }

    /** the recoil's share (PowerArmor.recoil) in each set under the setting: the same tiers as the spread */
    private static void recoil(FakePlayer p, String setting) {
        StringBuilder got = new StringBuilder();
        boolean pass = true;
        for (String set : all()) {
            p.setItemSlot(EquipmentSlot.CHEST, set.isEmpty() ? ItemStack.EMPTY : new ItemStack(item(chest(set))));
            float r = PowerArmor.recoil(p), want = set.isEmpty() ? 1F : expected(setting, set);
            pass &= Math.abs(r - want) < 1e-6F;
            got.append(set.isEmpty() ? "none" : set).append(' ').append(String.format(Locale.ROOT, "%.2f", r)).append(Math.abs(r - want) < 1e-6F ? "" : " (want " + want + ")").append("; ");
        }
        p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        check(pass, "setting " + setting + ": recoil share by set: " + got);
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
                case "1" -> "powerArmorSpread = 1\npowerArmorRecoil = 1\n";
                case "file" -> "powerArmorSpread = 0.3\npowerArmorSpread.fau = 0.9\npowerArmorRecoil = 0.3\npowerArmorRecoil.fau = 0.9\n";
                default -> "# the mod's own values\n";
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        // read now: the sprint's ticks go faster than the second ReaimTimes waits between two looks at the file
        ReaimTimes.reloadNow();
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
        LINES.add(String.join("\t", "choke", "gun", "ammo", "case", "NTM aimed", "NTM hip", "knee", "over", "X942", "want", "got"));
        LINES.addAll(CHOKE_LINES);
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
