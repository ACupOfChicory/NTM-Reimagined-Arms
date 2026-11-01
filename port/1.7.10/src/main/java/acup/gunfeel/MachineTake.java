package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.hbm.blocks.BlockDummyable;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.world.World;

// One machine taken in the game (MachineScan) a little each frame, no frame given more than its budget: on the game's
// thread only what needs the world or GL, each a piece at a time (its blocks found a slice of the space around the core
// at a time, its renderer drawn once, or its blocks drawn one by one into the chunk; its textures read through pixel
// buffers, GlRead); the triangles, their cells and the model made on a thread of its own, which also puts them in the
// tables (ModelCells.taken, MachineFaces.taken).
final class MachineTake {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ntm-gunfeel machine take");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    });

    private static final int FOOTPRINT = 0, DRAW = 1, TEXTURES = 2, CHUNK = 3, ATLAS = 4, WORK = 5, DONE = 6;

    final int x, y, z, meta;
    final Block block;
    final String id, key;
    private int stage = FOOTPRINT;

    private int dx = -MachineCapture.REACH;
    private final List<int[]> footprint = new ArrayList<int[]>();

    private MachineCapture.Drawn drawn;
    private final Map<Integer, GlRead> reads = new LinkedHashMap<Integer, GlRead>();
    private final Map<Integer, int[][]> textures = new HashMap<Integer, int[][]>();
    private final List<Integer> toRead = new ArrayList<Integer>();

    private RenderBlocks rb;
    private int drawnBlocks;
    private final List<float[]> quads = new ArrayList<float[]>();
    private GlRead atlasRead;
    private ShapesScenario.Atlas atlas;

    private Future<Result> work;
    private Result result;
    String how;

    static final class Result {
        final Map<String, long[]> cells;
        final int triangles;

        Result(Map<String, long[]> cells, int triangles) {
            this.cells = cells;
            this.triangles = triangles;
        }
    }

