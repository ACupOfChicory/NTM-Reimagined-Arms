package acup.gunfeel.smoke.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import acup.gunfeel.Config;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Client: the smoke drawn as slices (research, technique B), where the raymarch is not: with an Iris shader pack in use
 * (it draws into targets of its own), after the raymarch pass failed, or with {@code smokeQuality = slices}. A cloud is
 * a stack of translucent quads half a block apart across the axis nearest to the view, one quad a cell, its corners as
 * opaque as the smoke there. They go through the game's own translucent quads (depth tested, sorted far to near), so
 * walls and mobs cut them and a shader pack takes them as it takes any translucent geometry. Rebuilt when the cloud
 * steps or the axis changes.
 */
public final class SmokeSlices {
    private static final float STEP = 0.5F;
    // a corner this transparent counts as empty; a quad of four such corners is left out
    private static final float EMPTY = 0.004F;
    private static final Map<Integer, Built> BUILT = new HashMap<>();
    private static int drawnLast;

    private SmokeSlices() {
    }

    // one cloud's quads: the corners relative to the cloud's cell (0, 0, 0), x, y, z each, and their colours (ARGB)
    private record Built(SmokeClouds.Cloud cloud, int age, int axis, float[] corners, int[] colours, int count) {
    }

    /** How many clouds were drawn as slices in the last frame. */
    public static int drawnLast() {
        return drawnLast;
    }

    public static void submit(SubmitCustomGeometryEvent e) {
        drawnLast = 0;
        if (!SmokeRender.slicesWanted()) {
            if (!BUILT.isEmpty()) BUILT.clear();
            return;
        }
        SmokeClouds all = SmokeClient.current();
        if (all == null || all.isEmpty()) {
            BUILT.clear();
            return;
        }
        long t0 = System.nanoTime();
        Vec3 cam = e.getLevelRenderState().cameraRenderState.pos;
        var mc = Minecraft.getInstance();
        long time = mc.level == null ? 0 : mc.level.getGameTime();
        List<SmokeClouds.Cloud> near = new ArrayList<>();
        for (SmokeClouds.Cloud c : all.clouds()) {
            if (!c.grid.occupied() || c.grid.age() < time - c.born - 2) continue;
            if (distance(c, cam) > Config.smokeDistance) continue;
            near.add(c);
        }
        near.sort(Comparator.comparingDouble(c -> distance(c, cam)));
        if (near.size() > Config.smokeMaxClouds) near = near.subList(0, Config.smokeMaxClouds);
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        List<Built> draw = new ArrayList<>();
        Vec3 look = mc.player == null ? new Vec3(0, 0, 1) : mc.player.getViewVector(1F);
        for (SmokeClouds.Cloud c : near) {
            keep.add(c.id);
            int axis = axis(c, cam, look);
            Built b = BUILT.get(c.id);
            if (b == null || b.cloud != c || b.age != c.grid.age() || b.axis != axis) {
                b = build(c, axis);
                BUILT.put(c.id, b);
            }
            if (b.count > 0) draw.add(b);
        }
        BUILT.keySet().retainAll(keep);
        if (!draw.isEmpty()) {
            e.getSubmitNodeCollector().submitCustomGeometry(e.getPoseStack(), RenderTypes.debugQuads(), (p, vc) -> {
                for (Built b : draw) {
                    float ox = (float) (b.cloud.baseX() - cam.x), oy = (float) (b.cloud.baseY() - cam.y), oz = (float) (b.cloud.baseZ() - cam.z);
                    float[] q = b.corners;
                    int[] col = b.colours;
                    for (int i = 0, n = b.count * 4; i < n; i++)
                        vc.addVertex(p, ox + q[i * 3], oy + q[i * 3 + 1], oz + q[i * 3 + 2]).setColor(col[i]);
                }
            });
        }
        drawnLast = draw.size();
        SmokeStats.add(SmokeStats.SLICES, System.nanoTime() - t0);
    }

    // the axis (0 x, 1 y, 2 z) nearest to the view of the cloud: from the camera to its middle, or where the camera
    // looks when it is in the box
    private static int axis(SmokeClouds.Cloud c, Vec3 cam, Vec3 look) {
        int[] b = c.grid.box();
        double minX = c.baseX() + b[0], minY = c.baseY() + b[1], minZ = c.baseZ() + b[2];
        double maxX = c.baseX() + b[3] + 1, maxY = c.baseY() + b[4] + 1, maxZ = c.baseZ() + b[5] + 1;
        Vec3 d;
        if (cam.x >= minX - 1 && cam.x <= maxX + 1 && cam.y >= minY - 1 && cam.y <= maxY + 1 && cam.z >= minZ - 1 && cam.z <= maxZ + 1) d = look;
        else d = new Vec3((minX + maxX) / 2 - cam.x, (minY + maxY) / 2 - cam.y, (minZ + maxZ) / 2 - cam.z);
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        return ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
    }

