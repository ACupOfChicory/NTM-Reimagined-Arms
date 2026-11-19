package acup.gunfeel.smoke.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import acup.gunfeel.host.Hook;
import acup.gunfeel.host.HookPoint;
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
 * steps or the axis changes. The quads themselves are core's ({@link SmokeSliceMesh}); here the cloud's light, the
 * camera and the game's buffer.
 */
public final class SmokeSlices {
    private static final Map<Integer, Built> BUILT = new HashMap<>();
    private static int drawnLast;

    private SmokeSlices() {
    }

    // one cloud's quads: the corners relative to the cloud's cell (0, 0, 0), x, y, z each, and their colours (ARGB);
    // stamp: the grid's contentHash they were built from, age: the tick they were last found to still fit
    private record Built(SmokeClouds.Cloud cloud, int age, int axis, long stamp, float[] corners, int[] colours, int count) {
    }

    // the light the colours were built with is looked up again this often (ticks)
    private static final int RELIGHT = 100;

    /** How many clouds were drawn as slices in the last frame. */
    public static int drawnLast() {
        return drawnLast;
    }

    /** Forgets the built slices (the player left the world). */
    public static void clear() {
        BUILT.clear();
        drawnLast = 0;
    }

    @Hook(HookPoint.SMOKE_DRAW)
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
        List<SmokeClouds.Cloud> near = SmokeClient.nearest(all, cam, time, Config.smokeDistance, Config.smokeMaxClouds);
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        List<Built> draw = new ArrayList<>();
        Vec3 look = mc.player == null ? new Vec3(0, 0, 1) : mc.player.getViewVector(1F);
        for (SmokeClouds.Cloud c : near) {
            keep.add(c.id);
            Built b = BUILT.get(c.id);
            int axis = axis(c, cam, look, b == null ? -1 : b.axis);
            int age = c.grid.age();
            if (b == null || b.cloud != c || b.axis != axis || b.age / RELIGHT != age / RELIGHT) {
                b = build(c, axis);
                BUILT.put(c.id, b);
            } else if (b.age != age) {
                // a tick on: rebuilt only if a density changed (a cloud holding still for its 20 s costs nothing)
                long stamp = c.grid.contentHash();
                b = stamp == b.stamp ? new Built(c, age, b.axis, stamp, b.corners, b.colours, b.count) : build(c, axis);
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

    // the axis (0 x, 1 y, 2 z) nearest to the view of the cloud ({@link SmokeSliceMesh#axis})
    private static int axis(SmokeClouds.Cloud c, Vec3 cam, Vec3 look, int had) {
        return SmokeSliceMesh.axis(c.grid, c.baseX(), c.baseY(), c.baseZ(), cam.x, cam.y, cam.z, look.x, look.y, look.z, had);
    }

    private static Built build(SmokeClouds.Cloud c, int axis) {
        SmokeGrid g = c.grid;
        int rgb = c.kind().rgb();
        float light = 0.10F + 0.90F * SmokeLight.brightness(Minecraft.getInstance().level, c.origin);
        int r = Math.round((rgb >> 16 & 255) * light), gr = Math.round((rgb >> 8 & 255) * light), bl = Math.round((rgb & 255) * light);
        int colour = r << 16 | gr << 8 | bl;
        SmokeSliceMesh.Mesh m = SmokeSliceMesh.build(g, axis, colour);
        return new Built(c, g.age(), axis, g.contentHash(), m.corners(), m.colours(), m.count());
    }
}
