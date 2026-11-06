package acup.gunfeel.water;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

// Low-poly models of NTM bullets for the water effects: a lathe body with 8 sides for bullets, slugs and flechettes,
// an icosahedron / octahedron for shot, stones and shards. Ported from the water demo; no Minecraft classes, so the
// tests run it as is. Real sizes come from assets/ntmgunfeel/water/ammo.tsv (mm); the size in the world follows the
// spent casing NTM drops for the round (its BulletConfig.casing): the bullet is to NTM's casing as the real bullet is
// to the real case, along the round and across it. A round without a casing (the .357, stones) is real size × FALLBACK.
public final class AmmoModels {
    // NTM's spent casing of a round: the CasingType's name and the scale NTM draws casings.hbm_mesh with
    public record Casing(String type, float sx, float sy, float sz) {}

    // CasingParticleGroup scales casings.hbm_mesh by ParticleSpentCasing.dScale, then by the casing's own scale
    static final double CASING_DRAW = 0.05;
    // casings.hbm_mesh, per CasingType: {length along Z, width} of what NTM draws (AmmoModelsTest reads the mesh);
    // SHOTGUN draws the hull and the brass head, its width is the hull's
    static final Map<String, double[]> CASING_MESH = Map.of(
            "STRAIGHT", new double[] {1.6, 0.6},
            "BOTTLENECK", new double[] {1.8, 0.6},
            "SHOTGUN", new double[] {2.371375, 0.898248});
    // the real case of a calibre, {diameter at the base mm, length mm} (the rim left out: NTM's mesh has none);
    // a shotgun's is the hull. b75 is no real round: its case is taken in NTM's own 2:1
    static final Map<String, double[]> CASE = Map.of(
            "p22", new double[] {5.74, 15.6},
            "p9", new double[] {9.93, 19.15},
            "m357", new double[] {9.63, 32.77},
            "m44", new double[] {11.61, 32.6},
            "r556", new double[] {9.6, 44.7},
            "r762", new double[] {11.95, 51.2},
            "bmg50", new double[] {20.4, 99.3},
            "b75", new double[] {20, 40},
            "g12", new double[] {20.2, 70},
            "g10", new double[] {21.2, 89});
    // no casing to go by: real size times this (NTM's casings are 1.3 to 3.1 times their real case, 1.8 the middle)
    static final double FALLBACK = 2;
    private static final int SIDES = 8;

    // calibre -> {diameter mm, length mm, nose profile}
    private static final Map<String, Object[]> CALG = Map.of(
            "p22", new Object[] {5.7, 10.0, "round"},
            "p9", new Object[] {9.0, 15.5, "round"},
            "m357", new Object[] {9.1, 15.0, "swc"},
            "m44", new Object[] {10.9, 18.0, "swc"},
            "r556", new Object[] {5.7, 23.0, "spitzer"},
            "r762", new Object[] {7.8, 29.0, "spitzer"},
            "bmg50", new Object[] {12.95, 58.0, "spitzer"},
            "b75", new Object[] {19.0, 45.0, "bolt"});

    // profiles as (radius / R, height / length) from the tail to the nose
    private static final Map<String, float[][]> NOSE = Map.of(
            "round", p(0, 0, .9, 0, 1, .06, 1, .5, .92, .68, .72, .84, .4, .96, 0, 1),
            "swc", p(0, 0, .9, 0, 1, .06, 1, .5, .96, .55, .72, .6, .64, .85, .52, 1, 0, 1),
            "spitzer", p(0, 0, .75, 0, 1, .15, 1, .52, .82, .7, .5, .86, .18, .97, 0, 1),
            "bolt", p(0, 0, .55, 0, .55, .1, 1, .12, 1, .68, .75, .84, .35, .96, 0, 1),
            "slug", p(0, 0, .85, 0, 1, .06, 1, .72, .85, .9, .55, 1, 0, 1),
            "flechette", p(0, 0, 1, 0, 1, .22, .3, .28, .3, .86, 0, 1));
    // the opened mushroom, 12 points
    private static final float[][] MUSH = p(0, 0, .85, 0, 1, .08, 1, .34, 1.06, .44, 1.38, .53, 1.75, .6, 1.68, .67,
            1.15, .69, .65, .7, .28, .7, 0, .66);

    private static final Map<String, Row> ROWS = load();
    private static final Map<String, Model> CACHE = new HashMap<>();
    private static final Map<String, Model> BUILT = new HashMap<>();

    private AmmoModels() {}

    private record Row(String id, String cal, String var, String shape, double d, int nose, int body) {}

    public static final class Model {
        public final String id;
        public final String shape; // the table's shape column as is
        public final boolean capsule;
        public final float radius, length;
        public final boolean byCasing; // sized by NTM's casing of the round, not by FALLBACK
        public final boolean expands;
        public final float expandAmount;
        public final int noseColor, bodyColor;
        public final float[] positions;
        public final float[] normals;
        public final int[] colors;
        public final @Nullable float[] mushroom;

