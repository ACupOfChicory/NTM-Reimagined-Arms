package acup.gunfeel.shock;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.TestLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The shock of the 12 gauge electroshock shell, decided on the server. A hit on a living one (a player, an AI bot, any
 * mob) shocks it: at full for shockSeconds, then it wears off smoothly over FADE_TICKS more (envelope). Meanwhile
 * whatever gun it fires spreads its shots wide (shockSpread, added to the shot's spread where NTM works it out, so for
 * players and AI bots alike) and it walks slower (SLOW off its speed: a modifier of its movement speed, so a mob's and an
 * AI bot's steps on their path slow as well, and a player's view narrows by FOV at full, not as far as the game would narrow it for such a slowness). A
 * player is told (ShockPayloads.Shock): its client glitches and shakes the HUD, shakes the view a little and turns it
 * about on its own. After a shock ends the one shocked takes none for shockImmunity seconds: a stream of shells does
 * not keep anyone shocked for good. The discharge is heard where it hit, and the shooter (a player) is told it hit with
 * shock (a mark at the crosshair).
 */
public final class ShockServer {
    private ShockServer() {
    }

    /** after its shockSeconds at full a shock wears off over these game ticks (the client's glitch, shake, drift too) */
    public static final int FADE_TICKS = 60;
    /** the share of the speed a shock takes at full: a stun, far past vanilla's Slowness II (0.3) */
    public static final float SLOW = 0.8F;
    /**
     * the share of a player's view a shock takes at full (GunWeight.fov). The game would narrow it by the speed, to
     * x0.6 at SLOW 0.8, a tunnel; the shock's own narrowing is this instead, fading with the shock
     */
    public static final float FOV = 0.2F;
    public static final Identifier SLOW_ID = Identifier.fromNamespaceAndPath("ntmgunfeel", "shock_slow");

    /**
     * The shock's strength, 0 to 1, {@code t} game ticks into a shock of {@code full} ticks at full: 1 for those, then
     * down to 0 over FADE_TICKS along a half cosine (no step at either end). Server and client alike.
     */
    public static float envelope(float t, int full) {
        if (t < 0F) return 0F;
        if (t < full) return 1F;
        float u = (t - full) / FADE_TICKS;
        return u >= 1F ? 0F : 0.5F * (1F + (float) Math.cos(Math.PI * u));
    }

    // ---- the server's settings (ntm-gunfeel-server.properties; a client reads the server's while connected)

    public static boolean on() {
        return ReaimTimes.flag("shock", true);
    }

    public static float seconds() {
        return Math.clamp(ReaimTimes.number("shockSeconds", 2F), 0.25F, 10F);
    }

    public static float immunitySeconds() {
        return Math.clamp(ReaimTimes.number("shockImmunity", 3F), 0F, 60F);
    }

    /** NTM's spread units (a shot's direction off by about this many radians each way): buckshot's is 0.035 */
    public static float spread() {
        return Math.clamp(ReaimTimes.number("shockSpread", 0.12F), 0F, 1F);
    }

    /** the most the HUD glitches and shakes, 0 to 1 (each player may take less, down to shockGlitchMin) */
    public static float glitch() {
        return Math.clamp(ReaimTimes.number("shockGlitch", 1F), 0F, 1F);
    }

    /** the least share of shockGlitch a player may turn their own glitch down to */
    public static float glitchMin() {
        return Math.clamp(ReaimTimes.number("shockGlitchMin", 0.5F), 0F, 1F);
    }

    /** how fast the view turns about on its own at most, degrees a second (0: it does not) */
    public static float cameraDeg() {
        return Math.clamp(ReaimTimes.number("shockCamera", 45F), 0F, 360F);
    }

    /** the view's small shake at most, degrees */
    public static float shakeDeg() {
        return Math.clamp(ReaimTimes.number("shockShake", 0.6F), 0F, 5F);
    }

    // ---- who is shocked

    /** game ticks: when the shock started, how long at full, until when it lasts (faded out), until when it does not take again */
    private record State(long start, int full, long until, long immuneUntil) {
    }

    private static final Map<LivingEntity, State> STATE = new WeakHashMap<>();
    // the ones whose speed the shock holds down now
    private static final Set<LivingEntity> SLOWED = Collections.newSetFromMap(new WeakHashMap<>());

    /** whether a shock holds {@code e} now (on the server) */
    public static synchronized boolean shocked(LivingEntity e) {
        if (e == null || e.level().isClientSide()) return false;
        State s = STATE.get(e);
        return s != null && e.level().getGameTime() < s.until;
    }

    /** the shock's strength on {@code e} now (on the server), 0 to 1: envelope */
    public static synchronized float strength(LivingEntity e) {
        if (e == null || e.level().isClientSide()) return 0F;
        State s = STATE.get(e);
        return s == null ? 0F : envelope(e.level().getGameTime() - s.start, s.full);
    }

