package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.hbm.render.loader.GroupObject;
import com.hbm.render.loader.ObjMesh;

import acup.gunfeel.ai.client.SuitBendAccess;

// NTM's suit meshes cut at the bots' knee and elbow (SuitBend): straight, the halves are the mesh; bent, the band
// closes the cut, and the bot's skin stays inside the suit as much as it does straight. Against the other way of
// closing it (the cut's vertices turned half way, as bendy-lib bends a box) and against no bend at all (the mesh rigid
// on the thigh, as NTM draws it).
class SuitBendTest {
    static final String[] MESHES = { "t51", "taurun", "ajr", "remnant", "ncrpa", "bismuth", "bj", "envsuit", "hev", "fau", "dnt",
            "trenchmaster", "steamsuit", "bnuuy" };
    static final int STRIDE = 8, QUAD = 32;

    record Limb(String suit, GroupObject group, List<GroupObject> with, boolean leg, float side) {
        /** the joint's pivot in the mesh's frame: the knee on the leg's middle line, the elbow on the arm's */
        Vector3f pivot() {
            return leg ? new Vector3f(1.9F * side, 18F, 0F) : new Vector3f(6F * side, 6F, 0F);
        }

        float plane() {
            return leg ? 18F : 6F;
        }
    }

    static List<Limb> limbs() throws Exception {
        List<Limb> out = new ArrayList<>();
        for (String m : MESHES)
            try (var s = SuitBendTest.class.getResourceAsStream("/assets/hbm/models/armor/" + m + ".hbm_mesh")) {
                GroupObject[] all = ObjMesh.read(s, m);
                for (GroupObject g : all) {
                    String n = g.name.toLowerCase(Locale.ROOT);
                    boolean arm = n.endsWith("arm"), leg = n.endsWith("leg");
                    if (!arm && !leg) continue;
                    String side = n.startsWith("left") ? "left" : "right";
                    List<GroupObject> with = new ArrayList<>(List.of(g));
                    // the boot (another piece, the same limb) bends with the leg
                    if (leg) for (GroupObject b : all) {
                        String bn = b.name.toLowerCase(Locale.ROOT);
                        if (bn.startsWith(side) && (bn.endsWith("boot") || bn.endsWith("foot"))) with.add(b);
                    }
                    out.add(new Limb(m, g, with, leg, side.equals("left") ? 1F : -1F));
                }
            }
        return out;
    }

    static double area(float[] q) {
        double a = 0;
        for (int f = 0; f < q.length; f += QUAD) a += tri(q, f, 0, 1, 2) + tri(q, f, 0, 2, 3);
        return a;
    }

    static double tri(float[] q, int f, int i, int j, int k) {
        Vector3f a = v(q, f + i * STRIDE), b = v(q, f + j * STRIDE), c = v(q, f + k * STRIDE);
        return b.sub(a, new Vector3f()).cross(c.sub(a, new Vector3f())).length() / 2;
    }

    static Vector3f v(float[] q, int i) {
        return new Vector3f(q[i], q[i + 1], q[i + 2]);
    }

    @Test
    void straightTheHalvesAreTheMesh() throws Exception {
        int cut = 0;
        for (Limb l : limbs()) for (GroupObject g : l.with()) {
            var c = SuitBendAccess.cut(g, l.plane());
            float[] q = g.quads(true);
            double halves = (c.upper() == null ? 0 : area(c.upper().quads(true))) + (c.lower() == null ? 0 : area(c.lower().quads(true)));
            assertEquals(area(q), halves, 1e-3 * Math.max(1, area(q)), l.suit() + " " + g.name);
            // each half wholly on its side of the plane
            if (c.upper() != null) for (int i = 1; i < c.upper().quads(true).length; i += STRIDE) assertTrue(c.upper().quads(true)[i] <= l.plane() + 1e-3F);
            if (c.lower() != null) for (int i = 1; i < c.lower().quads(true).length; i += STRIDE) assertTrue(c.lower().quads(true)[i] >= l.plane() - 1e-3F);
            if (c.upper() != null && c.lower() != null) cut++;
        }
        // every suit's legs or arms reach over a joint
        assertTrue(cut >= 2 * 14, "cut groups " + cut);
    }