        private Model(String id, String shape, boolean capsule, float radius, float length, boolean byCasing,
                float expandAmount, int noseColor, int bodyColor, float[] positions, float[] normals, int[] colors,
                @Nullable float[] mushroom) {
            this.id = id;
            this.shape = shape;
            this.capsule = capsule;
            this.radius = radius;
            this.length = length;
            this.byCasing = byCasing;
            this.expands = mushroom != null;
            this.expandAmount = expandAmount;
            this.noseColor = noseColor;
            this.bodyColor = bodyColor;
            this.positions = positions;
            this.normals = normals;
            this.colors = colors;
            this.mushroom = mushroom;
        }

        // positions with the bullet opened by e in [0, 1]: A + (X - A) * e; out is reused when it fits
        public float[] morph(float e, float[] out) {
            if (out == null || out.length != positions.length) {
                out = new float[positions.length];
            }
            if (mushroom == null) {
                System.arraycopy(positions, 0, out, 0, positions.length);
                return out;
            }
            for (int i = 0; i < positions.length; i++) {
                out[i] = positions[i] + (mushroom[i] - positions[i]) * e;
            }
            return out;
        }

        public int triangles() {
            return positions.length / 9;
        }
    }

    public static Collection<String> ids() {
        return Collections.unmodifiableCollection(ROWS.keySet());
    }

    // ammoPath: an NTM item path without "hbm:", e.g. ammo_standard_m44_jhp; casing: NTM's spent casing of that round,
    // null when it has none; scale: waterBodyScale. Null when the table has no such round
    public static @Nullable Model of(String ammoPath, @Nullable Casing casing, double scale) {
        String id = id(ammoPath);
        if (id == null) return null;
        Row row = ROWS.get(id);
        String key = id + '|' + casing + '|' + scale;
        synchronized (BUILT) {
            return BUILT.computeIfAbsent(key, k -> build(row, casing, scale));
        }
    }

