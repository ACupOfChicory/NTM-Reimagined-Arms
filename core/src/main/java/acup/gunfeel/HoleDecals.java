package acup.gunfeel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import acup.gunfeel.host.render.Blend;
import acup.gunfeel.host.render.CameraFrame;
import acup.gunfeel.host.render.Intent;
import acup.gunfeel.host.render.Layouts;
import acup.gunfeel.host.render.MaterialSpec;
import acup.gunfeel.host.render.Part;
import acup.gunfeel.host.render.RenderPort;
import acup.gunfeel.host.render.RenderSink;
import acup.gunfeel.host.render.VertexWriter;

// Bullet holes drawn (the version makes them: Tracers). Three ways, by how the frame is laid (render.md §2):
// - kept on the graphics card in batches (holeCache): drawn after the solid features (Intent.AFTER_OPAQUE), and the
//   late ones after the translucent terrain (OVER_GLASS);
// - sent each frame into the game's queue (fading, on glass under the transparency chain, holeCache off), by groups
//   of one texture, late or not, culled or not (QUEUE; the late ones OVER_GLASS);
// - without the chain the late ones sent each frame are drawn over the glass by the version's own pass (OVER_GLASS).
// A late hole on a block of the translucent terrain (under) is drawn early as well: its back under the glass
// (HolePasses). The numbers are written as the version wrote them before the port, expression for expression.
final class HoleDecals {
    // what only the version knows of a hole
    interface Host {
        // a late hole on a block of the translucent terrain (asked each time: the models loaded anew may draw it otherwise)
        boolean under(HoleDecal h);

        // a batch drawn (the client check counts them)
        void batchDrawn();

        // the hole bench's pixel probe: wanted this frame, and run with the reference and the cached drawing
        boolean wantProbe();

        void probe(Runnable reference, Runnable cached);
    }

    static final int KINDS = Decals.COUNT, SCRATCH_TURN = 1000, VARIANT_SHIFT = 11, TURN_MASK = (1 << VARIANT_SHIFT) - 1;
    // the shader of the holes, as the version's pipelines name it
    static final String PROGRAM = "ntmgunfeel:core/bullet_hole", NAME = "ntmgunfeel_bullet_hole", LATE_NAME = "ntmgunfeel_bullet_hole_late";
    private static final int BATCH_HOLES = 128;

    // every hole, in the order made
    static final ArrayDeque<HoleDecal> HOLES = new ArrayDeque<>();

    private static RenderPort port = RenderPort.MISSING;
    private static Host host;
    // by group: kind + KINDS * ((late ? 1 : 0) | (cull ? 2 : 0))
    private static final int[] MATERIALS = new int[4 * KINDS];
    // the holes sent each frame into the game's queue, by group, and their parts
    @SuppressWarnings("unchecked")
    private static final List<HoleDecal>[] QUEUED = new List[4 * KINDS];
    private static final int[] QUEUED_PARTS = new int[4 * KINDS];
    // the late ones drawn by the version's own pass, by kind + (cull ? KINDS : 0), and their parts
    @SuppressWarnings("unchecked")
    private static final List<HoleDecal>[] LATE = new List[2 * KINDS];
    private static final int[] LATE_PARTS = new int[2 * KINDS];
    // what the parts read: set by their drawer when it sends them (the queue's parts are written later in the frame)
    private static double queuedX, queuedY, queuedZ, lateX, lateY, lateZ;
    private static long queuedNow, queuedLife, lateNow, lateLife;

    private HoleDecals() {
    }