    /**
     * The bot's knee and elbow found in the mesh's frame as NTM poses the mesh on the limb (ArmorWorldRenderer: the
     * limb, its fit to the whole straight limb, the bone's anchor and pivot): the cut on the joint, the turn the shin's
     */
    @Test
    void theJointInTheMeshsFrame() {
        var bot = acup.gunfeel.ai.client.BotModel.split(BotModelTest.vanilla(), ModelPoseCaptureTest.PARTS);
        // leg: fit anchor (0, 12, 0) the whole leg's bottom, bone anchor (0, 12, 0), pivot (1.9, 12, 0); arm: the
        // whole arm's top (1, -2, 0), bone anchor (1, -2, 0), pivot (5, 2, 0)
        record Case(String part, float[] fit, float[] anchor, float[] pivot, float plane, float pivotX, float turn) {
        }
        for (Case c : List.of(new Case("left_leg", new float[] { 0, 12, 0 }, new float[] { 0, 12, 0 }, new float[] { 1.9F, 12, 0 }, 18F, 1.9F, 0.7F),
                new Case("left_arm", new float[] { 1, -2, 0 }, new float[] { 1, -2, 0 }, new float[] { 5, 2, 0 }, 6F, 6F, -1.1F))) {
            var limb = bot.getChild(c.part());
            limb.xRot = 0.3F;
            var lower = acup.gunfeel.ai.client.BotModel.lower(limb);
            lower.xRot = c.turn();
            var parent = new Matrix4f().translation(0.5F, 1.2F, -0.3F).rotateY(0.4F);
            var pose = new com.mojang.blaze3d.vertex.PoseStack();
            pose.last().pose().set(parent);
            limb.translateAndRotate(pose);
            pose.translate(c.fit()[0] / 16F, c.fit()[1] / 16F, c.fit()[2] / 16F);
            pose.translate(-c.anchor()[0] / 16F, -c.anchor()[1] / 16F, -c.anchor()[2] / 16F);
            pose.scale(1 / 16F, 1 / 16F, 1 / 16F);
            pose.translate(-c.pivot()[0], -c.pivot()[1], -c.pivot()[2]);
            var bent = acup.gunfeel.ai.client.SuitBend.bent(parent, limb, pose.last().pose());
            assertEquals(c.plane(), bent.plane(), 1e-4F, c.part());
            assertEquals(c.pivotX(), bent.pivot().x, 1e-4F, c.part());
            // the lower half drawn by the mesh's pose times the turn is where the shin's own pose puts it
            var shin = new com.mojang.blaze3d.vertex.PoseStack();
            shin.last().pose().set(parent);
            limb.translateAndRotate(shin);
            lower.translateAndRotate(shin);
            Vector3f foot = new Vector3f(bent.pivot()).add(0, 6, 0);
            Vector3f drawn = new Matrix4f(pose.last().pose()).mul(bent.turn()).transformPosition(new Vector3f(foot));
            // the foot in the shin's frame: 6 px below the joint, the joint at the shin's origin
            Vector3f own = shin.last().pose().transformPosition(new Vector3f(0, 6 / 16F, 0));
            assertTrue(drawn.distance(own) < 1e-4F, c.part() + " " + drawn + " " + own);
        }
    }

    // ---------------------------------------------------------------- bent

    enum Way { RIGID, BAND, ARC, HALFWAY }

    /** the knee bends the shin back (x turn +), the elbow the forearm up (x turn -) */
    static Matrix4f turn(Limb l, float degrees) {
        Vector3f p = l.pivot();
        float a = (float) Math.toRadians(l.leg() ? degrees : -degrees);
        return new Matrix4f().translation(p).rotateX(a).translate(-p.x, -p.y, -p.z);
    }

    /** the bent surface's triangles, 9 floats each */
    static List<float[]> surface(Limb l, Way way, Matrix4f d) {
        List<float[]> tris = new ArrayList<>();
        for (GroupObject g : l.with()) surface(tris, g, l, way, d);
        return tris;
    }

