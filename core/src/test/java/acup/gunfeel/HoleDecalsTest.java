package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.render.CameraFrame;
import acup.gunfeel.host.render.Intent;
import acup.gunfeel.host.render.RecordingRenderPort;

// What HoleDecals asks of the render port, frame by frame: which holes go in which batch and group, where each is drawn
// in each way the frame is laid, and when a batch is written anew (render-step1.md §3). The vertices themselves are
// HoleDecalsBitTest's (26.2: against the drawing before the port).
class HoleDecalsTest {
    private static final int K = HoleDecals.KINDS;
    private static final long NOW = 100_000_000_000L, SECOND = 1_000_000_000L;
    private RecordingRenderPort port;
    private final Set<HoleDecal> under = new HashSet<>();
    private int drawn;
    private boolean cache, holes;
    private float life;

    @BeforeEach
    void setUp() {
        cache = Config.holeCache;
        holes = Config.bulletHoles;
        life = Config.holeLife;
        Config.holeCache = true;
        Config.bulletHoles = true;
        Config.holeLife = 30F;
        port = new RecordingRenderPort();
        HoleDecals.clear();
        HoleDecals.init(port, new HoleDecals.Host() {
            @Override
            public boolean under(HoleDecal h) {
                return h.late && under.contains(h);
            }

            @Override
            public void batchDrawn() {
                drawn++;
            }

            @Override
            public boolean wantProbe() {
                return false;
            }

            @Override
            public void probe(Runnable reference, Runnable cached) {
            }
        });
        port.camX = 0.5;
        port.camY = 0.5;
        port.camZ = -5;
        port.nanos = NOW;
    }

    @AfterEach
    void tearDown() {
        HoleDecals.clear();
        Config.holeCache = cache;
        Config.bulletHoles = holes;
        Config.holeLife = life;
    }

    // a flat hole on a north face (z = 0, seen from -z), its rect the whole block face
    private HoleDecal hole(int kind, boolean late, boolean cull, long born) {
        HoleDecal h = new HoleDecal(0.5, 0.5, 0, 2, -1, 0, born, 0, kind, 0.2F, new double[] {0, 1, 0, 1}, 0, 0, 0, 0, null, Double.NaN, late, cull,
                null, false, false, false, false, null);
        HoleDecals.HOLES.addLast(h);
        return h;
    }

    private int material(int kind, boolean late, boolean cull) {
        return port.material(HoleDecals.material(kind, late, cull));
    }

    private static int queuedPart(int group) {
        return group + 1;
    }

    private static int latePart(int group) {
        return 4 * K + 1 + group;
    }

    private List<String> frame(int translucency) {
        port.translucency = translucency;
        port.log.clear();
        // the frame's order on 26.2 (RenderPort26): the queue is filled first (SubmitCustomGeometryEvent, and over the glass
        // there under the chain), then the passes run: after the solid features, then over the glass (AfterWeather)
        boolean chain = translucency == CameraFrame.VANILLA_CHAIN;
        port.run(Intent.QUEUE);
        if (chain) port.run(Intent.OVER_GLASS);
        port.run(Intent.AFTER_OPAQUE);
        if (!chain) port.run(Intent.OVER_GLASS);
        return List.copyOf(port.log);
    }

    @Test
    void registersOnce() {
        assertEquals(6 * K, port.parts());
        assertEquals(4 * K, port.materials.size() - 1);
        for (int intent : new int[] {Intent.AFTER_OPAQUE, Intent.QUEUE, Intent.OVER_GLASS}) assertEquals(1, port.drawers(intent));
    }

