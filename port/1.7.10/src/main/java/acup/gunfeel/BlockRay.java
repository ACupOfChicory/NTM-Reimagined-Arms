package acup.gunfeel;

// A bullet's ray through the block grid as 26.2 casts it (BlockGetter.traverseBlocks, VoxelShape.clip, AABB.clip), with
// no Minecraft in it, so the port meets blocks where 26.2 does. 1.7.10's own ray (World.func_147447_a) differs:
//  - it stops after 200 cells (a long tick's path past them flies through everything),
//  - it walks to the cell of the end point and tests the block there only by its selection box (Block.collisionRayTrace
//    with setBlockBoundsBasedOnState: one box, a fence 1 high instead of its 1.5 collision),
//  - it moves its `from` vector as it goes (NTM makes the vector anew after the call),
//  - a path starting inside a block hits that block's far face instead of the point inside it.
// Here: every cell the segment crosses, start cell first, ends stretched by 1e-7 as 26.2 does; each cell's boxes as one
// shape: a test point 0.001 along the path inside a box is a hit "inside" at that point, else the nearest face it comes
// in through (faces within 1e-7). Faces are 26.2's Direction order, which is also 1.7.10's side numbering:
// 0 down, 1 up, 2 north (-z), 3 south (+z), 4 west (-x), 5 east (+x).
public final class BlockRay {
    private BlockRay() {
    }

    // where a ray meets a cell's shape
    public static final class Hit {
        public final int x, y, z, face;
        public final double ax, ay, az;
        // the path started inside the shape (26.2: BlockHitResult.isInside)
        public final boolean inside;

        public Hit(int x, int y, int z, int face, double ax, double ay, double az, boolean inside) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.face = face;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.inside = inside;
        }

        public double distanceSq(double px, double py, double pz) {
            double dx = ax - px, dy = ay - py, dz = az - pz;
            return dx * dx + dy * dy + dz * dz;
        }

        public double distance(double px, double py, double pz) {
            return Math.sqrt(distanceSq(px, py, pz));
        }