    MachineTake(int x, int y, int z, Block block, int meta, String key) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block;
        this.meta = meta;
        this.key = key;
        this.id = Block.blockRegistry.getNameForObject(block);
    }

    // works on until `deadline` (System.nanoTime); true: done (result, or null: it draws nothing). A piece of a stage
    // starts only when the longest one of that stage so far still fits before the deadline (the first piece of a frame
    // always), so a frame goes over its budget by little; the renderer's draw starts a frame
    boolean step(Minecraft mc, long deadline) throws Exception {
        World w = mc.theWorld;
        boolean first = true;
        while (stage != DONE) {
            long t0 = System.nanoTime();
            if (!first && (t0 + slowest[stage] > deadline || stage == DRAW)) return false;
            first = false;
            int was = stage;
            try {
                if (!piece(mc, w, deadline)) return false;
            } finally {
                long t = System.nanoTime() - t0;
                if (t > slowest[was]) slowest[was] = t;
            }
        }
        return true;
    }

    // the longest a piece of each stage took
    private final long[] slowest = new long[DONE + 1];
    private static final String[] STAGES = {"blocks", "draw", "textures", "chunk", "atlas", "work"};

    String slowest() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < DONE; i++) if (slowest[i] > 0) b.append(b.length() == 0 ? "" : ", ").append(STAGES[i]).append(' ')
                .append(String.format(java.util.Locale.ROOT, "%.2f", slowest[i] / 1e6));
        return b.toString();
    }

    // one piece of the stage; false: no more this frame
    private boolean piece(Minecraft mc, World w, long deadline) throws Exception {
        {
            switch (stage) {
                case FOOTPRINT:
                    footprint(w);
                    break;
                case DRAW:
                    drawn = MachineCapture.drawn(w, x, y, z);
                    if (drawn != null) {
                        how = "tesr";
                        toRead.addAll(drawn.textures());
                        stage = TEXTURES;
                    } else {
                        how = "chunk";
                        rb = new RenderBlocks(w);
                        MachineCapture.initIcons(mc);
                        stage = CHUNK;
                    }
                    // the draw took its share
                    return false;
                case TEXTURES:
                    if (!drawn.named()) {
                        drawn.name();
                        break;
                    }
                    // one read started a piece: the GPU copies it meanwhile, its pixels are taken out from the next
                    // frame on
                    if (!toRead.isEmpty()) {
                        int tex = toRead.remove(0);
                        reads.put(tex, new GlRead(tex));
                        break;
                    }
                    for (Map.Entry<Integer, GlRead> e : reads.entrySet()) {
                        if (textures.containsKey(e.getKey())) continue;
                        if (!e.getValue().step(deadline)) return false;
                        textures.put(e.getKey(), e.getValue().pixels());
                    }
                    reads.clear();
                    submit(new TesrTriangles(drawn, textures));
                    break;
                case CHUNK:
                    if (drawnBlocks < footprint.size()) {
                        quads.add(MachineCapture.drawBlock(rb, block, footprint.get(drawnBlocks++)));
                        break;
                    }
                    atlas = MachineScan.cachedAtlas();
                    if (atlas == null) {
                        mc.getTextureManager().bindTexture(net.minecraft.client.renderer.texture.TextureMap.locationBlocksTexture);
                        int glId = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
                        atlasRead = new GlRead(glId);
                        stage = ATLAS;
                        return false;
                    }
                    chunkWork();
                    break;
                case ATLAS:
                    if (!atlasRead.step(deadline)) return false;
                    atlas = ShapesScenario.Atlas.of(atlasRead.width(), atlasRead.height(), atlasRead.pixels()[1]);
                    atlasRead = null;
                    MachineScan.keepAtlas(atlas);
                    chunkWork();
                    break;
                case WORK:
                    if (!work.isDone()) return false;
                    result = work.get();
                    stage = DONE;
                    break;
                default:
                    throw new IllegalStateException("stage " + stage);
            }
        }
        return true;
    }

    Result result() {
        return result;
    }

    // the space around the core a slice (one x) at a time: the machine's blocks
    private void footprint(World w) {
        int r = MachineCapture.REACH;
        for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            int bx = x + dx, by = y + dy, bz = z + dz;
            if (w.getBlock(bx, by, bz) != block) continue;
            int[] c = ((BlockDummyable) block).findCore(w, bx, by, bz);
            if (c != null && c[0] == x && c[1] == y && c[2] == z) footprint.add(new int[] {bx, by, bz});
        }
        if (++dx > r) stage = DRAW;
    }

    private void chunkWork() {
        submit(new ChunkTriangles(new ArrayList<int[]>(footprint), new ArrayList<float[]>(quads), x, y, z, atlas));
    }

    // (classes, not lambdas: MachineScan loads them beforehand, a lambda is made at its first use on the game's thread)
    private interface Triangles {
        MachineCapture.Collect get();
    }

    private static final class TesrTriangles implements Triangles {
        private final MachineCapture.Drawn drawn;
        private final Map<Integer, int[][]> textures;

        TesrTriangles(MachineCapture.Drawn drawn, Map<Integer, int[][]> textures) {
            this.drawn = drawn;
            this.textures = textures;
        }

        @Override
        public MachineCapture.Collect get() {
            return drawn.triangles(textures);
        }
    }

    private static final class ChunkTriangles implements Triangles {
        private final List<int[]> blocks;
        private final List<float[]> quads;
        private final int x, y, z;
        private final ShapesScenario.Atlas atlas;

        ChunkTriangles(List<int[]> blocks, List<float[]> quads, int x, int y, int z, ShapesScenario.Atlas atlas) {
            this.blocks = blocks;
            this.quads = quads;
            this.x = x;
            this.y = y;
            this.z = z;
            this.atlas = atlas;
        }

        @Override
        public MachineCapture.Collect get() {
            return MachineCapture.chunkTriangles(blocks, quads, x, y, z, new Fixed(atlas));
        }
    }

    private static final class Fixed implements java.util.function.Supplier<ShapesScenario.Atlas> {
        private final ShapesScenario.Atlas atlas;

        Fixed(ShapesScenario.Atlas atlas) {
            this.atlas = atlas;
        }

        @Override
        public ShapesScenario.Atlas get() {
            return atlas;
        }
    }

    // the rest on the worker: cells, the model, the tables
    private static final class Work implements java.util.concurrent.Callable<Result> {
        private final Triangles triangles;
        private final String id, key;
        private final int meta, x, y, z;
        private final List<int[]> blocks;

        Work(Triangles triangles, String id, String key, int meta, List<int[]> blocks, int x, int y, int z) {
            this.triangles = triangles;
            this.id = id;
            this.key = key;
            this.meta = meta;
            this.blocks = blocks;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public Result call() {
            MachineCapture.Collect c = triangles.get();
            if (c == null || c.isEmpty()) return null;
            Map<String, long[]> cells = c.finish(id, meta, blocks, x, y, z);
            ModelCells.taken(cells);
            if (c.model != null) MachineFaces.taken(key, c.model);
            return new Result(cells, c.size());
        }
    }

    private void submit(Triangles triangles) {
        work = WORKER.submit(new Work(triangles, id, key, meta, new ArrayList<int[]>(footprint), x, y, z));
        stage = WORK;
    }

    // the GL buffers given back (the machine left unfinished)
    void cancel() {
        for (GlRead r : reads.values()) r.free();
        reads.clear();
        if (atlasRead != null) atlasRead.free();
        atlasRead = null;
        if (work != null) work.cancel(false);
    }
}
