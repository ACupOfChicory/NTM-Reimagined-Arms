package acup.gunfeel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import acup.gunfeel.mixin.ReceiverAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The balance's gun damage (stage 1b, server setting balanceGuns): a shot of an NTM gun does NTM's damage times its
 * gun and ammo's mult, and its ammo's armour threshold negation and piercing get the pair's additions
 * (BalanceGunsRule, the table {@code ntmgunfeel-balance-guns.tsv}; a file of the same name in config/ is read instead).
 *
 * <p>Put where a gun's shot is made (the bullet's and the beam's shot constructors: NTM's standard fire, the Tau's
 * charged shot, the accelerators'): the shooter's gun is the one in its hand. Everything NTM works out of the shot's
 * damage goes with it: the hit, an explosive round's blast, a beam's. Submunitions (made from a shot's damage) are not
 * multiplied again. The pair's additions go with the shot's own hits only: an explosive round's blast and the Tesla
 * Cannon's chained beams have their ammo's own (the blast's damage source is the shooter, not the shot; no pair of
 * explosive ammo has additions). Whoever fires: players, NTM's mobs (FBI agents), AI bots.
 *
 * <p>A gun's own line changes its receivers (the pause after a shot, auto fire, the magazine, the base damage) on
 * both sides; the setting turns them back.
 */
public final class BalanceGuns {
    public static final String FILE = "ntm-gunfeel-balance-guns.tsv";
    private static final String RESOURCE = "/ntmgunfeel-balance-guns.tsv";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    private BalanceGuns() {
    }

    public static boolean on() {
        return ReaimTimes.flag("balanceGuns", true);
    }

    // ---------------------------------------------------------------- the table

    private static BalanceGunsRule.Table table = null;
    private static long tableStamp = Long.MIN_VALUE, lastPoll;
    /** a gun's shots by ammo, worked out when the gun is first fired after the table was (re)read */
    private static final Map<Item, Map<BulletConfig, BalanceGunsRule.Shot>> SHOTS = new IdentityHashMap<>();
    /** where each ammo's numbers came from (the stand and the notes) */
    private static final Map<Item, Map<String, BalanceGunsRule.Resolved>> FROM = new IdentityHashMap<>();

    static synchronized BalanceGunsRule.Table table() {
        long now = System.currentTimeMillis();
        if (table == null || now - lastPoll > 2000L) {
            lastPoll = now;
            Path f = FMLPaths.CONFIGDIR.get().resolve(FILE);
            long stamp = -1L;
            try {
                if (Files.exists(f)) stamp = Files.getLastModifiedTime(f).toMillis();
            } catch (IOException e) {
                stamp = -1L;
            }
            if (table == null || stamp != tableStamp) {
                tableStamp = stamp;
                table = read(stamp >= 0 ? f : null);
                SHOTS.clear();
                FROM.clear();
            }
        }
        return table;
    }

    private static BalanceGunsRule.Table read(Path f) {
        List<String> lines;
        try {
            if (f != null) lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            else try (InputStream in = BalanceGuns.class.getResourceAsStream(RESOURCE)) {
                if (in == null) return BalanceGunsRule.Table.EMPTY;
                lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read the balance's gun table {}", f, e);
            return BalanceGunsRule.Table.EMPTY;
        }
        List<String> bad = new ArrayList<>();
        BalanceGunsRule.Table t = BalanceGunsRule.parse(lines, bad);
        for (String b : bad) LOGGER.warn("The balance's gun table{}: a line not read: {}", f != null ? " " + f : "", b);
        return t;
    }

    static String gunName(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null || !id.getNamespace().equals("hbm")) return null;
        String p = id.getPath();
        return p.startsWith("gun_") ? p.substring(4) : p;
    }

    static String ammoName(BulletConfig b) {
        // a config with no ammo item (N.I.4.N.I.'s arc, NTM's mobs' shots): ammoStack() would throw
        if (b.ammoItem == null) return "config_" + b.id;
        ItemStack s;
        try {
            s = b.ammoStack();
        } catch (RuntimeException e) {
            return "config_" + b.id;
        }
        if (s == null || s.isEmpty()) return "config_" + b.id;
        Identifier id = BuiltInRegistries.ITEM.getKey(s.getItem());
        String p = id.getPath();
        if (p.startsWith("ammo_standard_")) return p.substring("ammo_standard_".length());
        return p.startsWith("ammo_") ? p.substring(5) : p;
    }

    /** a gun's ammo (every receiver of every config) and NTM's damage of a click with each */
    private static List<BulletConfig> ammoOf(ItemGunBaseNT gun, ItemStack stack, Map<BulletConfig, Float> click) {
        List<BulletConfig> out = new ArrayList<>();
        for (int c = 0; c < gun.getConfigCount(); c++) {
            for (Receiver rec : gun.getConfig(stack, c).getReceivers(stack)) {
                for (BulletConfig b : AmmoLock.accepted(rec.getMagazine(stack))) {
                    if (click.containsKey(b)) continue;
                    out.add(b);
                    click.put(b, rec.getBaseDamage(stack) * b.damageMult * (b.projectilesMin + b.projectilesMax) / 2F * rec.getSplitProjectiles(stack));
                }
            }
        }
        return out;
    }

    /** what the pair does to a shot of {@code gun} with {@code ammo}, whether the setting is on or not */
    static synchronized BalanceGunsRule.Shot pair(Item gunItem, BulletConfig ammo) {
        BalanceGunsRule.Table t = table();
        if (!(gunItem instanceof ItemGunBaseNT gun) || ammo == null) return BalanceGunsRule.Shot.NONE;
        Map<BulletConfig, BalanceGunsRule.Shot> m = SHOTS.get(gunItem);
        if (m == null || !m.containsKey(ammo)) {
            String name = gunName(gunItem);
            m = new IdentityHashMap<>();
            if (name != null) {
                Map<BulletConfig, Float> click = new IdentityHashMap<>();
                List<BulletConfig> all = ammoOf(gun, new ItemStack(gunItem), click);
                // an ammo its magazines do not list (the Tau's charged shot has a magazine of its own)
                if (!click.containsKey(ammo)) {
                    all.add(ammo);
                    click.put(ammo, ammo.damageMult * (ammo.projectilesMin + ammo.projectilesMax) / 2F);
                }
                List<BalanceGunsRule.Ammo> named = new ArrayList<>();
                for (BulletConfig b : all) named.add(new BalanceGunsRule.Ammo(ammoName(b), click.get(b), (b.projectilesMin + b.projectilesMax) / 2F));
                Map<String, BalanceGunsRule.Resolved> r = BalanceGunsRule.resolve(t, name, named);
                for (BulletConfig b : all) m.put(b, r.get(ammoName(b)).shot());
                FROM.put(gunItem, r);
            }
            m.putIfAbsent(ammo, BalanceGunsRule.Shot.NONE);
            SHOTS.put(gunItem, m);
        }
        return m.get(ammo);
    }

    /** where a gun's ammo got its numbers (BalanceGunsRule.Resolved.from), after a shot or pair() */
    static synchronized Map<String, BalanceGunsRule.Resolved> from(Item gunItem) {
        BulletConfig first = firstAmmo(gunItem);
        if (first != null) pair(gunItem, first);
        return FROM.getOrDefault(gunItem, Map.of());
    }

    private static BulletConfig firstAmmo(Item item) {
        if (!(item instanceof ItemGunBaseNT gun)) return null;
        List<BulletConfig> all = ammoOf(gun, new ItemStack(item), new IdentityHashMap<>());
        return all.isEmpty() ? null : all.get(0);
    }

    /** the gun a shot of {@code shooter}'s comes from: the NTM gun in its main hand, else in its off hand */
    static Item gunOf(LivingEntity shooter) {
        Item main = shooter.getMainHandItem().getItem();
        if (main instanceof ItemGunBaseNT) return main;
        Item off = shooter.getOffhandItem().getItem();
        return off instanceof ItemGunBaseNT ? off : null;
    }

    // ---------------------------------------------------------------- a shot

    /** the additions of a shot that has some, by the projectile (its hits' damage source has it as the direct entity) */
    private static final Map<Entity, float[]> EXTRA = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * A shot of {@code shooter}'s with {@code ammo} has been made with NTM's damage {@code damage}: its damage after the
     * balance; the projectile keeps the pair's additions.
     */
    public static float shot(Entity projectile, LivingEntity shooter, BulletConfig ammo, float damage) {
        if (shooter == null || ammo == null || !on()) return damage;
        Item gun = gunOf(shooter);
        if (gun == null) return damage;
        BalanceGunsRule.Shot s = safePair(gun, ammo);
        if (s.none()) return damage;
        if (s.neg() != 0F || s.pen() != 0F) EXTRA.put(projectile, new float[] {s.neg(), s.pen()});
        if (watch != null) watch.accept(s);
        return damage * s.mult();
    }

    /** the stand: every shot that got the balance's numbers */
    static volatile java.util.function.Consumer<BalanceGunsRule.Shot> watch;

    /** a hit's additions to its threshold negation and piercing (the projectile's, when it is a balanced shot) */
    public static float[] extra(Entity direct) {
        return direct == null ? null : EXTRA.get(direct);
    }

    /** the tooltip's mult of an ammo in a gun (1 with the setting off) */
    public static float tooltipMult(Item gun, BulletConfig ammo) {
        if (ammo == null || !on()) return 1F;
        return safePair(gun, ammo).mult();
    }

    /** pair(), and NTM's damage for a shot it cannot work out (a shot must never fail over the table) */
    private static BalanceGunsRule.Shot safePair(Item gun, BulletConfig ammo) {
        try {
            return pair(gun, ammo);
        } catch (RuntimeException e) {
            if (FAILED.add(gun)) LOGGER.warn("The balance's gun table: no numbers for {} with config {}", gun, ammo.id, e);
            return BalanceGunsRule.Shot.NONE;
        }
    }

    private static final java.util.Set<Item> FAILED = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // ---------------------------------------------------------------- the receivers

    /** what a changed receiver had: its pause, auto fire and base damage as NTM made it */
    private record Before(int delay, boolean auto, float dmg) {
    }

    private static final Map<Receiver, Before> BEFORE = new IdentityHashMap<>();
    /** a changed magazine's rounds as NTM made it (by the magazine: receivers may share one) */
    private static final Map<MagazineSingleTypeBase, Integer> MAGS = new IdentityHashMap<>();
    private static boolean applied;
    private static BalanceGunsRule.Table appliedTable;

    /** the receivers as the setting and the table have them (called every tick on both sides; cheap when nothing changed) */
    public static synchronized void sync() {
        boolean want = on();
        BalanceGunsRule.Table t = table();
        if (want == applied && (!want || t == appliedTable)) return;
        Map<ItemGunBaseNT, BalanceGunsRule.GunLine> guns = new java.util.LinkedHashMap<>();
        if (want) {
            for (Map.Entry<String, BalanceGunsRule.GunLine> e : t.guns().entrySet()) {
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + e.getKey()));
                if (!(item instanceof ItemGunBaseNT gun)) continue;
                // a gun's stack needs the items' components: on the client's loading screen they are not bound yet
                // (new ItemStack throws), so the lines wait for a later tick
                if (!item.builtInRegistryHolder().areComponentsBound()) return;
                guns.put(gun, e.getValue());
            }
        }
        restore();
        guns.forEach(BalanceGuns::change);
        applied = want;
        appliedTable = t;
    }

    private static void change(ItemGunBaseNT gun, BalanceGunsRule.GunLine line) {
        if (line.delay() == null && line.auto() == null && line.mag() == null && line.dmg() == null) return;
        ItemStack stack = new ItemStack(gun);
        for (int c = 0; c < gun.getConfigCount(); c++) {
            GunConfig cfg = gun.getConfig(stack, c);
            for (Receiver rec : cfg.getReceivers(stack)) {
                ReceiverAccess a = (ReceiverAccess) rec;
                IMagazine<?> mag = a.gunfeel$magazine();
                BEFORE.putIfAbsent(rec, new Before(a.gunfeel$delay(), a.gunfeel$auto(), a.gunfeel$damage()));
                if (line.mag() != null && mag instanceof MagazineSingleTypeBase s) MAGS.putIfAbsent(s, s.capacity);
                if (line.delay() != null) a.gunfeel$setDelay(line.delay());
                if (line.auto() != null) a.gunfeel$setAuto(line.auto());
                if (line.dmg() != null) a.gunfeel$setDamage(line.dmg());
                if (line.mag() != null && mag instanceof MagazineSingleTypeBase s) s.capacity = line.mag();
            }
        }
    }

    private static void restore() {
        for (Map.Entry<Receiver, Before> e : BEFORE.entrySet()) {
            ReceiverAccess a = (ReceiverAccess) e.getKey();
            Before b = e.getValue();
            a.gunfeel$setDelay(b.delay());
            a.gunfeel$setAuto(b.auto());
            a.gunfeel$setDamage(b.dmg());
        }
        for (Map.Entry<MagazineSingleTypeBase, Integer> e : MAGS.entrySet()) e.getKey().capacity = e.getValue();
        BEFORE.clear();
        MAGS.clear();
    }
}
