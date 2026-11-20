package acup.gunfeel.smoke.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.buffers.Std140Builder;

import acup.gunfeel.host.render.RecordingRenderPort;
import acup.gunfeel.smoke.SmokeCloud;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeKind;
import net.minecraft.world.phys.Vec3;

/**
 * The render port's step 3 (notes/ports-base/render-step3.md): the smoke's march as core works it out (SmokeMarch, through
 * SmokeRender.frame and a port's map) against SmokeRender's own lines before the port, taken from git word for word
 * (OldMarch below): SmokeInfo's bytes, the groups, the part of the screen, the camera in a box, the copies' corner. 2000
 * frames: 1-16 clouds of a pool (grown 0-25 ticks, side by side, apart and overlapping), the camera outside and in a box,
 * a projection or none, every standOld.
 */
class SmokeMarchBitTest {
    // a cloud of core's in open air, its burst at a world block
    static final class Cloud extends SmokeCloud {
        Cloud(int id, SmokeKind kind, long seed, int x, int y, int z, float dirX, float dirZ) {
            super(id, new SmokeGrid(kind, seed, dirX, dirZ, 1F, 0F, 0F), x, y, z, 0L, dirX, dirZ, 1F);
        }
    }

    private static List<Cloud> pool(Random r) {
        List<Cloud> pool = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            float a = r.nextFloat() * 6.2832F;
            Cloud c = new Cloud(i, i % 3 == 0 ? SmokeKind.WP : SmokeKind.SMOKE, r.nextLong(), r.nextInt(70) - 35, 64 + r.nextInt(6), r.nextInt(70) - 35,
                    (float) Math.cos(a), (float) Math.sin(a));
            c.grid.scan((x, y, z) -> y < SmokeGrid.OY - 1);
            c.grid.place();
            int steps = r.nextInt(26);
            for (int s = 0; s < steps; s++) c.grid.step();
            pool.add(c);
        }
        return pool;
    }

    @Test
    void theSameAsBeforeThePort() {
        Random r = new Random(20261120L);
        List<Cloud> pool = pool(r);
        RecordingRenderPort port = new RecordingRenderPort();
        int params = port.params("Smoke info");
        SmokeMarch.Frame frame = new SmokeMarch.Frame();
        int[] stands = {0, SmokeRender.OLD, SmokeRender.OLD_FRONT, SmokeRender.OLD_THROUGH, SmokeRender.OLD_RECT | SmokeRender.OLD_DEPTH};
        int inside = 0, nullRects = 0, multiGroups = 0;
        for (int seed = 0; seed < 2000; seed++) {
            int n = 1 + r.nextInt(16);
            List<Cloud> chosen = new ArrayList<>(pool);
            java.util.Collections.shuffle(chosen, r);
            chosen = chosen.subList(0, n);
            List<Integer> index = new ArrayList<>();
            for (int i = 0; i < SmokeAtlasSlots.SLOTS; i++) index.add(i);
            java.util.Collections.shuffle(index, r);
            List<SmokeAtlasSlots.Slot> slots = new ArrayList<>();
            List<OldMarch.OldSlot> oldSlots = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                SmokeAtlasSlots.Slot s = new SmokeAtlasSlots.Slot(index.get(i));
                s.cloud = chosen.get(i);
                slots.add(s);
                oldSlots.add(new OldMarch.OldSlot(index.get(i), chosen.get(i)));
            }
            Vec3 cam;
            if (r.nextInt(4) == 0) {
                double[] b = SmokeMarch.worldBox(chosen.get(r.nextInt(n)));
                cam = new Vec3(b[0] + (b[3] - b[0]) * r.nextDouble(), b[1] + (b[4] - b[1]) * r.nextDouble(), b[2] + (b[5] - b[2]) * r.nextDouble());
            } else cam = new Vec3(r.nextDouble() * 120 - 60, 60 + r.nextDouble() * 20, r.nextDouble() * 120 - 60);
            Matrix4f view = new Matrix4f().rotateXYZ(r.nextFloat() * 6.3F, r.nextFloat() * 6.3F, r.nextFloat() * 6.3F);
            Matrix4f pv = r.nextInt(7) == 0 ? null
                    : new Matrix4f().perspective(0.6F + r.nextFloat() * 1.4F, 1.2F + r.nextFloat(), 0.05F, 512F, r.nextBoolean()).mul(view);
            int width = 320 + r.nextInt(1700), height = 240 + r.nextInt(900), div = 1 + r.nextInt(4);
            int w = Math.max(1, width / div), h = Math.max(1, height / div);
            int q = 1 + r.nextInt(4), standOld = stands[r.nextInt(stands.length)];
            boolean zeroToOne = r.nextBoolean();
            float partial = r.nextFloat(), seconds = r.nextFloat() * 3600F;

            boolean oldIn = OldMarch.cameraInBox(oldSlots, cam);
            assertEquals(oldIn, SmokeMarch.cameraInBox(slots, cam.x, cam.y, cam.z), "camera in a box, frame " + seed);
            if (oldIn) inside++;
            boolean lowInside = div <= 2 && oldIn;
            if (pv != null) {
                int[] was = OldMarch.screenRect(oldSlots, pv, cam, width, height, div);
                int[] now = SmokeMarch.screenRect(slots, pv, cam.x, cam.y, cam.z, width, height, div);
                assertArrayEquals(was, now, "screen rect, frame " + seed);
                if (was == null) nullRects++;
                assertArrayEquals(OldMarch.copyCorner(was, width, height), SmokeRender.copyCorner(now, width, height), "copy corner, frame " + seed);
            }

            SmokeRender.frame(frame, view, cam, width, w, height, h, q, lowInside, partial, seconds, zeroToOne, standOld, pv);
            ByteBuffer out = ByteBuffer.allocateDirect(SmokeRender.UBO_SIZE).order(ByteOrder.nativeOrder());
            int oldGroups = OldMarch.info(out, view, oldSlots, cam, width, w, height, h, q, partial, seconds,
                    zeroToOne, lowInside, standOld, pv);
            int groups = SmokeMarch.write(port, params, frame, slots);
            assertEquals(oldGroups, groups, "groups, frame " + seed);
            if (groups > 1) multiGroups++;
            byte[] want = new byte[SmokeRender.UBO_SIZE];
            out.position(0).get(want);
            assertArrayEquals(want, port.mapped, "SmokeInfo's bytes, frame " + seed);
            if (seed < 12 || seed % 127 == 0) acup.gunfeel.MapWriterProbe.verify(frame, slots, seed);
        }
        // the frames went through every way
        assertEquals(true, inside > 100 && nullRects > 50 && multiGroups > 300, "inside " + inside + ", null rects " + nullRects + ", groups > 1 " + multiGroups);
    }

    // a box's near corners just before the eye (w about 0.045, 0.05, 0.055 with no view turn): where the part of the
    // screen gives up (null) is where it gave up before
    @Test
    void cornersAtTheEyeAsBefore() {
        Random r = new Random(11L);
        List<Cloud> pool = pool(r);
        for (Cloud c : pool) {
            SmokeAtlasSlots.Slot s = new SmokeAtlasSlots.Slot(0);
            s.cloud = c;
            OldMarch.OldSlot o = new OldMarch.OldSlot(0, c);
            double[] b = SmokeMarch.worldBox(c);
            for (double d : new double[] {0.04, 0.045, 0.0499, 0.05, 0.0501, 0.055, 0.06}) {
                // in front of the eye, and off to the side and up (the part then less than the screen: not null for its size)
                for (Vec3 cam : new Vec3[] {new Vec3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, b[5] + d), new Vec3(b[0] - 3, b[1] - 3, b[5] + d)}) {
                    Matrix4f pv = new Matrix4f().perspective(1.2F, 1.6F, 0.01F, 512F);
                    int[] was = OldMarch.screenRect(List.of(o), pv, cam, 1920, 1080, 2);
                    assertArrayEquals(was, SmokeMarch.screenRect(List.of(s), pv, cam.x, cam.y, cam.z, 1920, 1080, 2), "the corner at " + d);
                    if (was != null && d < 0.05) sideRects++;
                }
            }
        }
        assertEquals(true, sideRects == 0, "a corner nearer than 0.05 gives the whole screen");
    }

    private static int sideRects;

    // the camera on a box's faces and edges: in it as before (>= and <=)
    @Test
    void cameraOnTheFacesAsBefore() {
        Random r = new Random(13L);
        int in = 0;
        for (Cloud c : pool(r)) {
            SmokeAtlasSlots.Slot s = new SmokeAtlasSlots.Slot(0);
            s.cloud = c;
            OldMarch.OldSlot o = new OldMarch.OldSlot(0, c);
            double[] b = SmokeMarch.worldBox(c);
            for (int k = 0; k < 27; k++) {
                double x = new double[] {b[0], (b[0] + b[3]) / 2, b[3]}[k % 3], y = new double[] {b[1], (b[1] + b[4]) / 2, b[4]}[k / 3 % 3],
                        z = new double[] {b[2], (b[2] + b[5]) / 2, b[5]}[k / 9];
                for (double e : new double[] {-1e-9, 0, 1e-9}) {
                    Vec3 cam = new Vec3(x + e, y + e, z + e);
                    boolean was = OldMarch.cameraInBox(List.of(o), cam);
                    assertEquals(was, SmokeMarch.cameraInBox(List.of(s), cam.x, cam.y, cam.z), "camera " + cam);
                    if (was) in++;
                }
            }
        }
        assertEquals(true, in > 1000, "in " + in);
    }

    @Test
    void groupsAndUnionAsBefore() {
        Random r = new Random(7L);
        for (int seed = 0; seed < 2000; seed++) {
            int n = 1 + r.nextInt(16);
            double[][] boxes = new double[n][];
            for (int i = 0; i < n; i++) {
                double x = r.nextInt(60), y = r.nextInt(10), z = r.nextInt(60);
                boxes[i] = new double[] {x, y, z, x + 1 + r.nextInt(26), y + 1 + r.nextInt(14), z + 1 + r.nextInt(26)};
            }
            double cx = r.nextDouble() * 100 - 20, cy = r.nextDouble() * 20, cz = r.nextDouble() * 100 - 20;
            int[][] was = OldMarch.groups(boxes, cx, cy, cz), now = SmokeMarch.groups(boxes, cx, cy, cz);
            assertEquals(was.length, now.length, "groups, seed " + seed);
            for (int g = 0; g < was.length; g++) {
                assertArrayEquals(was[g], now[g], "group " + g + ", seed " + seed);
                assertArrayEquals(OldMarch.union(boxes, was[g]), SmokeMarch.union(boxes, now[g]), "union, seed " + seed);
            }
        }
    }

    // ---- generated by scratchpad step3/oldmarch.py from c84fd5f: SmokeRender before the port, word for word but the
    // clouds' type (core's SmokeCloud: these use its grid and box only), the slot (OldSlot) and the map (a ByteBuffer)
    static final class OldMarch {
        record OldSlot(int index, SmokeCloud cloud) {
        }

        static int[] copyCorner(int[] rect, int width, int height) {
            return rect == null ? new int[] {width, height} : new int[] {Math.min(width, rect[0] + rect[2]), Math.min(height, rect[1] + rect[3])};
        }
        static int[] screenRect(List<OldSlot> slots, Matrix4f pv, Vec3 cam, int width, int height, int div) {
            float lx = Float.MAX_VALUE, ly = Float.MAX_VALUE, hx = -Float.MAX_VALUE, hy = -Float.MAX_VALUE;
            Vector4f v = new Vector4f();
            for (OldSlot s : slots) {
                double[] b = worldBox(s.cloud());
                for (int k = 0; k < 8; k++) {
                    v.set((float) ((k & 1) == 0 ? b[0] - cam.x : b[3] - cam.x), (float) ((k & 2) == 0 ? b[1] - cam.y : b[4] - cam.y),
                            (float) ((k & 4) == 0 ? b[2] - cam.z : b[5] - cam.z), 1F);
                    pv.transform(v);
                    if (!(v.w > 0.05F)) return null;
                    float x = v.x / v.w, y = v.y / v.w;
                    lx = Math.min(lx, x);
                    hx = Math.max(hx, x);
                    ly = Math.min(ly, y);
                    hy = Math.max(hy, y);
                }
            }
            int pad = 2 * div + 2;
            int x0 = clampPx((lx * 0.5F + 0.5F) * width - pad, width), x1 = clampPx((hx * 0.5F + 0.5F) * width + pad + 1, width);
            int y0 = clampPx((ly * 0.5F + 0.5F) * height - pad, height), y1 = clampPx((hy * 0.5F + 0.5F) * height + pad + 1, height);
            if (x1 <= x0 || y1 <= y0 || (long) (x1 - x0) * (y1 - y0) > (long) width * height * 9 / 10) return null;
            return new int[] {x0, y0, x1 - x0, y1 - y0};
        }
        private static int clampPx(float v, int size) {
            return (int) Math.max(0F, Math.min(size, Math.floor(v)));
        }
        static int[] cells(SmokeCloud c) {
            int[] box = c.grid.box();
            return new int[] {Math.max(-1, box[0] - 1), Math.max(-1, box[1] - 1), Math.max(-1, box[2] - 1),
                    Math.min(SmokeGrid.SX + 1, box[3] + 2), Math.min(SmokeGrid.SY + 1, box[4] + 2), Math.min(SmokeGrid.SZ + 1, box[5] + 2)};
        }
        static double[] worldBox(SmokeCloud c) {
            int[] k = cells(c);
            return new double[] {c.baseX() + k[0], c.baseY() + k[1], c.baseZ() + k[2], c.baseX() + k[3], c.baseY() + k[4], c.baseZ() + k[5]};
        }
        static int[][] groups(double[][] boxes, double cx, double cy, double cz) {
            int n = boxes.length;
            int[] root = new int[n];
            for (int i = 0; i < n; i++) root[i] = i;
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) if (meet(boxes[i], boxes[j])) {
                int a = find(root, i), b = find(root, j);
                if (a != b) root[Math.max(a, b)] = Math.min(a, b);
            }
            List<List<Integer>> by = new ArrayList<>();
            int[] slot = new int[n];
            java.util.Arrays.fill(slot, -1);
            for (int i = 0; i < n; i++) {
                int r = find(root, i);
                if (slot[r] < 0) {
                    slot[r] = by.size();
                    by.add(new ArrayList<>());
                }
                by.get(slot[r]).add(i);
            }
            int[][] out = new int[by.size()][];
            for (int g = 0; g < out.length; g++) out[g] = by.get(g).stream().mapToInt(Integer::intValue).toArray();
            double[] far = new double[out.length];
            for (int g = 0; g < out.length; g++) {
                double[] u = union(boxes, out[g]);
                double x = (u[0] + u[3]) * 0.5 - cx, y = (u[1] + u[4]) * 0.5 - cy, z = (u[2] + u[5]) * 0.5 - cz;
                far[g] = x * x + y * y + z * z;
            }
            Integer[] idx = new Integer[out.length];
            for (int g = 0; g < idx.length; g++) idx[g] = g;
            java.util.Arrays.sort(idx, Comparator.comparingDouble((Integer g) -> -far[g]));
            int[][] sorted = new int[out.length][];
            for (int g = 0; g < idx.length; g++) sorted[g] = out[idx[g]];
            return sorted;
        }
        private static boolean meet(double[] a, double[] b) {
            for (int k = 0; k < 3; k++) if (a[k] > b[k + 3] + MEET || b[k] > a[k + 3] + MEET) return false;
            return true;
        }
        private static int find(int[] root, int i) {
            while (root[i] != i) i = root[i] = root[root[i]];
            return i;
        }
        static double[] union(double[][] boxes, int[] which) {
            double[] u = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int i : which) for (int k = 0; k < 3; k++) {
                u[k] = Math.min(u[k], boxes[i][k]);
                u[k + 3] = Math.max(u[k + 3], boxes[i][k + 3]);
            }
            return u;
        }
        private static boolean cameraInBox(List<OldSlot> slots, Vec3 cam) {
            for (OldSlot s : slots) {
                double[] b = worldBox(s.cloud());
                if (cam.x >= b[0] && cam.x <= b[3] && cam.y >= b[1] && cam.y <= b[4] && cam.z >= b[2] && cam.z <= b[5]) return true;
            }
            return false;
        }
        static final double MEET = 1.0;
        static int maxSteps(int q) {
            return switch (q) {
                case 1 -> 16;
                case 2 -> 28;
                case 4 -> 72;
                default -> 48;
            };
        }
        static float stepLength(int q) {
            return switch (q) {
                case 1 -> 0.6F;
                case 2 -> 0.45F;
                case 4 -> 0.22F;
                default -> 0.3F;
            };
        }
        // c84fd5f: boxes/order from marchAndLay; info method below copied with only parameter/type substitutions.
        static int info(java.nio.ByteBuffer out, org.joml.Matrix4fc modelView, List<OldSlot> slots, Vec3 cam, int depthW, int w,
                int depthH, int h, int q, float partial, float seconds, boolean zeroToOne, boolean lowInside,
                int standOld, Matrix4f projView) {
                double[][] boxes = new double[slots.size()][];
                for (int i = 0; i < boxes.length; i++) boxes[i] = worldBox(slots.get(i).cloud());
                int[][] groups = groups(boxes, cam.x, cam.y, cam.z);
                List<OldSlot> order = new ArrayList<>(slots.size());
                for (int[] g : groups) for (int i : g) order.add(slots.get(i));
            writeInfo(out, modelView, depthW, depthH, w, h, zeroToOne, seconds, q, partial, groups, lowInside, projView, order, boxes, cam, standOld);
            return groups.length;
        }
        private static final Matrix4f VIEW = new Matrix4f(), INVERSE = new Matrix4f();
        static void writeInfo(java.nio.ByteBuffer data, org.joml.Matrix4fc modelView, int depthW, int depthH, int w, int h, boolean zeroToOne, float seconds, int q,
                float partial, int[][] groups, boolean lowInside, Matrix4f projView, List<OldSlot> order, double[][] boxes, Vec3 cam, int standOld) {
            Std140Builder b = Std140Builder.intoBuffer(data);
            b.putMat4f(VIEW.set(modelView));
            b.putVec4((float) depthW / w, zeroToOne ? 1F : 0F, seconds, maxSteps(q));
            b.putVec4(1F / SmokeAtlas.WIDTH, 1F / SmokeAtlas.HEIGHT, partial, stepLength(q));
            b.putVec4(groups.length, lowInside ? 1F : 0F, (float) depthH / h, (standOld & SmokeRender.OLD_FRONT) != 0 || projView == null ? 0F : 1F);
            b.putVec4((float) (Math.floor(cam.x / SmokeRender.ANCHOR) * SmokeRender.ANCHOR - cam.x), (float) (Math.floor(cam.y / SmokeRender.ANCHOR) * SmokeRender.ANCHOR - cam.y),
                    (float) (Math.floor(cam.z / SmokeRender.ANCHOR) * SmokeRender.ANCHOR - cam.z), (standOld & SmokeRender.OLD_THROUGH) != 0 ? 0.01F : SmokeRender.THROUGH_MIN);
            b.putMat4f(projView != null ? INVERSE.set(projView).invert() : INVERSE.identity());
            for (int i = 0; i < SmokeAtlas.SLOTS; i++) {
                if (i >= order.size()) {
                    for (int k = 0; k < 4; k++) b.putVec4(0F, 0F, 0F, 0F);
                    continue;
                }
                OldSlot s = order.get(i);
                SmokeCloud c = s.cloud();
                int[] cells = cells(c);
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
                double[] u = union(boxes, groups[i]);
                b.putVec4((float) (u[0] - cam.x), (float) (u[1] - cam.y), (float) (u[2] - cam.z), first);
                b.putVec4((float) (u[3] - cam.x), (float) (u[4] - cam.y), (float) (u[5] - cam.z), groups[i].length);
                first += groups[i].length;
            }
        }    }

}
