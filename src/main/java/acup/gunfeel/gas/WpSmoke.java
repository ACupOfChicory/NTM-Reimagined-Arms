package acup.gunfeel.gas;

import java.lang.ref.WeakReference;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * NTM's white phosphorus hand grenade lets this mod's white smoke out ({@link SmokeKind#WP}), on the server (system
 * {@code ntm_wp}): its three hazes (NTM's own white smoke, sent to the players around) give way to one cloud at the
 * grenade. The blast, the fire it leaves on the ground (NTM's flames rising off it, its burning), the blocks it sets
 * alight are NTM's. With smoke off on this server, or the spot shut in, the hazes go as before.
 *
 * <p>NTM's 40 mm and rocket white phosphorus have no white smoke (a blast as the high explosive one and the fire on the
 * ground), the artillery's and the HIMARS's a mushroom flash of 2.5 s: nothing here.
 */
public final class WpSmoke {
    /** The share of {@link SmokeKind#WP}'s volume a grenade lets out. */
    public static final float SCALE = 1F;
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
                && SmokeClouds.spawn(level, grenade.position().add(0, 0.25, 0), new Vec3(0, -1, 0), SmokeKind.WP, SCALE) >= 0;
        return lastLet;
    }

    private static boolean on() {
        Boolean f = forced;
        return (f == null || f) && Feature.NTM_WP.on();
    }
}