        public Hit withFace(int f) {
            return new Hit(x, y, z, f, ax, ay, az, inside);
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z + " face " + face + " at " + ax + "," + ay + "," + az + (inside ? " inside" : "");
        }
    }

    // a cell the ray passes: a hit on it ends the walk; null goes on
    public interface Cells {
        Hit cell(int x, int y, int z);
    }

    // traverseBlocks of 26.2: the cell of `from` first, then each cell the segment enters, in order; the first hit
    public static Hit walk(double fx, double fy, double fz, double tx, double ty, double tz, Cells cells) {
        if (fx == tx && fy == ty && fz == tz) return null;
        double toX = lerp(-1.0E-7, tx, fx), toY = lerp(-1.0E-7, ty, fy), toZ = lerp(-1.0E-7, tz, fz);
        double fromX = lerp(-1.0E-7, fx, tx), fromY = lerp(-1.0E-7, fy, ty), fromZ = lerp(-1.0E-7, fz, tz);
        int x = floor(fromX), y = floor(fromY), z = floor(fromZ);
        Hit first = cells.cell(x, y, z);
        if (first != null) return first;
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        int sx = sign(dx), sy = sign(dy), sz = sign(dz);
        double tDeltaX = sx == 0 ? Double.MAX_VALUE : sx / dx;
        double tDeltaY = sy == 0 ? Double.MAX_VALUE : sy / dy;
        double tDeltaZ = sz == 0 ? Double.MAX_VALUE : sz / dz;
        double tX = tDeltaX * (sx > 0 ? 1.0 - frac(fromX) : frac(fromX));
        double tY = tDeltaY * (sy > 0 ? 1.0 - frac(fromY) : frac(fromY));
        double tZ = tDeltaZ * (sz > 0 ? 1.0 - frac(fromZ) : frac(fromZ));
        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    x += sx;
                    tX += tDeltaX;
                } else {
                    z += sz;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                y += sy;
                tY += tDeltaY;
            } else {
                z += sz;
                tZ += tDeltaZ;
            }
            Hit h = cells.cell(x, y, z);
            if (h != null) return h;
        }
        return null;
    }

    // VoxelShape.clip of 26.2 for a shape of n boxes (world coordinates, minX minY minZ maxX maxY maxZ each) in the
    // cell (x, y, z): null when the segment misses them
    public static Hit clip(double[] boxes, int n, double fx, double fy, double fz, double tx, double ty, double tz, int x, int y, int z) {
        if (n <= 0) return null;
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        if (dx * dx + dy * dy + dz * dz < 1.0E-7) return null;
        double px = fx + dx * 0.001, py = fy + dy * 0.001, pz = fz + dz * 0.001;
        for (int i = 0; i < n; i++) {
            int o = i * 6;
            if (px >= boxes[o] && px < boxes[o + 3] && py >= boxes[o + 1] && py < boxes[o + 4] && pz >= boxes[o + 2] && pz < boxes[o + 5])
                return new Hit(x, y, z, opposite(nearest(dx, dy, dz)), px, py, pz, true);
        }
        double[] scale = {1.0};
        int face = -1;
        for (int i = 0; i < n; i++) {
            int o = i * 6;
            face = direction(boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5], fx, fy, fz, scale, face, dx, dy, dz);
        }
        if (face < 0) return null;
        double s = scale[0];
        return new Hit(x, y, z, face, fx + s * dx, fy + s * dy, fz + s * dz, false);
    }

    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;

    private static int direction(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double fx, double fy,
            double fz, double[] scale, int face, double dx, double dy, double dz) {
        if (dx > 1.0E-7) face = clipPoint(scale, face, dx, dy, dz, minX, minY, maxY, minZ, maxZ, WEST, fx, fy, fz);
        else if (dx < -1.0E-7) face = clipPoint(scale, face, dx, dy, dz, maxX, minY, maxY, minZ, maxZ, EAST, fx, fy, fz);
        if (dy > 1.0E-7) face = clipPoint(scale, face, dy, dz, dx, minY, minZ, maxZ, minX, maxX, DOWN, fy, fz, fx);
        else if (dy < -1.0E-7) face = clipPoint(scale, face, dy, dz, dx, maxY, minZ, maxZ, minX, maxX, UP, fy, fz, fx);
        if (dz > 1.0E-7) face = clipPoint(scale, face, dz, dx, dy, minZ, minX, maxX, minY, maxY, NORTH, fz, fx, fy);
        else if (dz < -1.0E-7) face = clipPoint(scale, face, dz, dx, dy, maxZ, minX, maxX, minY, maxY, SOUTH, fz, fx, fy);
        return face;
    }

    private static int clipPoint(double[] scale, int face, double da, double db, double dc, double point, double minB, double maxB,
            double minC, double maxC, int newFace, double fromA, double fromB, double fromC) {
        double s = (point - fromA) / da;
        double pb = fromB + s * db;
        double pc = fromC + s * dc;
        if (0.0 < s && s < scale[0] && minB - 1.0E-7 < pb && pb < maxB + 1.0E-7 && minC - 1.0E-7 < pc && pc < maxC + 1.0E-7) {
            scale[0] = s;
            return newFace;
        }
        return face;
    }

    // Direction.getApproximateNearest of 26.2: the face whose normal is nearest the vector (the first of a tie), in
    // floats as there; north when none points along it at all
    public static int nearest(double x, double y, double z) {
        float fx = (float) x, fy = (float) y, fz = (float) z;
        int best = NORTH;
        float most = Float.MIN_VALUE;
        for (int f = 0; f < 6; f++) {
            float d = fx * stepX(f) + fy * stepY(f) + fz * stepZ(f);
            if (d > most) {
                most = d;
                best = f;
            }
        }
        return best;
    }

    public static int opposite(int face) {
        return face ^ 1;
    }

    public static int stepX(int face) {
        return face == WEST ? -1 : face == EAST ? 1 : 0;
    }

    public static int stepY(int face) {
        return face == DOWN ? -1 : face == UP ? 1 : 0;
    }

    public static int stepZ(int face) {
        return face == NORTH ? -1 : face == SOUTH ? 1 : 0;
    }

    // Mth.floor
    static int floor(double v) {
        return (int) Math.floor(v);
    }

    private static int sign(double v) {
        return v == 0.0 ? 0 : v > 0.0 ? 1 : -1;
    }

    private static double frac(double v) {
        return v - lfloor(v);
    }

    private static long lfloor(double v) {
        long i = (long) v;
        return v < i ? i - 1L : i;
    }

    // Mth.lerp
    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
