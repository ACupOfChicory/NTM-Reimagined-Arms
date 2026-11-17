package acup.gunfeel.ammo;

import com.hbm.handler.threading.TargetPoint;
import com.hbm.main.Polaroid;
import com.hbm.packet.PacketWire;
import com.hbm.packet.toclient.MukePayload;

import net.minecraft.server.level.ServerLevel;

/**
 * The mod's mini nukes' cloud, one call for all (the nuclear .50). The mini nuke particles take it over once both are
 * in: the body becomes MiniNukeCloud.spawn(level, x, y, z, size, balefire), which sends this same MukePayload itself
 * where its cloud is off or out of the simulation distance. x, y, z: the blast's centre (as the Fat Mine's, whose
 * cloud is at its blast's centre too).
 */
public final class NuclearFx {
    private NuclearFx() {
    }

    /** the cloud of a mini nuke's blast of ExplosionVNT size {@code size} at x, y, z; for now the Fat Mine's own */
    public static void miniNuke(ServerLevel level, double x, double y, double z, float size) {
        PacketWire.sendToAllAround(new MukePayload(x, y, z, false, Polaroid.isBalefireDay() || level.getRandom().nextInt(100) == 0),
                new TargetPoint(level, x, y, z, 250.0));
    }
}
