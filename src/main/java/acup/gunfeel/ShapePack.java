package acup.gunfeel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;

// The cells of NTM's big multiblocks (Structures) for every one of them, made beforehand (ShapesStand) and shipped in
// the jar, so a bullet meets a machine as drawn before this client has drawn it, on a dedicated server too. Kept by
// machine: a group is the shapes of one core state turned north ("hbm:cargo_door[facing=north,open=false,skin=0]"),
// each block's under its place from the core ("1,2,0"), compressed on its own (xz) and unpacked on a thread of its own
// the first time it is asked for (MeshShapes asks for the machines near the player): unpacked, a machine's faces take
// megabytes and a world has a few kinds of them. Till it is unpacked the machine is met as before (get: null). The file
// carries the NTM version it was made with; with another NTM it is not used and the client takes the machines itself
// (MeshShapes).
public final class ShapePack {
    private ShapePack() {
    }

    public static final String FILE = "ntmgunfeel-machine-shapes.pack";
    private static final byte[] MAGIC = {'N', 'G', 'M', 'P'};
    private static final int FORMAT = 3;
    static final int DEFLATE = 0, XZ = 2;
    static final int CODEC = XZ;

    private record Group(int codec, int raw, byte[] packed) {
    }

    private record Index(String ntm, Map<String, Group> groups) {
        // made with this game's NTM (known once NTM is loaded)
        boolean trusted() {
            return !off && !groups.isEmpty() && ntm.equals(acup.gunfeel.compat.Compat.hbmVersion());
        }
    }

    // the stand (ShapesStand) takes every machine anew: the file is not used meanwhile
    static volatile boolean off;
    // machines (block ids) the file is not used for, as if it had none of them: -Pntmgunfeel.shapesSkip=a,b (the check
    // that a machine missing from the table is taken in the game, ShapesFallbackCheck)
    private static final java.util.Set<String> SKIP = java.util.Set.of(System.getProperty("ntmgunfeel.shapesSkip", "").split(","));

    private static boolean skipped(String name) {
        int b = name.indexOf('[');
        return SKIP.contains(b < 0 ? name : name.substring(0, b));
    }

    private static final Index NONE = new Index("", Map.of());
    private static volatile @Nullable Index index;
    private static volatile boolean reading;
    // machines unpacked: their shapes by place; asked for and not unpacked yet: absent, in UNPACKING
    private static final Map<String, Map<String, long[]>> OPEN = new ConcurrentHashMap<>();
    private static final java.util.Set<String> UNPACKING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ntm-gunfeel machine shapes");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // the shape under a Structures key from the jar's file: null when it has none, it was made with another NTM, or the
    // machine is not unpacked yet (then it is unpacked meanwhile)
    public static long @Nullable [] get(String key) {
        int at = key.lastIndexOf('@');
        if (at < 0) return null;
        Index idx = index;
        if (idx == null) {
            preload();
            return null;
        }
        if (!idx.trusted()) return null;
        String name = key.substring(0, at);
        if (skipped(name)) return null;
        Map<String, long[]> shapes = OPEN.get(name);
        if (shapes == null) {
            unpack(idx, name);
            return null;
        }
        return shapes.get(key.substring(at + 1));
    }

    // the jar's file has this machine (the key of any of its blocks), made with this game's NTM; it is unpacked
    // meanwhile if it is not yet. False while the file is being read
    public static boolean has(String key) {
        int at = key.lastIndexOf('@');
        Index idx = index;
        if (idx == null) {
            preload();
            return false;
        }
        if (at < 0 || !idx.trusted() || !idx.groups().containsKey(key.substring(0, at)) || skipped(key.substring(0, at))) return false;
        unpack(idx, key.substring(0, at));
        return true;
    }

    // the shape under this key is to come from the jar's file and is not there yet: the file is being read, or the
    // machine (made with this NTM, not skipped) is being unpacked
    public static boolean pending(String key) {
        int at = key.lastIndexOf('@');
        if (at < 0) return false;
        Index idx = index;
        if (idx == null) return reading;
        String name = key.substring(0, at);
        return idx.trusted() && idx.groups().containsKey(name) && !skipped(name) && !OPEN.containsKey(name);
    }

    // the shape under a key as the jar's file has it, unpacked here, skipped or not (for checks)
    static long @Nullable [] fromFile(String key) {
        int at = key.lastIndexOf('@');
        Index idx = index;
        if (at < 0 || idx == null) return null;
        Group g = idx.groups().get(key.substring(0, at));
        return g == null ? null : open(key.substring(0, at), g).get(key.substring(at + 1));
    }

    // the file has been read (preload)
    public static boolean ready() {
        return index != null;
    }

