package acup.gunfeel.ammo;

import com.hbm.main.Polaroid;

import acup.gunfeel.nuke.MiniNukeCloud;
import net.minecraft.server.level.ServerLevel;

/**
 * The mod's mini nukes' cloud, one call for all (the nuclear .50): NTM's bombs' mushroom cloud sized to the blast
 * (MiniNukeCloud), or NTM's own mini nuke particles where the cloud is off or out of the simulation distance. x, y, z:
 * the blast's centre (as the Fat Mine's, whose cloud is at its blast's centre too).
 */
public final class NuclearFx {
    private NuclearFx() {
    }

    /** the cloud of a mini nuke's blast of ExplosionVNT size {@code size} at x, y, z; balefire as the Fat Mine's */
    public static void miniNuke(ServerLevel level, double x, double y, double z, float size) {
        MiniNukeCloud.spawn(level, x, y, z, size, Polaroid.isBalefireDay() || level.getRandom().nextInt(100) == 0);
    }
}
