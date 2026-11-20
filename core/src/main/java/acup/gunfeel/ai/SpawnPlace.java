package acup.gunfeel.ai;

import acup.gunfeel.CoreServices;
import acup.gunfeel.GameMath;
import acup.gunfeel.GameRandom;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;
import acup.gunfeel.host.WorldView;

/**
 * Where a mob just made is put (26.2's AiSpawn): somewhere free round the point, on a floor, within a few blocks
 * (wider for more mobs), turned a random way; none free there (a cramped room's walls all round), false. The body is
 * put and turned through the version's BodyControl and asked again where it stands (its box) after each place tried.
 */
public final class SpawnPlace {
    private static final BodyControl CONTROL = CoreServices.load(BodyControl.class, BodyControl.MISSING);
    /** boxes asked for only to know whether there are any: none fit, the count comes back negative */
    private static final double[] NONE = {};

    private SpawnPlace() {
    }

    /** the mob put round (ax, ay, az) by the version's BodyControl; true where it stands free */
    public static boolean place(WorldView w, Body b, double ax, double ay, double az, GameRandom rng, int count) {
        return place(CONTROL, w, b, ax, ay, az, rng, count);
    }

    static boolean place(BodyControl c, WorldView w, Body b, double ax, double ay, double az, GameRandom rng, int count) {
        double reach = 2 + Math.sqrt(count) * 1.5;
        double[] box = new double[6];
        for (int attempt = 0; attempt <= 16; attempt++) {
            double a = rng.nextDouble() * Math.PI * 2, r = attempt == 16 ? 0 : 1.5 + rng.nextDouble() * reach;
            double x = Math.floor(ax + Math.cos(a) * r) + 0.5, z = Math.floor(az + Math.sin(a) * r) + 0.5;
            int bx = GameMath.floor(x), bz = GameMath.floor(z);
            for (int dy = 3; dy >= -4; dy--) {
                // BlockPos.containing(x, ay + dy, z): the block below its feet with no collision shape, nothing to stand on
                int fy = GameMath.floor(ay + dy);
                if (w.boxes(bx, fy - 1, bz, WorldView.COLLIDER, null, NONE) == 0) continue;
                c.place(b, x, fy, z);
                float yaw = rng.nextFloat() * 360F;
                c.yRot(b, yaw);
                c.yHeadRot(b, yaw);
                c.yBodyRot(b, yaw);
                // level.noCollision(mob): its box where it is now
                b.box(box);
                if (w.free(b, box[0], box[1], box[2], box[3], box[4], box[5])) return true;
            }
        }
        return false;
    }
}
