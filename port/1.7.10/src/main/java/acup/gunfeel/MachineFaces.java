package acup.gunfeel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// port: 26.2's exact machine faces (ModelSurface on Structures' cells) for NTM 1.7.10's machines. 26.2 keeps a face for
// each triangle of a machine's model in each block it reaches, with the alpha of its pixels; for every machine that is
// 1.6 million faces, too much for the jar or for memory. Here each machine facing keeps its triangles once (corners from
// the core block's corner, UV on its texture, the texture's index) and each texture its shown pixels once; a block's
// faces (ModelSurface) are made from them when a bullet first meets it, and kept. A facing whose triangles are another
// facing's turned about the core (as NTM's renderers turn them) is kept as that turn.
// Made in dev by ShapesScenario (MachineCapture, all four facings drawn), shipped in the jar as FILE.
final class MachineFaces {
    private MachineFaces() {
    }

    static final String FILE = "ntmgunfeel-machine-faces-1710.bin";
    private static final int VERSION = 1;

    // the largest mask a face keeps (a triangle over a tiled texture): bigger ones are taken as shown all over
    private static final int MAX_MASK = 256;

    // a texture's shown pixels (alpha over 0.1, as 1.7.10's alpha test); empty: listed in MachineGlass (clear is air)
    static final class Tex {
        final String name;
        final int w, h;
        final long[] shown;
        final boolean empty;

        Tex(String name, int w, int h, long[] shown) {
            this.name = name;
            this.w = w;
            this.h = h;
            this.shown = shown;
            this.empty = name != null && MachineGlass.empty(name);
        }

        // from a texture as read back from GL (DoorCapture.texture: {{w, h}, argb})
        static Tex of(String name, int[][] t) {
            int w = t[0][0], h = t[0][1];
            long[] bits = new long[(w * h + 63) / 64];
            for (int i = 0; i < w * h; i++) if ((t[1][i] >>> 24) > 25) bits[i >> 6] |= 1L << (i & 63);
            return new Tex(name, w, h, bits);
        }

        boolean shown(double u, double v) {
            if (w == 0) return true;
            int x = Math.floorMod((int) Math.floor(u * w), w), y = Math.floorMod((int) Math.floor(v * h), h);
            int k = x + w * y;
            return (shown[k >> 6] >>> (k & 63) & 1L) != 0;
        }
    }

    // a machine facing's triangles: tex[i] (-1: untextured), p (9 a triangle, from the core block's corner), uv (6)
    static final class Model {
        final int[] tex;
        final float[] p, uv;

        Model(int[] tex, float[] p, float[] uv) {
            this.tex = tex;
            this.p = p;
            this.uv = uv;
        }

        int size() {
            return tex.length;
        }

        // its texture indices moved on by `by` (dev: another texture list before its own)
        Model shifted(int by) {
            int[] t = tex.clone();
            for (int i = 0; i < t.length; i++) if (t[i] >= 0) t[i] += by;
            return new Model(t, p, uv);
        }

        // the model turned `turns` quarters about the core block's middle (x, z) -> (1 - z, x)
        Model turned(int turns) {
            float[] q = p.clone();
            for (int t = 0; t < (turns & 3); t++)
                for (int i = 0; i < q.length; i += 3) {
                    float x = q[i], z = q[i + 2];
                    q[i] = 1 - z;
                    q[i + 2] = x;
                }
            return new Model(tex, q, uv);
        }
    }

    // loaded: textures, and by "<id>/<meta>" a model (its own or another facing's turned)
    private static volatile List<Tex> textures;
    private static volatile Map<String, Model> models;
    // made shapes by MachineCells key: {the cells they were made on, the shape with faces or the cells}
    private static final Map<String, Object[]> MADE = new ConcurrentHashMap<String, Object[]>();
    // off: machines met by their cells alone (checkBullets' cell cases)
    static volatile boolean off;

    // the cells of the machine's block (its MachineCells key) with its exact faces, or the cells alone when none known
    static long[] with(long[] cells, String key) {
        if (off || cells == null || ModelSurface.present(cells)) return cells;
        Object[] made = MADE.get(key);
        if (made == null || made[0] != cells) {
            long[] shape = null;
            try {
                shape = make(cells, key);
            } catch (RuntimeException e) {
                GunFeel.LOGGER.warn("[ntm-gunfeel] Could not make the faces of " + key, e);
            }
            made = new Object[] {cells, shape == null ? cells : shape};
            MADE.put(key, made);
        }
        return (long[]) made[1];
    }

    // the faces ShapesScenario just made, in place of the jar's (dev)
    static synchronized void put(List<Tex> tex, Map<String, Model> made) {
        textures = tex;
        models = made;
        MADE.clear();
    }

