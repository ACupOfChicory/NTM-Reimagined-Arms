package acup.gunfeel.water;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

// Rings on the water where a bullet hit it (the sandbox's ring decals): flat, lying on the surface, drifting with the
// flow at half its speed and growing only downstream (the upstream edge stays where the bullet hit), never stretched.
// A ring is gone when its centre leaves the water or the surface under it jumps more than 0.3. It is drawn only on the
// water connected to where it was made (WaterSurface.connected): one flat piece per connected cell, the ring's square
// cut to the cell with its UVs, at the cell's own surface heights; past a step, a drop or air there is no ring.
public final class WaterRings {
    private WaterRings() {
    }

    static final int MAX = 160;

    private static final class Ring {
        boolean on;
        double t, delay, life, s0, s1, x, y, z, fx, fz, fm;
        float a;
        boolean adv, air;
        int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE, cells, span;
        double[][] allow = new double[49][];
    }

    private static final Ring[] RINGS = new Ring[MAX];
    static {
        for (int i = 0; i < MAX; i++) RINGS[i] = new Ring();
    }
    // for the stand: made, gone because they left the water, alive now
    public static int made, leftWater, widest;
    private static long lastNanos;

    public static void add(Level level, double x, double y, double z, double delay, double life, double s0, double s1, float a, boolean adv,
            boolean air) {
        if (!WaterFx.on()) return;
        for (Ring r : RINGS) {
            if (r.on) continue;
            r.on = true;
            r.t = 0;
            r.delay = delay;
            r.life = life;
            r.s0 = s0;
            r.s1 = s1;
            r.a = a;
            r.x = x;
            r.y = y;
            r.z = z;
            r.adv = adv;
            r.air = air;
            r.fx = r.fz = r.fm = 0;
            r.cx = r.cz = Integer.MIN_VALUE;
            // the cells it can reach: half its last size, plus the drift downstream (half the growth) and its own cell
            r.span = Math.min(WaterSurface.MAX_SPAN, (int) Math.ceil(s1 / 2 + (adv ? (s1 - s0) / 2 : 0)) + 1);
            widest = Math.max(widest, r.span);
            int side = 2 * r.span + 1;
            if (r.allow.length < side * side) r.allow = new double[side * side][];
            made++;
            return;
        }
    }

    public static int alive() {
        int n = 0;
        for (Ring r : RINGS) if (r.on) n++;
        return n;
    }

    public static void clear() {
        for (Ring r : RINGS) r.on = false;
    }

    private static void update(Level level, double dt) {
        double fs = WaterParticle.FLOW_SPEED;
        for (Ring d : RINGS) {
            if (!d.on) continue;
            d.t += dt;
            if (!d.air) {
                WaterSurface.Info s = WaterSurface.at(level, d.x, d.y + 0.4, d.z, false);
                if (s == null || Math.abs(s.y() - d.y) > 0.3) {
                    d.on = false;
                    leftWater++;
                    continue;
                }
                if (d.adv && (s.fx() != 0 || s.fz() != 0)) {
                    double nx = d.x + s.fx() * fs * 0.5 * dt, nz = d.z + s.fz() * fs * 0.5 * dt;
                    WaterSurface.Info s2 = WaterSurface.at(level, nx, d.y + 0.4, nz, false);
                    if (s2 != null && Math.abs(s2.y() - s.y()) < 0.3) {
                        d.x = nx;
                        d.z = nz;
                    }
                    d.fx = s.fx();
                    d.fz = s.fz();
                    d.fm = Math.hypot(s.fx(), s.fz());
                }
                d.y = s.y();
                int cx = (int) Math.floor(d.x), cz = (int) Math.floor(d.z);
                if (cx != d.cx || cz != d.cz) {
                    d.cx = cx;
                    d.cz = cz;
                    d.cells = WaterSurface.connected(level, cx, cz, d.y, d.allow, d.span);
                }
            }
            if (d.t - d.delay >= d.life) d.on = false;
        }
    }