    /** the spread a shot of {@code shooter}'s gets on top of its own (0 when not shocked; less as the shock wears off) */
    public static float spread(LivingEntity shooter, BulletConfig config) {
        if (!on()) return 0F;
        float k = strength(shooter);
        if (k <= 0F) return 0F;
        float s = spread() * k;
        // a beam's spread is in degrees (EntityBulletBeamBase), a bullet's in NTM's heading units
        return config != null && config.pType == BulletConfig.ProjectileType.BEAM ? (float) Math.toDegrees(s) : s;
    }

    /**
     * The shell hit something (its config's entity hit, after NTM's damage); {@code before}: the target's health and
     * absorption before that damage. A hit that took nothing off shocks no one; with {@code shock} off the shell only
     * does its damage.
     */
    static void hit(EntityBulletBaseMK4 bullet, HitResult hit, float before) {
        if (!on() || !(hit instanceof EntityHitResult eh) || !(eh.getEntity() instanceof LivingEntity target)) return;
        if (!(target.level() instanceof ServerLevel level)) return;
        if (!(target.getHealth() + target.getAbsorptionAmount() < before)) return;
        zap(level, target);
        LivingEntity shooter = bullet.getThrower();
        boolean took = shock(target);
        if (shooter instanceof ServerPlayer sp && shooter != target) PacketDistributor.sendToPlayer(sp, new ShockPayloads.Mark(took));
    }

    /**
     * Shocks {@code target} if it may be: on, alive, not a spectator or a creative player, not shocked already and not
     * in its immunity. Whether it was.
     */
    public static synchronized boolean shock(LivingEntity target) {
        if (!on() || !target.isAlive() || !(target.level() instanceof ServerLevel level)) return false;
        if (target instanceof Player p && (p.isSpectator() || p.isCreative())) return false;
        long now = level.getGameTime();
        State s = STATE.get(target);
        // during a shock and in the immunity after it a hit adds nothing
        if (s != null && now < s.immuneUntil) {
            log(level, target, "immune", s.until - now, s.immuneUntil - now);
            return false;
        }
        int ticks = Math.round(seconds() * 20F);
        long until = now + ticks + FADE_TICKS;
        s = new State(now, ticks, until, until + Math.round(immunitySeconds() * 20F));
        STATE.put(target, s);
        SLOWED.add(target);
        slow(target, 1F);
        if (target instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new ShockPayloads.Shock(ticks));
        log(level, target, "shock", ticks, s.immuneUntil - now);
        return true;
    }

    /** the stand: {until, immuneUntil} in game ticks, or null */
    public static synchronized long[] state(LivingEntity e) {
        State s = STATE.get(e);
        return s == null ? null : new long[] {s.until, s.immuneUntil};
    }

    /** the stand: no shock nor immunity left on anyone */
    public static synchronized void clear() {
        for (LivingEntity e : SLOWED) slow(e, 0F);
        SLOWED.clear();
        STATE.clear();
    }

    /** each server tick: the slowing follows the shock's strength, and goes when it ends (or the shock is turned off) */
    public static synchronized void tick() {
        if (SLOWED.isEmpty()) return;
        boolean on = on();
        for (Iterator<LivingEntity> it = SLOWED.iterator(); it.hasNext();) {
            LivingEntity e = it.next();
            float k = e.isAlive() && !e.isRemoved() ? strength(e) : 0F;
            // shock = false meanwhile: no slowing, but it comes back if turned on again within the shock
            slow(e, on ? k : 0F);
            if (k <= 0F) it.remove();
        }
    }

    /** a player made anew (a respawn, back from the End): the old one's slowing may have been copied, and is not its own */
    public static void cloned(net.minecraft.world.entity.player.Player p) {
        slow(p, 0F);
    }

    // SLOW times k off the speed (a modifier the game does not save: a shock never outlives the entity's load)
    private static void slow(LivingEntity e, float k) {
        AttributeInstance speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        AttributeModifier now = speed.getModifier(SLOW_ID);
        if (k <= 0F) {
            if (now != null) speed.removeModifier(SLOW_ID);
            return;
        }
        double v = -SLOW * k;
        if (now == null || Math.abs(now.amount() - v) > 1e-4) speed.addOrUpdateTransientModifier(new AttributeModifier(SLOW_ID, v, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void log(ServerLevel level, LivingEntity target, String what, long left, long immune) {
        if (TestLog.on(level)) TestLog.at(level, "shock", "what", what, "id", target.getId(), "who", target.getPlainTextName(), "left", left, "immune", immune);
    }

    // the discharge where it hit: NTM's Tesla zap, heard by everyone near
    private static void zap(ServerLevel level, LivingEntity target) {
        SoundEvent s = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.fromNamespaceAndPath("hbm", "weapon.tesla"));
        if (s == null) return;
        level.playSound(null, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), s, SoundSource.PLAYERS, 1.2F,
                1.3F + level.getRandom().nextFloat() * 0.3F);
    }
}