    // key: <id>#machine/<meta>/<dx>,<dy>,<dz>
    private static long[] make(long[] cells, String key) {
        load();
        int hash = key.indexOf("#machine/");
        String[] rest = key.substring(hash + 9).split("/");
        String[] d = rest[1].split(",");
        int dx = Integer.parseInt(d[0]), dy = Integer.parseInt(d[1]), dz = Integer.parseInt(d[2]);
        Model m = models.get(key.substring(0, hash) + "/" + rest[0]);
        if (m == null) return null;
        List<ModelSurface.Face> faces = new ArrayList<ModelSurface.Face>();
        double[] tri = new double[9], u = new double[3], v = new double[3];
        for (int i = 0; i < m.size(); i++) {
            for (int k = 0; k < 9; k++) tri[k] = m.p[i * 9 + k] - (k % 3 == 0 ? dx : k % 3 == 1 ? dy : dz);
            if (!overlaps(tri)) continue;
            int t = m.tex[i];
            if (t < 0) {
                faces.add(new ModelSurface.Face(tri.clone(), new double[6], 1, 1, new long[] {1}));
                continue;
            }
            Tex tex = textures.get(t);
            for (int c = 0; c < 3; c++) {
                u[c] = m.uv[i * 6 + c * 2];
                v[c] = m.uv[i * 6 + c * 2 + 1];
            }
            ModelSurface.Face shown = face(tri, tex, u, v, false);
            if (shown != null) faces.add(shown);
            // a window: not on a texture whose clear pixels are air (MachineGlass' list), not a light (clear all over)
            boolean light = !tex.shown(u[0], v[0]) && !tex.shown(u[1], v[1]) && !tex.shown(u[2], v[2]);
            if (tex.empty || light) continue;
            ModelSurface.Face window = face(tri, tex, u, v, true);
            if (window != null) faces.add(window);
        }
        return ModelSurface.attach(cells, faces);
    }

    // does the triangle (in its block's space) reach into the block
    private static boolean overlaps(double[] p) {
        for (int a = 0; a < 3; a++) {
            double lo = Math.min(p[a], Math.min(p[a + 3], p[a + 6])), hi = Math.max(p[a], Math.max(p[a + 3], p[a + 6]));
            if (hi < -1e-7 || lo > 1 + 1e-7) return false;
        }
        return true;
    }

