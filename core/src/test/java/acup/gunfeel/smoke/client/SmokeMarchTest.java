package acup.gunfeel.smoke.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import acup.gunfeel.host.render.RecordingRenderPort;
import acup.gunfeel.host.render.ParamsWriter;
import acup.gunfeel.smoke.SmokeCloud;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeKind;

class SmokeMarchTest {
    @Test void failedWriteAbortsWithoutDoneAndPreservesTheException() {
        for (Throwable failure : List.of(new RuntimeException("ошибка записи"), new AssertionError("ошибка записи"))) {
            boolean[] called = new boolean[2];
            RecordingRenderPort p = new RecordingRenderPort() {
                @Override public ParamsWriter map(int params, int length) {
                    ParamsWriter delegate = super.map(params, length);
                    return new ParamsWriter() {
                        int vec4s;
                        @Override public ParamsWriter vec4(float x, float y, float z, float w) {
                            if (++vec4s == 3) {
                                if (failure instanceof RuntimeException e) throw e;
                                throw (Error) failure;
                            }
                            delegate.vec4(x, y, z, w);
                            return this;
                        }
                        @Override public ParamsWriter mat4(org.joml.Matrix4fc m) {
                            delegate.mat4(m);
                            return this;
                        }
                        @Override public void done() {
                            called[1] = true;
                            delegate.done();
                        }
                        @Override public void abort() {
                            called[0] = true;
                            delegate.abort();
                        }
                    };
                }
            };
            int params = p.params("SmokeInfo");
            SmokeMarch.Frame f = new SmokeMarch.Frame();
            f.view = new Matrix4f();
            assertSame(failure, assertThrows(Throwable.class, () -> SmokeMarch.write(p, params, f, List.of(slot(0)))));
            assertTrue(called[0], "abort вызван");
            assertFalse(called[1], "done не вызван");
            assertNull(p.mapped);
        }
    }

    static SmokeAtlasSlots.Slot slot(int index) {
        SmokeCloud c = new SmokeCloud(index, new SmokeGrid(SmokeKind.SMOKE, index, 0, 1, 1, 0, 0),
                10, 20, 30, 0, 0, 1, 1) {};
        c.grid.scan((x, y, z) -> false);
        c.grid.place();
        c.grid.step();
        SmokeAtlasSlots.Slot s = new SmokeAtlasSlots.Slot(index);
        s.cloud = c;
        return s;
    }

    @Test void meetIncludesOneBlockAndChainsTransitively() {
        double[][] boxes = {{0,0,0,1,1,1}, {1.95,0,0,2.95,1,1}, {3.9,0,0,4.9,1,1}};
        int[][] g = SmokeMarch.groups(boxes, 0,0,0);
        assertEquals(1, g.length);
        assertArrayEquals(new int[] {0,1,2}, g[0]);
        boxes[1][0] = 2.001;
        assertEquals(2, SmokeMarch.groups(boxes, 0,0,0).length);
    }

    @Test void groupsRunFarToNearAndUnionUsesMinThenMax() {
        double[][] boxes = {{0,0,0,1,1,1}, {10,2,3,11,4,6}, {-30,-5,-4,-29,-2,-1}};
        int[][] g = SmokeMarch.groups(boxes, 0,0,0);
        assertArrayEquals(new int[] {2}, g[0]);
        assertArrayEquals(new int[] {1}, g[1]);
        assertArrayEquals(new int[] {0}, g[2]);
        assertArrayEquals(new double[] {-30,-5,-4,11,4,6}, SmokeMarch.union(boxes, new int[] {1,2}));
    }

    @Test void cameraIncludesAllFacesAndRejectsJustOutside() {
        var s = slot(0);
        double[] b = SmokeMarch.worldBox(s.cloud());
        for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 2; side++) {
            double[] p = {(b[0]+b[3])/2, (b[1]+b[4])/2, (b[2]+b[5])/2};
            p[axis] = b[axis + side*3];
            assertTrue(SmokeMarch.cameraInBox(List.of(s), p[0],p[1],p[2]));
            p[axis] += side == 0 ? -1e-6 : 1e-6;
            assertFalse(SmokeMarch.cameraInBox(List.of(s), p[0],p[1],p[2]));
        }
    }

    @Test void behindEyeAndOverNineTenthsUseWholeScreen() {
        var s = slot(0);
        assertNull(SmokeMarch.screenRect(List.of(s), new Matrix4f().perspective(1, 1, .05F, 512),
                10,20,0,1000,1000,1));
        double[] b = SmokeMarch.worldBox(s.cloud());
        assertNull(SmokeMarch.screenRect(List.of(s), new Matrix4f(),
                (b[0]+b[3])/2,(b[1]+b[4])/2,(b[2]+b[5])/2,1000,1000,1));
    }

    @Test void std140OffsetsAndEmptySlotsAreZeroed() {
        RecordingRenderPort p = new RecordingRenderPort();
        int params = p.params("SmokeInfo");
        SmokeMarch.Frame f = new SmokeMarch.Frame();
        f.view = new Matrix4f().translate(3,4,5);
        f.projView = new Matrix4f();
        f.depthAcross = 2; f.depthUp = 3; f.seconds = 7; f.partial = .25F;
        f.maxSteps = 144; f.stepLength = .5F; f.lowInside = true; f.front = true; f.through = .02F;
        var s = slot(4);
        assertEquals(1, SmokeMarch.write(p,params,f,List.of(s)));
        assertEquals(1728,p.mapped.length);
        ByteBuffer b = ByteBuffer.wrap(p.mapped).order(ByteOrder.nativeOrder());
        assertEquals(3,b.getFloat(48)); assertEquals(4,b.getFloat(52)); assertEquals(5,b.getFloat(56));
        assertEquals(2,b.getFloat(64)); assertEquals(7,b.getFloat(72)); assertEquals(144,b.getFloat(76));
        assertEquals(.25F,b.getFloat(88)); assertEquals(.5F,b.getFloat(92));
        assertEquals(1,b.getFloat(96)); assertEquals(1,b.getFloat(100));
        assertEquals(3,b.getFloat(104)); assertEquals(1,b.getFloat(108));
        assertEquals(.02F,b.getFloat(124)); assertEquals(1,b.getFloat(128)); assertEquals(1,b.getFloat(188));
        assertEquals(s.cloud().baseX(),b.getFloat(192)); assertEquals(4,b.getFloat(204));
        int[] cells = SmokeMarch.cells(s.cloud());
        for (int i = 0; i < 3; i++) {
            assertEquals(cells[i],b.getFloat(208+i*4));
            assertEquals(cells[i+3],b.getFloat(224+i*4));
        }
        assertEquals(1,b.getFloat(252));
        for (int i=256;i<1216;i++) assertEquals(0,p.mapped[i],"empty cloud byte "+i);
        double[] box = SmokeMarch.worldBox(s.cloud());
        for (int i=0;i<3;i++) {
            assertEquals((float)box[i],b.getFloat(1216+i*4));
            assertEquals((float)box[i+3],b.getFloat(1232+i*4));
        }
        assertEquals(0,b.getFloat(1228)); assertEquals(1,b.getFloat(1244));
        for (int i=1248;i<1728;i++) assertEquals(0,p.mapped[i],"empty group byte "+i);
        SmokeMarch.write(p,params,f,List.of());
        for (int i=192;i<1728;i++) assertEquals(0,p.mapped[i],"stale byte "+i);
    }
}
