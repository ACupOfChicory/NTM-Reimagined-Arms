package acup.gunfeel;

import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
// writes them to config/ntm-gunfeel-bullet-shapes.properties when they differ from the jar's; copied into the jar's
// file they reach the server.
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
            if (!out.contains(b) && "model".equals(BulletRules.path(b.defaultBlockState()))) out.add(b);
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

    // every shape known now (blocks and big models) to config/, for copying into the jar's file
    private static void write() {
        Map<String, long[]> all = PassableClip.all();
        Path f = FMLPaths.CONFIGDIR.get().resolve(PassableClip.SHAPES_FILE);
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            w.write("# made by NTM Gun Feel from this client's block models and big NTM models: 16x16x16 cells a bullet meets,\n");
            w.write("# bit x+16*(y+16*z), deflated and in base64 after z; states with the same cells point to one @n\n");
            Map<String, String> ids = new java.util.LinkedHashMap<>();
            Map<String, Integer> uses = new TreeMap<>();
            for (long[] bits : all.values()) uses.merge(PassableClip.pack(bits), 1, Integer::sum);
            for (var e : uses.entrySet()) if (e.getValue() > 1) ids.put(e.getKey(), "@" + ids.size());
            for (var e : ids.entrySet()) w.write(e.getValue() + " = " + e.getKey() + "\n");
            for (var e : all.entrySet()) {
                String packed = PassableClip.pack(e.getValue());
                w.write(e.getKey().replace(":", "\\:").replace("=", "\\=") + " = " + ids.getOrDefault(packed, packed) + "\n");
            }
            GunFeel.LOGGER.info("Wrote {} bullet shapes to {}", all.size(), f);
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not write {}", f, e);
        }
    }

    // ---- big NTM models (cargo door, cargo elevator): their cells from what is drawn, see Structures

    private static final Map<BlockPos, BlockState> CAPTURED = new java.util.HashMap<>();
    private static final Map<BlockPos, BlockState> LAST_SEEN = new java.util.HashMap<>();
    private static int ticks;

    public static void forget() {
        CAPTURED.clear();
        LAST_SEEN.clear();
    }

    // every 2 s: the multiblocks near the player, standing still since the last look, not read in this state yet
    public static void tick() {
        if (++ticks % 40 != 0) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        BlockPos me = mc.player.blockPosition();
        Map<String, long[]> made = new TreeMap<>();
        Map<BlockPos, BlockState> seen = new java.util.HashMap<>();
        int cx = me.getX() >> 4, cz = me.getZ() >> 4;
        for (int x = cx - 3; x <= cx + 3; x++) for (int z = cz - 3; z <= cz + 3; z++) {
            var chunk = level.getChunkSource().getChunk(x, z, false);
            if (chunk == null) continue;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockState state = be.getBlockState();
                BlockPos pos = be.getBlockPos();
                if (!Structures.taken(state) || pos.distSqr(me) > 48 * 48 || !Structures.atRest(level, pos, state)) continue;
                seen.put(pos.immutable(), state);
                if (state.equals(CAPTURED.get(pos)) || !state.equals(LAST_SEEN.get(pos))) continue;
                try {
                    made.putAll(capture(level, be, state));
                } catch (Throwable t) {
                    GunFeel.LOGGER.warn("Could not read the cells of {} at {}", state, pos, t);
                }
                CAPTURED.put(pos.immutable(), state);
            }
        }
        LAST_SEEN.clear();
        LAST_SEEN.putAll(seen);
        boolean differs = false;
        for (var e : made.entrySet()) {
            long[] had = PassableClip.localByKey(e.getKey());
            if (had != null && java.util.Arrays.equals(had, e.getValue())) continue;
            if (had != null) {
                int diff = 0;
                for (int i = 0; i < 64; i++) diff += Long.bitCount(had[i] ^ e.getValue()[i]);
                GunFeel.LOGGER.info("Cells of {} differ from those known by {}", e.getKey(), diff);
            }
            differs = true;
        }
        if (!differs) return;
        PassableClip.putShapes(made);
        write();
    }

    // the cells of each block of the multiblock, keyed as Structures.key
    private static Map<String, long[]> capture(ClientLevel level, BlockEntity be, BlockState coreState) {
        long start = System.nanoTime();
        BlockPos core = be.getBlockPos();
        Map<BlockPos, long[]> bits = new java.util.HashMap<>();
        for (BlockPos b : com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level, core)) {
            // the elevator: its base and one section stand for all
            if (Structures.layer(coreState, b.getY() - core.getY()) != b.getY() - core.getY()) continue;
            bits.put(b.immutable(), new long[65]);
        }
        int tris = 0;
        if (Structures.rendered(coreState)) {
            for (RenderedMesh.Tri t : RenderedMesh.of(be)) {
                tris++;
                sample(t.p(), (u, v) -> t.tex().opaque(u, v), t.uv(), bits);
            }
        }
        for (BlockPos b : com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level, core)) {
            BlockState st = level.getBlockState(b);
            if (st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
            for (BakedQuad q : quads(st)) {
                for (int k = 0; k < 2; k++) {
                    int[] c = k == 0 ? new int[] {0, 1, 2} : new int[] {0, 2, 3};
                    double[] p = new double[9];
                    float[] uv = new float[6];
                    for (int i = 0; i < 3; i++) {
                        var v = q.position(c[i]);
                        p[i * 3] = b.getX() + v.x();
                        p[i * 3 + 1] = b.getY() + v.y();
                        p[i * 3 + 2] = b.getZ() + v.z();
                        uv[i * 2] = UVPair.unpackU(q.packedUV(c[i]));
                        uv[i * 2 + 1] = UVPair.unpackV(q.packedUV(c[i]));
                    }
                    TextureAtlasSprite sprite = q.materialInfo().sprite();
                    tris++;
                    sample(p, (u, v) -> opaque(sprite, u, v), uv, bits);
                }
            }
        }
        Map<String, long[]> out = new TreeMap<>();
        net.minecraft.world.level.block.Rotation r = Structures.toNorth(coreState.getValue(com.hbm.blocks.multiblock.BlockMultiblockCore.FACING));
        int set = 0;
        for (var e : bits.entrySet()) {
            long[] world = e.getValue(), north = new long[65];
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int[] n = Structures.turn(x, z, r);
                for (int y = 0; y < 16; y++) {
                    if (!PassableClip.cell(world, x, y, z)) continue;
                    int k = n[0] + 16 * (y + 16 * n[1]);
                    north[k >> 6] |= 1L << (k & 63);
                    set++;
                }
            }
            BlockPos b = e.getKey();
            out.put(Structures.key(coreState, b.getX() - core.getX(), b.getY() - core.getY(), b.getZ() - core.getZ()), north);
        }
        GunFeel.LOGGER.info("Read the cells of {} at {}: {} blocks, {} triangles, {} cells set, {} ms", PassableClip.key(coreState), core,
                out.size(), tris, set, (System.nanoTime() - start) / 1_000_000);
        return out;
    }

    private interface Shown {
        boolean at(float u, float v);
    }

    // a triangle (corners in the world, their u, v) sampled 4 times a cell: its shown points set the cell they lie in,
    // of the block they lie in (one on a block's face: either block that is one of the multiblock's)
    private static void sample(double[] p, Shown shown, float[] uv, Map<BlockPos, long[]> bits) {
        double edge = 0;
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            edge = Math.max(edge, Math.sqrt(sq(p[i * 3] - p[j * 3]) + sq(p[i * 3 + 1] - p[j * 3 + 1]) + sq(p[i * 3 + 2] - p[j * 3 + 2])));
        }
        int n = Math.max(2, (int) Math.ceil(edge * 64));
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n - i; j++) {
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
                if (in == null || !shown.at(u, v)) continue;
                int cx = Math.clamp((int) Math.floor((bx - at.getX()) * 16), 0, 15);
                int cy = Math.clamp((int) Math.floor((by - at.getY()) * 16), 0, 15);
                int cz = Math.clamp((int) Math.floor((bz - at.getZ()) * 16), 0, 15);
                int k = cx + 16 * (cy + 16 * cz);
                in[k >> 6] |= 1L << (k & 63);
            }
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
        for (BakedQuad q : quads) {
            String twin = planeKey(q, true);
            boolean behind = twin == null || !planes.contains(twin);
            for (int t = 0; t < 2; t++) {
                int a = 0, b = t == 0 ? 1 : 2, c = t == 0 ? 2 : 3;
                var pa = q.position(a); var pb = q.position(b); var pc = q.position(c);
                float[] u = {UVPair.unpackU(q.packedUV(a)), UVPair.unpackU(q.packedUV(b)), UVPair.unpackU(q.packedUV(c))};
                float[] v = {UVPair.unpackV(q.packedUV(a)), UVPair.unpackV(q.packedUV(b)), UVPair.unpackV(q.packedUV(c))};
                TextureAtlasSprite sprite = q.materialInfo().sprite();
                fill(bits, new double[] {pa.x(), pa.y(), pa.z(), pb.x(), pb.y(), pb.z(), pc.x(), pc.y(), pc.z()}, behind,
                        (wa, wb, wc) -> opaque(sprite, (float) (wa * u[0] + wb * u[1] + wc * u[2]), (float) (wa * v[0] + wb * v[1] + wc * v[2])));
            }
        }
        return bits;
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
