package acup.gunfeel;

import org.jspecify.annotations.Nullable;

/**
 * A bullet's path this tick through the blocks, piercing them while it has the damage: the first block that stops
 * it, else a miss at the end. Up to 16 blocks: each met ({@link Way#first}), its way out found ({@link Way#exit}),
 * the damage it keeps through it worked out ({@link Shot#left}; behind water with f² of it, {@link Way#water}, in
 * the tick's units), paid for ({@link Shot#pierce}) unless already paid in an earlier tick ({@link PaidBlocks}; a
 * machine's window is paid each time, {@link Way#once}); one it comes out of past the tick's end keeps it inside
 * till the next tick. The vectors are Vec3's arithmetic on doubles, the same operations in the same order (26.2's
 * subtract, length, scale, add, distanceToSqr), so each step comes out as it did. The version holds the bullet and
 * does what is done: the world's side is {@link Way}, the bullet's {@link Shot}. PierceWalkTest holds it to
 * BulletServer.clip's loop as it was.
 */
public final class PierceWalk {
    private PierceWalk() {
    }

    /** the most blocks one tick's path goes through */
    public static final int STEPS = 16;

    /** The blocks on the way, as the version meets them ({@code H}: its hit on a block, or its miss). */
    public interface Way<H> {
        /**
         * the first block the bullet meets from (ax, ay, az) to the tick's end (tx, ty, tz), else a miss. Not only a
         * read: the blocks it flies through on the way (by a "through" rule) are worn and logged by the version here.
         */
        H first(double ax, double ay, double az, double tx, double ty, double tz);

        boolean miss(H hit);

        /** where it hit */
        double x(H hit);

        double y(H hit);

        double z(H hit);

        /** the block it hit, its place as {@link PosLong#block} */
        long pos(H hit);

        /** paid for once, not each time it is met (not a machine's window) */
        boolean once(H hit);

        /** where it comes out of the block going along the unit (dx, dy, dz), none: it does not */
        @Nullable H exit(H hit, double dx, double dy, double dz);

        /** the share of the bullet's damage left at the hit behind water (1: no water) */
        float water(H hit);

        /** a miss at the tick's end, on the side the last block was met on */
        H missAt(H last);

        /** a miss at the tick's end after {@link #STEPS} blocks */
        H missUp();
    }

    /** The bullet: its damage and penetration, and what is done to it and by it. */
    public interface Shot<H> {
        float damage();

        /** its penetration spent so far (to go back to) */
        double spent();

        /** the damage it keeps through the block from in to out with this damage, below 0: it is held */
        float left(H in, H out, float damage);

        /**
         * it pierced the block and pays for it (the test log, the block's wear by its break rule with the water's
         * share k, the account of the tick's blocks with the penetration spent before it, then its damage is left)
         */
        void pierce(H in, H out, float left, float k, double spentBefore);

        /** it stops at this block (out: where it would have come out, none when it does not; the test log) */
        H stop(H hit, @Nullable H out, String why);
    }

    public static <H> H clip(Way<H> way, Shot<H> shot, double fx, double fy, double fz, double tx, double ty, double tz, PaidBlocks paid) {
        double ax = fx, ay = fy, az = fz;
        for (int i = 0; i < STEPS; i++) {
            H hit = way.first(ax, ay, az, tx, ty, tz);
            if (way.miss(hit)) return hit;
            // Vec3 d = to.subtract(at) (to.add(-at)); len = d.length()
            double dx = tx + -ax, dy = ty + -ay, dz = tz + -az;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6) return hit;
            // dir = d.scale(1.0 / len)
            double s = 1.0 / len;
            double ux = dx * s, uy = dy * s, uz = dz * s;
            long pos = way.pos(hit);
            boolean once = way.once(hit), known = once && paid.contains(pos);
            H out = way.exit(hit, ux, uy, uz);
            if (out == null) return shot.stop(hit, null, "no_exit");
            double spentBefore = shot.spent();
            // behind water it has f² of the damage, kept in the tick's units
            float k = way.water(hit);
            float left = known ? shot.damage() : k == 1F ? shot.left(hit, out, shot.damage())
                    : k <= 0F ? -1F : shot.left(hit, out, shot.damage() * k) / k;
            if (left < 0F) return shot.stop(hit, out, "held");
            if (!known) {
                if (once) paid.add(pos);
                shot.pierce(hit, out, left, k, spentBefore);
            }
            // it comes out beyond this tick's end: it stays inside the block till the next tick
            // (out.distanceToSqr(at): at - out, squared, summed in order)
            double ox = way.x(out), oy = way.y(out), oz = way.z(out);
            double ex = ax - ox, ey = ay - oy, ez = az - oz;
            if (ex * ex + ey * ey + ez * ez >= len * len) return way.missAt(hit);
            // at = out.add(dir.scale(1e-3))
            ax = ox + ux * 1e-3;
            ay = oy + uy * 1e-3;
            az = oz + uz * 1e-3;
        }
        return way.missUp();
    }
}
