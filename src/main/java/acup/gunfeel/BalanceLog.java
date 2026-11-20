package acup.gunfeel;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The fight log (balance.final.log; Fable 2026-11-12, 10.1): a line for every kill of a player by a player in a file of
 * its own ({@code logs/ntmgunfeel-fights.log}, at most balance.final.log.size MB, then the old one kept as {@code .1}):
 * the killer's gun and ammo, both players' sets, the distance, the time from the first hit to the death, the shots, the
 * hit clicks and those on the head, the reloads in between. It is what the usual fight on a server is measured by (the
 * corridor of the equals' median, 4–7 s), not the stand. Off: no line, and no work in a shot or a hit. The count and
 * the line are BalanceLogBase's (core); here the game's and NTM's reads.
 */
public final class BalanceLog extends BalanceLogBase {
    private BalanceLog() {
    }

    public static boolean on() {
        return ReaimTimes.flag("balance.final.log", true) && BalanceFinal.on();
    }

    // ---------------------------------------------------------------- a shot

    /** a player's shot of the scheme (BalanceFinal.fired, with the log on): its number on the projectile, a reload before it counted */
    static void shot(Entity projectile, LivingEntity shooter, net.minecraft.world.item.Item gunItem) {
        if (!(shooter instanceof Player player)) return;
        int ammo = ammo(player, gunItem);
        shot(projectile, shooter, shooter.level().getGameTime(), ammo);
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

    // ---------------------------------------------------------------- a hit

    /**
     * a hit of the scheme on {@code target} by {@code projectile} (or the source's direct entity) of the pair {@code id}
     * at {@code tier} (a blast's: the launcher's pair, "grenade.<filling>"): counted to the fight
     */
    static void hit(LivingEntity target, net.minecraft.world.damagesource.DamageSource source, Entity projectile, String id, double tier, boolean head) {
        if (!(target instanceof Player) || !(source.getEntity() instanceof Player shooter)) return;
        Long shot = projectile != null ? shotOf(projectile) : null;
        if (shot == null) shot = shotOf(source.getDirectEntity());
        if (shot == null) shot = ExplosiveDamage.bulletNow() == null ? null : shotOf(ExplosiveDamage.bulletNow());
        count(target.getUUID(), shooter.getUUID(), shooter, shot, target.level().getGameTime(), id, tier, head);
    }

    // ---------------------------------------------------------------- a death

    /** a player died: a player's kill of it written down, its fights forgotten */
    static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        if (!(e.getEntity() instanceof Player victim) || victim.level().isClientSide()) return;
        Map<UUID, Fight> of = forget(victim.getUUID());
        if (!on() || !(e.getSource().getEntity() instanceof Player killer) || killer == victim) return;
        try {
            long now = victim.level().getGameTime();
            Fight f = fight(of, killer.getUUID(), now);
            // the sets read the server's keys: in the order the line read them
            String killerSet = set(killer);
            double killerTier = tier(killer);
            String victimSet = set(victim);
            double victimTier = tier(victim);
            write(line(LocalDateTime.now(), killer.getGameProfile().name(), victim.getGameProfile().name(), killer, f, now, killerSet, killerTier, victimSet,
                    victimTier, killer.distanceTo(victim)));
        } catch (RuntimeException ex) {
            LOGGER.warn("The fight log: a kill not written", ex);
        }
    }

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false,
                net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, BalanceLog::death);
        // a player who leaves: the fights on them forgotten
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class,
                e -> forget(e.getEntity().getUUID()));
    }

    private static String set(LivingEntity who) {
        var a = BalanceFinal.armorOf(who, BalanceFinal.state());
        return a == null ? "-" : a.kind();
    }

    private static double tier(LivingEntity who) {
        var a = BalanceFinal.armorOf(who, BalanceFinal.state());
        return a == null ? Double.NaN : a.tier();
    }
}