    // once, before any hole is drawn: the materials, parts and drawers
    static void init(RenderPort renderPort, Host versionHost) {
        port = renderPort;
        host = versionHost;
        for (int g = 0; g < 4 * KINDS; g++) {
            int kind = g % KINDS;
            boolean late = (g / KINDS & 1) != 0, cull = (g / KINDS & 2) != 0;
            MATERIALS[g] = port.material(material(kind, late, cull));
            QUEUED[g] = new ArrayList<>();
            List<HoleDecal> list = QUEUED[g];
            QUEUED_PARTS[g] = port.part(out -> write(out, list, queuedX, queuedY, queuedZ, queuedNow, queuedLife));
        }
        for (int g = 0; g < 2 * KINDS; g++) {
            LATE[g] = new ArrayList<>();
            List<HoleDecal> list = LATE[g];
            LATE_PARTS[g] = port.part(out -> write(out, list, lateX, lateY, lateZ, lateNow, lateLife));
        }
        port.drawer(Intent.AFTER_OPAQUE, HoleDecals::afterOpaque);
        port.drawer(Intent.QUEUE, HoleDecals::queue);
        port.drawer(Intent.OVER_GLASS, HoleDecals::overGlass);
    }

    // a hole's material: like entity_translucent, no depth written, a small depth bias (HoleRender)
    static MaterialSpec material(int kind, boolean late, boolean cull) {
        return new MaterialSpec(late ? LATE_NAME : NAME, PROGRAM, Layouts.DECAL, Blend.TRANSLUCENT, 0.1F, true, false, 1.0F, 10.0F, cull,
                "ntmgunfeel:textures/effect/" + Decals.file(kind) + ".png", true, true);
    }

    static long life() {
        return (long) (Math.max(0.1F, Config.holeLife) * 1e9);
    }

    static boolean any() {
        return !HOLES.isEmpty() || !BATCHES.isEmpty();
    }

    // the holes gone by age
    static void expire(long now, long life) {
        while (!HOLES.isEmpty() && now - HOLES.peekFirst().born > life) HOLES.removeFirst();
    }

    // every hole gone (a world left, /gunfeel clearholes)
    static void clear() {
        HOLES.clear();
        for (Batch b : BATCHES) b.close();
        BATCHES.clear();
        OPEN.clear();
    }

    // ---- this frame's batches and groups, as the frame's queue is filled (the QUEUE drawer, first)

    // false: nothing to send this frame
    private static boolean prepare(long now, long life, double camX, double camY, double camZ, boolean lateApart) {
        for (List<HoleDecal> l : QUEUED) l.clear();
        long ps = Perf.start();
        batch(now, life, lateApart);
        if (HOLES.isEmpty() || !Config.bulletHoles) return false;
        // the rest (fading, on moving parts, on glass, holeCache off) sent this frame, sorted in one pass
        for (HoleDecal h : UNBATCHED) {
            QUEUED[h.kind + KINDS * ((h.late ? 1 : 0) | (h.cull ? 2 : 0))].add(h);
            // a late hole drawn early too: a copy under the translucent glass (HolePasses), as the batches have it,
            // when the camera is behind it
            if (h.late && HolePasses.underCopy(host.under(h), towardCamera(h, camX, camY, camZ))) QUEUED[h.kind + KINDS * (h.cull ? 2 : 0)].add(h);
        }
        Perf.add(Perf.SORT, ps);
        queuedX = camX;
        queuedY = camY;
        queuedZ = camZ;
        queuedNow = now;
        queuedLife = life;
        return true;
    }

    // which holes are in which batch, then the groups not late into the game's queue; the version has run its own
    // checks on the holes just before (their blocks, the moving parts). Under the transparency chain the late holes
    // are sent each frame (lateApart), drawn with the translucent terrain
    private static void queue(RenderSink sink) {
        CameraFrame f = sink.frame();
        if (!prepare(f.nanos(), life(), f.camX(), f.camY(), f.camZ(), HoleBenchMetrics.legacy() || f.translucency() == CameraFrame.VANILLA_CHAIN)) return;
        for (int g = 0; g < 4 * KINDS; g++) {
            if ((g / KINDS & 1) != 0 || QUEUED[g].isEmpty()) continue;
            sink.queue(MATERIALS[g], QUEUED_PARTS[g], 0);
        }
    }

