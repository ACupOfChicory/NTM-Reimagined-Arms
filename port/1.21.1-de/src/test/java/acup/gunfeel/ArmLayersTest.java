package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.VertexConsumer;

// Port copy of the root's test (1.21.1's VertexConsumer has no setLineWidth to override).
// ArmLayers.Clip: what the layers draw on the arm is kept near it and cut where the arm box is cut
class ArmLayersTest {
    // the positions and uvs sent on
    private static final class Sink implements VertexConsumer {
        final List<float[]> v = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            v.add(new float[] {x, y, z, 0F, 0F});
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v0, int overlay, int light, float nx, float ny, float nz) {
            v.add(new float[] {x, y, z, u, v0});
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v0) {
            float[] last = v.getLast();
            last[3] = u;
            last[4] = v0;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v0) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v0) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    // a face of the sleeve from y 0 to 1 (the arm's frame), uv v following y
    private static void quad(VertexConsumer to, float x0, float y0, float y1, float z) {
        to.addVertex(x0, y0, z, -1, 0F, y0, 0, 0xF000F0, 0F, 0F, 1F);
        to.addVertex(x0 + 0.25F, y0, z, -1, 1F, y0, 0, 0xF000F0, 0F, 0F, 1F);
        to.addVertex(x0 + 0.25F, y1, z, -1, 1F, y1, 0, 0xF000F0, 0F, 0F, 1F);
        to.addVertex(x0, y1, z, -1, 0F, y1, 0, 0xF000F0, 0F, 0F, 1F);
    }

    private static Sink run(float cutY, java.util.function.Consumer<VertexConsumer> draw) {
        ArmLayers.Clip clip = new ArmLayers.Clip(4);
        draw.accept(clip);
        Sink sink = new Sink();
        clip.flush(sink, new ArmLayers.Cut(new Matrix4f(), cutY, true), null);
        return sink;
    }

    @Test
    void wholeArmPassesUntouched() {
        Sink s = run(Float.NaN, b -> quad(b, 0F, 0F, 0.75F, 0F));
        assertEquals(4, s.v.size());
        assertEquals(0.75F, s.v.get(2)[1], 1e-6F);
    }

    @Test
    void cutDropsTheShoulderEndAndKeepsTheTexture() {
        // the arm shown from 0.25 on: the face is cut there, its uv cut with it
        Sink s = run(0.25F, b -> quad(b, 0F, 0F, 0.75F, 0F));
        assertEquals(4, s.v.size());
        float minY = Float.MAX_VALUE;
        for (float[] v : s.v) {
            minY = Math.min(minY, v[1]);
            assertTrue(v[1] >= 0.25F - 1e-6F, "kept below the cut: " + v[1]);
            assertEquals(v[1], v[4], 1e-6F, "uv follows the cut");
        }
        assertEquals(0.25F, minY, 1e-6F);
    }

    @Test
    void faceWhollyPastTheCutIsGone() {
        Sink s = run(0.5F, b -> quad(b, 0F, 0F, 0.25F, 0F));
        assertEquals(0, s.v.size());
    }

    @Test
    void geometryAwayFromTheArmIsGone() {
        // a head or body moved away while the layers ran
        Sink s = run(Float.NaN, b -> {
            quad(b, 0F, 1000F, 1000.5F, 0F);
            quad(b, 0F, 0F, 0.5F, 0F);
        });
        assertEquals(4, s.v.size());
        assertEquals(0.5F, s.v.get(2)[1], 1e-6F);
    }

    @Test
    void cornerCutMakesAPentagonAsTwoQuads() {
        // a quad turned so that the cut takes one corner: five points, sent as two quads
        Sink s = run(0.1F, b -> {
            b.addVertex(0F, 0F, 0F, -1, 0F, 0F, 0, 0, 0F, 0F, 1F);
            b.addVertex(1F, 0.5F, 0F, -1, 0F, 0F, 0, 0, 0F, 0F, 1F);
            b.addVertex(0.5F, 1.5F, 0F, -1, 0F, 0F, 0, 0, 0F, 0F, 1F);
            b.addVertex(-0.5F, 1F, 0F, -1, 0F, 0F, 0, 0, 0F, 0F, 1F);
        });
        assertEquals(8, s.v.size());
        for (float[] v : s.v) assertTrue(v[1] >= 0.1F - 1e-6F);
    }

    @Test
    void powerArmorHalvesRecoil() {
        // no armour, ordinary armour: x1; power armour: the key (0.5 by default); 1 = off
        assertEquals(1F, PowerArmor.factor(false, 0.5F, false));
        assertEquals(0.5F, PowerArmor.factor(true, 0.5F, false));
        assertEquals(1F, PowerArmor.factor(true, 1F, false));
        assertEquals(0F, PowerArmor.factor(true, -2F, false));
        // a server minimum: the camera's no lower than half
        assertEquals(0.5F, PowerArmor.factor(true, 0.1F, true));
        assertEquals(0.8F, PowerArmor.factor(true, 0.8F, true));
        assertEquals(1F, PowerArmor.factor(false, 0.1F, true));
        assertEquals(1F, PowerArmor.factor(true, Float.NaN, false));
        assertEquals(0.5F, Config.powerArmorRecoil);
    }
}
