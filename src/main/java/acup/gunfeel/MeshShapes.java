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
    static int capturesCompleted;
    private static long lastReport;

    public static void forget() {
        CAPTURED.clear();LAST_SEEN.clear();QUEUE.clear();QUEUED.clear();active=null;
    }
    private static void world(ClientLevel level) {
        if(observed!=level){forget();observed=level;}
    }
    // Раз в две секунды только обнаруживаем кандидатов. Сама геометрия обрабатывается между кадрами.
    public static void tick() {
        Minecraft mc=Minecraft.getInstance();ClientLevel level=mc.level;world(level);
        if(level==null || mc.player==null || ++ticks%40!=0)return;
        BlockPos me=mc.player.blockPosition();
        Map<BlockPos,BlockState> seen=new java.util.HashMap<>();
        int cx=me.getX()>>4,cz=me.getZ()>>4;
        for(int x=cx-3;x<=cx+3;x++)for(int z=cz-3;z<=cz+3;z++) {
            var chunk=level.getChunkSource().getChunk(x,z,false);if(chunk==null)continue;
            for(BlockEntity be:chunk.getBlockEntities().values()) {
                BlockState state=be.getBlockState();BlockPos pos=be.getBlockPos();
                if(!Structures.taken(state) || pos.distSqr(me)>48*48 || !Structures.atRest(level,pos,state))continue;
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
        long start=System.nanoTime();
        try {
            if(active==null) {
                Candidate next=QUEUE.pollFirst();if(next==null)return;
                var be=level.getBlockEntity(next.pos());
                if(be==null || !valid(level,next.pos(),next.state())){QUEUED.remove(next.pos());return;}
                try{active=new Capture(level,be,next.state());}
                catch(Throwable e){QUEUED.remove(next.pos());throw e;}
            }
            if(!valid(level,active.core,active.state)){QUEUED.remove(active.core);active=null;return;}
            if(!active.step(start+1_500_000L))return;
            var made=active.out;
            // Публикация только целиком и только если машина всё ещё в захваченном состоянии.
            if(valid(level,active.core,active.state)) {
                var changed=new TreeMap<String,long[]>();
                for(var entry:made.entrySet())
                    if(!java.util.Arrays.equals(PassableClip.localByKey(entry.getKey()),entry.getValue()))changed.put(entry.getKey(),entry.getValue());
                if(!changed.isEmpty()){PassableClip.putShapes(changed);write();}
                CAPTURED.put(active.core,active.state);capturesCompleted++;
            }
            QUEUED.remove(active.core);active=null;
        } catch(Throwable e) {
            GunFeel.LOGGER.warn("Could not capture incremental bullet shapes",e);
            if(active!=null){QUEUED.remove(active.core);active=null;}
        } finally {
            long cost=System.nanoTime()-start;captureFramePeak=Math.max(captureFramePeak,cost);
            if(System.nanoTime()-lastReport>5_000_000_000L && (capturesCompleted>0 || active!=null)) {
                GunFeel.LOGGER.info("Bullet shape capture: completed={}, queued={}, active={}, peak render work={} ms",
                        capturesCompleted,QUEUE.size(),active!=null,captureFramePeak/1e6);
                lastReport=System.nanoTime();
            }
        }
    }
    private static boolean valid(ClientLevel level,BlockPos core,BlockState state) {
        var player=Minecraft.getInstance().player;
        return player!=null && level.hasChunkAt(core) && core.distSqr(player.blockPosition())<=48*48
                && level.getBlockState(core).equals(state) && Structures.atRest(level,core,state);
    }

    private record Triangle(double[] p,float[] uv,Shown shown,java.util.function.Supplier<ModelSurface.Face> face) {}
    private static final class Capture {
        final BlockPos core;
        final BlockState state;
        final boolean rendered;
        final List<BlockPos> blocks;
        final net.minecraft.world.level.block.Rotation rotation;
        final Map<BlockPos,long[]> bits=new java.util.HashMap<>();
        final List<Triangle> triangles=new ArrayList<>();
        final List<ModelSurface.Face> surfaces=new ArrayList<>();
        final Map<String,long[]> out=new TreeMap<>();
        int triangle,block;
        Sample sampling;
        Capture(ClientLevel level,BlockEntity be,BlockState state) {
            this.core=be.getBlockPos().immutable();this.state=state;rendered=Structures.rendered(state);
            rotation=Structures.toNorth(state.getValue(com.hbm.blocks.multiblock.BlockMultiblockCore.FACING));
            blocks=com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level,core).stream().map(BlockPos::immutable).toList();
            for(var pos:blocks)if(Structures.layer(state,pos.getY()-core.getY())==pos.getY()-core.getY())bits.put(pos,new long[65]);
            if(rendered)for(var tri:RenderedMesh.of(be))
                triangles.add(new Triangle(tri.p(),tri.uv(),(u,v)->tri.tex().opaque(u,v),()->surface(tri)));
            for(var pos:blocks) {
                var st=level.getBlockState(pos);
                if(st.getRenderShape()!=net.minecraft.world.level.block.RenderShape.MODEL)continue;
                for(var quad:quads(st))for(int k=0;k<2;k++) {
                    int[] corners=k==0?new int[]{0,1,2}:new int[]{0,2,3};
                    double[] p=new double[9];float[] uv=new float[6];
                    for(int i=0;i<3;i++) {
                        var v=quad.position(corners[i]);p[i*3]=pos.getX()+v.x();p[i*3+1]=pos.getY()+v.y();p[i*3+2]=pos.getZ()+v.z();
                        uv[i*2]=UVPair.unpackU(quad.packedUV(corners[i]));uv[i*2+1]=UVPair.unpackV(quad.packedUV(corners[i]));
                    }
                    var sprite=quad.materialInfo().sprite();
                    triangles.add(new Triangle(p,uv,(u,v)->opaque(sprite,u,v),
                            ()->surface(p,new float[]{uv[0],uv[2],uv[4]},new float[]{uv[1],uv[3],uv[5]},sprite)));
                }
            }
        }
        boolean step(long deadline) {
            while(triangle<triangles.size()) {
                if(System.nanoTime()>=deadline)return false;
                var tri=triangles.get(triangle);
                if(sampling==null) {
                    surfaces.add(tri.face().get());
                    sampling=new Sample(tri.p(),tri.shown(),tri.uv(),bits);
                }
                if(!sampling.step(deadline))return false;
                sampling=null;triangle++;
            }
            while(block<blocks.size()) {
                if(System.nanoTime()>=deadline)return false;
                var pos=blocks.get(block++);var world=bits.get(pos);if(world==null)continue;
                long[] north=new long[65];
                for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                    int[] n=Structures.turn(x,z,rotation);
                    for(int y=0;y<16;y++)if(PassableClip.cell(world,x,y,z)) {
                        int at=n[0]+16*(y+16*n[1]);north[at>>6]|=1L<<(at&63);
                    }
                }
                if(rendered) {
                    List<ModelSurface.Face> local=new ArrayList<>();
                    for(var face:surfaces)if(ModelSurface.overlaps(face,pos))local.add(ModelSurface.local(face,pos,rotation));
                    north=ModelSurface.attach(north,local);
                }
                out.put(Structures.key(state,pos.getX()-core.getX(),pos.getY()-core.getY(),pos.getZ()-core.getZ()),north);
            }
            return true;
        }
    }

    interface Shown {
        boolean at(float u, float v);
    }

    // Та же сетка и те же пробы, но даже один большой треугольник можно остановить между порциями.
    static final class Sample {
        final double[] p;
        final Shown shown;
        final float[] uv;
        final Map<BlockPos,long[]> bits;
        final int n;
        final BlockPos.MutableBlockPos at=new BlockPos.MutableBlockPos();
        int i,j;
        Sample(double[] p,Shown shown,float[] uv,Map<BlockPos,long[]> bits) {
            this.p=p;this.shown=shown;this.uv=uv;this.bits=bits;
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
                if((checked++&127)==0 && System.nanoTime()>=deadline)return false;
                point();j++;
                if(j>n-i){i++;j=0;}
            }
            return true;
        }
        private void point() {
                double wa = (double) i / n, wb = (double) j / n, wc = 1 - wa - wb;
                double x = wa * p[0] + wb * p[3] + wc * p[6];
                double y = wa * p[1] + wb * p[4] + wc * p[7];
                double z = wa * p[2] + wb * p[5] + wc * p[8];
                float u = (float) (wa * uv[0] + wb * uv[2] + wc * uv[4]);
                float v = (float) (wa * uv[1] + wb * uv[3] + wc * uv[5]);
                long[] in = null;
                double bx = 0, by = 0, bz = 0;
                for (int nudge = 0; nudge < 8 && in == null; nudge++) {
                    bx = x - ((nudge & 1) != 0 ? 1e-4 : 0);
                    by = y - ((nudge & 2) != 0 ? 1e-4 : 0);
                    bz = z - ((nudge & 4) != 0 ? 1e-4 : 0);
                    in = bits.get(at.set(Math.floor(bx), Math.floor(by), Math.floor(bz)));
                }
                if (in == null || !shown.at(u, v)) return;
                int cx = Math.clamp((int) Math.floor((bx - at.getX()) * 16), 0, 15);
                int cy = Math.clamp((int) Math.floor((by - at.getY()) * 16), 0, 15);
                int cz = Math.clamp((int) Math.floor((bz - at.getZ()) * 16), 0, 15);
                int k = cx + 16 * (cy + 16 * cz);
                in[k >> 6] |= 1L << (k & 63);
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
                faces.add(surface(p, u, v, sprite));
                fill(bits, p, behind,
                        (wa, wb, wc) -> opaque(sprite, (float) (wa * u[0] + wb * u[1] + wc * u[2]), (float) (wa * v[0] + wb * v[1] + wc * v[2])));
            }
        }
        return PassableClip.shell(bits) ? bits : ModelSurface.attach(bits, faces);
    }

    private static ModelSurface.Face surface(double[] p, float[] u, float[] v, TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        int w = contents.width(), h = contents.height();
        long[] alpha = new long[(w * h + 63) / 64];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (!contents.isTransparent(0, x, y)) {
                int k = x + w * y;
                alpha[k >> 6] |= 1L << (k & 63);
            }
        }
        if (solid(alpha, w * h)) return new ModelSurface.Face(p, new double[6], 1, 1, new long[] {1});
        double[] uv = new double[6];
        for (int i = 0; i < 3; i++) {
            uv[2 * i] = (u[i] - sprite.getU0()) / (sprite.getU1() - sprite.getU0()) * w;
            uv[2 * i + 1] = (v[i] - sprite.getV0()) / (sprite.getV1() - sprite.getV0()) * h;
        }
        return new ModelSurface.Face(p, uv, w, h, alpha);
    }

    // Геометрия рендера: прозрачность записывается в собственной сетке текстуры, без клиентских объектов.
    private static ModelSurface.Face surface(RenderedMesh.Tri tri) {
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
        long[] alpha = new long[(w * h + 63) / 64];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (tri.tex().opaque((float) (u0 + (x + .5) * du), (float) (v0 + (y + .5) * dv))) {
                int k = x + w * y;
                alpha[k >> 6] |= 1L << (k & 63);
            }
        }
        if (solid(alpha, w * h)) return new ModelSurface.Face(tri.p(), new double[6], 1, 1, new long[] {1});
        double[] uv = new double[6];
        for (int i = 0; i < 3; i++) {
            uv[2 * i] = (tri.uv()[2 * i] - u0) / du;
            uv[2 * i + 1] = (tri.uv()[2 * i + 1] - v0) / dv;
        }
        return new ModelSurface.Face(tri.p(), uv, w, h, alpha);
    }

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