    // holes on glass: under the transparency chain the late groups sent each frame go into the queue after the
    // translucent terrain (the version's place); without it, after the clouds and rain, which show through the
    // glass behind them: the late batches, then the late holes sent each frame by the version's own pass
    private static void overGlass(RenderSink sink) {
        if (sink.frame().translucency() == CameraFrame.VANILLA_CHAIN) {
            for (int g = 0; g < 4 * KINDS; g++) {
                if ((g / KINDS & 1) == 0 || QUEUED[g].isEmpty()) continue;
                sink.queue(MATERIALS[g], QUEUED_PARTS[g], 0);
            }
            return;
        }
        if (HOLES.isEmpty() || !Config.bulletHoles) return;
        long pl = Perf.start();
        try {
            if (HoleBenchMetrics.CHECK_MOTION && host.wantProbe()) try {
                host.probe(() -> lateHoles(sink, true), () -> {
                    drawBatches(sink, true);
                    lateHoles(sink, false);
                });
            } catch (RuntimeException ex) {
                HoleBenchMetrics.failure = "Проверка изображения: " + ex;
            }
            long cached = Perf.start();
            drawBatches(sink, true);
            Perf.add(Perf.HOLE_DRAW, cached);
            lateHoles(sink, false);
        } finally {
            Perf.add(Perf.LATE, pl);
        }
    }

    private static void lateHoles(RenderSink sink, boolean reference) {
        CameraFrame f = sink.frame();
        double cx = f.camX(), cy = f.camY(), cz = f.camZ();
        // (the clock read here, as it was before the port: the version's frame reads it when asked at this place)
        long now = f.nanos();
        long life = life();
        for (List<HoleDecal> l : LATE) l.clear();
        for (HoleDecal h : HOLES) {
            if (!h.late || !reference && h.batch != null) continue;
            LATE[h.kind + (h.cull ? KINDS : 0)].add(h);
        }
        lateX = cx;
        lateY = cy;
        lateZ = cz;
        lateNow = now;
        lateLife = life;
        for (int group = 0; group < 2 * KINDS; group++) {
            List<HoleDecal> list = LATE[group];
            if (list.isEmpty()) continue;
            int kind = group % KINDS;
            boolean cull = group >= KINDS;
            int vertices = 0;
            for (HoleDecal h : list) vertices += h.mesh != null ? h.mesh.length / 2 + 8 : 4 * 256;
            sink.queue(MATERIALS[kind + KINDS * (1 | (cull ? 2 : 0))], LATE_PARTS[group], vertices);
        }
    }

    // a group's holes, when the version writes its part
    private static void write(VertexWriter out, List<HoleDecal> list, double cx, double cy, double cz, long now, long life) {
        long pd = Perf.start();
        drawHoles(out, list, cx, cy, cz, now, life);
        Perf.add(Perf.HOLE_DRAW, pd);
        Perf.add(Perf.ONE, pd);
        double fadeFrom = Math.max(0.0, life / 1e9 - 3.0);
        for (HoleDecal h : list) Perf.oneByOne(h.late ? Perf.ON_GLASS : (now - h.born) / 1e9 >= fadeFrom ? Perf.FADING : h.moving ? Perf.MOVING : Perf.OTHER);
    }

    static void drawHoles(VertexWriter out, List<HoleDecal> list, double cx, double cy, double cz, long now, long life) {
        for (HoleDecal h : list) {
            double age = (now - h.born) / 1e9;
            double fadeFrom = Math.max(0.0, life / 1e9 - 3.0);
            int alpha = (int) (255 * Math.clamp(1.0 - (age - fadeFrom) / 3.0, 0.0, 1.0));
            if (alpha <= 0) continue;
            float half = h.size * 0.5F;
            if (h.mesh != null) {
                meshQuads(out, h, cx, cy, cz, alpha);
                continue;
            }
            flat(out, h, cx, cy, cz, half, alpha, FRAME);
        }
    }

