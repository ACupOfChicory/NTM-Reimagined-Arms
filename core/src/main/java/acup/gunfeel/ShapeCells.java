package acup.gunfeel;

// A block's shape as a bullet meets it, without the game: 16³ cells (bit x + 16 * (y + 16 * z)), bits[64] the shell's
// flag, the exact faces after them (ModelSurfaceBase); packed as 1024 hex digits or "z" and the deflated bytes in
// base64 ("!" before it for a shell), as the shapes' file and the server's payload carry it. PassableClip and the
// shapes' makers read and write it here, and a bullet's way through the cells is walked here (march, leave).
public final class ShapeCells {
    private ShapeCells() {
    }

    // bits[64] = 1: the model closes its hitbox all round (see MeshShapes.shell)
    public static boolean shell(long[] bits) {
        return bits.length > 64 && bits[64] != 0L;
    }

    static long[] unpack(String v) {
        long[] bits = new long[65];
        if (v.startsWith("!")) {
            bits[64] = 1L;
            v = v.substring(1);
        }
        try {
            if (v.length() == 1024) {
                for (int j = 0; j < 64; j++) bits[j] = Long.parseUnsignedLong(v.substring(j * 16, j * 16 + 16), 16);
                return bits;
            }
            if (!v.startsWith("z")) return null;
            byte[] compressed = java.util.Base64.getDecoder().decode(v.substring(1));
            try (var in = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(compressed))) {
                byte[] data = in.readNBytes(1_048_577);
                if (data.length < 512 || data.length > 1_048_576 || data.length % 8 != 0) return null;
                bits = java.util.Arrays.copyOf(bits, 65 + (data.length - 512) / 8);
                var longs = java.nio.ByteBuffer.wrap(data).asLongBuffer();
                longs.get(bits, 0, 64);
                if (longs.hasRemaining()) longs.get(bits, 65, longs.remaining());
                return ModelSurfaceBase.valid(bits) ? bits : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static String pack(long[] bits) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(512 + Math.max(0, bits.length - 65) * 8);
        var longs = buf.asLongBuffer();
        longs.put(bits, 0, 64);
        if (bits.length > 65) longs.put(bits, 65, bits.length - 65);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.util.zip.DeflaterOutputStream(bytes)) {
            out.write(buf.array());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return (shell(bits) ? "!z" : "z") + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    public static boolean cell(long[] bits, int x, int y, int z) {
        int i = x + 16 * (y + 16 * z);
        return (bits[i >> 6] >>> (i & 63) & 1L) != 0L;
    }

    // the sides a walk of the cells meets a cell or leaves the block by, as the game's Direction numbers them (DOWN, UP,
    // NORTH, SOUTH, WEST, EAST); KEPT: no step was made, the side it came in by stays (march), or its opposite (leave)
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5, KEPT = 6;

    /**
     * From (px, py, pz), where the bullet enters the block's hitbox (in cells, 0 to 16 on each axis), along the unit
     * direction (dx, dy, dz), to the first set cell in at most {@code cells} cells: the side it met that cell by (KEPT:
     * the one it came in by), its distance along the way in cells in {@code out[0]}; -1 when it leaves the block first.
     */
    public static int march(long[] bits, double px, double py, double pz, double dx, double dy, double dz, int cells, double[] out) {
        // На границе клетки берём сторону, в которую летит пуля. Иначе отрицательное направление
        // на x=3/16 проверяло пустую клетку 3 вместо металла в клетке 2 (верхняя половина двери).
        int x = Math.clamp((int) Math.floor(px + dx * 1e-7), 0, 15);
        int y = Math.clamp((int) Math.floor(py + dy * 1e-7), 0, 15);
        int z = Math.clamp((int) Math.floor(pz + dz * 1e-7), 0, 15);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tx = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) - px) / dx;
        double ty = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) - py) / dy;
        double tz = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) - pz) / dz;
        double ddx = Math.abs(1 / dx), ddy = Math.abs(1 / dy), ddz = Math.abs(1 / dz);
        double t = 0;
        int face = KEPT;
        for (int n = 0; n < cells; n++) {
            if (cell(bits, x, y, z)) {
                out[0] = t;
                return face;
            }
            if (tx <= ty && tx <= tz) {
                t = tx; tx += ddx; x += sx; face = sx > 0 ? WEST : EAST;
            } else if (ty <= tz) {
                t = ty; ty += ddy; y += sy; face = sy > 0 ? DOWN : UP;
            } else {
                t = tz; tz += ddz; z += sz; face = sz > 0 ? NORTH : SOUTH;
            }
            if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) return -1;
        }
        return -1;
    }

    /**
     * From (px, py, pz) in a set cell (in cells), along the unit direction (dx, dy, dz), to the first empty cell or the
     * block's side: the far side it leaves by (KEPT: no step, the opposite of the side it came in by), its distance in
     * cells in {@code out[0]}
     */
    public static int leave(long[] bits, double px, double py, double pz, double dx, double dy, double dz, double[] out) {
        int x = Math.clamp((int) Math.floor(px), 0, 15), y = Math.clamp((int) Math.floor(py), 0, 15), z = Math.clamp((int) Math.floor(pz), 0, 15);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tx = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) - px) / dx;
        double ty = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) - py) / dy;
        double tz = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) - pz) / dz;
        double ddx = Math.abs(1 / dx), ddy = Math.abs(1 / dy), ddz = Math.abs(1 / dz);
        double t = 0;
        int face = KEPT;
        for (int n = 0; n < 64 && cell(bits, x, y, z); n++) {
            if (tx <= ty && tx <= tz) {
                t = tx; tx += ddx; x += sx; face = sx > 0 ? EAST : WEST;
            } else if (ty <= tz) {
                t = ty; ty += ddy; y += sy; face = sy > 0 ? UP : DOWN;
            } else {
                t = tz; tz += ddz; z += sz; face = sz > 0 ? SOUTH : NORTH;
            }
            if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) break;
        }
        out[0] = t;
        return face;
    }
}
