package acup.gunfeel.gl54.frag;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import acup.gunfeel.PassableClip;
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

    public static void handle(FragPayload p) {
        ClientLevel level = Minecraft.getInstance().level;
        FragSpec spec = FragSpec.of(p.kind());
        if (level == null || spec == null) return;
        int n = Math.min(spec.rays(), FragSpec.MAX_RAYS);
        float[] dir = new float[n * 3], len = new float[n];
        double[] d = new double[3];
        for (int i = 0; i < n; i++) {
            FragPattern.direction(spec, p.seed(), i, p.dir().x, p.dir().y, p.dir().z, d);
            dir[i * 3] = (float) d[0];
            dir[i * 3 + 1] = (float) d[1];
            dir[i * 3 + 2] = (float) d[2];
            Vec3 to = p.point().add(d[0] * FragSpec.RANGE, d[1] * FragSpec.RANGE, d[2] * FragSpec.RANGE);
            var hit = PassableClip.clip(level, p.point(), to);
            len[i] = hit.getType() == HitResult.Type.MISS ? (float) FragSpec.RANGE : (float) hit.getLocation().distanceTo(p.point());
        }
        synchronized (LIVE) {
            LIVE.add(new Burst(Math.min(p.kind().ordinal(), 255), System.nanoTime(), dir, len, p.point().x, p.point().y, p.point().z));
        }
        if (puffs) level.addParticle(ParticleTypes.EXPLOSION, p.point().x, p.point().y, p.point().z, 0, 0, 0);
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