    static void surface(List<float[]> tris, GroupObject g, Limb l, Way way, Matrix4f d) {
        Matrix4f none = new Matrix4f(), half = SuitBendAccess.partway(d, l.pivot(), 0.5F);
        if (way == Way.RIGID) {
            quads(tris, g.quads(true), v -> none);
            return;
        }
        var c = SuitBendAccess.cut(g, l.plane());
        boolean halfway = way == Way.HALFWAY;
        if (c.upper() != null) quads(tris, c.upper().quads(true), v -> halfway && Math.abs(v.y - l.plane()) < 1e-3F ? half : none);
        if (c.lower() != null) quads(tris, c.lower().quads(true), v -> halfway && Math.abs(v.y - l.plane()) < 1e-3F ? half : d);
        if (!halfway) {
            float[] s = c.seam();
            int k = way == Way.ARC ? SuitBendAccess.SEGMENTS : 1;
            for (int e = 0; e < s.length; e += 2 * STRIDE)
                // the drawing's band: where the cut opens
                for (int r = 0; r < k && (way != Way.ARC || SuitBendAccess.opens(d, s, e)); r++) {
                    Matrix4f m0 = SuitBendAccess.partway(d, l.pivot(), (float) r / k), m1 = r + 1 == k ? d : SuitBendAccess.partway(d, l.pivot(), (float) (r + 1) / k);
                    Vector3f a = v(s, e), b = v(s, e + STRIDE);
                    Vector3f bu = m0.transformPosition(new Vector3f(b)), au = m0.transformPosition(new Vector3f(a)),
                            al = m1.transformPosition(new Vector3f(a)), bl = m1.transformPosition(new Vector3f(b));
                    tris.add(new float[] { bu.x, bu.y, bu.z, au.x, au.y, au.z, al.x, al.y, al.z });
                    tris.add(new float[] { bu.x, bu.y, bu.z, al.x, al.y, al.z, bl.x, bl.y, bl.z });
                }
        }
    }

    static void quads(List<float[]> tris, float[] q, java.util.function.Function<Vector3f, Matrix4f> by) {
        for (int f = 0; f < q.length; f += QUAD) {
            Vector3f[] c = new Vector3f[4];
            for (int i = 0; i < 4; i++) {
                Vector3f p = v(q, f + i * STRIDE);
                c[i] = by.apply(p).transformPosition(p, new Vector3f());
            }
            tris.add(new float[] { c[0].x, c[0].y, c[0].z, c[1].x, c[1].y, c[1].z, c[2].x, c[2].y, c[2].z });
            tris.add(new float[] { c[0].x, c[0].y, c[0].z, c[2].x, c[2].y, c[2].z, c[3].x, c[3].y, c[3].z });
        }
    }

    /** the farthest the surface is along the ray, or NaN */
    static double far(List<float[]> tris, Vector3f o, Vector3f d) {
        double best = Double.NaN;
        Vector3f e1 = new Vector3f(), e2 = new Vector3f(), p = new Vector3f(), t = new Vector3f(), q = new Vector3f();
        for (float[] tr : tris) {
            e1.set(tr[3] - tr[0], tr[4] - tr[1], tr[5] - tr[2]);
            e2.set(tr[6] - tr[0], tr[7] - tr[1], tr[8] - tr[2]);
            d.cross(e2, p);
            float det = e1.dot(p);
            if (Math.abs(det) < 1e-9F) continue;
            t.set(o.x - tr[0], o.y - tr[1], o.z - tr[2]);
            float u = t.dot(p) / det;
            if (u < 0 || u > 1) continue;
            t.cross(e1, q);
            float w = d.dot(q) / det;
            if (w < 0 || u + w > 1) continue;
            float s = e2.dot(q) / det;
            if (s > 0 && (Double.isNaN(best) || s > best)) best = s;
        }
        return best;
    }

    /** where the ray leaves the skin's boxes: the upper one, the lower one and its overlap turned by d */
    static double skin(Limb l, Matrix4f d, Vector3f o, Vector3f dir) {
        Vector3f p = l.pivot();
        float x0 = p.x - 2, x1 = p.x + 2, y = l.plane();
        float top = l.leg() ? y - 6 : y - 6, bottom = y + 6;
        double out = exit(new Matrix4f(), x0, top, -2, x1, y, 2, o, dir);
        out = Math.max(out, exit(d, x0, y, -2, x1, bottom, 2, o, dir));
        out = Math.max(out, exit(d, x0 + 0.01F, y - 2, -2 + 0.01F, x1 - 0.01F, y, 2 - 0.01F, o, dir));
        return out;
    }

