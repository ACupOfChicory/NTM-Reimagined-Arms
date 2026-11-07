package acup.gunfeel.gl54.frag;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import acup.gunfeel.PassableClip;
import acup.gunfeel.Tracers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

// The client's side of a fragment burst: from the packet's point, direction and seed it makes the same rays as the server
// (FragPattern), clips each against its own blocks for where it stops, and draws a bright streak flying out along each. No
// creature is looked for: a streak that crossed one goes on to the block or the end of the range.
public final class FragFx {
    private FragFx() {
    }

    // streak speed and length, blocks per second and blocks
    static final double SPEED = 30.0, TAIL = 2.5;
    // the colours of the streak's head and tail (RGB) per kind, by the grenade's tint
    private static final int[][] COLORS = {{0xFFF0B0, 0xFF9020}, {0xCFE0FF, 0x4468FF}, {0xFFC060, 0xFF3000}};

    private record Burst(int kind, long born, float[] dir, float[] len, double px, double py, double pz) {
    }

    private static final List<Burst> LIVE = new ArrayList<>();

    // dev (the stand): the age of every burst held at this many seconds (NaN: real time); streaks drawn the last frame;
    // whether a burst puffs
    public static volatile double holdAge = Double.NaN;
    public static volatile int drawn;
    public static volatile boolean puffs = true;
    // dev: what the last packet cost on the client (casting the rays and handing the holes over), ns, and the holes handed over
    public static volatile long lastHandleNanos;
    public static volatile int lastHoles;

    public static void handle(FragPayload p) {
        long t0 = System.nanoTime();
        ClientLevel level = Minecraft.getInstance().level;
        FragSpec spec = FragSpec.of(p.kind());
        if (level == null || spec == null) return;
        int n = Math.min(spec.rays(), FragSpec.MAX_RAYS);
        float[] dir = new float[n * 3], len = new float[n];
        net.minecraft.world.phys.BlockHitResult[] stops = new net.minecraft.world.phys.BlockHitResult[n];
        double[] d = new double[3];
        for (int i = 0; i < n; i++) {
            FragPattern.direction(spec, p.seed(), i, p.dir().x, p.dir().y, p.dir().z, d);
            dir[i * 3] = (float) d[0];
            dir[i * 3 + 1] = (float) d[1];
            dir[i * 3 + 2] = (float) d[2];
            Vec3 to = p.point().add(d[0] * FragSpec.RANGE, d[1] * FragSpec.RANGE, d[2] * FragSpec.RANGE);
            var hit = PassableClip.clip(level, p.point(), to);
            len[i] = hit.getType() == HitResult.Type.MISS ? (float) FragSpec.RANGE : (float) hit.getLocation().distanceTo(p.point());
            if (hit.getType() == HitResult.Type.BLOCK) stops[i] = hit;
        }
        lastHoles = holes(level, spec, p, stops, dir);
        synchronized (LIVE) {
            LIVE.add(new Burst(Math.min(p.kind().ordinal(), 255), System.nanoTime(), dir, len, p.point().x, p.point().y, p.point().z));
        }
        lastHandleNanos = System.nanoTime() - t0;
        if (puffs) level.addParticle(ParticleTypes.EXPLOSION, p.point().x, p.point().y, p.point().z, 0, 0, 0);
    }

    // most holes one burst leaves, the scale of a bullet's hole they are made at, and every how-many-th of them has the hit's
    // particles and sound (the cost of a burst is the holes and these few)
    static final int MAX_HOLES = 24, FX_EVERY = 8;
    static final float HOLE_SCALE = 0.4F;

    // the holes where the rays stopped on blocks, put by the bullets' own code (Tracers.fragmentHit); the server's rays are the
    // same, so no packet carries them. More stops than MAX_HOLES: every k-th, so cone and ring both leave marks. Returns how
    // many were handed over.
    static int holes(ClientLevel level, FragSpec spec, FragPayload p, net.minecraft.world.phys.BlockHitResult[] stops, float[] dir) {
        if (!acup.gunfeel.Config.bulletHoles && !acup.gunfeel.Config.bulletFx) return 0;
        var config = com.hbm.items.weapon.sedna.factory.XFactory9mm.p9_sp;
        if (config == null) return 0;
        int stopped = 0;
        for (var h : stops) if (h != null) stopped++;
        if (stopped == 0) return 0;
        int every = (stopped + MAX_HOLES - 1) / MAX_HOLES, seen = 0, made = 0;
        long born = Tracers.fragmentBurstTime();
        for (int i = 0; i < stops.length; i++) {
            if (stops[i] == null || seen++ % every != 0) continue;
            Vec3 d = new Vec3(dir[i * 3], dir[i * 3 + 1], dir[i * 3 + 2]);
            Tracers.fragmentHit(level, stops[i], d, config, born, HOLE_SCALE, made % FX_EVERY == 0);
            made++;
        }
        return made;
    }