    public static void submit(SubmitCustomGeometryEvent event, Level level, Vec3 cam) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.clamp((now - lastNanos) / 1e9, 0, 0.1);
        lastNanos = now;
        if (net.minecraft.client.Minecraft.getInstance().isPaused()) dt = 0;
        if (alive() == 0) return;
        update(level, dt);
        TextureAtlasSprite sprite = WaterParticle.sprite("ntmgunfeel:water_ring");
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.entityTranslucent(TextureAtlas.LOCATION_PARTICLES),
                (p, vc) -> {
                    for (Ring d : RINGS) if (d.on && !d.air && d.t >= d.delay) draw(level, p, vc, d, sprite, cam);
                });
    }

    private static final BlockPos.MutableBlockPos CELL = new BlockPos.MutableBlockPos();
    private static final double[] PX = new double[16], PZ = new double[16], QX = new double[16], QZ = new double[16];

    private static void draw(Level level, PoseStack.Pose p, VertexConsumer vc, Ring d, TextureAtlasSprite sprite, Vec3 cam) {
        double f = (d.t - d.delay) / d.life;
        if (f >= 1) return;
        double e = 1 - Math.pow(1 - f, 2), sc = d.s0 + (d.s1 - d.s0) * e;
        // on a stream it grows only downstream: the centre moves on by half the growth
        double hx = 1, hz = 0, sh = 0;
        if (d.fm > 0.01) {
            hx = d.fx / d.fm;
            hz = d.fz / d.fm;
            sh = (sc - d.s0) * 0.5;
        }
        double cx = d.x + hx * sh, cz = d.z + hz * sh, h = sc / 2;
        // the square on the water's plane (seen from above): along the flow (or x) and across it
        double bx = -hz, bz = hx;
        float alpha = (float) (d.a * (1 - f));
        int color = ((int) (Math.clamp(alpha, 0, 1) * 255) << 24) | 0xFFFFFF;
        float iu = (sprite.getU1() - sprite.getU0()) / 32F, iv = (sprite.getV1() - sprite.getV0()) / 32F;
        float u0 = sprite.getU0() + iu, u1 = sprite.getU1() - iu, v0 = sprite.getV0() + iv, v1 = sprite.getV1() - iv;
        int span = d.span, side = 2 * span + 1;
        // the cells under the square as it is now (it starts small)
        double ex = h * (Math.abs(hx) + Math.abs(bx)), ez = h * (Math.abs(hz) + Math.abs(bz));
        int x0 = Math.max(-span, (int) Math.floor(cx - ex) - d.cx), x1 = Math.min(span, (int) Math.floor(cx + ex) - d.cx);
        int z0 = Math.max(-span, (int) Math.floor(cz - ez) - d.cz), z1 = Math.min(span, (int) Math.floor(cz + ez) - d.cz);
        for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
            int k = (dz + span) * side + dx + span;
            double[] c = d.allow[k];
            if (c == null) continue;
            int gx = d.cx + dx, gz = d.cz + dz;
            // the square's corners, cut to the cell
            int n = 4;
            PX[0] = cx + hx * h + bx * h;
            PZ[0] = cz + hz * h + bz * h;
            PX[1] = cx - hx * h + bx * h;
            PZ[1] = cz - hz * h + bz * h;
            PX[2] = cx - hx * h - bx * h;
            PZ[2] = cz - hz * h - bz * h;
            PX[3] = cx + hx * h - bx * h;
            PZ[3] = cz + hz * h - bz * h;
            n = clip(n, gx, 1, true);
            n = clip(n, gx + 1, -1, true);
            n = clip(n, gz, 1, false);
            n = clip(n, gz + 1, -1, false);
            if (n < 3) continue;
            int light = LightCoordsUtil.getLightCoords(level, CELL.set(gx, (int) Math.floor(c[0] + 0.5), gz));
            // a fan of triangles as quads (the last vertex twice), both ways round: seen from above and from below
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 1; i + 1 < n; i++) {
                    for (int k2 = 0; k2 < 4; k2++) {
                        int j = k2 == 0 ? 0 : pass == 0 ? (k2 == 1 ? i : i + 1) : (k2 == 1 ? i + 1 : i);
                        double x = PX[j], z = PZ[j];
                        double y = WaterSurface.height(c, x - gx, z - gz) + 0.03;
                        double u = ((x - cx) * hx + (z - cz) * hz) / sc + 0.5, v = ((x - cx) * bx + (z - cz) * bz) / sc + 0.5;
                        vc.addVertex(p, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).setColor(color)
                                .setUv(u0 + (float) u * (u1 - u0), v0 + (float) v * (v1 - v0)).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                                .setNormal(p, 0F, pass == 0 ? 1F : -1F, 0F);
                    }
                }
            }
        }
    }

    // Sutherland-Hodgman against one side of the cell: keep where sign·(coord - edge) >= 0
    private static int clip(int n, double edge, int sign, boolean onX) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ai = sign * ((onX ? PX[i] : PZ[i]) - edge), aj = sign * ((onX ? PX[j] : PZ[j]) - edge);
            if (ai >= 0) {
                QX[m] = PX[i];
                QZ[m] = PZ[i];
                m++;
            }
            if ((ai >= 0) != (aj >= 0)) {
                double t = ai / (ai - aj);
                QX[m] = PX[i] + (PX[j] - PX[i]) * t;
                QZ[m] = PZ[i] + (PZ[j] - PZ[i]) * t;
                m++;
            }
        }
        System.arraycopy(QX, 0, PX, 0, m);
        System.arraycopy(QZ, 0, PZ, 0, m);
        return m;
    }
}
