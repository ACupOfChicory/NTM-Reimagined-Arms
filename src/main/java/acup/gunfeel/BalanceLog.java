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

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The fight log (balance.final.log; Fable 2026-11-12, 10.1): a line for every kill of a player by a player in a file of
 * its own ({@code logs/ntmgunfeel-fights.log}, at most balance.final.log.size MB, then the old one kept as {@code .1}):
 * the killer's gun and ammo, both players' sets, the distance, the time from the first hit to the death, the shots, the
 * hit clicks and those on the head, the reloads in between. It is what the usual fight on a server is measured by (the
 * corridor of the equals' median, 4–7 s), not the stand. Off: no line, and no work in a shot or a hit.
 */
public final class BalanceLog {
    private BalanceLog() {
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");
    static final String FILE = "logs/ntmgunfeel-fights.log";
    static final String HEADER = "# time\tkiller\tvictim\tgun\tammo\tpair tier\tkiller set\tkiller tier\tvictim set\tvictim tier\tdistance\tttk s\tshots\thits\thead hits\treloads\n";

    public static boolean on() {
        return ReaimTimes.flag("balance.final.log", true) && BalanceFinal.on();
    }

    // ---------------------------------------------------------------- a shot

    /** a shooter's shots so far and its reloads (a magazine fuller than after its last shot) */
    private static final class Gunner {
        long shots, reloads, tick = Long.MIN_VALUE;
        int ammo = -1;
    }

    private static final Map<Entity, Gunner> GUNNERS = Collections.synchronizedMap(new WeakHashMap<>());
    /** the shot (its number) a projectile is of */
    private static final Map<Entity, Long> SHOT = Collections.synchronizedMap(new WeakHashMap<>());

    /** a player's shot of the scheme (BalanceFinal.fired, with the log on): its number on the projectile, a reload before it counted */
    static void shot(Entity projectile, LivingEntity shooter, net.minecraft.world.item.Item gunItem) {
        if (!(shooter instanceof Player player)) return;
        Gunner g = GUNNERS.computeIfAbsent(shooter, s -> new Gunner());
        int ammo = ammo(player, gunItem);
        // the projectiles of one click (a shotgun's pellets) are made in the same tick and leave the magazine as it was: one shot
        long now = shooter.level().getGameTime();
        if (now != g.tick || ammo != g.ammo) {
            if (g.ammo >= 0 && ammo > g.ammo) g.reloads++;
            g.shots++;
            g.ammo = ammo;
            g.tick = now;
        }
        SHOT.put(projectile, g.shots);
    }

    private static int ammo(Player player, net.minecraft.world.item.Item gunItem) {
        var stack = player.getMainHandItem().getItem() == gunItem ? player.getMainHandItem() : player.getOffhandItem();
        if (!(stack.getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)) return -1;
        try {
            return gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmount(stack, player.getInventory());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** the stand: what the log holds now (shooters, projectiles, fights), to see that a shot does no work with it off */
    static int held() {
        return GUNNERS.size() + SHOT.size() + FIGHTS.size();
    }

    // ---------------------------------------------------------------- a hit

    /** a fight of one shooter on one target: since its first hit */
    private static final class Fight {
        long firstTick, lastTick, shot0, reloads0;
        String pair = "-";
        double tier = Double.NaN;
        final Set<Long> clicks = new HashSet<>(), heads = new HashSet<>();
    }

    /** by the target's id: its shooters' fights */
    private static final Map<UUID, Map<UUID, Fight>> FIGHTS = Collections.synchronizedMap(new HashMap<>());
    /** a fight with no hit for this long starts again (ticks) */
    static final long FORGET = 20 * 60;

    /**
     * a hit of the scheme on {@code target} by {@code projectile} (or the source's direct entity) of the pair {@code id}
     * at {@code tier} (a blast's: the launcher's pair, "grenade.<filling>"): counted to the fight
     */
    static void hit(LivingEntity target, net.minecraft.world.damagesource.DamageSource source, Entity projectile, String id, double tier, boolean head) {
        if (!(target instanceof Player) || !(source.getEntity() instanceof Player shooter)) return;
        Long shot = projectile != null ? SHOT.get(projectile) : null;
        if (shot == null) shot = SHOT.get(source.getDirectEntity());
        if (shot == null) shot = ExplosiveDamage.bulletNow() == null ? null : SHOT.get(ExplosiveDamage.bulletNow());
        long now = target.level().getGameTime();
        synchronized (FIGHTS) {
            Map<UUID, Fight> of = FIGHTS.computeIfAbsent(target.getUUID(), u -> new HashMap<>());
            Fight f = of.get(shooter.getUUID());
            if (f == null || now - f.lastTick > FORGET) {
                f = new Fight();
                f.firstTick = now;
                Gunner g = GUNNERS.get(shooter);
                f.shot0 = shot != null ? shot : g == null ? 0 : g.shots;
                f.reloads0 = g == null ? 0 : g.reloads;
                of.put(shooter.getUUID(), f);
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

    /** a player died: a player's kill of it written down, its fights forgotten */
    static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        if (!(e.getEntity() instanceof Player victim) || victim.level().isClientSide()) return;
        Map<UUID, Fight> of = FIGHTS.remove(victim.getUUID());
        if (!on() || !(e.getSource().getEntity() instanceof Player killer) || killer == victim) return;
        try {
            Fight f = of == null ? null : of.get(killer.getUUID());
            write(line(killer, victim, f, victim.level().getGameTime()));
        } catch (RuntimeException ex) {
            LOGGER.warn("The fight log: a kill not written", ex);
        }
    }

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false,
                net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, BalanceLog::death);
    }

    private static String set(LivingEntity who) {
        var a = BalanceFinal.armorOf(who, BalanceFinal.state());
        return a == null ? "-" : a.kind();
    }

    private static double tier(LivingEntity who) {
        var a = BalanceFinal.armorOf(who, BalanceFinal.state());
        return a == null ? Double.NaN : a.tier();
    }

    static String line(Player killer, Player victim, Fight f, long now) {
        Gunner g = GUNNERS.get(killer);
        String pair = f == null ? "-" : f.pair;
        int dot = pair.indexOf('.');
        String gun = dot > 0 ? pair.substring(0, dot) : pair, ammo = dot > 0 ? pair.substring(dot + 1) : "-";
        String ttk = f == null ? "-" : num((now - f.firstTick) / 20.0);
        String shots = f == null || g == null ? "-" : String.valueOf(Math.max(0, g.shots - f.shot0 + 1));
        String reloads = f == null || g == null ? "-" : String.valueOf(Math.max(0, g.reloads - f.reloads0));
        return String.join("\t", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 19), killer.getGameProfile().name(),
                victim.getGameProfile().name(), gun, ammo, f == null ? "-" : num(f.tier), set(killer), num(tier(killer)), set(victim), num(tier(victim)),
                num(killer.distanceTo(victim)), ttk, shots, f == null ? "-" : String.valueOf(f.clicks.size()), f == null ? "-" : String.valueOf(f.heads.size()),
                reloads) + "\n";
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
