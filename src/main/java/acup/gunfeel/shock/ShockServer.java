package acup.gunfeel.shock;

import java.util.Map;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The shock of the 12 gauge electroshock shell, decided on the server. A hit on a living one (a player, an AI bot, any
 * mob) shocks it for shockSeconds: whatever gun it fires meanwhile spreads its shots wide (shockSpread, added to the
 * shot's spread where NTM works it out, so for players and AI bots alike). A player is told (ShockPayloads.Shock): its
 * client glitches and shakes the HUD, shakes the view a little and turns it about on its own. After a shock ends the
 * one shocked takes none for shockImmunity seconds: a stream of shells does not keep anyone shocked for good. The
 * discharge is heard where it hit, and the shooter (a player) is told it hit with shock (a mark at the crosshair).
 */
public final class ShockServer {
    private ShockServer() {
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

    /** game ticks: until when the shock lasts, until when it does not take again */
    private record State(long until, long immuneUntil) {
    }

    private static final Map<LivingEntity, State> STATE = new WeakHashMap<>();

    /** whether a shock holds {@code e} now (on the server) */
    public static synchronized boolean shocked(LivingEntity e) {
        if (e == null || e.level().isClientSide()) return false;
        State s = STATE.get(e);
        return s != null && e.level().getGameTime() < s.until;
    }

    /** the spread a shot of {@code shooter}'s gets on top of its own (0 when not shocked) */
    public static float spread(LivingEntity shooter, BulletConfig config) {
        if (!on() || !shocked(shooter)) return 0F;
        float s = spread();
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
        s = new State(now + ticks, now + ticks + Math.round(immunitySeconds() * 20F));
        STATE.put(target, s);
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
        STATE.clear();
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
