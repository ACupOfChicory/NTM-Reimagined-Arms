package acup.gunfeel.gas;

import java.lang.ref.WeakReference;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * NTM's white phosphorus lets this mod's white smoke out ({@link SmokeKind#WP}, the rounds' and shells'
 * {@link SmokeKind#WP_SHELL}), on the server (system {@code ntm_wp}):
 *
 * <ul>
 * <li>the hand grenade: its three hazes (NTM's own white smoke, sent to the players around) give way to one cloud at
 * the grenade;
 * <li>the 40 mm round and the rocket, which had no white smoke in NTM (a blast as the high explosive one's and the fire
 * on the ground): a cloud where they burst, the 40 mm's {@link #G40} of a grenade's;
 * <li>the artillery shell (the cluster's too, each of its shells) and the HIMARS rocket: a cloud in place of NTM's
 * mushroom sprite of 2.5 s, the burst's flash that came with it kept.
 * </ul>
 *
 * The blasts, the fire on the ground (NTM's flames rising off it, its burning), the phosphorus on the living and the
 * blocks set alight are NTM's. With smoke off on this server, or the spot shut in, everything goes as NTM had it.
 */
public final class WpSmoke {
    /** The share of {@link SmokeKind#WP}'s volume a hand grenade lets out. */
    public static final float SCALE = 1F;
    /**
     * The 40 mm round's: its fire on the ground is 5 by 2 blocks for 20 s against the grenade's 6 by 2 for 30 s, and a
     * grenade launcher fires a magazine of them (some 16 blocks across against 21).
     */
    public static final float G40 = 0.6F;
    /** The rocket's (its fire as the grenade's), the artillery shell's and the HIMARS rocket's: a grenade's. */
    public static final float ROCKET = 1F, ARTILLERY = 1F, HIMARS = 1F;
    private static final Vec3 DOWN = new Vec3(0, -1, 0);
    // how far NTM sent its mushroom, blocks
    private static final double FLASH = 250;
    /** The stand's switch: null as the game has it, false NTM's hazes, true the cloud (with smoke on). */
    public static volatile Boolean forced;

    // the grenade the last haze was for and what came of it: its three hazes ask one after another (the server's thread)
    private static WeakReference<Entity> last = new WeakReference<>(null);
    private static boolean lastLet;

    private WpSmoke() {
    }

    /**
     * NTM's grenade sends one of its hazes: true when this mod's cloud takes its place (the haze is not sent). The first
     * haze of a grenade lets the cloud out, the next two go the way it went.
     */
    public static boolean haze(Entity grenade) {
        if (grenade == null) return false;
        if (last.get() == grenade) return lastLet;
        last = new WeakReference<>(grenade);
        lastLet = on() && grenade.level() instanceof ServerLevel level
                && SmokeClouds.spawn(level, grenade.position().add(0, 0.25, 0), DOWN, SmokeKind.WP, SCALE) >= 0;
        return lastLet;
    }

    /** A 40 mm round or a rocket of white phosphorus burst at {@code hit} (NTM's blast and fire are out): its cloud. */
    public static void round(Level level, HitResult hit, float scale) {
        if (!on() || !(level instanceof ServerLevel server) || hit == null) return;
        Vec3 at = hit.getLocation();
        // out of the face it hit, so a round in a wall lets its smoke out in front of it
        if (hit instanceof BlockHitResult b) at = at.add(b.getDirection().getStepX() * 0.1, b.getDirection().getStepY() * 0.1, b.getDirection().getStepZ() * 0.1);
        SmokeClouds.spawn(server, at, DOWN, SmokeKind.WP_SHELL, scale);
    }

    /**
     * An artillery shell or a HIMARS rocket of white phosphorus sends NTM's mushroom (a sprite and the burst's flash):
     * true when its cloud takes the sprite's place (the flash goes, as far as NTM sent it), false for NTM's mushroom.
     */
    public static boolean mushroom(Level level, double x, double y, double z, float scale) {
        if (!on() || !(level instanceof ServerLevel server)) return false;
        if (SmokeClouds.spawn(server, new Vec3(x, y, z), DOWN, SmokeKind.WP_SHELL, scale) < 0) return false;
        // the flash to whom NTM sent its mushroom (within 250 blocks), seen as far as NTM's
        for (ServerPlayer p : server.players())
            if (p.distanceToSqr(x, y, z) <= FLASH * FLASH) server.sendParticles(p, ParticleTypes.EXPLOSION_EMITTER, true, true, x, y, z, 1, 0, 0, 0, 0);
        return true;
    }

    private static boolean on() {
        Boolean f = forced;
        return (f == null || f) && Feature.NTM_WP.on();
    }
}
