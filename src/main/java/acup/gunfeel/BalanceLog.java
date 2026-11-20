package acup.gunfeel;

import java.time.LocalDateTime;

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
        BalanceLogBase.shot(projectile, shooter == null ? null : EntityBody.of(shooter), gunItem, Reads.READS);
    }

    /** The game's reads; the first magazine, with its original exception boundary. */
    static final class Reads implements BalanceLogBase.Reads {
        static final Reads READS = new Reads();

        @Override public int ammo(acup.gunfeel.host.Body player, Object gun) { return ammo0((Player) EntityBody.entity(player), (net.minecraft.world.item.Item) gun); }
        @Override public acup.gunfeel.host.Body cause(Object source) {
            Entity e = ((net.minecraft.world.damagesource.DamageSource) source).getEntity();
            return e == null ? null : EntityBody.of(e);
        }
        @Override public Object direct(Object source) { return ((net.minecraft.world.damagesource.DamageSource) source).getDirectEntity(); }
        @Override public Object bulletNow() { return ExplosiveDamage.bulletNow(); }
        @Override public Object raw(acup.gunfeel.host.Body b) { return EntityBody.entity(b); }
        @Override public double distance(acup.gunfeel.host.Body killer, acup.gunfeel.host.Body victim) { return EntityBody.entity(killer).distanceTo(EntityBody.entity(victim)); }
        @Override public boolean on() { return BalanceLog.on(); }
        @Override public LocalDateTime time() { return LocalDateTime.now(); }
        @Override public void write(String line) { BalanceLogBase.write(line); }
        @Override public void failed(RuntimeException ex) { LOGGER.warn("The fight log: a kill not written", ex); }
        @Override public acup.gunfeel.host.HurtNow hurt() { return EuphImmunityBase.NowPort.NOW; }
        @Override public java.util.function.BooleanSupplier balanceOn() { return BalanceOn.ON; }

        private static int ammo0(Player player, net.minecraft.world.item.Item gunItem) {
            var stack = player.getMainHandItem().getItem() == gunItem ? player.getMainHandItem() : player.getOffhandItem();
            if (!(stack.getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)) return -1;
            try {
                return gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmount(stack, player.getInventory());
            } catch (RuntimeException e) {
                return -1;
            }
        }
    }

    // ---------------------------------------------------------------- a hit

    /**
     * a hit of the scheme on {@code target} by {@code projectile} (or the source's direct entity) of the pair {@code id}
     * at {@code tier} (a blast's: the launcher's pair, "grenade.<filling>"): counted to the fight
     */
    static void hit(LivingEntity target, net.minecraft.world.damagesource.DamageSource source, Entity projectile, String id, double tier, boolean head) {
        BalanceLogBase.hit(target == null ? null : EntityBody.of(target), source, projectile, id, tier, head, Reads.READS);
    }

    // ---------------------------------------------------------------- a death

    /** a player died: a player's kill of it written down, its fights forgotten */
    static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        BalanceLogBase.death(EntityBody.of(e.getEntity()), e::getSource, Reads.READS);
    }

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false,
                net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, BalanceLog::death);
        // a player who leaves: the fights on them forgotten
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class,
                e -> logout(EntityBody.of(e.getEntity())));
    }
}