    // the triangle as a face: its pixels on the texture's grid over its UV box, shown (glass: clear) ones set; null
    // when none is; all set: one pixel
    static ModelSurface.Face face(double[] tri, Tex tex, double[] u, double[] v, boolean glass) {
        int tw = tex.w, th = tex.h;
        if (tw == 0) return glass ? null : new ModelSurface.Face(tri.clone(), new double[6], 1, 1, new long[] {1});
        double u0 = Math.min(u[0], Math.min(u[1], u[2])) * tw, u1 = Math.max(u[0], Math.max(u[1], u[2])) * tw;
        double v0 = Math.min(v[0], Math.min(v[1], v[2])) * th, v1 = Math.max(v[0], Math.max(v[1], v[2])) * th;
        int x0 = (int) Math.floor(u0 + 1e-6), y0 = (int) Math.floor(v0 + 1e-6);
        int w = Math.max(1, (int) Math.ceil(u1 - 1e-6) - x0), h = Math.max(1, (int) Math.ceil(v1 - 1e-6) - y0);
        if (w > MAX_MASK || h > MAX_MASK) return glass ? null : new ModelSurface.Face(tri.clone(), new double[6], 1, 1, new long[] {1});
        long[] alpha = new long[(w * h + 63) / 64];
        int set = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (tex.shown((x0 + x + .5) / tw, (y0 + y + .5) / th) != glass) {
                int k = x + w * y;
                alpha[k >> 6] |= 1L << (k & 63);
                set++;
            }
        }
        if (set == 0) return null;
        if (set == w * h) return new ModelSurface.Face(tri.clone(), new double[6], 1, 1, new long[] {1}, glass);
        double[] uv = new double[6];
        for (int i = 0; i < 3; i++) {
            uv[2 * i] = u[i] * tw - x0;
            uv[2 * i + 1] = v[i] * th - y0;
        }
        return new ModelSurface.Face(tri.clone(), uv, w, h, alpha, glass);
    }

    private static void load() {
        if (models != null) return;
        synchronized (MachineFaces.class) {
            if (models != null) return;
            List<Tex> tex = new ArrayList<Tex>();
            Map<String, Model> m = new HashMap<String, Model>();
            InputStream raw = MachineFaces.class.getResourceAsStream("/" + FILE);
            if (raw == null) {
                GunFeel.LOGGER.warn("[ntm-gunfeel] No {} in the jar: machines are met by their cells", FILE);
            } else {
                try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new java.util.zip.InflaterInputStream(raw)))) {
                    read(in, tex, m);
                } catch (IOException | RuntimeException e) {
                    GunFeel.LOGGER.warn("[ntm-gunfeel] Could not read " + FILE + ": machines are met by their cells", e);
                    tex.clear();
                    m.clear();
                }
            }
            textures = tex;
            models = m;
        }
    }

    // dev: the jar's file as it is
    static void readJar(List<Tex> tex, Map<String, Model> out) {
        InputStream raw = MachineFaces.class.getResourceAsStream("/" + FILE);
        if (raw == null) return;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new java.util.zip.InflaterInputStream(raw)))) {
            read(in, tex, out);
        } catch (IOException e) {
            GunFeel.LOGGER.warn("[ntm-gunfeel] Could not read " + FILE, e);
        }
    }

    static void read(DataInputStream in, List<Tex> tex, Map<String, Model> out) throws IOException {
        if (in.readInt() != VERSION) throw new IOException("not version " + VERSION);
        int nt = in.readInt();
        for (int i = 0; i < nt; i++) {
            String name = in.readUTF();
            int w = in.readInt(), h = in.readInt();
            long[] bits = new long[(w * h + 63) / 64];
            for (int k = 0; k < bits.length; k++) bits[k] = in.readLong();
            tex.add(new Tex(name, w, h, bits));
        }
        int nm = in.readInt();
        Map<String, String> turnOf = new HashMap<String, String>();
        Map<String, Integer> turns = new HashMap<String, Integer>();
        for (int i = 0; i < nm; i++) {
            String key = in.readUTF();
            int kind = in.readByte();
            if (kind == 1) {
                turnOf.put(key, in.readUTF());
                turns.put(key, (int) in.readByte());
                continue;
            }
            int n = in.readInt();
            int[] t = new int[n];
            float[] p = new float[n * 9], uv = new float[n * 6];
            for (int k = 0; k < n; k++) {
                t[k] = in.readShort();
                for (int j = 0; j < 9; j++) p[k * 9 + j] = in.readFloat();
                for (int j = 0; j < 6; j++) uv[k * 6 + j] = in.readFloat();
            }
            out.put(key, new Model(t, p, uv));
        }
        for (Map.Entry<String, String> e : turnOf.entrySet()) {
            Model base = out.get(e.getValue());
            if (base != null) out.put(e.getKey(), base.turned(turns.get(e.getKey())));
        }
    }

    // dev: textures and models by "<id>/<meta>" written, a facing that is another's turn as that turn
    static void write(OutputStream raw, List<Tex> tex, Map<String, Model> models) throws IOException {
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new java.util.zip.DeflaterOutputStream(raw, d)))) {
            out.writeInt(VERSION);
            out.writeInt(tex.size());
            for (Tex t : tex) {
                out.writeUTF(t.name == null ? "" : t.name);
                out.writeInt(t.w);
                out.writeInt(t.h);
                for (long b : t.shown) out.writeLong(b);
            }
            out.writeInt(models.size());
            Map<String, List<String>> byId = new java.util.TreeMap<String, List<String>>();
            for (String k : new java.util.TreeSet<String>(models.keySet())) {
                String id = k.substring(0, k.lastIndexOf('/'));
                if (!byId.containsKey(id)) byId.put(id, new ArrayList<String>());
                byId.get(id).add(k);
            }
            for (List<String> keys : byId.values()) {
                List<String> own = new ArrayList<String>();
                for (String k : keys) {
                    Model m = models.get(k);
                    String base = null;
                    int turn = 0;
                    for (String o : own) {
                        for (int t = 1; t < 4 && base == null; t++) if (same(models.get(o).turned(t), m)) {
                            base = o;
                            turn = t;
                        }
                        if (base != null) break;
                    }
                    out.writeUTF(k);
                    if (base != null) {
                        out.writeByte(1);
                        out.writeUTF(base);
                        out.writeByte(turn);
                        continue;
                    }
                    own.add(k);
                    out.writeByte(0);
                    out.writeInt(m.size());
                    for (int i = 0; i < m.size(); i++) {
                        out.writeShort(m.tex[i]);
                        for (int j = 0; j < 9; j++) out.writeFloat(m.p[i * 9 + j]);
                        for (int j = 0; j < 6; j++) out.writeFloat(m.uv[i * 6 + j]);
                    }
                }
            }
        } finally {
            d.end();
        }
    }

    // the same triangles in the same order, corners within 1/1024 block
    static boolean same(Model a, Model b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.tex.length; i++) if (a.tex[i] != b.tex[i]) return false;
        for (int i = 0; i < a.p.length; i++) if (Math.abs(a.p[i] - b.p[i]) > 1F / 1024) return false;
        for (int i = 0; i < a.uv.length; i++) if (Math.abs(a.uv[i] - b.uv[i]) > 1e-5F) return false;
        return true;
    }

    static int models() {
        load();
        return models.size();
    }
}