    // the machine (a key of its blocks) is unpacked
    public static boolean open(String key) {
        int at = key.lastIndexOf('@');
        return at > 0 && OPEN.containsKey(key.substring(0, at));
    }

    // the index read on a thread of its own while the game starts (GunFeelMod)
    public static synchronized void preload() {
        if (index != null || reading) return;
        reading = true;
        THREAD.execute(ShapePack::read);
    }

    public static int machines() {
        Index i = index;
        return i == null ? 0 : i.groups().size();
    }

    public static String madeWith() {
        Index i = index;
        return i == null ? "" : i.ntm();
    }

    private static void unpack(Index idx, String name) {
        if (OPEN.containsKey(name)) return;
        Group g = idx.groups().get(name);
        if (g == null || !UNPACKING.add(name)) return;
        THREAD.execute(() -> {
            try {
                OPEN.put(name, open(name, g));
            } finally {
                // past an error open does not catch, the machine is met by NTM's boxes rather than waited for for good
                OPEN.putIfAbsent(name, Map.of());
                UNPACKING.remove(name);
            }
        });
    }

    private static Map<String, long[]> open(String name, Group g) {
        long start = System.nanoTime();
        try {
            Map<String, long[]> out = readGroup(decompress(g.packed(), g.raw(), g.codec()));
            DebugLog.LOGGER.debug("Bullet shapes of {} unpacked in {} ms", name, (System.nanoTime() - start) / 1_000_000);
            return out;
        } catch (IOException | RuntimeException | LinkageError e) {
            DebugLog.LOGGER.warn("Could not unpack the bullet shapes of {}", name, e);
            return Map.of();
        }
    }

