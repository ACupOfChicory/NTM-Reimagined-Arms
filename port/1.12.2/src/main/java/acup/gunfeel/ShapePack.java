package acup.gunfeel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

// The jar's bullet shapes (ModelCells) and machine faces (MachineFaces) packed small: the shapes each written once with
// the keys sorted and sharing their prefixes, the faces' corners on a 1/16384 block grid and their UV on a 1/65536 one,
// corners and UV shared within a model, everything as deltas. The whole is compressed (CODEC) and carries the NTM
// version it was made with: a jar's table made with another NTM is not trusted for NTM's machines (MachineScan takes
// them again in the game).
final class ShapePack {
    private ShapePack() {
    }

    private static final byte[] MAGIC = {'N', 'G', 'S', 'P'};
    private static final int FORMAT = 1;
    static final int DEFLATE = 0, XZ = 2;
    // xz (LZMA2): the smallest of deflate, zstd and xz on these tables, read in about 0.1 s (ModelCells.preload)
    static final int CODEC = XZ;

    // corners and UV on these grids (a corner within 3e-5 block, UV within 8e-6 of a texture)
    static final int POS = 16384, UV = 65536;

    // a file read back: the NTM version it was made with and what it holds
    static final class Read {
        final String ntm;
        final byte[] raw;

        Read(String ntm, byte[] raw) {
            this.ntm = ntm;
            this.raw = raw;
        }
    }

    // ---- the container: magic, format, codec, NTM version, raw length, the compressed bytes

