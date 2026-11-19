package acup.gunfeel;

// A block's shape as a bullet meets it, without the game: 16³ cells (bit x + 16 * (y + 16 * z)), bits[64] the shell's
// flag, the exact faces after them (ModelSurfaceBase); packed as 1024 hex digits or "z" and the deflated bytes in
// base64 ("!" before it for a shell), as the shapes' file and the server's payload carry it. PassableClip and the
// shapes' makers read and write it here.
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
}