    private static void read() {
        Index read = NONE;
        long start = System.nanoTime();
        try (InputStream in = ShapePack.class.getResourceAsStream("/" + FILE)) {
            if (in != null) {
                read = readIndex(in.readAllBytes());
                DebugLog.LOGGER.info("Bullet shapes of {} NTM machines in the jar, made with NTM {}, read in {} ms", read.groups().size(), read.ntm(),
                        (System.nanoTime() - start) / 1_000_000);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            DebugLog.LOGGER.warn("Could not read {}", FILE, e);
        } finally {
            // set whatever happens: unset, the machines would wait for the file for good (pending)
            index = read;
        }
    }

    // ---- the file: magic, format, NTM version, groups (name, codec, raw length, packed length), then their bytes

    // the shapes of the file, all groups unpacked: for tests and the stand
    static Map<String, long[]> read(byte[] file) throws IOException {
        Map<String, long[]> out = new TreeMap<>();
        for (var g : readIndex(file).groups().entrySet()) {
            Group grp = g.getValue();
            for (var e : readGroup(decompress(grp.packed(), grp.raw(), grp.codec())).entrySet()) out.put(g.getKey() + "@" + e.getKey(), e.getValue());
        }
        return out;
    }

    static String ntmOf(byte[] file) throws IOException {
        return readIndex(file).ntm();
    }

    private static Index readIndex(byte[] file) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("not a shape pack");
        int format = in.readUnsignedByte();
        if (format != FORMAT) throw new IOException("shape pack format " + format + ", not " + FORMAT);
        String ntm = in.readUTF();
        int n = in.readInt();
        String[] names = new String[n];
        int[] codec = new int[n], raw = new int[n], packed = new int[n];
        for (int i = 0; i < n; i++) {
            names[i] = in.readUTF();
            codec[i] = in.readUnsignedByte();
            raw[i] = in.readInt();
            packed[i] = in.readInt();
        }
        Map<String, Group> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            byte[] b = new byte[packed[i]];
            in.readFully(b);
            groups.put(names[i], new Group(codec[i], raw[i], b));
        }
        return new Index(ntm, Map.copyOf(groups));
    }

    // the shapes by Structures key, grouped by machine; keys without "@" are left out
    static byte[] write(Map<String, long[]> shapes, String ntm, int codec) throws IOException {
        Map<String, Map<String, long[]>> groups = new TreeMap<>();
        for (var e : shapes.entrySet()) {
            int at = e.getKey().lastIndexOf('@');
            if (at < 0) continue;
            groups.computeIfAbsent(e.getKey().substring(0, at), k -> new TreeMap<>()).put(e.getKey().substring(at + 1), e.getValue());
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(head);
        h.write(MAGIC);
        h.writeByte(FORMAT);
        h.writeUTF(ntm);
        h.writeInt(groups.size());
        for (var g : groups.entrySet()) {
            byte[] raw = group(g.getValue());
            byte[] packed = compress(raw, codec);
            h.writeUTF(g.getKey());
            h.writeByte(codec);
            h.writeInt(raw.length);
            h.writeInt(packed.length);
            body.write(packed);
        }
        h.flush();
        body.writeTo(head);
        return head.toByteArray();
    }

    // ---- a group, before compression: the corners of its faces, each once (a corner: its place in 1/16384 of a block
    // and its texture coordinates in 1/65536, each written as the step from the same number of the corner before, zigzag
    // varint); then the shapes met in it, each once: its 65 cell words and its faces (ModelSurface), a face being its
    // three corners by index (the first as the step from the first of the face before, the others from the first), its
    // size and glass, and its pixel mask, by index when the group has it already; then its places and the shape of each.
    // The triangles of a model share their corners, so a corner is written once and met by index. 16384ths of a block
    // are well under a pixel of a cell (1/16): what a bullet meets does not change.

    static final double POS = 16384, UV = 65536;

    private static byte[] group(Map<String, long[]> shapes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        Map<Shape, Integer> seen = new HashMap<>();
        java.util.List<long[]> unique = new java.util.ArrayList<>();
        int[] ref = new int[shapes.size()];
        int i = 0;
        for (long[] v : shapes.values()) {
            Integer before = seen.putIfAbsent(new Shape(v), unique.size());
            if (before == null) {
                ref[i++] = unique.size();
                unique.add(v);
            } else ref[i++] = before;
        }
        // the corners, in the order the faces meet them
        Map<Shape, Integer> corners = new HashMap<>();
        java.util.List<long[]> cornerList = new java.util.ArrayList<>();
        java.util.List<int[]> faceCorners = new java.util.ArrayList<>();
        for (long[] v : unique)
            for (var f : ModelSurface.faces(v)) {
                int[] at = new int[3];
                for (int q = 0; q < 3; q++) {
                    long[] c = {Math.round(f.p()[3 * q] * POS), Math.round(f.p()[3 * q + 1] * POS), Math.round(f.p()[3 * q + 2] * POS),
                            Math.round(f.uv()[2 * q] * UV), Math.round(f.uv()[2 * q + 1] * UV)};
                    Integer idx = corners.putIfAbsent(new Shape(c), cornerList.size());
                    if (idx == null) {
                        at[q] = cornerList.size();
                        cornerList.add(c);
                    } else at[q] = idx;
                }
                faceCorners.add(at);
            }
        varint(out, cornerList.size());
        long[] last = new long[5];
        for (long[] c : cornerList)
            for (int k = 0; k < 5; k++) {
                varint(out, zigzag(c[k] - last[k]));
                last[k] = c[k];
            }
        varint(out, unique.size());
        Map<Shape, Integer> masks = new HashMap<>();
        int face = 0, lastFirst = 0;
        for (long[] v : unique) {
            for (int k = 0; k < 65; k++) out.writeLong(k < v.length ? v[k] : 0L);
            var faces = ModelSurface.faces(v);
            varint(out, ModelSurface.present(v) ? faces.size() + 1 : 0);
            for (var f : faces) {
                int[] at = faceCorners.get(face++);
                varint(out, zigzag(at[0] - lastFirst));
                varint(out, zigzag(at[1] - at[0]));
                varint(out, zigzag(at[2] - at[0]));
                lastFirst = at[0];
                varint(out, f.width());
                varint(out, f.height() << 1 | (f.glass() ? 1 : 0));
                Shape mask = new Shape(f.alpha());
                Integer m = masks.get(mask);
                if (m != null) {
                    varint(out, m + 1);
                    continue;
                }
                masks.put(mask, masks.size());
                varint(out, 0);
                for (long l : f.alpha()) out.writeLong(l);
            }
        }
        varint(out, shapes.size());
        i = 0;
        for (String place : shapes.keySet()) {
            out.writeUTF(place);
            varint(out, ref[i++]);
        }
        out.flush();
        return bytes.toByteArray();
    }

    private record Shape(long[] a) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Shape s && Arrays.equals(a, s.a);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(a);
        }
    }

    static Map<String, long[]> readGroup(byte[] raw) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int nc = (int) unvarint(in);
        double[] corner = new double[nc * 5];
        long[] last = new long[5];
        for (int j = 0; j < nc; j++)
            for (int k = 0; k < 5; k++) {
                last[k] += unzigzag(unvarint(in));
                corner[j * 5 + k] = last[k] / (k < 3 ? POS : UV);
            }
        int u = (int) unvarint(in);
        long[][] unique = new long[u][];
        int lastFirst = 0;
        java.util.List<long[]> masks = new java.util.ArrayList<>();
        for (int j = 0; j < u; j++) {
            long[] cells = new long[65];
            for (int k = 0; k < 65; k++) cells[k] = in.readLong();
            int n = (int) unvarint(in);
            if (n == 0) {
                unique[j] = cells;
                continue;
            }
            java.util.List<ModelSurface.Face> faces = new java.util.ArrayList<>(n - 1);
            for (int f = 0; f < n - 1; f++) {
                double[] p = new double[9], uv = new double[6];
                int first = lastFirst + (int) unzigzag(unvarint(in));
                int[] at = {first, first + (int) unzigzag(unvarint(in)), first + (int) unzigzag(unvarint(in))};
                lastFirst = first;
                for (int q = 0; q < 3; q++) {
                    for (int k = 0; k < 3; k++) p[3 * q + k] = corner[at[q] * 5 + k];
                    uv[2 * q] = corner[at[q] * 5 + 3];
                    uv[2 * q + 1] = corner[at[q] * 5 + 4];
                }
                int w = (int) unvarint(in), hg = (int) unvarint(in);
                int m = (int) unvarint(in);
                long[] alpha;
                if (m > 0) alpha = masks.get(m - 1);
                else {
                    alpha = new long[(w * (hg >>> 1) + 63) / 64];
                    for (int k = 0; k < alpha.length; k++) alpha[k] = in.readLong();
                    masks.add(alpha);
                }
                faces.add(new ModelSurface.Face(p, uv, w, hg >>> 1, alpha, (hg & 1) != 0));
            }
            unique[j] = ModelSurface.attach(cells, faces);
        }
        int n = (int) unvarint(in);
        Map<String, long[]> out = new HashMap<>(n * 4 / 3 + 1);
        for (int j = 0; j < n; j++) {
            String place = in.readUTF();
            long[] s = unique[(int) unvarint(in)];
            if (ModelSurface.valid(s)) out.put(place, s);
        }
        return out;
    }

    private static void varint(DataOutputStream out, long v) throws IOException {
        while ((v & ~0x7FL) != 0) {
            out.writeByte((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte((int) v);
    }

    private static long unvarint(DataInputStream in) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.readUnsignedByte();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("bad varint");
    }

    private static long zigzag(long v) {
        return v << 1 ^ v >> 63;
    }

    private static long unzigzag(long v) {
        return v >>> 1 ^ -(v & 1);
    }

    // a shape as the file brings it back: its faces' numbers on the file's steps (for tests and the stand)
    static long[] quantized(long[] shape) {
        if (!ModelSurface.present(shape)) return shape;
        java.util.List<ModelSurface.Face> faces = new java.util.ArrayList<>();
        for (var f : ModelSurface.faces(shape)) {
            double[] p = f.p().clone(), uv = f.uv().clone();
            for (int k = 0; k < 9; k++) p[k] = Math.round(p[k] * POS) / POS;
            for (int k = 0; k < 6; k++) uv[k] = Math.round(uv[k] * UV) / UV;
            faces.add(new ModelSurface.Face(p, uv, f.width(), f.height(), f.alpha(), f.glass()));
        }
        return ModelSurface.attach(java.util.Arrays.copyOf(shape, 65), faces);
    }

    private static byte[] compress(byte[] raw, int codec) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (codec == XZ) {
            var o = new org.tukaani.xz.LZMA2Options(org.tukaani.xz.LZMA2Options.PRESET_MAX);
            // a dictionary no bigger than the group: unpacking takes that much memory, not the preset's 64 MB
            o.setDictSize(Math.max(org.tukaani.xz.LZMA2Options.DICT_SIZE_MIN, Math.min(o.getDictSize(), Integer.highestOneBit(Math.max(1, raw.length - 1)) << 1)));
            try (var x = new org.tukaani.xz.XZOutputStream(out, o, org.tukaani.xz.XZ.CHECK_NONE)) {
                x.write(raw);
            }
            return out.toByteArray();
        }
        if (codec != DEFLATE) throw new IOException("unknown codec " + codec);
        var d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true);
        try {
            d.setInput(raw);
            d.finish();
            byte[] buf = new byte[65536];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    static byte[] decompress(byte[] packed, int n, int codec) throws IOException {
        byte[] raw = new byte[n];
        if (codec == XZ) {
            try (var x = new org.tukaani.xz.XZInputStream(new ByteArrayInputStream(packed))) {
                new DataInputStream(x).readFully(raw);
            }
            return raw;
        }
        if (codec != DEFLATE) throw new IOException("unknown codec " + codec);
        var f = new java.util.zip.Inflater(true);
        try {
            f.setInput(packed);
            int at = 0;
            while (at < n) {
                int got = f.inflate(raw, at, n - at);
                if (got == 0 && (f.finished() || f.needsInput())) break;
                at += got;
            }
            if (at != n) throw new IOException("short shape pack");
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            f.end();
        }
        return raw;
    }
}