    static byte[] wrap(byte[] raw, String ntm, int codec) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length / 4 + 64);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeByte(FORMAT);
        out.writeByte(codec);
        out.writeUTF(ntm == null ? "" : ntm);
        out.writeInt(raw.length);
        out.write(compress(raw, codec));
        out.flush();
        return bytes.toByteArray();
    }

    static Read unwrap(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("not a shape pack");
        int format = in.readUnsignedByte();
        if (format != FORMAT) throw new IOException("shape pack format " + format + ", not " + FORMAT);
        int codec = in.readUnsignedByte();
        String ntm = in.readUTF();
        int n = in.readInt();
        byte[] packed = readAll(in);
        return new Read(ntm, decompress(packed, n, codec));
    }

    private static byte[] compress(byte[] raw, int codec) throws IOException {
        switch (codec) {
            case DEFLATE: {
                java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true);
                try {
                    d.setInput(raw);
                    d.finish();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    while (!d.finished()) out.write(buf, 0, d.deflate(buf));
                    return out.toByteArray();
                } finally {
                    d.end();
                }
            }
            case XZ: {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                org.tukaani.xz.LZMA2Options o = new org.tukaani.xz.LZMA2Options(6);
                o.setDictSize(Math.max(1 << 20, Integer.highestOneBit(Math.max(1, raw.length)) * 2));
                try (org.tukaani.xz.XZOutputStream x = new org.tukaani.xz.XZOutputStream(out, o, org.tukaani.xz.XZ.CHECK_NONE)) {
                    x.write(raw);
                }
                return out.toByteArray();
            }
            default:
                throw new IOException("unknown codec " + codec);
        }
    }

    private static byte[] decompress(byte[] packed, int n, int codec) throws IOException {
        byte[] raw = new byte[n];
        switch (codec) {
            case DEFLATE: {
                java.util.zip.Inflater f = new java.util.zip.Inflater(true);
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
            case XZ: {
                try (DataInputStream x = new DataInputStream(new org.tukaani.xz.XZInputStream(new ByteArrayInputStream(packed)))) {
                    x.readFully(raw);
                }
                return raw;
            }
            default:
                throw new IOException("unknown codec " + codec);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---- bullet shapes by key (ModelCells): each shape once, then the keys sorted, then each key's shape

    private static final int CELLS = 0, SHELL = 1, FACES = 2;

    static byte[] shapes(Map<String, long[]> table) throws IOException {
        Out out = new Out();
        List<long[]> shapes = new ArrayList<long[]>();
        Map<Key, Integer> index = new HashMap<Key, Integer>();
        TreeSet<String> keys = new TreeSet<String>(table.keySet());
        int[] of = new int[keys.size()];
        int k = 0;
        for (String key : keys) {
            long[] s = table.get(key);
            Key id = new Key(s);
            Integer i = index.get(id);
            if (i == null) {
                i = shapes.size();
                shapes.add(s);
                index.put(id, i);
            }
            of[k++] = i;
        }
        out.varint(shapes.size());
        for (long[] s : shapes) {
            if (ModelSurface.present(s)) {
                out.write(FACES);
                out.varint(s.length);
                for (long l : s) out.writeLong(l);
            } else {
                out.write(ModelCells.shell(s) ? SHELL : CELLS);
                for (int i = 0; i < 64; i++) out.writeLong(s[i]);
            }
        }
        out.keys(keys);
        for (int i : of) out.varint(i);
        return out.bytes();
    }

    static Map<String, long[]> shapes(byte[] raw) throws IOException {
        In in = new In(raw);
        int ns = in.varint();
        long[][] shapes = new long[ns][];
        for (int i = 0; i < ns; i++) {
            int kind = in.readUnsignedByte();
            long[] s;
            if (kind == FACES) {
                s = new long[in.varint()];
                for (int j = 0; j < s.length; j++) s[j] = in.readLong();
            } else {
                s = new long[65];
                for (int j = 0; j < 64; j++) s[j] = in.readLong();
                if (kind == SHELL) s[64] = 1L;
            }
            shapes[i] = s;
        }
        String[] keys = in.keys();
        Map<String, long[]> out = new HashMap<String, long[]>(keys.length * 4 / 3 + 1);
        // shapes shared by keys stay shared, as the text file's "@n" references were
        for (String key : keys) out.put(key, shapes[in.varint()]);
        return out;
    }

    // ---- machine faces (MachineFaces): textures, then models by key, a facing that is another's turn as that turn

    static byte[] faces(List<MachineFaces.Tex> tex, Map<String, MachineFaces.Model> models, Map<String, String> turnOf, Map<String, Integer> turns)
            throws IOException {
        Out out = new Out();
        out.varint(tex.size());
        for (MachineFaces.Tex t : tex) {
            out.writeUTF(t.name == null ? "" : t.name);
            out.varint(t.w);
            out.varint(t.h);
            for (long b : t.shown) out.writeLong(b);
        }
        TreeSet<String> keys = new TreeSet<String>(models.keySet());
        out.keys(keys);
        for (String key : keys) {
            String base = turnOf.get(key);
            if (base != null) {
                out.write(1);
                out.writeUTF(base);
                out.write(turns.get(key));
                continue;
            }
            out.write(0);
            model(out, models.get(key));
        }
        return out.bytes();
    }

    private static void model(Out out, MachineFaces.Model m) throws IOException {
        Map<Long3, Integer> corners = new HashMap<Long3, Integer>();
        Map<Long, Integer> uvs = new HashMap<Long, Integer>();
        List<int[]> cl = new ArrayList<int[]>();
        List<int[]> ul = new ArrayList<int[]>();
        int n = m.size();
        int[] ci = new int[n * 3], ui = new int[n * 3];
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 3; c++) {
                int[] p = {Math.round(m.p[i * 9 + c * 3] * POS), Math.round(m.p[i * 9 + c * 3 + 1] * POS), Math.round(m.p[i * 9 + c * 3 + 2] * POS)};
                Long3 key = new Long3(p[0], p[1], p[2]);
                Integer at = corners.get(key);
                if (at == null) {
                    at = cl.size();
                    cl.add(p);
                    corners.put(key, at);
                }
                ci[i * 3 + c] = at;
                int[] q = {Math.round(m.uv[i * 6 + c * 2] * UV), Math.round(m.uv[i * 6 + c * 2 + 1] * UV)};
                long uk = (long) q[0] << 32 | (q[1] & 0xffffffffL);
                at = uvs.get(uk);
                if (at == null) {
                    at = ul.size();
                    ul.add(q);
                    uvs.put(uk, at);
                }
                ui[i * 3 + c] = at;
            }
        }
        out.varint(cl.size());
        int[] prev = new int[3];
        for (int[] p : cl) for (int a = 0; a < 3; a++) {
            out.zigzag(p[a] - prev[a]);
            prev[a] = p[a];
        }
        out.varint(ul.size());
        prev = new int[2];
        for (int[] q : ul) for (int a = 0; a < 2; a++) {
            out.zigzag(q[a] - prev[a]);
            prev[a] = q[a];
        }
        out.varint(n);
        int pt = 0, pc = 0, pu = 0;
        for (int i = 0; i < n; i++) {
            out.zigzag(m.tex[i] - pt);
            pt = m.tex[i];
            for (int c = 0; c < 3; c++) {
                out.zigzag(ci[i * 3 + c] - pc);
                pc = ci[i * 3 + c];
                out.zigzag(ui[i * 3 + c] - pu);
                pu = ui[i * 3 + c];
            }
        }
    }

    static void faces(byte[] raw, List<MachineFaces.Tex> tex, Map<String, MachineFaces.Model> out) throws IOException {
        In in = new In(raw);
        int nt = in.varint();
        for (int i = 0; i < nt; i++) {
            String name = in.readUTF();
            int w = in.varint(), h = in.varint();
            long[] bits = new long[(w * h + 63) / 64];
            for (int k = 0; k < bits.length; k++) bits[k] = in.readLong();
            tex.add(new MachineFaces.Tex(name, w, h, bits));
        }
        String[] keys = in.keys();
        Map<String, String> turnOf = new HashMap<String, String>();
        Map<String, Integer> turns = new HashMap<String, Integer>();
        for (String key : keys) {
            if (in.readUnsignedByte() == 1) {
                turnOf.put(key, in.readUTF());
                turns.put(key, in.readUnsignedByte());
                continue;
            }
            out.put(key, model(in));
        }
        for (Map.Entry<String, String> e : turnOf.entrySet()) {
            MachineFaces.Model base = out.get(e.getValue());
            if (base != null) out.put(e.getKey(), base.turned(turns.get(e.getKey())));
        }
    }

    private static MachineFaces.Model model(In in) throws IOException {
        int nc = in.varint();
        float[] cx = new float[nc * 3];
        int[] prev = new int[3];
        for (int i = 0; i < nc; i++) for (int a = 0; a < 3; a++) {
            prev[a] += in.zigzag();
            cx[i * 3 + a] = (float) prev[a] / POS;
        }
        int nu = in.varint();
        float[] ux = new float[nu * 2];
        prev = new int[2];
        for (int i = 0; i < nu; i++) for (int a = 0; a < 2; a++) {
            prev[a] += in.zigzag();
            ux[i * 2 + a] = (float) prev[a] / UV;
        }
        int n = in.varint();
        int[] t = new int[n];
        float[] p = new float[n * 9], uv = new float[n * 6];
        int pt = 0, pc = 0, pu = 0;
        for (int i = 0; i < n; i++) {
            pt += in.zigzag();
            t[i] = pt;
            for (int c = 0; c < 3; c++) {
                pc += in.zigzag();
                pu += in.zigzag();
                System.arraycopy(cx, pc * 3, p, i * 9 + c * 3, 3);
                System.arraycopy(ux, pu * 2, uv, i * 6 + c * 2, 2);
            }
        }
        return new MachineFaces.Model(t, p, uv);
    }

    // ---- bits

    private static final class Key {
        final long[] s;
        final int hash;

        Key(long[] s) {
            this.s = s;
            this.hash = java.util.Arrays.hashCode(s);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && java.util.Arrays.equals(s, ((Key) o).s);
        }
    }

    private static final class Long3 {
        final int x, y, z;

        Long3(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Long3)) return false;
            Long3 l = (Long3) o;
            return l.x == x && l.y == y && l.z == z;
        }
    }

    private static final class Out extends DataOutputStream {
        Out() {
            super(new ByteArrayOutputStream(1 << 20));
        }

        void varint(int v) throws IOException {
            while ((v & ~0x7f) != 0) {
                write(v & 0x7f | 0x80);
                v >>>= 7;
            }
            write(v);
        }

        void zigzag(int v) throws IOException {
            varint(v << 1 ^ v >> 31);
        }

        // sorted keys, each as how much it shares with the one before and the rest
        void keys(java.util.Collection<String> keys) throws IOException {
            varint(keys.size());
            byte[] prev = new byte[0];
            for (String k : keys) {
                byte[] b = k.getBytes(StandardCharsets.UTF_8);
                int same = 0;
                while (same < b.length && same < prev.length && b[same] == prev[same]) same++;
                varint(same);
                varint(b.length - same);
                write(b, same, b.length - same);
                prev = b;
            }
        }

        byte[] bytes() throws IOException {
            flush();
            return ((ByteArrayOutputStream) out).toByteArray();
        }
    }

    private static final class In extends DataInputStream {
        In(byte[] raw) {
            super(new ByteArrayInputStream(raw));
        }

        int varint() throws IOException {
            int v = 0;
            for (int shift = 0; ; shift += 7) {
                int b = readUnsignedByte();
                v |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return v;
                if (shift > 28) throw new IOException("bad varint");
            }
        }

        int zigzag() throws IOException {
            int v = varint();
            return v >>> 1 ^ -(v & 1);
        }

        String[] keys() throws IOException {
            int n = varint();
            String[] out = new String[n];
            byte[] prev = new byte[0];
            for (int i = 0; i < n; i++) {
                int same = varint(), rest = varint();
                byte[] b = new byte[same + rest];
                System.arraycopy(prev, 0, b, 0, same);
                readFully(b, same, rest);
                out[i] = new String(b, StandardCharsets.UTF_8);
                prev = b;
            }
            return out;
        }
    }

}