    static double exit(Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, Vector3f o, Vector3f dir) {
        Matrix4f inv = m.invert(new Matrix4f());
        Vector3f lo = inv.transformPosition(new Vector3f(o)), ld = inv.transformDirection(new Vector3f(dir));
        double tin = -1e9, tout = 1e9;
        float[] mn = { x0, y0, z0 }, mx = { x1, y1, z1 }, oo = { lo.x, lo.y, lo.z }, dd = { ld.x, ld.y, ld.z };
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dd[i]) < 1e-9) {
                if (oo[i] < mn[i] || oo[i] > mx[i]) return 0;
                continue;
            }
            double a = (mn[i] - oo[i]) / dd[i], b = (mx[i] - oo[i]) / dd[i];
            tin = Math.max(tin, Math.min(a, b));
            tout = Math.min(tout, Math.max(a, b));
        }
        return tout >= Math.max(tin, 0) ? tout : 0;
    }

    /** the most the skin sticks out of the suit, px, over rays from the joint across the limb, all round it */
    static double poke(Limb l, Way way, float degrees) {
        Matrix4f d = turn(l, degrees);
        var tris = surface(l, way, d);
        double worst = 0;
        Vector3f p = l.pivot();
        for (float dx = -1.5F; dx <= 1.5F; dx += 1F)
            for (int a = 0; a < 360; a += 5) {
                Vector3f o = new Vector3f(p.x + dx, p.y, p.z);
                double r = Math.toRadians(a);
                Vector3f dir = new Vector3f(0, (float) Math.cos(r), (float) Math.sin(r));
                double suit = far(tris, o, dir);
                if (Double.isNaN(suit)) continue;
                worst = Math.max(worst, skin(l, d, o, dir) - suit);
            }
        return worst;
    }

    @Test
    void bentTheSkinStaysInTheSuit() throws Exception {
        float[] angles = { 0, 30, 60, 90, 120 };
        StringBuilder table = new StringBuilder();
        double[] sum = new double[Way.values().length], sum120 = new double[Way.values().length];
        int rows = 0;
        List<String> worse = new ArrayList<>();
        for (Limb l : limbs()) {
            if (!l.group().name.toLowerCase(Locale.ROOT).startsWith("left")) continue;
            int seam = 0;
            for (GroupObject g : l.with()) seam += SuitBendAccess.cut(g, l.plane()).seam().length / 16;
            table.append(String.format(Locale.ROOT, "SUITBEND %-12s %-9s seam %3d", l.suit(), l.group().name, seam));
            for (Way w : Way.values())
                for (float a : angles) {
                    double px = poke(l, w, a);
                    if (a == 90) sum[w.ordinal()] += px;
                    if (a == 120) sum120[w.ordinal()] += px;
                    table.append(String.format(Locale.ROOT, " %s%.0f=%.2f", w.name().charAt(0), a, px));
                }
            rows++;
            table.append('\n');
            // the band keeps the skin in no worse than the rigid mesh does, at any bend
            // NTM's bismuth is crystals on the skin, not a suit round it: what of the skin a ray meets is chance
            if (!l.suit().equals("bismuth")) for (float a : angles)
                if (poke(l, Way.ARC, a) > Math.max(poke(l, Way.RIGID, a), poke(l, Way.ARC, 0)) + 0.05) worse.add(l.suit() + " " + l.group().name + " " + a);
        }
        System.out.print(table);
        System.out.printf(Locale.ROOT, "SUITBEND mean at 90: rigid %.2f band %.2f arc %.2f halfway %.2f px over %d limbs%n", sum[0] / rows, sum[1] / rows, sum[2] / rows, sum[3] / rows, rows);
        System.out.printf(Locale.ROOT, "SUITBEND mean at 120: rigid %.2f band %.2f arc %.2f halfway %.2f px%n", sum120[0] / rows, sum120[1] / rows, sum120[2] / rows, sum120[3] / rows);
        assertTrue(sum[Way.ARC.ordinal()] / rows < 1.0 && sum[Way.ARC.ordinal()] < sum[Way.RIGID.ordinal()] / 3, "mean at 90");
        assertTrue(worse.isEmpty(), "the arc lets the skin out more than the rigid mesh and than straight: " + worse);
    }
}
