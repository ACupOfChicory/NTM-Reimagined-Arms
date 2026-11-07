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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// The living spread in power armour, on a dedicated server (./gradlew runCheckSpread -PnoAssets): a player without
// armour and in a T-51 chestplate asks NTM for a shot's spread (Lego.calcSpread, through MixinLegoSpread and Spread)
// standing, walking, sprinting, in the air and in a held-down burst, once with powerArmorSpread = 1 (the armour does
// nothing, the spread as before it) and once with the mod's own value. A tsv goes to the file and the server stops
// (exit 1 if a check failed): with 1 the armour changes nothing; with the mod's value standing is the same and what
// moving, the air and the bloom add is powerArmorSpread times as much.
public final class SpreadCheck {
    public static final String PROPERTY = "ntmgunfeel.checkSpread";
    private static final String[] GUNS = {"hbm:gun_g3", "hbm:gun_am180"};
    private static final String[] CASES = {"stand", "walk", "sprint", "jump", "stand aim", "walk aim", "burst aim"};
    // the bloom halves every 5 ticks: 100 ticks after a shot and 200 after a burst leave none of it worth a digit
    private static final int BURST = 30, SETTLE = 100, SETTLE_BURST = 200;

    private SpreadCheck() {
    }

    private record Key(String setting, String gun, boolean armour, String c) {
    }

    // one tick's work; a shot is one call of NTM's spread, at most one a tick (the bloom counts a tick's first call)
    private static final List<Runnable> STEPS = new ArrayList<>();
    private static final Map<Key, Float> SPREAD = new HashMap<>();
    private static final Map<Key, Float> PACE = new HashMap<>();
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

    private static void plan(FakePlayer p) {
        for (String setting : new String[] {"1", "mod"}) {
            STEPS.add(() -> settings(setting));
            wait(30); // the server's file is looked at once a second
            STEPS.add(() -> LINES.add("#\tpowerArmorSpread = " + ReaimTimes.number("powerArmorSpread", 1F)));
            for (String gun : GUNS) for (boolean armour : new boolean[] {false, true}) {
                STEPS.add(() -> p.setItemSlot(EquipmentSlot.CHEST, armour ? new ItemStack(item("hbm:t51_plate")) : ItemStack.EMPTY));
                for (String c : CASES) {
                    Key k = new Key(setting, gun, armour, c);
                    ItemStack stack = new ItemStack(item(gun));
                    int shots = c.startsWith("burst") ? BURST : 1;
                    int every = Math.max(1, ((ItemGunBaseNT) stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0].getDelayAfterFire(stack));
                    for (int i = 0; i < shots; i++) {
                        STEPS.add(() -> shot(p, stack, k));
                        if (i < shots - 1) wait(every - 1);
                    }
                    wait(shots > 1 ? SETTLE_BURST : SETTLE); // the bloom gone before the next case
                }
            }
        }
    }

    private static void wait(int ticks) {
        for (int i = 0; i < ticks; i++) STEPS.add(() -> {
        });
    }

    private static void settings(String setting) {
        try {
            Files.writeString(FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE), setting.equals("1") ? "powerArmorSpread = 1\n" : "# the mod's own values\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static net.minecraft.world.item.Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static void shot(FakePlayer p, ItemStack stack, Key k) {
        String c = k.c();
        float speed = c.startsWith("walk") ? SpreadModel.WALK : c.equals("sprint") ? SpreadModel.SPRINT : 0F;
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
        LINES.add(String.join("\t", "powerArmorSpread", "gun", "case", "pace", "none", "t51", "none°", "t51°", "t51 extra / none extra"));
        for (String setting : new String[] {"1", "mod"}) for (String gun : GUNS) for (String c : CASES) {
            float none = SPREAD.get(new Key(setting, gun, false, c)), t51 = SPREAD.get(new Key(setting, gun, true, c));
            // what the case adds over standing (aimed cases over standing aimed): moving, the air, the bloom
            String base = c.endsWith("aim") ? "stand aim" : "stand";
            float b0 = SPREAD.get(new Key(setting, gun, false, base)), b1 = SPREAD.get(new Key(setting, gun, true, base));
            float extraNone = none - b0, extraT51 = t51 - b1;
            String ratio = extraNone > 1e-6F ? String.format(Locale.ROOT, "%.3f", extraT51 / extraNone) : "-";
            LINES.add(String.join("\t", setting, gun, c, String.format(Locale.ROOT, "%.3f", PACE.get(new Key(setting, gun, false, c))),
                    f(none), f(t51), deg(none), deg(t51), ratio));
        }
        float k = ReaimTimes.number("powerArmorSpread", 1F); // the mod's own value, the file now holds none
        check(k > 0F && k < 1F, "the mod's powerArmorSpread is " + k);
        for (String gun : GUNS) for (String c : CASES) {
            float pace = PACE.get(new Key("mod", gun, false, c));
            check(c.startsWith("walk") || c.equals("sprint") ? pace > 0.9F : pace == 0F, gun + " " + c + ": pace " + pace);
            float none1 = SPREAD.get(new Key("1", gun, false, c)), t511 = SPREAD.get(new Key("1", gun, true, c));
            check(Math.abs(none1 - t511) < 1e-6F, gun + " " + c + ", powerArmorSpread = 1: T-51 " + f(t511) + " = none " + f(none1));
            float none = SPREAD.get(new Key("mod", gun, false, c)), t51 = SPREAD.get(new Key("mod", gun, true, c));
            check(Math.abs(none - none1) < 1e-6F, gun + " " + c + ": without armour the mod's value changes nothing (" + f(none) + ")");
            float b = SPREAD.get(new Key("mod", gun, false, c.endsWith("aim") ? "stand aim" : "stand"));
            float want = b + k * (none - b);
            if (c.startsWith("stand")) check(Math.abs(t51 - none) < 1e-6F, gun + " " + c + ": T-51 " + f(t51) + " = none " + f(none));
            else check(none - b > 1e-3F && Math.abs(t51 - want) < 1e-5F,
                    gun + " " + c + ": T-51 " + f(t51) + " = stand " + f(b) + " + " + k + " of " + f(none - b));
        }
    }
}