    private static Built build(SmokeClouds.Cloud c, int axis) {
        SmokeGrid g = c.grid;
        int[] b = g.box();
        // u, v: the two other axes; corners at the cell centres, one cell out around the box so the edge fades
        int ua = axis == 0 ? 1 : 0, va = axis == 2 ? 1 : 2;
        int[] lo = {b[0] - 1, b[1] - 1, b[2] - 1}, hi = {b[3] + 1, b[4] + 1, b[5] + 1};
        int nu = hi[ua] - lo[ua] + 1, nv = hi[va] - lo[va] + 1;
        float ext = c.kind().extinction();
        int rgb = c.kind().rgb();
        float light = 0.10F + 0.90F * SmokeLight.brightness(Minecraft.getInstance().level, c.origin);
        int r = Math.round((rgb >> 16 & 255) * light), gr = Math.round((rgb >> 8 & 255) * light), bl = Math.round((rgb & 255) * light);
        int colour = r << 16 | gr << 8 | bl;
        float from = b[axis];
        float to = b[axis + 3] + 1;
        int slices = Math.max(1, Math.round((to - from) / STEP));
        float[] alpha = new float[nu * nv];
        float[] out = new float[256 * 12];
        int[] col = new int[256 * 4];
        int count = 0;
        int[] at = new int[3];
        for (int s = 0; s < slices; s++) {
            float w = from + (s + 0.5F) * STEP;
            // the slice between the cell layers k and k + 1 (centres at k + 0.5)
            float sc = w - 0.5F;
            int k = (int) Math.floor(sc);
            float f = sc - k;
            for (int iu = 0; iu < nu; iu++)
                for (int iv = 0; iv < nv; iv++) {
                    at[ua] = lo[ua] + iu;
                    at[va] = lo[va] + iv;
                    at[axis] = k;
                    boolean c0 = g.closed(at[0], at[1], at[2]);
                    float d0 = g.density(at[0], at[1], at[2]);
                    at[axis] = k + 1;
                    boolean c1 = g.closed(at[0], at[1], at[2]);
                    float d1 = g.density(at[0], at[1], at[2]);
                    float d = c0 && c1 ? 0F : c0 ? d1 : c1 ? d0 : d0 + (d1 - d0) * f;
                    alpha[iu * nv + iv] = 1F - (float) Math.exp(-ext * d * STEP);
                }
            for (int iu = 0; iu + 1 < nu; iu++)
                for (int iv = 0; iv + 1 < nv; iv++) {
                    float a00 = alpha[iu * nv + iv], a10 = alpha[(iu + 1) * nv + iv], a11 = alpha[(iu + 1) * nv + iv + 1], a01 = alpha[iu * nv + iv + 1];
                    if (a00 < EMPTY && a10 < EMPTY && a11 < EMPTY && a01 < EMPTY) continue;
                    if ((count + 1) * 4 > col.length) {
                        out = java.util.Arrays.copyOf(out, out.length * 2);
                        col = java.util.Arrays.copyOf(col, col.length * 2);
                    }
                    int o = count * 4;
                    corner(out, col, o, axis, ua, va, w, lo[ua] + iu + 0.5F, lo[va] + iv + 0.5F, a00, colour);
                    corner(out, col, o + 1, axis, ua, va, w, lo[ua] + iu + 1.5F, lo[va] + iv + 0.5F, a10, colour);
                    corner(out, col, o + 2, axis, ua, va, w, lo[ua] + iu + 1.5F, lo[va] + iv + 1.5F, a11, colour);
                    corner(out, col, o + 3, axis, ua, va, w, lo[ua] + iu + 0.5F, lo[va] + iv + 1.5F, a01, colour);
                    count++;
                }
        }
        return new Built(c, g.age(), axis, out, col, count);
    }

    // corner i: w along the slicing axis, u and v along the other two
    private static void corner(float[] out, int[] col, int i, int axis, int ua, int va, float w, float u, float v, float a, int colour) {
        out[i * 3 + axis] = w;
        out[i * 3 + ua] = u;
        out[i * 3 + va] = v;
        col[i] = Math.clamp(Math.round(a * 255F), 0, 255) << 24 | colour;
    }

    private static double distance(SmokeClouds.Cloud c, Vec3 cam) {
        int[] b = c.grid.box();
        double x = Math.clamp(cam.x, c.baseX() + b[0], c.baseX() + b[3] + 1), y = Math.clamp(cam.y, c.baseY() + b[1], c.baseY() + b[4] + 1),
                z = Math.clamp(cam.z, c.baseZ() + b[2], c.baseZ() + b[5] + 1);
        return cam.distanceTo(new Vec3(x, y, z));
    }
}
