package acup.gunfeel.ammo;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.TestLog;
import acup.gunfeel.ai.AiBots;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What a rubber slug's hit does besides its damage, decided on the server (RubberRule's numbers):
 * <ul>
 * <li>its damage never takes anyone's health below FLOOR (players, mobs, AI bots), last of the damage's changes;
 * <li>the one hit is pushed back along the shot (less from afar; knockback resistance holds heavy ones, a golem not at all);
 * <li>a player is slowed for SLOW_TICKS and its controls are mixed up for CONFUSE_TICKS (RubberPayload: the client swaps
 * the actions, not the keys, RubberClient); an AI bot is slowed as well and its aim swings off for as long (drift);
 * <li>an undead (not a boss, not an AI bot) takes fright: it drops its target and runs from the shooter for FEAR_TICKS,
 * and after that cannot be frightened for FEAR_IMMUNE_TICKS.
 * </ul>
 * Nothing of it is saved: it never outlives the entity's load.
 */
public final class RubberServer {
    private RubberServer() {
    }

    public static final Identifier SLOW_ID = Identifier.fromNamespaceAndPath("ntmgunfeel", "rubber_slow");

    /** game ticks: until when slowed, when the confusion began (MIN_VALUE: none), the fear's end and its immunity's, whom it runs from */
    private static final class State {
        long slowUntil = Long.MIN_VALUE, confused = Long.MIN_VALUE, fearUntil = Long.MIN_VALUE, fearImmune = Long.MIN_VALUE;
        Vec3 from;
        final float phase;

        State(float phase) {
            this.phase = phase;
        }

        boolean over(long now) {
            return now >= slowUntil && now >= confused + RubberRule.CONFUSE_TICKS && now >= fearImmune;
        }
    }

    private static final Map<LivingEntity, State> STATE = new WeakHashMap<>();