    // how flat() lays each part of the face: as seen this frame, a batch's front and two-sided back, its back only
    private static final int FRAME = 0, BAKE = 1, BAKE_BACK = 2;

    // the parts of the face a flat hole covers: its rect, or the runs of shown cells of its mask, row by row
    private static void flat(VertexWriter out, HoleDecal h, double cx, double cy, double cz, float half, int alpha, int how) {
        double[] r = h.rect;
        if (h.solid == null) {
            cell(out, h, cx, cy, cz, half, alpha, how, r[0], r[1], r[2], r[3]);
            return;
        }
        for (int j = 0; j < h.maskH; j++) {
            for (int i = 0; i < h.maskW; i++) {
                if (!h.solid[j * h.maskW + i]) continue;
                int e = i;
                while (e + 1 < h.maskW && h.solid[j * h.maskW + e + 1]) e++;
                cell(out, h, cx, cy, cz, half, alpha, how, Math.max(r[0], (h.maskU0 + i) / 16.0), Math.min(r[1], (h.maskU0 + e + 1) / 16.0),
                        Math.max(r[2], (h.maskV0 + j) / 16.0), Math.min(r[3], (h.maskV0 + j + 1) / 16.0));
                i = e;
            }
        }
    }

    private static void cell(VertexWriter out, HoleDecal h, double cx, double cy, double cz, float half, int alpha, int how, double c0, double c1,
            double c2, double c3) {
        if (how == FRAME) {
            quad(out, h, cx, cy, cz, half, alpha, c0, c1, c2, c3);
            return;
        }
        if (how == BAKE) quadAt(out, h, cx, cy, cz, half, 255, c0, c1, c2, c3, h.step, false, false);
        // two-sided (iron bars): its back as well, lifted to the back and wound the other way. The same normal on
        // both: lit as the hole sent each frame is from either side
        if (!h.cull) quadAt(out, h, cx, cy, cz, half, 255, c0, c1, c2, c3, -h.step, true, false);
    }

    // the face's two axes: u, v as world axis indices, by the face's axis (x: z, y; y: x, z; z: x, y)
    private static final int[] AXIS_U = {2, 0, 0}, AXIS_V = {1, 2, 1};