    @Test
    void plainKeepsBatchesAndRewritesOnlyTheOpenOne() {
        hole(0, false, true, NOW - 10 * SECOND);
        HoleDecal glass = hole(1, true, true, NOW - 10 * SECOND);
        under.add(glass);
        // fading: sent each frame
        hole(0, false, true, NOW - 28 * SECOND);
        int early = material(0, false, true), late = material(1, true, true);
        String fading = "queue " + early + " " + queuedPart(2 * K) + " 0 -> 4";
        // the first frame: the fading one into the queue; the stone's batch (buffer 1) and the glass's (2, its backs 3:
        // none for a culled flat hole) written; the glass's backs drawn under the glass (none), its holes over it
        assertEquals(List.of(fading, "fill 1 64 -> 4", "drawAt " + early + " 1 0 4 0.5 0.5 0.0 1.0", "fill 2 64 -> 4", "fill 3 64 -> 0",
                "drawAt " + late + " 2 0 4 0.5 0.5 0.0 1.0"), frame(CameraFrame.PLAIN));
        assertEquals(2, drawn);
        // a frame more: nothing written anew
        assertEquals(List.of(fading, "drawAt " + early + " 1 0 4 0.5 0.5 0.0 1.0", "drawAt " + late + " 2 0 4 0.5 0.5 0.0 1.0"), frame(CameraFrame.PLAIN));
        // a hole more on stone: its open batch written anew, the glass's not
        hole(0, false, true, NOW);
        assertEquals(List.of(fading, "fill 1 128 -> 8", "drawAt " + early + " 1 0 8 0.5 0.5 0.0 1.0", "drawAt " + late + " 2 0 4 0.5 0.5 0.0 1.0"),
                frame(CameraFrame.PLAIN));
        // 18 s on: the first stone hole and the glass's fade, out of their batches (the glass's gone, its buffers freed;
        // the stone's written anew with the one left), the old fading one gone by age (Tracers expires them first). The
        // glass's drawn by the pass of its own over the glass, at the clock the frame reads there
        port.nanos = NOW + 18 * SECOND;
        HoleDecals.expire(port.nanos, HoleDecals.life());
        assertEquals(List.of("free 2", "free 3", fading, "fill 1 64 -> 4", "drawAt " + early + " 1 0 4 0.5 0.5 0.0 1.0",
                "queue " + late + " " + latePart(1 + K) + " 1024 -> 4"), frame(CameraFrame.PLAIN));
        assertEquals(1, HoleDecals.BATCHES.size());
    }

    @Test
    void chainSendsTheLateOnesEachFrameWithTheirCopyUnderTheGlass() {
        HoleDecal glass = hole(1, true, true, NOW - 10 * SECOND);
        under.add(glass);
        int lateCull = material(1, true, true), earlyCull = material(1, false, true);
        // the camera in front: the late group only
        assertEquals(List.of("queue " + lateCull + " " + queuedPart(1 + 3 * K) + " 0 -> 4"), frame(CameraFrame.VANILLA_CHAIN));
        // behind it (through its block): its copy under the glass first, with the early ones
        port.camZ = 5;
        assertEquals(List.of("queue " + earlyCull + " " + queuedPart(1 + 2 * K) + " 0 -> 4", "queue " + lateCull + " " + queuedPart(1 + 3 * K) + " 0 -> 4"),
                frame(CameraFrame.VANILLA_CHAIN));
        assertEquals(0, HoleDecals.BATCHES.size());
    }

    @Test
    void groupsGoInTheirOrderAndTwoSidedOnesApart() {
        // kinds and culling mixed, made out of order: sent by group (kind, then culled after not), each in the order made
        hole(2, false, true, NOW - 29 * SECOND);
        hole(0, false, false, NOW - 29 * SECOND);
        hole(2, false, true, NOW - 29 * SECOND);
        hole(1, false, false, NOW - 29 * SECOND);
        assertEquals(List.of("queue " + material(0, false, false) + " " + queuedPart(0) + " 0 -> 4", "queue " + material(1, false, false) + " " + queuedPart(1) + " 0 -> 4",
                "queue " + material(2, false, true) + " " + queuedPart(2 + 2 * K) + " 0 -> 8"), frame(CameraFrame.OIT));
    }

    @Test
    void nothingWhenHolesAreOff() {
        hole(0, false, true, NOW - 10 * SECOND);
        frame(CameraFrame.PLAIN);
        // off: the batches emptied (their buffers freed) and nothing drawn
        Config.bulletHoles = false;
        assertEquals(List.of("free 1"), frame(CameraFrame.PLAIN));
    }
}
