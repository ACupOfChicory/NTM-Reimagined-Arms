package acup.gunfeel.ai;

import java.util.Arrays;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * A path's step costs more across the line of fire of one of its own: a mob that runs keeps out of its friends' shots
 * (a friend holds fire while it is in the line: AiGunner, AiBots.friendInLine). The lines are its own side's AI mobs'
 * that are firing, from the eye to where they aim, taken anew on the server thread before each search ({@link #now});
 * the search reads them on any thread.
 */
public final class FireLines implements PathSearch.Cost {
    /** what a step in a friend's line costs on top of its length (a step in the open: ExposureCost.SEEN) */
    static final float CROSS = 3F;
    /**
     * a cell is in a line this near it across at the shooter, blocks, widening as the friend's own check does
     * (AiBots.crosses: its box grown by 0.3 and CLEAR_CONE a block out, the box's half width 0.3), and the line within
     * its height there (grown the same)
     */
    static final double HALF = 0.3, GROW = 0.3, NEAR = HALF + GROW, HEIGHT = 1.8;
    static final double WIDEN = Math.tan(Math.toRadians(AiGunner.CLEAR_CONE));
    /** the friend's check looks a block past the aim */
    static final double PAST = 1.0;
    /** friends this far off at most, blocks */
    static final double REACH = 48;

    /** off only for the stands' "before" */
    static boolean on = true;

    /** x0, y0, z0, x1, y1, z1 a line */
    private final double[] lines;
    /** whose they are, to take them anew (server thread only); none for a fixed set */
    private final ServerLevel level;
    private final Mob mob;

    FireLines(double[] lines) {
        this(lines, null, null);
    }

    private FireLines(double[] lines, ServerLevel level, Mob mob) {
        this.lines = lines;
        this.level = level;
        this.mob = mob;
    }

    /** its own side's lines of fire, taken anew before each search (none now: none yet), or NONE if off */
    static PathSearch.Cost of(ServerLevel level, Mob mob) {
        if (!on) return PathSearch.Cost.NONE;
        return new FireLines(lines(level, mob), level, mob);
    }

    @Override
    public PathSearch.Cost now() {
        return level == null || mob.isRemoved() ? this : new FireLines(lines(level, mob), level, mob);
    }

    /** it stands in a line of fire of one of its own now (the stands) */
    static boolean standsIn(ServerLevel level, Mob mob) {
        double[] l = lines(level, mob);
        for (int i = 0; i < l.length; i += 6)
            if (near(l, i, mob.getX(), mob.getY(), mob.getZ())) return true;
        return false;
    }

    /** x0, y0, z0, x1, y1, z1 a line: its own side's AI mobs near that fire, from the eye to the aim */
    private static double[] lines(ServerLevel level, Mob mob) {
        double[] out = new double[0];
        int n = 0;
        for (AiBot b : AiBots.bots(level)) {
            Mob m = b.mob;
            if (m == mob || m.isRemoved() || !m.isAlive() || !AiTeams.own(mob, m) || m.distanceToSqr(mob) > REACH * REACH) continue;
            Vec3 aim = b.gunner.firingAt();
            if (aim == null) continue;
            Vec3 eye = AiLean.eye(m);
            if (out.length < (n + 1) * 6) out = Arrays.copyOf(out, Math.max(12, out.length * 2));
            out[n * 6] = eye.x;
            out[n * 6 + 1] = eye.y;
            out[n * 6 + 2] = eye.z;
            out[n * 6 + 3] = aim.x;
            out[n * 6 + 4] = aim.y;
            out[n * 6 + 5] = aim.z;
            n++;
        }
        return Arrays.copyOf(out, n * 6);
    }

    @Override
    public float extra(int x, int y, int z) {
        double px = x + 0.5, pz = z + 0.5;
        for (int i = 0; i < lines.length; i += 6)
            if (near(lines, i, px, y, pz)) return CROSS;
        return 0F;
    }

    /**
     * A mob standing here (its feet at y, the cell's middle px, pz) is in line i: the line (to a block past the aim)
     * comes within its reach across, NEAR at the shooter widening by WIDEN a block, at a height within its body there
     */
    static boolean near(double[] l, int i, double px, double y, double pz) {
        double ax = l[i], ay = l[i + 1], az = l[i + 2];
        double dx = l[i + 3] - ax, dy = l[i + 4] - ay, dz = l[i + 5] - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return false;
        // a block past the aim, as the friend's own check
        double s = (len + PAST) / len;
        dx *= s;
        dy *= s;
        dz *= s;
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0 : Math.clamp(((px - ax) * dx + (pz - az) * dz) / len2, 0, 1);
        double cx = ax + dx * t - px, cz = az + dz * t - pz, h = ay + dy * t - y;
        double reach = NEAR + WIDEN * t * len * s;
        double grown = reach - HALF;
        return cx * cx + cz * cz <= reach * reach && h >= -grown && h <= HEIGHT + grown;
    }
}
