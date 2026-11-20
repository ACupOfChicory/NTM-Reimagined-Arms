package acup.gunfeel.smoke.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.buffers.Std140Builder;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import acup.gunfeel.smoke.TestClouds;
import net.minecraft.world.phys.Vec3;

/**
 * SmokeInfo's bytes as SmokeRender.info writes them, with its two kept matrices, against marchAndLay's block before
 * (OldInfo, from git word for word), which made two new matrices a frame and crashed the JIT's C2 of JDK 25 (notes/
 * ports-base/bugs.md). 2000 frames: 1-16 clouds of a pool (grown 0-25 ticks, side by side, apart and overlapping), the
 * camera outside and in a box, a projection or none, every standOld. OldInfo.info runs uncompiled (the 26.2 tests'
 * jvmArgs exclude it from the JIT): compiled, it is what crashes the JVM.
 */
class SmokeInfoBitTest {
    static final class OldInfo {
        // generated from 519acb5 by oldinfo.py: the block of marchAndLay word for word, its names made parameters or SmokeRender's
        static void info(ByteBuffer data, Matrix4fc modelView, int depthW, int depthH, int w, int h, boolean zeroToOne, float seconds, int q,
                float partial, int[][] groups, boolean lowInside, Matrix4f projView, List<SmokeAtlas.Slot> order, double[][] boxes, Vec3 cam) {
            Std140Builder b = Std140Builder.intoBuffer(data);
            b.putMat4f(new Matrix4f(modelView));
            b.putVec4((float) depthW / w, zeroToOne ? 1F : 0F, seconds, SmokeRender.maxSteps(q));
            b.putVec4(1F / SmokeAtlas.WIDTH, 1F / SmokeAtlas.HEIGHT, partial, SmokeRender.stepLength(q));
            b.putVec4(groups.length, lowInside ? 1F : 0F, (float) depthH / h, (SmokeRender.standOld & SmokeRender.OLD_FRONT) != 0 || projView == null ? 0F : 1F);
            b.putVec4((float) (Math.floor(cam.x / SmokeRender.ANCHOR) * SmokeRender.ANCHOR - cam.x), (float) (Math.floor(cam.y / SmokeRender.ANCHOR) * SmokeRender.ANCHOR - cam.y),
                    (float) (Math.floor(cam.z / SmokeRender.ANCHOR) * SmokeRender.ANCHOR - cam.z), (SmokeRender.standOld & SmokeRender.OLD_THROUGH) != 0 ? 0.01F : SmokeRender.THROUGH_MIN);
            b.putMat4f(projView != null ? new Matrix4f(projView).invert() : new Matrix4f());
            for (int i = 0; i < SmokeAtlas.SLOTS; i++) {
                if (i >= order.size()) {
                    for (int k = 0; k < 4; k++) b.putVec4(0F, 0F, 0F, 0F);
                    continue;
                }
                SmokeAtlas.Slot s = order.get(i);
                SmokeClouds.Cloud c = s.cloud();
                int[] cells = SmokeRender.cells(c);
                b.putVec4((float) (c.baseX() - cam.x), (float) (c.baseY() - cam.y), (float) (c.baseZ() - cam.z), s.index());
                b.putVec4(cells[0], cells[1], cells[2], c.kind().look());
                b.putVec4(cells[3], cells[4], cells[5], 0F);
                int rgb = c.kind().rgb();
                b.putVec4((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
            }
            for (int i = 0, first = 0; i < SmokeAtlas.SLOTS; i++) {
                if (i >= groups.length) {
                    b.putVec4(0F, 0F, 0F, 0F);
                    b.putVec4(0F, 0F, 0F, 0F);
                    continue;
                }
                double[] u = SmokeRender.union(boxes, groups[i]);
                b.putVec4((float) (u[0] - cam.x), (float) (u[1] - cam.y), (float) (u[2] - cam.z), first);
                b.putVec4((float) (u[3] - cam.x), (float) (u[4] - cam.y), (float) (u[5] - cam.z), groups[i].length);
                first += groups[i].length;
            }
        }

    }

    @Test
    void theSameBytesAsBefore() {
        Random r = new Random(20261120L);
        List<SmokeClouds.Cloud> pool = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            float a = r.nextFloat() * 6.2832F;
            pool.add(TestClouds.grown(i, i % 3 == 0 ? SmokeKind.WP : SmokeKind.SMOKE, r.nextLong(), r.nextInt(70) - 35, 64 + r.nextInt(6), r.nextInt(70) - 35,
                    (float) Math.cos(a), (float) Math.sin(a), r.nextInt(26)));
        }
        int[] stands = {0, SmokeRender.OLD, SmokeRender.OLD_FRONT, SmokeRender.OLD_THROUGH, SmokeRender.OLD_RECT | SmokeRender.OLD_DEPTH};
        int standWas = SmokeRender.standOld, noProjection = 0, multiGroups = 0, full = 0;
        ByteBuffer was = ByteBuffer.allocateDirect(SmokeRender.UBO_SIZE).order(ByteOrder.nativeOrder());
        ByteBuffer now = ByteBuffer.allocateDirect(SmokeRender.UBO_SIZE).order(ByteOrder.nativeOrder());
        byte[] a = new byte[SmokeRender.UBO_SIZE], b = new byte[SmokeRender.UBO_SIZE];
        try {
            for (int frame = 0; frame < 2000; frame++) {
                int n = 1 + r.nextInt(16);
                List<SmokeClouds.Cloud> chosen = new ArrayList<>(pool);
                Collections.shuffle(chosen, r);
                chosen = chosen.subList(0, n);
                List<Integer> index = new ArrayList<>();
                for (int i = 0; i < SmokeAtlas.SLOTS; i++) index.add(i);
                Collections.shuffle(index, r);
                List<SmokeAtlas.Slot> slots = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    SmokeAtlas.Slot s = new SmokeAtlas.Slot(index.get(i));
                    s.cloud = chosen.get(i);
                    slots.add(s);
                }
                Vec3 cam;
                if (r.nextInt(4) == 0) {
                    double[] box = SmokeRender.worldBox(chosen.get(r.nextInt(n)));
                    cam = new Vec3(box[0] + (box[3] - box[0]) * r.nextDouble(), box[1] + (box[4] - box[1]) * r.nextDouble(), box[2] + (box[5] - box[2]) * r.nextDouble());
                } else cam = new Vec3(r.nextDouble() * 120 - 60, 60 + r.nextDouble() * 20, r.nextDouble() * 120 - 60);
                Matrix4f view = new Matrix4f().rotateXYZ(r.nextFloat() * 6.3F, r.nextFloat() * 6.3F, r.nextFloat() * 6.3F);
                Matrix4f pv = r.nextInt(7) == 0 ? null
                        : new Matrix4f().perspective(0.6F + r.nextFloat() * 1.4F, 1.2F + r.nextFloat(), 0.05F, 512F, r.nextBoolean()).mul(view);
                if (pv == null) noProjection++;
                int width = 320 + r.nextInt(1700), height = 240 + r.nextInt(900), div = 1 + r.nextInt(4);
                int w = Math.max(1, width / div), h = Math.max(1, height / div);
                int q = 1 + r.nextInt(4);
                SmokeRender.standOld = stands[r.nextInt(stands.length)];
                boolean zeroToOne = r.nextBoolean(), lowInside = r.nextBoolean();
                float partial = r.nextFloat(), seconds = r.nextFloat() * 3600F;
                // as marchAndLay: the boxes, their groups far to near, the clouds in the groups' order
                double[][] boxes = new double[n][];
                for (int i = 0; i < n; i++) boxes[i] = SmokeRender.worldBox(slots.get(i).cloud());
                int[][] groups = SmokeRender.groups(boxes, cam.x, cam.y, cam.z);
                if (groups.length > 1) multiGroups++;
                if (n == SmokeAtlas.SLOTS) full++;
                List<SmokeAtlas.Slot> order = new ArrayList<>(n);
                for (int[] g : groups) for (int i : g) order.add(slots.get(i));
                // what an earlier frame left in the buffer must not show
                fill(was, r);
                fill(now, r);
                OldInfo.info(was, view, width, height, w, h, zeroToOne, seconds, q, partial, groups, lowInside, pv, order, boxes, cam);
                SmokeRender.info(now, view, width, height, w, h, zeroToOne, seconds, q, partial, groups, lowInside, pv, order, boxes, cam);
                was.position(0).get(a);
                now.position(0).get(b);
                was.clear();
                now.clear();
                assertArrayEquals(a, b, "SmokeInfo's bytes, frame " + frame);
            }
        } finally {
            SmokeRender.standOld = standWas;
        }
        assertTrue(noProjection > 200 && multiGroups > 300 && full > 50, "no projection " + noProjection + ", groups > 1 " + multiGroups + ", 16 clouds " + full);
    }

    private static void fill(ByteBuffer buf, Random r) {
        for (int i = 0; i < buf.capacity(); i++) buf.put(i, (byte) r.nextInt(256));
    }
}
