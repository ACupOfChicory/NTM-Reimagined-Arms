package acup.gunfeel;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

// Client: the 3D shapes bullets meet in blocks whose model sits inside their hitbox (NTM's steel scaffold and
// poles). The server has no models, so on joining a world the client turns the models of the blocks listed under
// "generate" in ntmgunfeel-bullet-shapes.properties into 16 x 16 x 16 cells (set where the model shows a pixel) and
// Помимо сетки сохраняются точные грани и прозрачность пикселей. Данные пишутся
// в config/ntm-gunfeel-bullet-shapes.properties; сервер передаёт их клиентам вместе с сеткой.
public final class MeshShapes {
    private static boolean done;

    private MeshShapes() {
    }

    public static List<Block> listed() {
        List<Block> out = new ArrayList<>();
        try (var in = MeshShapes.class.getResourceAsStream("/ntmgunfeel-bullet-shapes.properties")) {
            if (in == null) return out;
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String id : p.getProperty("generate", "").split(",")) {
                Identifier i = Identifier.tryParse(id.trim());
                if (i != null) BuiltInRegistries.BLOCK.getOptional(i).ifPresent(out::add);
            }
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read the bullet shape list", e);
        }
        // and the blocks a "path ... model" bullet rule names
        for (Block b : BuiltInRegistries.BLOCK) {
            if (!out.contains(b) && ("model".equals(BulletRules.path(b.defaultBlockState()))
                    || PassableClip.needsModelMask(b.defaultBlockState()))) out.add(b);
        }
        return out;
    }

    public static void generate() {
        forget();
        if (done) return;
        done = true;
        long start = System.nanoTime();
        Map<String, long[]> made = new TreeMap<>();
        // states drawn with the same model (powered, waterlogged) share its cells
        Map<Object, long[]> byModel = new java.util.IdentityHashMap<>();
        boolean differs = false;
        for (Block block : listed()) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                long[] bits = byModel.computeIfAbsent(Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state), m -> cells(state));
                String key = PassableClip.key(state);
                long[] had = PassableClip.shape(state);
                if (had == null || !java.util.Arrays.equals(had, bits)) differs = true;
                made.put(key, bits);
            }
        }
        if (made.isEmpty()) return;
        GunFeel.LOGGER.info("Made {} bullet shapes ({} models) in {} ms", made.size(), byModel.size(), (System.nanoTime() - start) / 1_000_000);
        PassableClip.putShapes(made);
        if (differs) write();
    }

    private static final ShapeStore STORE=ShapeStore.background();
    private static void write() {
        STORE.request(FMLPaths.CONFIGDIR.get().resolve(PassableClip.SHAPES_FILE),PassableClip.all());
    }

    // ---- big NTM models (cargo door, cargo elevator): their cells from what is drawn, see Structures

    private static final Map<BlockPos, BlockState> CAPTURED = new java.util.HashMap<>();
    private static final Map<BlockPos, BlockState> LAST_SEEN = new java.util.HashMap<>();
    private static int ticks;
    private record Candidate(BlockPos pos,BlockState state) {}
    private static final java.util.ArrayDeque<Candidate> QUEUE=new java.util.ArrayDeque<>();
    private static final java.util.Set<BlockPos> QUEUED=new java.util.HashSet<>();
    private static ClientLevel observed;
    private static Capture active;
    static long captureFramePeak;
    // the pieces the longest frame ran (stage and milliseconds): what is left of its time is around them
    static volatile String peakFrame="";
    static int capturesCompleted;
    // takes what is captured instead of the game's own table (ShapesStand: neither kept there nor written)
    static java.util.function.@org.jspecify.annotations.Nullable Consumer<Map<String,long[]>> sink;
    // the last machine's longest piece of each stage (nanoseconds, Capture.STAGES) and of its blocks' faces
    // (ShapesStand's report: written out there, not in the frame)
    static volatile long[] lastSlowest=new long[0];
    record QuadsCost(long nanos,int of,BlockPos at) {}
    static volatile QuadsCost lastQuads;
    static String describe(long[] slowest) {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<slowest.length;i++)if(slowest[i]>0)b.append(b.length()==0?"":", ").append(Capture.STAGES[i]).append(' ')
                .append(String.format(java.util.Locale.ROOT,"%.2f",slowest[i]/1e6));
        return b.toString();
    }
    private static long lastReport;

    // this machine taken now, not waited for (ShapesStand: a core with no block entity, which tick does not find)
    static void take(BlockPos core,BlockState state) {
        if(QUEUED.add(core.immutable()))QUEUE.addLast(new Candidate(core.immutable(),state));
    }
    public static void forget() {
        CAPTURED.clear();LAST_SEEN.clear();QUEUE.clear();QUEUED.clear();if(active!=null)active.cancel();active=null;
    }
    private static void world(ClientLevel level) {
        if(observed!=level){forget();observed=level;}
    }
    // Раз в две секунды только обнаруживаем кандидатов. Сама геометрия обрабатывается между кадрами.
    public static void tick() {
        Minecraft mc=Minecraft.getInstance();ClientLevel level=mc.level;world(level);
        if(level==null || mc.player==null || ++ticks%40!=0 || !ShapePack.ready())return;
        BlockPos me=mc.player.blockPosition();
        Map<BlockPos,BlockState> seen=new java.util.HashMap<>();
        int cx=me.getX()>>4,cz=me.getZ()>>4;
        for(int x=cx-3;x<=cx+3;x++)for(int z=cz-3;z<=cz+3;z++) {
            var chunk=level.getChunkSource().getChunk(x,z,false);if(chunk==null)continue;
            for(BlockEntity be:chunk.getBlockEntities().values()) {
                BlockState state=be.getBlockState();BlockPos pos=be.getBlockPos();
                if(!Structures.taken(state) || pos.distSqr(me)>48*48 || !Structures.atRest(level,pos,state))continue;
                // the jar's pack has it, made with this NTM (ShapePack): unpacked meanwhile, not taken
                if(ShapePack.has(Structures.key(state,0,0,0)))continue;
                seen.put(pos.immutable(),state);
                if(state.equals(CAPTURED.get(pos)) || !state.equals(LAST_SEEN.get(pos)) || !QUEUED.add(pos.immutable()))continue;
                QUEUE.addLast(new Candidate(pos.immutable(),state));
            }
        }
        LAST_SEEN.clear();LAST_SEEN.putAll(seen);
    }
    public static void frame() {
        var mc=Minecraft.getInstance();var level=mc.level;world(level);
        if(level==null || mc.player==null)return;
        long start=System.nanoTime(),deadline=GunClock.nanos()+1_250_000L;
        Capture ran=null;
        try {
            if(active==null) {
                Candidate next=QUEUE.pollFirst();if(next==null)return;
                // a core with no block entity (the vending machine) has no renderer: its blocks' models alone
                if(!valid(level,next.pos(),next.state())){QUEUED.remove(next.pos());return;}
                active=new Capture(next.state(),next.pos());
            }
            if(!valid(level,active.core,active.state)){QUEUED.remove(active.core);active.cancel();active=null;return;}
            ran=active;
            if(!active.step(level,deadline))return;
            // published in a frame of its own when this one is spent
            if(!active.publishNow && GunClock.nanos()>deadline-500_000L){active.publishNow=true;return;}
            lastSlowest=active.slowest.clone();lastQuads=active.quadsCost;
            var made=active.out;
            // Публикация только целиком и только если машина всё ещё в захваченном состоянии.
            if(valid(level,active.core,active.state) && sink!=null) {
                sink.accept(made);
                CAPTURED.put(active.core,active.state);capturesCompleted++;
            } else if(valid(level,active.core,active.state)) {
                // into the table on the worker: putShapes and the file copy the whole table
                WORKER.execute(()->{
                    try {
                        var changed=new TreeMap<String,long[]>();
                        for(var entry:made.entrySet())
                            if(!java.util.Arrays.equals(PassableClip.localByKey(entry.getKey()),entry.getValue()))changed.put(entry.getKey(),entry.getValue());
                        if(!changed.isEmpty()){PassableClip.putShapes(changed);write();}
                    } catch(Throwable e) {
                        GunFeel.LOGGER.warn("Could not keep the bullet shapes taken",e);
                    }
                });
                CAPTURED.put(active.core,active.state);capturesCompleted++;
            }
            QUEUED.remove(active.core);active=null;
        } catch(Throwable e) {
            GunFeel.LOGGER.warn("Could not capture incremental bullet shapes",e);
            if(active!=null){QUEUED.remove(active.core);active.cancel();active=null;}
        } finally {
            long cost=System.nanoTime()-start;
            if(cost>captureFramePeak){captureFramePeak=cost;peakFrame=(ran==null?"":ran.frameLog.toString())+String.format(java.util.Locale.ROOT,"; на %.3f с от запуска JVM",java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime()/1e3);}
            if(System.nanoTime()-lastReport>5_000_000_000L && (capturesCompleted>0 || active!=null)) {
                GunFeel.LOGGER.info("Bullet shape capture: completed={}, queued={}, active={}, peak render work={} ms",
                        capturesCompleted,QUEUE.size(),active==null?"no":active.where(),captureFramePeak/1e6);
                lastReport=System.nanoTime();
            }
        }
    }
    private static boolean valid(ClientLevel level,BlockPos core,BlockState state) {
        var player=Minecraft.getInstance().player;
        return player!=null && level.hasChunkAt(core) && core.distSqr(player.blockPosition())<=48*48
                && level.getBlockState(core).equals(state) && Structures.atRest(level,core,state);
    }

    // window: the sprite of a block model's face whose clear pixels may be a window (looked into on the worker)
    private record Triangle(double[] p,float[] uv,Shown shown,java.util.function.Supplier<ModelSurface.Face> face,@org.jspecify.annotations.Nullable TextureAtlasSprite window) {
        Triangle(double[] p,float[] uv,Shown shown,java.util.function.Supplier<ModelSurface.Face> face){this(p,uv,shown,face,null);}
    }
    // the thread a machine's cells are made on (Capture's last stage)
    private static final java.util.concurrent.ExecutorService WORKER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"ntm-gunfeel machine take");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
    });

    // One machine taken a little each frame, no frame given more than its budget: on the game's thread only what needs
    // the world or the game's textures, a piece at a time (its blocks; its renderer's state; the textures it draws with,
    // one a piece; the parts posing a shared model, one a piece; its blocks' model faces, QUAD_PIECE at a time); on the
    // worker the renderer's submit, its texture files and the rest of its parts, then its cells and faces. A piece of a
    // stage starts only when the longest one of that stage so far still fits before the deadline (the first piece of a
    // frame always), and the renderer's state starts a frame.
    private static final class Capture {
        private static final int BLOCKS=0,EXTRACT=1,SUBMIT=2,RESOLVE=3,PREPARE=4,EMIT=5,QUADS=6,WORK=7,DONE=8;
        static final String[] STAGES={"blocks","extract","submit","resolve","prepare","emit","quads","work"};
        private static final int QUAD_PIECE=96,TRI_PIECE=1024;
        final BlockPos core;
        final BlockState state;
        final boolean rendered;
        List<BlockPos> blocks;
        final net.minecraft.world.level.block.Rotation rotation;
        final Map<BlockPos,long[]> bits=new java.util.HashMap<>();
        final List<Triangle> triangles=new ArrayList<>();
        final Map<String,long[]> out=new TreeMap<>();
        private int stage=BLOCKS,quadsOf,quadAt,trisAt=-1;
        private List<BakedQuad> blockQuads;
        private BlockPos quadsPos;
        private RenderedMesh.Staged staged;
        private java.util.concurrent.Future<?> work;
        final long[] slowest=new long[DONE];
        boolean publishNow;
        private QuadsCost quadsCost;
        final StringBuilder frameLog=new StringBuilder();
        Capture(BlockState state,BlockPos core) {
            this.core=core.immutable();this.state=state;rendered=Structures.rendered(state);
            rotation=Structures.toNorth(state.getValue(com.hbm.blocks.multiblock.BlockMultiblockCore.FACING));
        }
        // works on until `deadline` (GunClock); true: done, `out` holds its cells
        boolean step(ClientLevel level,long deadline) throws Exception {
            boolean first=true;
            frameLog.setLength(0);
            while(stage!=DONE) {
                long t0=GunClock.nanos();
                // a stage not met yet in this machine has no known cost: it starts a frame of its own
                if(!first && (slowest[stage]==0 || t0+slowest[stage]>deadline || stage==EXTRACT))return false;
                first=false;
                int was=stage;
                try{if(!piece(level,deadline))return false;}
                finally{
                    long t=GunClock.nanos()-t0;if(t>slowest[was])slowest[was]=t;
                    frameLog.append(frameLog.length()==0?"":", ").append(STAGES[was]).append(' ').append(t/10_000/100.0);
                }
            }
            return true;
        }
        // one piece of the stage; false: no more this frame
        private boolean piece(ClientLevel level,long deadline) throws Exception {
            switch(stage) {
                case BLOCKS -> {
                    blocks=new ArrayList<>();
                    for(BlockPos b:com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level,core))blocks.add(b.immutable());
                    for(var pos:blocks)if(Structures.layer(state,pos.getY()-core.getY())==pos.getY()-core.getY())bits.put(pos,new long[65]);
                    stage=rendered?EXTRACT:QUADS;
                }
                case EXTRACT -> {
                    var be=level.getBlockEntity(core);
                    staged=be==null?null:RenderedMesh.stage(be);
                    if(staged==null)stage=QUADS;
                    else{var s=staged;work=WORKER.submit(new Runnable(){public void run(){s.submit();}});stage=SUBMIT;}
                    // the renderer's state took its share
                    return false;
                }
                case SUBMIT -> {
                    if(!work.isDone())return false;
                    work.get();work=null;stage=RESOLVE;
                }
                case RESOLVE -> {
                    if(staged.resolve()){var s=staged;work=WORKER.submit(new Runnable(){public void run(){s.prepare();}});stage=PREPARE;}
                }
                case PREPARE -> {
                    if(!work.isDone())return false;
                    work.get();work=null;stage=EMIT;
                }
                case EMIT -> {
                    if(trisAt<0){if(staged.emit())trisAt=0;break;}
                    // a window (MachineGlass) is a face of its own and fills no cells: the cells are what the casing holds
                    var tris=staged.tris();int end=Math.min(tris.size(),trisAt+TRI_PIECE);
                    for(;trisAt<end;trisAt++) {
                        var tri=tris.get(trisAt);
                        triangles.add(new Triangle(tri.p(),tri.uv(),new TriShown(tri),new TriFace(tri)));
                    }
                    if(trisAt>=tris.size()){staged=null;stage=QUADS;}
                }
                case QUADS -> {
                    if(blockQuads!=null) {
                        int end=Math.min(blockQuads.size(),quadAt+QUAD_PIECE);
                        long t0=System.nanoTime();
                        for(;quadAt<end;quadAt++)quad(quadsPos,blockQuads.get(quadAt));
                        long t=System.nanoTime()-t0;
                        if(quadsCost==null || t>quadsCost.nanos())quadsCost=new QuadsCost(t,blockQuads.size(),quadsPos);
                        if(quadAt>=blockQuads.size())blockQuads=null;
                        break;
                    }
                    if(quadsOf<blocks.size()) {
                        quadsPos=blocks.get(quadsOf++);
                        var st=level.getBlockState(quadsPos);
                        if(st.getRenderShape()==net.minecraft.world.level.block.RenderShape.MODEL){blockQuads=MeshShapes.quads(st);quadAt=0;}
                        break;
                    }
                    work=WORKER.submit(new Runnable(){public void run(){cells();}});stage=WORK;
                }
                case WORK -> {
                    if(!work.isDone())return false;
                    work.get();work=null;stage=DONE;
                }
                default -> throw new IllegalStateException("stage "+stage);
            }
            return true;
        }
        // the triangles of one face of a block's model
        private void quad(BlockPos pos,BakedQuad quad) {
            for(int k=0;k<2;k++) {
                int[] corners=k==0?new int[]{0,1,2}:new int[]{0,2,3};
                double[] p=new double[9];float[] uv=new float[6];
                for(int i=0;i<3;i++) {
                    var v=quad.position(corners[i]);p[i*3]=pos.getX()+(double)v.x();p[i*3+1]=pos.getY()+(double)v.y();p[i*3+2]=pos.getZ()+(double)v.z();
                    uv[i*2]=UVPair.unpackU(quad.packedUV(corners[i]));uv[i*2+1]=UVPair.unpackV(quad.packedUV(corners[i]));
                }
                var sprite=quad.materialInfo().sprite();
                triangles.add(new Triangle(p,uv,new SpriteShown(sprite),new QuadFace(p,uv,sprite),sprite));
            }
        }
        // on the worker: the cells the triangles cover, the faces, each block turned to face north
        private void cells() {
            List<ModelSurface.Face> surfaces=new ArrayList<>();
            long tf=0,ts=0,c0=System.nanoTime();
            for(var tri:triangles) {
                long a=System.nanoTime();
                surfaces.add(tri.face().get());
                long b=System.nanoTime();tf+=b-a;
                // the machine's window baked into its block model (the chemical plant's): a glass face, no cells
                if(tri.window()!=null && RenderedMesh.windowOf(tri.window(),tri.uv())!=null) {
                    float[] uv=tri.uv();
                    surfaces.add(surface(tri.p(),new float[]{uv[0],uv[2],uv[4]},new float[]{uv[1],uv[3],uv[5]},tri.window(),true));
                }
                long c=System.nanoTime();
                new Sample(tri.p(),tri.shown(),tri.uv(),bits,core).step(Long.MAX_VALUE);
                ts+=System.nanoTime()-c;
            }
            long c1=System.nanoTime();
            // the faces by the blocks their bounds reach: a big machine has thousands of blocks and faces
            Map<BlockPos,List<ModelSurface.Face>> near=new java.util.HashMap<>();
            if(rendered)for(var face:surfaces) {
                double[] p=face.p();int[] lo=new int[3],hi=new int[3];
                for(int a=0;a<3;a++){double min=Math.min(p[a],Math.min(p[a+3],p[a+6])),max=Math.max(p[a],Math.max(p[a+3],p[a+6]));lo[a]=(int)Math.floor(min-1e-3);hi[a]=(int)Math.floor(max+1e-3);}
                for(int x=lo[0];x<=hi[0];x++)for(int y=lo[1];y<=hi[1];y++)for(int z=lo[2];z<=hi[2];z++) {
                    BlockPos at=new BlockPos(x,y,z);
                    if(bits.containsKey(at))near.computeIfAbsent(at,k->new ArrayList<>()).add(face);
                }
            }
            for(var pos:blocks) {
                var world=bits.get(pos);if(world==null)continue;
                long[] north=new long[65];
                for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                    int[] n=Structures.turn(x,z,rotation);
                    for(int y=0;y<16;y++)if(PassableClip.cell(world,x,y,z)) {
                        int at=n[0]+16*(y+16*n[1]);north[at>>6]|=1L<<(at&63);
                    }
                }
                if(rendered) {
                    List<ModelSurface.Face> local=new ArrayList<>();
                    for(var face:near.getOrDefault(pos,List.of()))if(ModelSurface.overlaps(face,pos)){var cut=ModelSurface.crop(ModelSurface.local(face,pos,rotation));if(cut!=null)local.add(cut);}
                    north=ModelSurface.attach(north,local);
                }
                out.put(Structures.key(state,pos.getX()-core.getX(),pos.getY()-core.getY(),pos.getZ()-core.getZ()),north);
            }
            MASKS.clear();SPRITES.clear();
            GunFeel.LOGGER.debug("Machine cells of {} triangles: faces {} ms, sample {} ms, blocks {} ms",triangles.size(),tf/1_000_000,ts/1_000_000,(System.nanoTime()-c1)/1_000_000);
        }
        // the stage it is at, for the log
        String where() {
            return stage==DONE?"done":STAGES[stage]+(stage==QUADS?" "+quadsOf+"/"+(blocks==null?0:blocks.size()):"")+(work!=null?" (worker)":"");
        }
        // the machine left unfinished
        void cancel() {
            if(work!=null)work.cancel(false);
        }
    }

    interface Shown {
        boolean at(float u, float v);
    }

    // Plain classes, not lambdas, for what a frame's piece makes: a lambda's first call links it there (over a
    // millisecond in a client that has taken no machine yet).
    // a triangle the renderer drew: a window (MachineGlass) is a face of its own and fills no cells
    private record TriShown(RenderedMesh.Tri tri) implements Shown {
        public boolean at(float u, float v) {
            return !(tri.tex() instanceof RenderedMesh.Window) && tri.tex().opaque(u, v);
        }
    }

    private record TriFace(RenderedMesh.Tri tri) implements java.util.function.Supplier<ModelSurface.Face> {
        public ModelSurface.Face get() {
            return surface(tri);
        }
    }

    private record SpriteShown(TextureAtlasSprite sprite) implements Shown {
        public boolean at(float u, float v) {
            return opaque(sprite, u, v);
        }
    }

    private record QuadFace(double[] p, float[] uv, TextureAtlasSprite sprite) implements java.util.function.Supplier<ModelSurface.Face> {
        public ModelSurface.Face get() {
            return surface(p, new float[] {uv[0], uv[2], uv[4]}, new float[] {uv[1], uv[3], uv[5]}, sprite, false);
        }
    }

    // Та же сетка и те же пробы, но даже один большой треугольник можно остановить между порциями.
    static final class Sample {
        final double[] p;
        final Shown shown;
        final float[] uv;
        final Map<BlockPos,long[]> bits;
        final int n,ox,oy,oz;
        final BlockPos.MutableBlockPos at=new BlockPos.MutableBlockPos();
        int i,j,lx,ly,lz;
        boolean lastOk;
        long[] lastIn;
        Sample(double[] p,Shown shown,float[] uv,Map<BlockPos,long[]> bits) {
            this(p,shown,uv,bits,BlockPos.ZERO);
        }
        // the points are worked out from the machine's core, not from the world's zero: a point on a cell's border
        // rounds the same way wherever the machine stands (world numbers past 16 lose bits and flip such cells)
        Sample(double[] p,Shown shown,float[] uv,Map<BlockPos,long[]> bits,BlockPos origin) {
            ox=origin.getX();oy=origin.getY();oz=origin.getZ();
            this.p=p.clone();
            for(int k=0;k<3;k++){this.p[k*3]-=ox;this.p[k*3+1]-=oy;this.p[k*3+2]-=oz;}
            this.shown=shown;this.uv=uv;this.bits=bits;
            p=this.p;
            double edge=0;
            for(int k=0;k<3;k++) {
                int next=(k+1)%3;
                edge=Math.max(edge,Math.sqrt(sq(p[k*3]-p[next*3])+sq(p[k*3+1]-p[next*3+1])+sq(p[k*3+2]-p[next*3+2])));
            }
            n=Math.max(2,(int)Math.ceil(edge*64));
        }
        boolean step(long deadline) {
            int checked=0;
            while(i<=n) {
                if((checked++&127)==0 && GunClock.nanos()>=deadline)return false;
                j+=point()+1;
                if(j>n-i){i++;j=0;}
            }
            return true;
        }
        // looks at point (i, j); returns how many points after it along the row are sure to change nothing (they fall
        // in the same empty block, or in the same cell once it is filled), to be stepped over
        private int point() {
                double wa = (double) i / n, wb = (double) j / n, wc = 1 - wa - wb;
                double x = wa * p[0] + wb * p[3] + wc * p[6];
                double y = wa * p[1] + wb * p[4] + wc * p[7];
                double z = wa * p[2] + wb * p[5] + wc * p[8];
                float u = (float) (wa * uv[0] + wb * uv[2] + wc * uv[4]);
                float v = (float) (wa * uv[1] + wb * uv[3] + wc * uv[5]);
                long[] in = null;
                double bx = 0, by = 0, bz = 0;
                int fx = (int) Math.floor(x), fy = (int) Math.floor(y), fz = (int) Math.floor(z);
                // away from a block's border every nudge meets the same block: one look, the last one's if it is that
                if (fx == (int) Math.floor(x - 1e-4) && fy == (int) Math.floor(y - 1e-4) && fz == (int) Math.floor(z - 1e-4)) {
                    if (!(lastOk && fx == lx && fy == ly && fz == lz)) {
                        lastIn = bits.get(at.set(fx + ox, fy + oy, fz + oz));
                        lx = fx; ly = fy; lz = fz; lastOk = true;
                    } else at.set(fx + ox, fy + oy, fz + oz);
                    in = lastIn;
                    bx = x; by = y; bz = z;
                    if (in == null) return ahead(x, y, z, fx + 1e-4, fy + 1e-4, fz + 1e-4, fx + 1, fy + 1, fz + 1);
                } else for (int nudge = 0; nudge < 8 && in == null; nudge++) {
                    bx = x - ((nudge & 1) != 0 ? 1e-4 : 0);
                    by = y - ((nudge & 2) != 0 ? 1e-4 : 0);
                    bz = z - ((nudge & 4) != 0 ? 1e-4 : 0);
                    in = bits.get(at.set((int) Math.floor(bx) + ox, (int) Math.floor(by) + oy, (int) Math.floor(bz) + oz));
                }
                if (in == null) return 0;
                int ax = at.getX() - ox, ay = at.getY() - oy, az = at.getZ() - oz;
                int cx = Math.clamp((int) Math.floor((bx - ax) * 16), 0, 15);
                int cy = Math.clamp((int) Math.floor((by - ay) * 16), 0, 15);
                int cz = Math.clamp((int) Math.floor((bz - az) * 16), 0, 15);
                int k = cx + 16 * (cy + 16 * cz);
                // a cell already filled needs no look at the texture
                boolean filled = (in[k >> 6] & 1L << (k & 63)) != 0;
                if (!filled) {
                    if (!shown.at(u, v)) return 0;
                    in[k >> 6] |= 1L << (k & 63);
                }
                if (bx != x || by != y || bz != z) return 0;
                return ahead(x, y, z, Math.max(ax + 1e-4, ax + cx / 16.0), Math.max(ay + 1e-4, ay + cy / 16.0), Math.max(az + 1e-4, az + cz / 16.0),
                        ax + (cx + 1) / 16.0, ay + (cy + 1) / 16.0, az + (cz + 1) / 16.0);
        }
        // the points after (x, y, z) along the row (j up) that stay inside the box lo..hi, less one to be safe. The box is
        // shrunk by a hair and a point at its edge steps over nothing: a point's numbers come out an ulp either side of
        // a border the row runs along (a face in the plane y = 0.5)
        private int ahead(double x, double y, double z, double lx, double ly, double lz, double hx, double hy, double hz) {
            final double e = 1e-9;
            lx += e; ly += e; lz += e; hx -= e; hy -= e; hz -= e;
            if (x < lx || y < ly || z < lz || x >= hx || y >= hy || z >= hz) return 0;
            double t = Math.min(steps(x, p[3] - p[6], lx, hx), Math.min(steps(y, p[4] - p[7], ly, hy), steps(z, p[5] - p[8], lz, hz)));
            return t >= Integer.MAX_VALUE ? n : Math.max(0, (int) Math.floor(t) - 1);
        }
        private double steps(double at, double span, double lo, double hi) {
            double d = span / n;
            if (d > 0) return (hi - at) / d;
            if (d < 0) return (at - lo) / -d;
            return Double.MAX_VALUE;
        }
    }

    private static double sq(double d) {
        return d * d;
    }

    // all of the model's faces, culled or not
    public static List<BakedQuad> quads(BlockState state) {
        List<BakedQuad> out = new ArrayList<>();
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(net.minecraft.util.RandomSource.create(42L), parts);
        for (var part : parts) {
            for (Direction d : Direction.values()) out.addAll(part.getQuads(d));
            out.addAll(part.getQuads(null));
        }
        return out;
    }

    private static long[] cells(BlockState state) {
        long[] bits = new long[65];
        List<BakedQuad> quads = quads(state);
        if (shell(state, quads)) bits[64] = 1L;
        // the planes faces lie in: a face with a twin facing the other way in its plane is one side of a
        // double-sided plane (a chain's, a ladder's), which is not pushed behind (it would come out two cells thick)
        java.util.Set<String> planes = new java.util.HashSet<>();
        for (BakedQuad q : quads) {
            String k = planeKey(q, false);
            if (k != null) planes.add(k);
        }
        List<ModelSurface.Face> faces = new ArrayList<>();
        for (BakedQuad q : quads) {
            String twin = planeKey(q, true);
            boolean behind = twin == null || !planes.contains(twin);
            for (int t = 0; t < 2; t++) {
                int a = 0, b = t == 0 ? 1 : 2, c = t == 0 ? 2 : 3;
                var pa = q.position(a); var pb = q.position(b); var pc = q.position(c);
                float[] u = {UVPair.unpackU(q.packedUV(a)), UVPair.unpackU(q.packedUV(b)), UVPair.unpackU(q.packedUV(c))};
                float[] v = {UVPair.unpackV(q.packedUV(a)), UVPair.unpackV(q.packedUV(b)), UVPair.unpackV(q.packedUV(c))};
                TextureAtlasSprite sprite = q.materialInfo().sprite();
                double[] p = {pa.x(), pa.y(), pa.z(), pb.x(), pb.y(), pb.z(), pc.x(), pc.y(), pc.z()};
                faces.add(surface(p, u, v, sprite, false));
                fill(bits, p, behind,
                        (wa, wb, wc) -> opaque(sprite, (float) (wa * u[0] + wb * u[1] + wc * u[2]), (float) (wa * v[0] + wb * v[1] + wc * v[2])));
            }
        }
        return PassableClip.shell(bits) ? bits : ModelSurface.attach(bits, faces);
    }

    // glass: the face of a machine's window, its clear pixels shown (MachineGlass)
    private static ModelSurface.Face surface(double[] p, float[] u, float[] v, TextureAtlasSprite sprite, boolean glass) {
        var contents = sprite.contents();
        int w = contents.width(), h = contents.height();
        String texture = contents.name().getNamespace() + ":textures/" + contents.name().getPath() + ".png";
        boolean areas = glass && !MachineGlass.areas(texture).isEmpty();
        // the sprite's mask is the same for each of its faces: read once a machine
        var key = new SpriteKey(sprite, glass);
        long[] alpha = SPRITES.get(key);
        if (alpha == null) {
            alpha = new long[(w * h + 63) / 64];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                // a window shows its clear pixels, but those in an area listed as empty
                if (contents.isTransparent(0, x, y) == glass && !(areas && MachineGlass.emptyAt(texture, x, y))) {
                    int k = x + w * y;
                    alpha[k >> 6] |= 1L << (k & 63);
                }
            }
            if (solid(alpha, w * h)) alpha = SOLID;
            SPRITES.put(key, alpha);
        }
        if (alpha == SOLID) return new ModelSurface.Face(p, new double[6], 1, 1, new long[] {1}, glass);
        double[] uv = new double[6];
        for (int i = 0; i < 3; i++) {
            uv[2 * i] = (u[i] - sprite.getU0()) / (sprite.getU1() - sprite.getU0()) * w;
            uv[2 * i + 1] = (v[i] - sprite.getV0()) / (sprite.getV1() - sprite.getV0()) * h;
        }
        return new ModelSurface.Face(p, uv, w, h, alpha, glass);
    }

    // Геометрия рендера: прозрачность записывается в собственной сетке текстуры, без клиентских объектов.
    private static ModelSurface.Face surface(RenderedMesh.Tri tri) {
        boolean glass = tri.tex() instanceof RenderedMesh.Window;
        if (!tri.tex().seeThrough()) return new ModelSurface.Face(tri.p(), new double[6], 1, 1, new long[] {1});
        double[] grid = tri.tex().grid();
        double minU = Math.min(tri.uv()[0], Math.min(tri.uv()[2], tri.uv()[4]));
        double maxU = Math.max(tri.uv()[0], Math.max(tri.uv()[2], tri.uv()[4]));
        double minV = Math.min(tri.uv()[1], Math.min(tri.uv()[3], tri.uv()[5]));
        double maxV = Math.max(tri.uv()[1], Math.max(tri.uv()[3], tri.uv()[5]));
        double du = grid == null ? Math.max(1e-8, (maxU - minU) / 64) : grid[1];
        double dv = grid == null ? Math.max(1e-8, (maxV - minV) / 64) : grid[3];
        double u0 = grid == null ? minU : grid[0] + Math.floor((minU - grid[0]) / du) * du;
        double v0 = grid == null ? minV : grid[2] + Math.floor((minV - grid[2]) / dv) * dv;
        int w = Math.max(1, (int) Math.ceil((maxU - u0) / du));
        int h = Math.max(1, (int) Math.ceil((maxV - v0) / dv));
        // the two triangles of a quad, and the same part posed again, read the same pixels: read once
        var key = new MaskKey(tri.tex(), u0, v0, du, dv, w, h);
        long[] alpha = MASKS.get(key);
        if (alpha == null) {
            alpha = new long[(w * h + 63) / 64];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if (tri.tex().opaque((float) (u0 + (x + .5) * du), (float) (v0 + (y + .5) * dv))) {
                    int k = x + w * y;
                    alpha[k >> 6] |= 1L << (k & 63);
                }
            }
            if (solid(alpha, w * h)) alpha = SOLID;
            MASKS.put(key, alpha);
        }
        if (alpha == SOLID) return new ModelSurface.Face(tri.p(), new double[6], 1, 1, new long[] {1}, glass);
        double[] uv = new double[6];
        for (int i = 0; i < 3; i++) {
            uv[2 * i] = (tri.uv()[2 * i] - u0) / du;
            uv[2 * i + 1] = (tri.uv()[2 * i + 1] - v0) / dv;
        }
        return new ModelSurface.Face(tri.p(), uv, w, h, alpha, glass);
    }

    private record MaskKey(RenderedMesh.Tex tex, double u0, double v0, double du, double dv, int w, int h) {
    }

    private record SpriteKey(TextureAtlasSprite sprite, boolean glass) {
    }

    private static final long[] SOLID = {};
    private static final Map<SpriteKey, long[]> SPRITES = new java.util.concurrent.ConcurrentHashMap<>();
    // the masks read lately (emptied after each machine taken)
    private static final Map<MaskKey, long[]> MASKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean solid(long[] alpha, int pixels) {
        for (int i = 0; i < pixels; i++) if ((alpha[i >> 6] >>> (i & 63) & 1L) == 0) return false;
        return true;
    }

    interface PixelShown {
        boolean at(double wa, double wb, double wc);
    }

    // The cells a triangle (p = a, b, c as x, y, z) covers with shown pixels. A face or an edge lying on a cell
    // border (a pole's side at 2/16, the edge of a diagonal brace there) goes into the cell of the part it bounds,
    // not the empty one beside it: each point is taken a hair towards the triangle's middle, and (behind) a hair
    // behind the face (its normal points out).
    static void fill(long[] bits, double[] p, boolean behind, PixelShown shown) {
        double ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2];
        double vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double back = behind && nl > 1e-9 ? 1e-3 / nl : 0;
        nx *= back; ny *= back; nz *= back;
        double mx = grid((p[0] + p[3] + p[6]) / 3), my = grid((p[1] + p[4] + p[7]) / 3), mz = grid((p[2] + p[5] + p[8]) / 3);
        double edge = Math.max(Math.sqrt(ux * ux + uy * uy + uz * uz), Math.max(Math.sqrt(vx * vx + vy * vy + vz * vz),
                Math.sqrt((p[6] - p[3]) * (p[6] - p[3]) + (p[7] - p[4]) * (p[7] - p[4]) + (p[8] - p[5]) * (p[8] - p[5]))));
        int n = Math.max(2, (int) Math.ceil(edge * 64));
        final double in = 2e-3;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n - i; j++) {
                double wa = (double) i / n, wb = (double) j / n, wc = 1 - wa - wb;
                if (!shown.at(wa, wb, wc)) continue;
                double x = grid(wa * p[0] + wb * p[3] + wc * p[6]), y = grid(wa * p[1] + wb * p[4] + wc * p[7]),
                        z = grid(wa * p[2] + wb * p[5] + wc * p[8]);
                x += (mx - x) * in - nx;
                y += (my - y) * in - ny;
                z += (mz - z) * in - nz;
                int cx = (int) Math.floor(x * 16), cy = (int) Math.floor(y * 16), cz = (int) Math.floor(z * 16);
                if (x >= 1.0 && x < 1.0 + 3e-3) cx = 15;
                if (y >= 1.0 && y < 1.0 + 3e-3) cy = 15;
                if (z >= 1.0 && z < 1.0 + 3e-3) cz = 15;
                if (x < 0 && x > -3e-3) cx = 0;
                if (y < 0 && y > -3e-3) cy = 0;
                if (z < 0 && z > -3e-3) cz = 0;
                if (cx < 0 || cx > 15 || cy < 0 || cy > 15 || cz < 0 || cz > 15) continue;
                int k = cx + 16 * (cy + 16 * cz);
                bits[k >> 6] |= 1L << (k & 63);
            }
        }
    }

    // a coordinate a rounding error off a cell border (a plane at 0.5 summed from its corners) put on it
    private static double grid(double c) {
        double g = Math.rint(c * 16) / 16;
        return Math.abs(c - g) < 1e-6 ? g : c;
    }

    // the face's plane as text (its normal and distance, rounded), or the plane of a face turned the other way in it
    private static String planeKey(BakedQuad q, boolean flipped) {
        var a = q.position(0); var b = q.position(1); var c = q.position(2);
        double ux = b.x() - a.x(), uy = b.y() - a.y(), uz = b.z() - a.z(), vx = c.x() - a.x(), vy = c.y() - a.y(), vz = c.z() - a.z();
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-9) return null;
        double f = flipped ? -1 : 1;
        nx *= f / nl; ny *= f / nl; nz *= f / nl;
        double d = nx * a.x() + ny * a.y() + nz * a.z();
        return Math.round(nx * 500) + " " + Math.round(ny * 500) + " " + Math.round(nz * 500) + " " + Math.round(d * 2000);
    }

    // The model's faces (shown pixels or not) cover every face of the block's hitbox: a closed box such as a door,
    // a trapdoor, a lantern or copper grate, entered only through its outer faces. Not: iron bars, a ladder, chains,
    // scaffold poles, whose model lies inside the hitbox.
    static boolean shell(BlockState state, List<BakedQuad> quads) {
        var boxes = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).toAabbs();
        if (boxes.isEmpty()) return false;
        int points = 0, covered = 0;
        for (var b : boxes) {
            double[] lo = {b.minX, b.minY, b.minZ}, hi = {b.maxX, b.maxY, b.maxZ};
            for (int ax = 0; ax < 3; ax++) {
                int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
                for (double plane : new double[] {lo[ax], hi[ax]}) {
                    int facePoints = 0, faceCovered = 0;
                    for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
                        double[] p = new double[3];
                        p[ax] = plane;
                        p[a1] = lo[a1] + (hi[a1] - lo[a1]) * (i + 0.5) / 4;
                        p[a2] = lo[a2] + (hi[a2] - lo[a2]) * (j + 0.5) / 4;
                        // a face shared with another of the boxes is inside the hitbox
                        if (inOther(boxes, b, p, ax)) continue;
                        facePoints++;
                        if (onAnyQuad(quads, p, ax)) faceCovered++;
                    }
                    // a bare face on the block's edge is a seam to the other half
                    // (door halves have no face where they meet)
                    boolean edge = Math.abs(plane) < 1e-4 || Math.abs(plane - 1) < 1e-4;
                    if (edge && faceCovered == 0) continue;
                    points += facePoints;
                    covered += faceCovered;
                }
            }
        }
        return points > 0 && covered >= points * 0.95;
    }

    private static boolean inOther(List<net.minecraft.world.phys.AABB> boxes, net.minecraft.world.phys.AABB self, double[] p, int ax) {
        for (var o : boxes) {
            if (o == self) continue;
            double[] lo = {o.minX, o.minY, o.minZ}, hi = {o.maxX, o.maxY, o.maxZ};
            boolean in = true;
            for (int i = 0; i < 3; i++) in &= p[i] >= lo[i] - 1e-4 && p[i] <= hi[i] + 1e-4;
            if (in) return true;
        }
        return false;
    }

    // an axis-aligned quad of the model lies in that plane over the point
    private static boolean onAnyQuad(List<BakedQuad> quads, double[] p, int ax) {
        for (BakedQuad q : quads) {
            double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int k = 0; k < 4; k++) {
                var v = q.position(k);
                double[] c = {v.x(), v.y(), v.z()};
                for (int i = 0; i < 3; i++) {
                    mn[i] = Math.min(mn[i], c[i]);
                    mx[i] = Math.max(mx[i], c[i]);
                }
            }
            if (mx[ax] - mn[ax] > 1e-4 || Math.abs(mn[ax] - p[ax]) > 1e-3) continue;
            boolean in = true;
            for (int i = 0; i < 3; i++) if (i != ax) in &= p[i] >= mn[i] - 1e-4 && p[i] <= mx[i] + 1e-4;
            if (in) return true;
        }
        return false;
    }

    // the sprite's pixel colour at atlas coordinates (u, v), ARGB (0 = not readable)
    public static int color(TextureAtlasSprite sprite, float u, float v) {
        var contents = sprite.contents();
        // the purple and black "missing" texture (a model naming a sprite that is not in the atlas): no colour
        if (contents.name().equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation())) return 0;
        float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
        if (du == 0F || dv == 0F) return 0;
        int px = Math.clamp((int) ((u - sprite.getU0()) / du * contents.width()), 0, contents.width() - 1);
        int py = Math.clamp((int) ((v - sprite.getV0()) / dv * contents.height()), 0, contents.height() - 1);
        try {
            return image(contents).getPixel(px, py);
        } catch (Throwable e) {
            if (!colourFailNoted) {
                colourFailNoted = true;
                GunFeel.LOGGER.warn("Bullet crumbs: no pixel colour from sprite {}", contents.name(), e);
            }
            return 0;
        }
    }

    private static java.lang.invoke.MethodHandle imageGetter;
    private static boolean colourFailNoted;

    // the sprite's image (NeoForge's SpriteContents.getOriginalImage, else its private field), looked up on
    // SpriteContents itself: a subclass of it would hide the field
    private static com.mojang.blaze3d.platform.NativeImage image(net.minecraft.client.renderer.texture.SpriteContents contents) throws Throwable {
        if (imageGetter == null) {
            var lookup = java.lang.invoke.MethodHandles.lookup();
            Class<?> c = net.minecraft.client.renderer.texture.SpriteContents.class;
            try {
                imageGetter = lookup.unreflect(c.getMethod("getOriginalImage"));
            } catch (NoSuchMethodException e) {
                var f = c.getDeclaredField("originalImage");
                f.setAccessible(true);
                imageGetter = lookup.unreflectGetter(f);
            }
        }
        return (com.mojang.blaze3d.platform.NativeImage) imageGetter.invoke(contents);
    }

    // the sprite's pixel at atlas coordinates (u, v) is shown
    public static boolean opaque(TextureAtlasSprite sprite, float u, float v) {
        var contents = sprite.contents();
        float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
        if (du == 0F || dv == 0F) return true;
        int px = Math.clamp((int) ((u - sprite.getU0()) / du * contents.width()), 0, contents.width() - 1);
        int py = Math.clamp((int) ((v - sprite.getV0()) / dv * contents.height()), 0, contents.height() - 1);
        return !contents.isTransparent(0, px, py);
    }
}