    public static void clear() {
        synchronized (LIVE) {
            LIVE.clear();
        }
    }

    private static int[] colors(int kindOrdinal) {
        var kind = acup.gunfeel.gl54.GrenadeKind.values()[kindOrdinal];
        return switch (kind) {
            case AP -> COLORS[1];
            case INCENDIARY -> COLORS[2];
            default -> COLORS[0];
        };
    }

    public static void submit(SubmitCustomGeometryEvent event) {
        List<float[]> streaks = new ArrayList<>();
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        Minecraft mc = Minecraft.getInstance();
        float pixel = (float) (2.0 * Math.tan(Math.toRadians(mc.options.fov().get()) * 0.5) / Math.max(1, mc.getWindow().getHeight()));
        long now = System.nanoTime();
        synchronized (LIVE) {
            Iterator<Burst> it = LIVE.iterator();
            while (it.hasNext()) {
                Burst b = it.next();
                double age = Double.isNaN(holdAge) ? (now - b.born) / 1e9 : holdAge;
                double flown = SPEED * age, tail = Math.max(0, flown - TAIL);
                if (tail > FragSpec.RANGE) {
                    it.remove();
                    continue;
                }
                int[] c = colors(b.kind);
                int n = b.len.length;
                for (int i = 0; i < n; i++) {
                    double head = Math.min(b.len[i], flown), back = Math.min(head, tail);
                    if (head - back < 1e-3) continue;
                    float dx = b.dir[i * 3], dy = b.dir[i * 3 + 1], dz = b.dir[i * 3 + 2];
                    streaks.add(new float[] {(float) (b.px + dx * head - cam.x), (float) (b.py + dy * head - cam.y), (float) (b.pz + dz * head - cam.z),
                            (float) (b.px + dx * back - cam.x), (float) (b.py + dy * back - cam.y), (float) (b.pz + dz * back - cam.z),
                            Float.intBitsToFloat(c[0]), Float.intBitsToFloat(c[1]), pixel});
                }
            }
        }
        drawn = streaks.size();
        if (streaks.isEmpty()) return;
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.debugQuads(), (p, vc) -> {
            for (float[] k : streaks) streak(p.pose(), vc, k);
        });
    }

    // one streak as a flat strip facing the camera (both windings), never thinner than about a pixel
    private static void streak(Matrix4f m, VertexConsumer vc, float[] k) {
        float ax = k[0] - k[3], ay = k[1] - k[4], az = k[2] - k[5];
        if (ax * ax + ay * ay + az * az < 1e-8F) return;
        // side = along x head
        float sx = ay * k[2] - az * k[1], sy = az * k[0] - ax * k[2], sz = ax * k[1] - ay * k[0];
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-5F) return;
        sx /= sl;
        sy /= sl;
        sz /= sl;
        float hd = (float) Math.sqrt(k[0] * k[0] + k[1] * k[1] + k[2] * k[2]), td = (float) Math.sqrt(k[3] * k[3] + k[4] * k[4] + k[5] * k[5]);
        float wh = Math.max(0.025F, 1.5F * k[8] * hd), wt = Math.max(0.0125F, 1.5F * k[8] * td);
        int light = Float.floatToRawIntBits(k[6]) | 0xFF000000, dark = (Float.floatToRawIntBits(k[7]) & 0x00FFFFFF) | 0x30000000;
        float[][] q = {{k[0] + sx * wh, k[1] + sy * wh, k[2] + sz * wh}, {k[0] - sx * wh, k[1] - sy * wh, k[2] - sz * wh},
                {k[3] - sx * wt, k[4] - sy * wt, k[5] - sz * wt}, {k[3] + sx * wt, k[4] + sy * wt, k[5] + sz * wt}};
        int[] col = {light, light, dark, dark};
        for (int pass = 0; pass < 2; pass++)
            for (int j = 0; j < 4; j++) {
                int i = pass == 0 ? j : 3 - j;
                vc.addVertex(m, q[i][0], q[i][1], q[i][2]).setColor(col[i]);
            }
    }
}