    // the model at real size × FALLBACK (no casing, scale 1)
    public static @Nullable Model of(String ammoPath) {
        String id = id(ammoPath);
        if (id == null) return null;
        Row row = ROWS.get(id);
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(id, k -> build(row, null, 1));
        }
    }

    // the table's id of an item path, null when the table has no such round
    private static @Nullable String id(String ammoPath) {
        String id = ammoPath;
        if (id.startsWith("ammo_")) {
            id = id.substring(5);
        }
        if (!ROWS.containsKey(id)) {
            for (String prefix : new String[] {"standard_", "secret_"}) {
                if (id.startsWith(prefix) && ROWS.containsKey(id.substring(prefix.length()))) {
                    id = id.substring(prefix.length());
                    break;
                }
            }
        }
        return ROWS.containsKey(id) ? id : null;
    }

    private static float[][] p(double... v) {
        float[][] out = new float[v.length / 2][];
        for (int i = 0; i < out.length; i++) {
            out[i] = new float[] {(float) v[2 * i], (float) v[2 * i + 1]};
        }
        return out;
    }

    private static Map<String, Row> load() {
        Map<String, Row> rows = new TreeMap<>();
        try (InputStream in = AmmoModels.class.getResourceAsStream("/assets/ntmgunfeel/water/ammo.tsv")) {
            if (in == null) {
                throw new IllegalStateException("ammo.tsv is missing");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            r.readLine(); // header: id cal var proj shape d nose body
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split("\t", -1);
                rows.put(c[0], new Row(c[0], c[1], c[2], c[4], Double.parseDouble(c[5]), color(c[6]), color(c[7])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    private static int color(String s) {
        return Integer.parseInt(s.substring(1), 16);
    }

    // the nose of a bullet by its variant: hollow point, sharp AP / DU core, blunt soft point
    private static float[][] noseFor(String base, String v) {
        List<float[]> p = new ArrayList<>();
        float cut = switch (v) {
            case "jhp" -> .9F;
            case "ap", "du" -> .55F;
            case "sp" -> .6F;
            default -> Float.MAX_VALUE;
        };
        for (float[] q : NOSE.get(base)) {
            if (q[1] < cut) {
                p.add(q.clone());
            }
        }
        switch (v) {
            case "jhp" -> p.addAll(List.of(new float[] {.58F, .92F}, new float[] {.34F, .92F}, new float[] {0, .84F}));
            case "ap", "du" -> p.addAll(List.of(new float[] {.7F, .72F}, new float[] {.35F, .88F},
                    new float[] {.08F, .98F}, new float[] {0, 1}));
            case "sp" -> p.addAll(List.of(new float[] {.95F, .72F}, new float[] {.8F, .86F}, new float[] {.5F, .97F},
                    new float[] {0, .99F}));
            default -> {}
        }
        return p.toArray(new float[0][]);
    }

    private static float[][] resample(float[][] p, int n) {
        float[] len = new float[p.length];
        for (int i = 1; i < p.length; i++) {
            len[i] = len[i - 1] + (float) Math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1]);
        }
        float[][] out = new float[n][];
        for (int k = 0; k < n; k++) {
            float t = len[len.length - 1] * k / (n - 1);
            int i = 1;
            while (i < p.length - 1 && len[i] < t) {
                i++;
            }
            float d = len[i] - len[i - 1];
            float u = (t - len[i - 1]) / (d == 0 ? 1 : d);
            out[k] = new float[] {p[i - 1][0] + (p[i][0] - p[i - 1][0]) * u, p[i - 1][1] + (p[i][1] - p[i - 1][1]) * u};
        }
        return out;
    }

    // three.js LatheGeometry (8 segments), non-indexed, moved so the middle is the origin and the nose looks to -Z
    private static float[] lathe(float[][] prof, float r, float len) {
        int n = prof.length;
        float[] out = new float[SIDES * (n - 1) * 18];
        int o = 0;
        for (int i = 0; i < SIDES; i++) {
            for (int j = 0; j < n - 1; j++) {
                int[][] quad = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}}; // a b c d
                int[] tri = {0, 1, 3, 2, 3, 1};
                for (int t : tri) {
                    int seg = quad[t][0], k = quad[t][1];
                    double phi = (double) seg / SIDES * Math.PI * 2;
                    float x = prof[k][0] * r, y = prof[k][1] * len - len / 2;
                    out[o++] = (float) (x * Math.sin(phi));
                    out[o++] = (float) (x * Math.cos(phi));
                    out[o++] = -y;
                }
            }
        }
        return out;
    }

    private static float[] sphere(String shape) {
        float t = (float) ((1 + Math.sqrt(5)) / 2);
        float[][] v;
        int[] idx;
        if (shape.equals("shard")) {
            v = new float[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            idx = new int[] {0, 2, 4, 0, 4, 3, 0, 3, 5, 0, 5, 2, 1, 2, 5, 1, 5, 3, 1, 3, 4, 1, 4, 2};
        } else {
            v = new float[][] {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t},
                    {0, 1, -t}, {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
            idx = new int[] {0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11, 1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6,
                    7, 1, 8, 3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9, 4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1};
        }
        List<float[]> tris = new ArrayList<>();
        for (int i = 0; i < idx.length; i += 3) {
            tris.add(flat(v, idx, i));
        }
        if (!shape.equals("shard") && !shape.equals("pebble")) { // detail 1: every face into four
            List<float[]> sub = new ArrayList<>();
            for (float[] f : tris) {
                float[] a = {f[0], f[1], f[2]}, b = {f[3], f[4], f[5]}, c = {f[6], f[7], f[8]};
                float[] ab = mid(a, b), bc = mid(b, c), ca = mid(c, a);
                sub.add(flat(a, ab, ca));
                sub.add(flat(b, bc, ab));
                sub.add(flat(c, ca, bc));
                sub.add(flat(ab, bc, ca));
            }
            tris = sub;
        }
        float[] out = new float[tris.size() * 9];
        int o = 0;
        for (float[] f : tris) {
            for (int k = 0; k < 9; k += 3) {
                if (shape.equals("shard")) { // the octahedron has no projection: it stays as is, radius 1
                    out[o++] = f[k];
                    out[o++] = f[k + 1];
                    out[o++] = f[k + 2];
                } else {
                    float l = (float) Math.sqrt(f[k] * f[k] + f[k + 1] * f[k + 1] + f[k + 2] * f[k + 2]);
                    out[o++] = f[k] / l;
                    out[o++] = f[k + 1] / l;
                    out[o++] = f[k + 2] / l;
                }
            }
        }
        return out;
    }

    private static float[] flat(float[][] v, int[] idx, int i) {
        float[] a = v[idx[i]], b = v[idx[i + 1]], c = v[idx[i + 2]];
        return flat(a, b, c);
    }

    private static float[] flat(float[] a, float[] b, float[] c) {
        return new float[] {a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]};
    }

    private static float[] mid(float[] a, float[] b) {
        return new float[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2};
    }

    // blocks per real mm {across the round, along it}: NTM's casing of the round over the real case; FALLBACK without one
    static double @Nullable [] perMm(String cal, @Nullable Casing c) {
        double[] mesh = c == null ? null : CASING_MESH.get(c.type());
        double[] real = CASE.get(cal);
        if (c == null || mesh == null || real == null) return null;
        double across = CASING_DRAW * Math.max(c.sx(), c.sy()) * mesh[1] / real[0];
        double along = CASING_DRAW * c.sz() * mesh[0] / real[1];
        if (!(across > 0) || !(along > 0) || !Double.isFinite(across) || !Double.isFinite(along)) return null;
        return new double[] {across, along};
    }

    private static Model build(Row a, @Nullable Casing casing, double scale) {
        double[] per = perMm(a.cal, casing);
        boolean byCasing = per != null;
        if (per == null) per = new double[] {FALLBACK / 1000, FALLBACK / 1000};
        double across = per[0] * scale, along = per[1] * scale;
        boolean capsule = a.shape.equals("bullet") || a.shape.equals("slug") || a.shape.equals("flechette")
                || a.shape.equals("mini");
        int count;
        float r, len;
        float[] pos, mush = null, normals;
        float amt = 0;
        if (capsule) {
            double d, l;
            String base;
            if (a.shape.equals("bullet")) {
                Object[] g = CALG.get(a.cal);
                d = (Double) g[0];
                l = (Double) g[1];
                base = (String) g[2];
            } else if (a.shape.equals("mini")) { // uranium mini-bullets of g10_du
                d = 6;
                l = 14;
                base = "spitzer";
            } else if (a.shape.equals("slug")) {
                d = a.d;
                l = a.d * 0.92;
                base = "slug";
            } else {
                d = 3;
                l = 30;
                base = "flechette";
            }
            r = (float) (d / 2 * across);
            len = (float) (l * along);
            boolean bullet = a.shape.equals("bullet");
            float[][] prof = noseFor(base, bullet || a.shape.equals("mini") ? a.var : "");
            if (bullet && (a.var.equals("jhp") || a.var.equals("sp"))) {
                amt = a.var.equals("jhp") ? 1 : 0.47F;
                prof = resample(prof, MUSH.length);
                float[][] m = new float[prof.length][];
                for (int i = 0; i < m.length; i++) {
                    m[i] = new float[] {prof[i][0] + (MUSH[i][0] - prof[i][0]) * amt,
                            prof[i][1] + (MUSH[i][1] - prof[i][1]) * amt};
                }
                mush = lathe(m, r, len);
            }
            pos = lathe(prof, r, len);
            normals = smoothNormals(pos);
        } else {
            r = (float) (a.d / 2 * across);
            len = 2 * r;
            pos = sphere(a.shape);
            normals = new float[pos.length];
            float[] s = a.shape.equals("shard") ? new float[] {.7F, .5F, 1.5F}
                    : a.shape.equals("pebble") ? new float[] {1.1F, .8F, 1} : new float[] {1, 1, 1};
            for (int i = 0; i < pos.length; i += 3) {
                float x = pos[i], y = pos[i + 1], z = pos[i + 2];
                float l = (float) Math.sqrt(x * x + y * y + z * z);
                normals[i] = x / l;
                normals[i + 1] = y / l;
                normals[i + 2] = z / l;
                pos[i] = x * r * s[0];
                pos[i + 1] = y * r * s[1];
                pos[i + 2] = z * r * s[2];
            }
        }
        int body = a.shape.equals("mini") ? 0x3f5a3a : a.body;
        count = pos.length / 3;
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            colors[i] = !capsule ? a.nose : (-pos[i * 3 + 2] > len * 0.12F ? a.nose : body);
        }
        return new Model(a.id, a.shape, capsule, r, len, byCasing, amt, a.nose, body, pos, normals, colors, mush);
    }

    // face normals summed (by area) over vertices that share a position, so the body looks smooth
    private static float[] smoothNormals(float[] pos) {
        Map<Long, float[]> sum = new HashMap<>();
        for (int t = 0; t < pos.length; t += 9) {
            float ux = pos[t + 3] - pos[t], uy = pos[t + 4] - pos[t + 1], uz = pos[t + 5] - pos[t + 2];
            float vx = pos[t + 6] - pos[t], vy = pos[t + 7] - pos[t + 1], vz = pos[t + 8] - pos[t + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int k = 0; k < 9; k += 3) {
                float[] s = sum.computeIfAbsent(key(pos, t + k), q -> new float[3]);
                s[0] += nx;
                s[1] += ny;
                s[2] += nz;
            }
        }
        float[] out = new float[pos.length];
        for (int i = 0; i < pos.length; i += 3) {
            float[] s = sum.get(key(pos, i));
            float l = (float) Math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
            if (l > 1e-12F) {
                out[i] = s[0] / l;
                out[i + 1] = s[1] / l;
                out[i + 2] = s[2] / l;
            } else {
                out[i + 2] = -1;
            }
        }
        return out;
    }

    private static long key(float[] p, int i) {
        long a = Math.round(p[i] * 1e5), b = Math.round(p[i + 1] * 1e5), c = Math.round(p[i + 2] * 1e5);
        return (a * 1_000_003L + b) * 1_000_003L + c;
    }
}