    private static double comp(double x, double y, double z, int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    // the hole as a square on the face, cut to its rect; turned and mirrored by h.turn (0..7)
    private static void quad(VertexWriter out, HoleDecal h, double cx, double cy, double cz, float size, int alpha, double c0, double c1, double c2,
            double c3) {
        double base = Double.isNaN(h.plane) ? comp(h.x, h.y, h.z, h.axis) : h.plane;
        // lifted off the face towards the camera: a two-sided hole (iron bars) seen from behind would otherwise lie
        // behind the bars' own pixels and show only where the depth bias wins, cut off along a straight line
        int side = h.step;
        if (!h.cull) {
            double d = comp(cx, cy, cz, h.axis) - base;
            if (d != 0) side = d > 0 ? 1 : -1;
        }
        quadAt(out, h, cx, cy, cz, size, alpha, c0, c1, c2, c3, side, false, side != h.step);
    }

    // the hole's square lifted to `side` of its face, positions from (cx, cy, cz); reverse = wound the other way
    // (seen from that side through a culling pipeline), flipNormal = its normal turned to the other side
    static void quadAt(VertexWriter out, HoleDecal h, double cx, double cy, double cz, float size, int alpha, double c0, double c1, double c2,
            double c3, int side, boolean reverse, boolean flipNormal) {
        int au = AXIS_U[h.axis], av = AXIS_V[h.axis];
        double pu = comp(h.x, h.y, h.z, au), pv = comp(h.x, h.y, h.z, av);
        double u0 = Math.max(pu - size, c0), u1 = Math.min(pu + size, c1);
        double v0 = Math.max(pv - size, c2), v1 = Math.min(pv + size, c3);
        if (u0 >= u1 || v0 >= v1) return;
        int normalAxis = h.axis;
        double base = Double.isNaN(h.plane) ? comp(h.x, h.y, h.z, normalAxis) : h.plane;
        double plane = base + 0.002 * side;
        float nx = normalAxis == 0 ? h.step : 0, ny = normalAxis == 1 ? h.step : 0, nz = normalAxis == 2 ? h.step : 0;
        if (flipNormal) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        int color = (alpha << 24) | 0xFFFFFF;
        // counter-clockwise seen from outside the face (its front for a one-sided hole): the face's u, v axes turn
        // the other way on east / west and up / down faces
        boolean flip = (normalAxis == 2 ? 1 : -1) * h.step < 0;
        for (int j = 0; j < 4; j++) {
            // the corners {u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}
            int k = flip != reverse ? 3 - j : j;
            double cu = k == 0 || k == 3 ? u0 : u1, cv = k < 2 ? v0 : v1;
            // where this corner falls on the hole's texture, 0..1, then turned / mirrored
            holeUv(h.turn, (float) ((cu - (pu - size)) / (2 * size)), (float) ((cv - (pv - size)) / (2 * size)));
            float tu = uvU, tv = uvV;
            double wx = au == 0 ? cu : av == 0 ? cv : plane;
            double wy = au == 1 ? cu : av == 1 ? cv : plane;
            double wz = au == 2 ? cu : av == 2 ? cv : plane;
            out.decal((float) (wx - cx), (float) (wy - cy), (float) (wz - cz), color, tu, 1F - tv, h.light, nx, ny, nz);
        }
    }

    // holeUv's answer (the render thread's alone)
    private static float uvU, uvV;

    // where (a, b) on a hole's square (0..1 each) falls on its texture: turned / mirrored by `turn` (0..7), or a
    // scratch's turned by its angle and shrunk by 1/sqrt 2 about the centre, so the square's corners stay on it;
    // then in its variant's cell (the turn's bits from VARIANT_SHIFT). v grows up, as the callers' 1 - v expects
    static void holeUv(int turnAndVariant, float a, float b) {
        turned(turnAndVariant & TURN_MASK, a, b);
        int variant = turnAndVariant >>> VARIANT_SHIFT;
        float u = Decals.cellU(variant, uvU), v = Decals.cellV(variant, 1F - uvV);
        uvU = u;
        uvV = 1F - v;
    }

    private static void turned(int turn, float a, float b) {
        if (turn >= SCRATCH_TURN) {
            double r = Math.toRadians(turn - SCRATCH_TURN), c = Math.cos(r), sn = Math.sin(r);
            double da = (a - 0.5) * 0.7071, db = (b - 0.5) * 0.7071;
            uvU = (float) (0.5 + da * c + db * sn);
            uvV = (float) (0.5 - da * sn + db * c);
            return;
        }
        if ((turn & 4) != 0) a = 1F - a;
        switch (turn & 3) {
            case 1 -> {
                uvU = b;
                uvV = 1F - a;
            }
            case 2 -> {
                uvU = 1F - a;
                uvV = 1F - b;
            }
            case 3 -> {
                uvU = 1F - b;
                uvV = a;
            }
            default -> {
                uvU = a;
                uvV = b;
            }
        }
    }

    // a cut hole sent each frame: towards the camera, as in quad(); a one-sided hole seen from behind is not drawn
    static void meshQuads(VertexWriter out, HoleDecal h, double cx, double cy, double cz, int alpha) {
        float[] m = h.moving ? h.movedMesh : h.mesh;
        if (m == null) return;
        double px = h.moving ? h.mx : h.x, py = h.moving ? h.my : h.y, pz = h.moving ? h.mz : h.z;
        int light = h.moving ? h.movedLight : h.light;
        float nx = m[0], ny = m[1], nz = m[2];
        if ((cx - px) * nx + (cy - py) * ny + (cz - pz) * nz < 0) {
            if (h.front) return;
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        // on the faces themselves, only pulled towards the camera along each vertex's own line of sight (a tenth of a
        // percent of the distance): it stays on the same screen pixels, so the parts on two faces of an edge still
        // meet, yet lies in front of the face (a two-sided hole on bars seen from behind). Lifted along the normal
        // instead, each face's part stood off its face and an edge showed a strip of the block between them
        float bx = (float) (px - cx), by = (float) (py - cy), bz = (float) (pz - cz);
        final float toward = 0.999F;
        int color = (alpha << 24) | 0xFFFFFF;
        int k = 3;
        while (k < m.length) {
            int n = (int) m[k++];
            int base = k;
            // a fan of quads (the last vertex twice where a triangle is left)
            for (int i = 1; i + 1 < n; i += 2) {
                for (int t = 0; t < 4; t++) {
                    int j = t == 0 ? 0 : t == 1 ? i : t == 2 ? i + 1 : Math.min(i + 2, n - 1);
                    int o = base + j * 6;
                    out.decal((bx + m[o]) * toward, (by + m[o + 1]) * toward, (bz + m[o + 2]) * toward,
                            m[o + 5] >= 1F ? color : ((int) (alpha * m[o + 5]) << 24) | 0xFFFFFF, m[o + 3], m[o + 4], light, nx, ny, nz);
                }
            }
            k = base + n * 6;
        }
    }

    // how far the camera is in front of the hole, along its normal: below 0 behind it, looking through its block
    static double towardCamera(HoleDecal h, double cx, double cy, double cz) {
        float[] m = h.moving ? h.movedMesh : h.mesh;
        if (m != null) {
            double px = h.moving ? h.mx : h.x, py = h.moving ? h.my : h.y, pz = h.moving ? h.mz : h.z;
            return (cx - px) * m[0] + (cy - py) * m[1] + (cz - pz) * m[2];
        }
        double base = Double.isNaN(h.plane) ? comp(h.x, h.y, h.z, h.axis) : h.plane;
        return (comp(cx, cy, cz, h.axis) - base) * h.step;
    }

    // ---- Holes kept on the graphics card (holeCache): in batches of up to BATCH_HOLES of one texture, flat or cut,
    // made in a row; a batch is built again only when a hole joins or leaves it, not every frame. Its positions are
    // from an origin of its own (the version adds origin - camera), and it is drawn both ways round through the
    // culling pipeline, so nothing in it depends on where the camera is. Not in a batch: holes fading out, on glass
    // under the transparency chain (drawn late). Holes on moving parts are in batches too: their batch is built again
    // on the frames the part moves.
    static final class Batch {
        final int kind;
        final boolean mesh, late, under;
        List<HoleDecal> holes = new ArrayList<>();
        List<HoleDecal> now = new ArrayList<>();
        boolean dirty = true;
        // vb: every hole as drawn after the glass (late) or with the rest (early); back: an under batch's holes once
        // more, only their backs, drawn early under the glass (the port's kept buffers, NONE until first written)
        int vb, back;
        int count, backCount;
        double ox, oy, oz;

        Batch(int kind, boolean mesh, boolean late, boolean under) {
            this.kind = kind;
            this.mesh = mesh;
            this.late = late;
            this.under = under;
        }

        int key() {
            return HolePasses.batchKey(kind, under, late, mesh);
        }

        void close() {
            if (vb != RenderPort.NONE) port.free(vb);
            if (back != RenderPort.NONE) port.free(back);
        }
    }

    static final List<Batch> BATCHES = new ArrayList<>();
    private static final java.util.Map<Integer, Batch> OPEN = new java.util.HashMap<>();
    // the holes left out of the batches this frame, in the order made (the same pass sorts them, not a second look at
    // every hole)
    private static final List<HoleDecal> UNBATCHED = new ArrayList<>();

    // which holes are in which batch this frame; lateApart: the late ones sent each frame (under the transparency
    // chain they are drawn with the translucent terrain, not after it)
    private static void batch(long now, long life, boolean lateApart) {
        boolean on = Config.holeCache && Config.bulletHoles;
        double fadeFrom = Math.max(0.0, life / 1e9 - 3.0);
        for (Batch b : BATCHES) b.now.clear();
        UNBATCHED.clear();
        if (!on) UNBATCHED.addAll(HOLES);
        else for (HoleDecal h : HOLES) {
            if (h.late && lateApart || (now - h.born) / 1e9 >= fadeFrom) {
                UNBATCHED.add(h);
                continue;
            }
            Batch b = h.batch;
            // (a hole on glass changes batch when the models loaded anew draw its block otherwise)
            if (b != null && b.late && b.under != host.under(h)) b = h.batch = null;
            if (b == null) {
                boolean under = host.under(h);
                int key = HolePasses.batchKey(h.kind, under, h.late, h.mesh != null);
                b = OPEN.get(key);
                // (holes go in the order made: the open batch's older ones are counted already)
                if (b == null || b.now.size() >= BATCH_HOLES) {
                    b = new Batch(h.kind, h.mesh != null, h.late, under);
                    BATCHES.add(b);
                    OPEN.put(key, b);
                }
                h.batch = b;
                b.dirty = true;
            }
            b.now.add(h);
        }
        Iterator<Batch> it = BATCHES.iterator();
        while (it.hasNext()) {
            Batch b = it.next();
            if (b.now.size() != b.holes.size()) b.dirty = true;
            if (!b.dirty && b.now.size() == b.holes.size()) continue;
            java.util.Set<HoleDecal> keep = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            keep.addAll(b.now);
            for (HoleDecal h : b.holes) if (!keep.contains(h) && h.batch == b) h.batch = null;
            List<HoleDecal> was = b.holes;
            b.holes = b.now;
            b.now = was;
            if (b.holes.isEmpty()) {
                b.close();
                it.remove();
                OPEN.remove(b.key(), b);
            }
        }
    }

    // the batches, drawn after the solid features (entities and the like) and before the translucent ones, where the
    // holes sent each frame are drawn. The late ones on translucent glass (under) once here as well, under the glass
    private static void afterOpaque(RenderSink sink) {
        if (BATCHES.isEmpty() || !Config.bulletHoles) return;
        long pd = Perf.start();
        drawBatches(sink, false);
        Perf.add(Perf.HOLE_DRAW, pd);
    }

    private static void drawBatches(RenderSink sink, boolean late) {
        long pd = Perf.start();
        for (Batch b : BATCHES) {
            // early: the batches not late, and the late ones under the translucent glass once more
            if (late ? !b.late : !HolePasses.early(b.late, b.under)) continue;
            if (b.dirty) {
                long pb = Perf.start();
                bake(b);
                Perf.add(Perf.HOLE_BAKE, pb);
            }
            // under the glass only the holes' backs: from in front the late copy is enough, and the two laid on each
            // other darken its half-clear edges twice
            boolean back = !late && b.late;
            int vb = back ? b.back : b.vb;
            int count = back ? b.backCount : b.count;
            if (count == 0 || vb == RenderPort.NONE) continue;
            // cut holes pulled towards the camera by a tenth of a percent of the distance, as meshQuads() does
            sink.drawAt(MATERIALS[b.kind + KINDS * ((b.late ? 1 : 0) | 2)], vb, 0, count, b.ox, b.oy, b.oz, b.mesh ? 0.999F : 1F);
            host.batchDrawn();
            Perf.batched(b.holes.size());
        }
        Perf.add(Perf.BATCHES, pd);
    }

    private static void bake(Batch b) {
        b.dirty = false;
        b.count = b.backCount = 0;
        if (b.holes.isEmpty()) return;
        HoleDecal o = b.holes.get(0);
        b.ox = o.x;
        b.oy = o.y;
        b.oz = o.z;
        if (b.vb == RenderPort.NONE) b.vb = port.buffer(Layouts.DECAL, 64);
        b.count = bake(b, false, b.vb);
        if (b.under) {
            if (b.back == RenderPort.NONE) b.back = port.buffer(Layouts.DECAL, 64);
            b.backCount = bake(b, true, b.back);
        }
    }

    // the batch's holes into its buffer, all of them or (back) only their backs
    private static int bake(Batch b, boolean back, int buffer) {
        int vertices = 0;
        for (HoleDecal h : b.holes) vertices += h.mesh != null ? h.mesh.length + 16 : 64;
        VertexWriter out = port.fill(buffer, vertices);
        bakeHoles(out, b.holes, b.ox, b.oy, b.oz, back);
        return out.done();
    }

    static void bakeHoles(VertexWriter out, List<HoleDecal> holes, double ox, double oy, double oz, boolean back) {
        for (HoleDecal h : holes) {
            if (h.mesh != null) {
                bakeMesh(out, h, ox, oy, oz, back);
                continue;
            }
            float half = h.size * 0.5F;
            flat(out, h, ox, oy, oz, half, 255, back ? BAKE_BACK : BAKE);
        }
    }

    // a cut hole for a batch: each polygon wound so it is seen from the side its normal points to, and (two-sided)
    // once more the other way round. The normal is turned as the polygon winds, so it is lit as meshQuads() lights it
    static void bakeMesh(VertexWriter out, HoleDecal h, double ox, double oy, double oz, boolean back) {
        float[] m = h.moving ? h.movedMesh : h.mesh;
        if (m == null) return;
        double px = h.moving ? h.mx : h.x, py = h.moving ? h.my : h.y, pz = h.moving ? h.mz : h.z;
        int light = h.moving ? h.movedLight : h.light;
        float nx = m[0], ny = m[1], nz = m[2];
        float bx = (float) (px - ox), by = (float) (py - oy), bz = (float) (pz - oz);
        int copies = h.front || h.cull ? 1 : 2;
        int k = 3;
        while (k < m.length) {
            int n = (int) m[k++];
            int base = k;
            k = base + n * 6;
            float sign = 0F;
            for (int i = 1; i + 1 < n && sign == 0F; i++) {
                int a = base, q = base + i * 6, r = base + (i + 1) * 6;
                float ux = m[q] - m[a], uy = m[q + 1] - m[a + 1], uz = m[q + 2] - m[a + 2];
                float wx = m[r] - m[a], wy = m[r + 1] - m[a + 1], wz = m[r + 2] - m[a + 2];
                float d = (uy * wz - uz * wy) * nx + (uz * wx - ux * wz) * ny + (ux * wy - uy * wx) * nz;
                if (Math.abs(d) > 1e-12F) sign = Math.signum(d);
            }
            if (sign == 0F) continue;
            for (int copy = back ? 1 : 0; copy < copies; copy++) {
                boolean rev = (sign < 0F) != (copy == 1);
                for (int i = 1; i + 1 < n; i += 2) {
                    for (int t = 0; t < 4; t++) {
                        int s = rev ? (4 - t) % 4 : t;
                        int j = s == 0 ? 0 : s == 1 ? i : s == 2 ? i + 1 : Math.min(i + 2, n - 1);
                        int o = base + j * 6;
                        out.decal(bx + m[o], by + m[o + 1], bz + m[o + 2], ((int) (255 * m[o + 5]) << 24) | 0xFFFFFF, m[o + 3], m[o + 4], light,
                                sign * nx, sign * ny, sign * nz);
                    }
                }
            }
        }
    }
}