    static void register() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(EventPriority.LOWEST, false, LivingDamageEvent.Pre.class, RubberServer::floor);
        bus.addListener(LivingChangeTargetEvent.class, RubberServer::target);
        bus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> tick());
        bus.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone.class, e -> slow(e.getEntity(), false));
    }

    // ---------------------------------------------------------------- the floor

    // after every change of the damage (armour, the head, the cap): the slug takes no one below the floor
    private static void floor(LivingDamageEvent.Pre e) {
        if (!(e.getSource().getDirectEntity() instanceof EntityBulletBaseMK4 b) || !RubberSlug.is(b.config)) return;
        LivingEntity target = e.getEntity();
        float d = RubberRule.floored(e.getNewDamage(), target.getHealth());
        if (d < e.getNewDamage()) e.setNewDamage(d);
    }

    // ---------------------------------------------------------------- a hit

    /**
     * the slug hit {@code target} (after its damage, which may be none: the floor). A player the shooter may not harm
     * (no PvP on the server, a team's friendly fire off) gets none of it
     */
    static synchronized void hit(EntityBulletBaseMK4 bullet, LivingEntity target) {
        if (!(target.level() instanceof ServerLevel level) || !target.isAlive()) return;
        if (target instanceof Player p && (p.isSpectator() || p.isCreative())) return;
        LivingEntity shooter = bullet.getThrower();
        if (target instanceof Player p && shooter instanceof Player sp && sp != p && !sp.canHarmPlayer(p)) return;
        long now = level.getGameTime();
        push(bullet, target, shooter);
        boolean bot = AiBots.get(target) != null;
        State s = STATE.computeIfAbsent(target, k -> new State(level.getRandom().nextFloat() * 6.2832F));
        if (target instanceof ServerPlayer || bot) {
            s.slowUntil = now + RubberRule.SLOW_TICKS;
            s.confused = now;
            slow(target, true);
            if (target instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new RubberPayload(RubberRule.CONFUSE_TICKS));
            log(level, target, "confuse");
        } else if (!bot && shooter != null && undead(target) && now >= s.fearImmune) {
            s.fearUntil = now + RubberRule.FEAR_TICKS;
            s.fearImmune = s.fearUntil + RubberRule.FEAR_IMMUNE_TICKS;
            s.from = shooter.position();
            if (target instanceof Mob m) m.setTarget(null);
            run(target, s);
            log(level, target, "fear");
        }
    }

    /** whether {@code e} takes fright: an undead, not a boss */
    static boolean undead(LivingEntity e) {
        var h = e.getType().builtInRegistryHolder();
        return h.is(EntityTypeTags.UNDEAD) && !h.is(Tags.EntityTypes.BOSSES);
    }

    // back along the shot, LivingEntity.knockback (its resistance counts; a player's motion is sent to it)
    private static void push(EntityBulletBaseMK4 bullet, LivingEntity target, LivingEntity shooter) {
        Vec3 v = bullet.getDeltaMovement();
        if (v.horizontalDistanceSqr() < 1e-8) return;
        double d = shooter == null ? 0 : shooter.distanceTo(target);
        target.knockback(RubberRule.knock(d), -v.x, -v.z, target.damageSources().generic(), 0F);
    }

    // ---------------------------------------------------------------- an AI bot's aim, a mob's target

    /** an AI bot's aim off by the confusion now: {yaw, pitch} degrees (0, 0 when not confused) */
    public static synchronized float[] drift(LivingEntity bot) {
        State s = STATE.get(bot);
        if (s == null || s.confused == Long.MIN_VALUE) return new float[] {0F, 0F};
        return RubberRule.drift(bot.level().getGameTime() - s.confused, s.phase);
    }

    /** whether {@code e} is frightened now */
    public static synchronized boolean afraid(LivingEntity e) {
        State s = STATE.get(e);
        return s != null && e.level().getGameTime() < s.fearUntil;
    }

    // a frightened mob takes no target
    private static void target(LivingChangeTargetEvent e) {
        if (e.getNewAboutToBeSetTarget() != null && !e.getEntity().level().isClientSide() && afraid(e.getEntity())) e.setCanceled(true);
    }

    // ---------------------------------------------------------------- each tick

    static synchronized void tick() {
        if (STATE.isEmpty()) return;
        for (Iterator<Map.Entry<LivingEntity, State>> it = STATE.entrySet().iterator(); it.hasNext();) {
            var en = it.next();
            LivingEntity e = en.getKey();
            State s = en.getValue();
            if (!e.isAlive() || e.isRemoved()) {
                slow(e, false);
                it.remove();
                continue;
            }
            long now = e.level().getGameTime();
            if (now >= s.slowUntil) slow(e, false);
            if (now < s.fearUntil) {
                if (e instanceof Mob m && m.getTarget() != null) m.setTarget(null);
                if ((s.fearUntil - now) % 10 == 0) run(e, s);
            }
            if (s.over(now)) it.remove();
        }
    }

    // a place away from the one it fears, and off it goes, a little faster than it walks
    private static void run(LivingEntity e, State s) {
        if (!(e instanceof PathfinderMob m) || s.from == null) return;
        Vec3 to = LandRandomPos.getPosAway(m, RubberRule.FEAR_RANGE, 7, s.from);
        if (to != null) m.getNavigation().moveTo(to.x, to.y, to.z, 1.3);
    }

    /** the stand: no one slowed, confused or frightened */
    public static synchronized void clear() {
        for (LivingEntity e : STATE.keySet()) slow(e, false);
        STATE.clear();
    }

    // SLOW off the speed (a modifier the game does not save)
    private static void slow(LivingEntity e, boolean on) {
        AttributeInstance speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        if (!on) {
            if (speed.getModifier(SLOW_ID) != null) speed.removeModifier(SLOW_ID);
            return;
        }
        speed.addOrUpdateTransientModifier(new AttributeModifier(SLOW_ID, -RubberRule.SLOW, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void log(ServerLevel level, LivingEntity target, String what) {
        if (TestLog.on(level)) TestLog.at(level, "rubber", "what", what, "id", target.getId(), "who", target.getPlainTextName());
    }
}
