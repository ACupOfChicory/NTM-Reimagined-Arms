package acup.gunfeel;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// A bullet's path through blocks, the same on the server (hits) and the client (tracers, holes).
// See-through blocks (bars, chain-link fence, grates) have a mask of which texture pixels are solid
// (ntmgunfeel-bullet-masks.properties, made by tools/bullet_masks.py, since the server has no textures):
// a bullet meeting a clear pixel flies on, a solid pixel stops it. Blocks tagged ntmgunfeel:bullet_passable
// (empty in the jar; a datapack can fill it) are always flown through.
public final class PassableClip extends ClipContext {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");
    public static final TagKey<net.minecraft.world.level.block.Block> PASSABLE =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("ntmgunfeel", "bullet_passable"));
    private final Set<BlockPos> skip;

    private PassableClip(Vec3 from, Vec3 to, Set<BlockPos> skip) {
        super(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        this.skip = skip;
    }

    @Override
    public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.is(PASSABLE) || skip.contains(pos) || BulletRules.through(state)) return Shapes.empty();
        // a model inside its hitbox: only the model's cells stop a bullet (march), whatever the collision box
        // (scaffolding has none unless you stand on it). A shell (door, trapdoor) keeps its hitbox: see clip().
        long[] vox = shape(state);
        if (vox != null && !shell(vox)) return Shapes.block();
        if (level instanceof Level l && Structures.cells(l, pos, state) != null) return Shapes.block();
        return super.getBlockShape(state, level, pos);
    }

    // /gunfeel holedebug: the client's steps through blocks, to the log
    public static volatile boolean debug;

    // the first block that stops a bullet going from `from` to `to`
    public static BlockHitResult clip(Level level, Vec3 from, Vec3 to) {
        BlockHitResult r = clip0(level, from, to);
        // the server's bullet too (every tick, misses included): where it really stops
        if (debug && (r.getType() != HitResult.Type.MISS || !level.isClientSide()))
            LOGGER.info("[holedebug] {} clip {} -> {} {}", side(level), from, to,
                    r.getType() == HitResult.Type.MISS ? "flies on" : "stops at " + r.getBlockPos() + " " + r.getDirection() + " " + r.getLocation());
        return r;
    }

    // as clip(), and every block the bullet always flies through (bullet rules "through") that it crossed before it
    // stopped, entered from outside: one hit each (a path starting inside such a block does not count it again)
    public static BlockHitResult clip(Level level, Vec3 from, Vec3 to, java.util.function.Consumer<BlockHitResult> passed) {
        BlockHitResult r = clip(level, from, to);
        if (!BulletRules.anyThrough()) return r;
        Vec3 end = r.getType() == HitResult.Type.MISS ? to : r.getLocation();
        Vec3 d = end.subtract(from);
        double len = d.length();
        if (len < 1e-6) return r;
        BlockPos start = BlockPos.containing(from);
        // the cells the segment crosses, in order (a grid walk)
        int x = start.getX(), y = start.getY(), z = start.getZ();
        int sx = d.x > 0 ? 1 : -1, sy = d.y > 0 ? 1 : -1, sz = d.z > 0 ? 1 : -1;
        double tx = d.x == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - from.x : from.x - x) / Math.abs(d.x));
        double ty = d.y == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - from.y : from.y - y) / Math.abs(d.y));
        double tz = d.z == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - from.z : from.z - z) / Math.abs(d.z));
        double dx = d.x == 0 ? Double.MAX_VALUE : 1 / Math.abs(d.x), dy = d.y == 0 ? Double.MAX_VALUE : 1 / Math.abs(d.y),
                dz = d.z == 0 ? Double.MAX_VALUE : 1 / Math.abs(d.z);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 512; i++) {
            if (tx <= ty && tx <= tz) {
                if (tx > 1) break;
                x += sx;
                tx += dx;
            } else if (ty <= tz) {
                if (ty > 1) break;
                y += sy;
                ty += dy;
            } else {
                if (tz > 1) break;
                z += sz;
                tz += dz;
            }
            at.set(x, y, z);
            BlockState state = level.getBlockState(at);
            if (state.isAir() || !BulletRules.through(state)) continue;
            BlockHitResult in = state.getShape(level, at).clip(from, end, at);
            if (in != null) passed.accept(in);
        }
        return r;
    }

    // a bullet that pierces the block it hit (bullet rules "pierce"): where it comes out of the block's shape, going
    // along `dir` (unit), as a hit on that far face from outside; null when its shape gives no way out
    public static @Nullable BlockHitResult exit(Level level, BlockHitResult in, Vec3 dir) {
        BlockPos pos = in.getBlockPos();
        BlockState state = level.getBlockState(pos);
        // a model's cells (a scaffold's post, a board): out where its pixels end along the way, not through the
        // whole hitbox
        long[] vox = shape(state);
        long[] cells = vox != null ? (shell(vox) ? null : vox) : Structures.cells(level, pos, state);
        if (cells != null) return leave(cells, in, dir);
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) shape = state.getShape(level, pos);
        if (shape.isEmpty()) shape = Shapes.block();
        Vec3 far = in.getLocation().add(dir.scale(2.0));
        BlockHitResult out = shape.clip(far, in.getLocation(), pos);
        return out == null || out.isInside() ? null : out;
    }

    // a bullet with this much damage goes into the block at `in` and would come out at `out` (exit()): the damage
    // left, else -1 (it stops in it). A break rule with pierce= says it; else the block's material (Materials, as
    // NTM's armour: its threshold and resistance over the path's length), against the round's threshold negation
    // and armour piercing. Less than 1 left stops it.
    public static float pierced(Level level, BlockHitResult in, BlockHitResult out, float damage,
            @Nullable BulletConfig round) {
        BlockState state = level.getBlockState(in.getBlockPos());
        if (state.isAir() || !(damage >= 1F)) return -1F;
        BulletRules.Break rule = BulletRules.breakRule(state);
        if (rule != null && rule.pierce() > 0F) return rule.pierced(damage, state.getDestroySpeed(level, in.getBlockPos()), in.getLocation().distanceTo(out.getLocation()));
        Materials.Verdict v = BulletRules.material(Cells.ruleState(level, in.getBlockPos(), state));
        float negation = round == null ? 0F : round.armorThresholdNegation;
        float piercing = round == null ? 0F : round.armorPiercingPercent;
        float left = Materials.left(v, in.getLocation().distanceTo(out.getLocation()), damage, negation, piercing);
        return left >= 1F ? left : -1F;
    }

    private static String side(Level level) {
        return level.isClientSide() ? "client" : "server";
    }

    // NTM's multiblock shapes name the block that holds the hit point (BlockPos.containing): a point on the top face
    // of a block (the elevator's platform on its base) is then put in the block above it. The block whose face it is.
    private static BlockHitResult owner(BlockHitResult hit) {
        Direction f = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        double at = hit.getLocation().get(f.getAxis()), lo = pos.get(f.getAxis());
        boolean onFar = f.getAxisDirection() == Direction.AxisDirection.POSITIVE ? at == lo : at == lo + 1;
        if (!onFar || hit.isInside()) return hit;
        return new BlockHitResult(hit.getLocation(), f, pos.relative(f.getOpposite()), false);
    }

    private static BlockHitResult clip0(Level level, Vec3 from, Vec3 to) {
        Set<BlockPos> skip = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            BlockHitResult hit = level.clip(new PassableClip(from, to, skip));
            if (hit.getType() == HitResult.Type.MISS) return hit;
            hit = owner(hit);
            BlockState state = level.getBlockState(hit.getBlockPos());
            if (debug)
                LOGGER.info("[holedebug]   {} step {}: {} {} face={} at={} inside={} shape={} cells={}{}", side(level), i, hit.getBlockPos(), key(state),
                        hit.getDirection(), hit.getLocation(), hit.isInside(), shape(state) != null, Structures.cells(level, hit.getBlockPos(), state) != null,
                        Structures.debug(level, hit.getBlockPos(), state));
            long[] vox = shape(state);
            long[] cells = vox == null ? Structures.cells(level, hit.getBlockPos(), state) : null;
            if (cells != null) {
                // a block of a big model (NTM's cargo door): its cells as drawn, then its solid parts (an elevator's
                // platform) as NTM has them
                // whichever the bullet meets first: the elevator's platform lies over its base, in the same block
                BlockHitResult in = march(cells, hit, to.subtract(from), 64);
                BlockHitResult solid = Structures.solid(level, hit.getBlockPos(), state, from, to);
                if (debug)
                    LOGGER.info("[holedebug]     {} model cells: {}, solid part: {}", side(level),
                            in == null ? "none" : in.getLocation(), solid == null ? "none" : solid.getLocation());
                if (solid != null && (in == null || solid.getLocation().distanceToSqr(from) < in.getLocation().distanceToSqr(from))) in = solid;
                if (in != null) return in;
            } else if (vox != null) {
                // a shell (its model closes the hitbox all round: a door, a trapdoor, a lantern) is decided where the
                // bullet comes in, as a mask block is: a shown pixel stops it there, a see-through one lets it through
                // the whole block (not onto its far inner face, seen through the window). Already inside it (came in
                // through a see-through pixel the last tick): flies on.
                BlockHitResult in = !shell(vox) ? march(vox, hit, to.subtract(from), 64)
                        : hit.isInside() ? null : march(vox, hit, to.subtract(from), 1);
                if (in != null) return in;
            } else if (hit.isInside() && maskOf(state) != null) {
                // already inside a see-through block (the last tick or frame ended there after it came in through a
                // clear pixel): its faces were passed, it flies on
            } else if (maskOf(state) != null && hiddenFace(level, hit, state)) {
                // came in through a face its neighbour hides (two copper grates or iron bars side by side are drawn as
                // one thick grate, with no face between them): no pixel there to meet
            } else if (solid(state, hit)) {
                return hit;
            }
            if (TestLog.on(level) && TestLog.shot() >= 0)
                TestLog.at(level, "gap", "block", state, "pos", hit.getBlockPos(), "at", hit.getLocation(),
                        "how", cells != null ? "cells" : vox != null ? (shell(vox) ? "shell" : "model") : maskOf(state) != null ? "mask" : "open");
            skip.add(hit.getBlockPos().immutable());
        }
        Vec3 d = to.subtract(from);
        return BlockHitResult.miss(to, Direction.getApproximateNearest(d.x, d.y, d.z), BlockPos.containing(to));
    }

    // Blocks with a 3D model inside their hitbox (NTM's steel scaffold, poles): 16 x 16 x 16 cells, set where the
    // model shows a pixel (ntmgunfeel-bullet-shapes.properties, made by the client from its models, see MeshShapes).
    // Bit x + 16 * (y + 16 * z). Key: block id and its properties, see key().
    // The jar's shapes; this game's (the jar's, then the config file's and this client's); on another game's server the
    // jar's and that server's (ServerSettings). The maps are replaced whole, never changed in place: bullets read them
    // on other threads.
    private static Map<String, long[]> jar = Map.of();
    private static volatile Map<String, long[]> local = Map.of();
    private static volatile @Nullable Map<String, long[]> remote;
    private static volatile int localVersion;
    private static final Map<BlockState, long[]> SHAPE_OF = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long[] NONE = new long[0];
    private static volatile boolean shapesLoaded;
    private static volatile int shapesOfRules = -1;

    public static String key(BlockState state) {
        StringBuilder b = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var props = new java.util.ArrayList<>(state.getProperties());
        props.sort(java.util.Comparator.comparing(net.minecraft.world.level.block.state.properties.Property::getName));
        char sep = '[';
        for (var prop : props) {
            b.append(sep).append(prop.getName()).append('=').append(name(state, prop));
            sep = ',';
        }
        if (sep == ',') b.append(']');
        return b.toString();
    }

    private static <T extends Comparable<T>> String name(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }

    public static long[] shape(BlockState state) {
        if (!shapesLoaded) loadShapes();
        int g = BulletRules.generation();
        if (g != shapesOfRules) {
            // the bullet rules were read anew: their shape lines may have changed
            SHAPE_OF.clear();
            shapesOfRules = g;
        }
        long[] v = SHAPE_OF.get(state);
        if (v == null) {
            // not in computeIfAbsent: cellsOf takes the class's lock (masks), which putShapes holds while clearing
            long[] bits = cellsOf(state);
            v = bits == null ? NONE : bits;
            SHAPE_OF.putIfAbsent(state, v);
        }
        return v == NONE ? null : v;
    }

    // The cells a bullet meets in the block. A "path" bullet rule says how (model: its own cells; a family: that
    // family's; hitbox, pixels, through: none). Without one: its own cells when listed (doors, scaffold), else a
    // fence, gate or wall of any mod takes its family's, unless it has a mask (NTM's chain-link fence: by its pixels).
    // Fences, gates and walls have a collision box that is a solid wall up to 1.5 blocks: with the family's cells
    // (tools/fence_shapes.py) the bullet goes through the gaps (пролёт).
    private static long @Nullable [] cellsOf(BlockState state) {
        String path = BulletRules.path(state);
        if (path == null || path.equals("model")) {
            long[] own = shapes().get(key(state));
            if (own != null || path != null) return own;
        }
        String family = familyKey(state, path);
        return family == null ? null : shapes().get(family);
    }

    static @Nullable String familyKey(BlockState state, @Nullable String path) {
        net.minecraft.world.level.block.Block b = state.getBlock();
        String family = family(path, classFamily(b), masks().containsKey(b));
        if (family == null) return null;
        Map<String, String> props = new HashMap<>();
        for (var prop : state.getProperties()) props.put(prop.getName(), name(state, prop));
        return familyKey(family, props);
    }

    // the family a block takes: the path rule's when it names one (another path: none), else its class's unless it
    // has a mask
    static @Nullable String family(@Nullable String path, @Nullable String byClass, boolean masked) {
        if (path != null) return FAMILIES.containsKey(path) ? path : null;
        return masked ? null : byClass;
    }

    // the words of a "path" bullet rule: the four ways and the families
    public static final Set<String> PATH_WORDS = Set.of("hitbox", "pixels", "model", "through", "fence", "fence_gate", "wall",
            "bamboo_fence", "bamboo_fence_gate");

    // the block's pixel mask, unless a path rule says other than pixels
    private static long[] @Nullable [] maskOf(BlockState state) {
        String path = BulletRules.path(state);
        if (path != null && !path.equals("pixels")) return null;
        return masks().get(state.getBlock());
    }

    private static @Nullable String classFamily(net.minecraft.world.level.block.Block b) {
        if (b == net.minecraft.world.level.block.Blocks.BAMBOO_FENCE) return "bamboo_fence";
        if (b == net.minecraft.world.level.block.Blocks.BAMBOO_FENCE_GATE) return "bamboo_fence_gate";
        if (b instanceof net.minecraft.world.level.block.FenceBlock) return "fence";
        if (b instanceof net.minecraft.world.level.block.FenceGateBlock) return "fence_gate";
        if (b instanceof net.minecraft.world.level.block.WallBlock) return "wall";
        return null;
    }

    // per family the properties that change its model, their values, the first one the default (for a block made a
    // fence by a rule without the fence's sides: a post), as tools/fence_shapes.py makes them
    private static final List<String> BOOL = List.of("false", "true");
    private static final Map<String, List<String>> FENCE = Map.of("east", BOOL, "north", BOOL, "south", BOOL, "west", BOOL);
    private static final Map<String, List<String>> GATE = Map.of("facing", List.of("north", "east", "south", "west"), "in_wall", BOOL,
            "open", BOOL);
    private static final List<String> SIDE = List.of("none", "low", "tall");
    public static final Map<String, Map<String, List<String>>> FAMILIES = Map.of("fence", FENCE, "fence_gate", GATE,
            "wall", Map.of("east", SIDE, "north", SIDE, "south", SIDE, "west", SIDE, "up", List.of("true", "false")),
            "bamboo_fence", FENCE, "bamboo_fence_gate", GATE);

    // the key of the family's cells for a block with these properties (the family's own, the rest left out; one it
    // lacks or with a value the family has not takes the default)
    static String familyKey(String family, Map<String, String> props) {
        Map<String, List<String>> own = FAMILIES.get(family);
        StringBuilder k = new StringBuilder("~").append(family).append('[');
        String sep = "";
        for (String name : new java.util.TreeSet<>(own.keySet())) {
            String v = props.get(name);
            if (v == null || !own.get(name).contains(v)) v = own.get(name).getFirst();
            k.append(sep).append(name).append('=').append(v);
            sep = ",";
        }
        return k.append(']').toString();
    }

    // bits[64] = 1: the model closes its hitbox all round (see MeshShapes.shell)
    public static boolean shell(long[] bits) {
        return bits.length > 64 && bits[64] != 0L;
    }

    public static boolean shell(BlockState state) {
        long[] v = shape(state);
        return v != null && shell(v);
    }

    // shapes made by this client: used at once (single player, the client's own tracers); on another game's server they
    // wait until the client leaves it, as that server goes by its own
    public static synchronized void putShapes(Map<String, long[]> shapes) {
        Map<String, long[]> next = new HashMap<>(local());
        next.putAll(shapes);
        local = next;
        localVersion++;
        forget();
    }

    // the server's shapes beyond the jar's (packed, by key), or null: back to this game's own
    public static synchronized void useRemoteShapes(@Nullable Map<String, String> packed) {
        loadShapes();
        if (packed == null) {
            remote = null;
        } else {
            Map<String, long[]> m = new HashMap<>(jar);
            for (Map.Entry<String, String> e : packed.entrySet()) {
                long[] bits = unpack(e.getValue());
                if (bits != null) m.put(e.getKey(), bits);
            }
            remote = m;
        }
        forget();
    }

    private static void forget() {
        SHAPE_OF.clear();
        Structures.clear();
    }

    // goes up when this game's shapes change (the server sends them again)
    public static int localVersion() {
        return localVersion;
    }

    // this game's shapes that the jar has not (or has other cells for), packed: what the server sends its players
    public static Map<String, String> extraShapes() {
        Map<String, long[]> mine = local();
        Map<String, String> out = new java.util.TreeMap<>();
        for (Map.Entry<String, long[]> e : mine.entrySet()) {
            if (!java.util.Arrays.equals(jar.get(e.getKey()), e.getValue())) out.put(e.getKey(), pack(e.getValue()));
        }
        return out;
    }

    // the cells under a key (block shapes and the cells of big models: see Structures), as bullets meet them
    static long @org.jspecify.annotations.Nullable [] byKey(String key) {
        return shapes().get(key);
    }

    // the cells under a key that this game knows (not the server's)
    static long @org.jspecify.annotations.Nullable [] localByKey(String key) {
        return local().get(key);
    }

    // every key and its cells this game knows (the jar's, the config's, this client's)
    public static Map<String, long[]> all() {
        return new java.util.TreeMap<>(local());
    }

    // the shapes bullets go by: the server's on another game's server, else this game's
    private static Map<String, long[]> shapes() {
        Map<String, long[]> r = remote;
        return r != null ? r : local();
    }

    private static Map<String, long[]> local() {
        if (!shapesLoaded) loadShapes();
        return local;
    }

    private static synchronized void loadShapes() {
        if (shapesLoaded) return;
        Map<String, long[]> fromJar = new HashMap<>();
        try (var in = PassableClip.class.getResourceAsStream("/ntmgunfeel-bullet-shapes.properties")) {
            if (in != null) readShapes(new InputStreamReader(in, StandardCharsets.UTF_8), fromJar);
        } catch (Exception e) {
            LOGGER.warn("Could not read the bullet shapes", e);
        }
        Map<String, long[]> mine = new HashMap<>(fromJar);
        // made by a client of this mod version (MeshShapes); wins over the jar's
        java.nio.file.Path f = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(SHAPES_FILE);
        if (java.nio.file.Files.exists(f)) {
            try (var r = java.nio.file.Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                readShapes(r, mine);
            } catch (Exception e) {
                LOGGER.warn("Could not read {}", SHAPES_FILE, e);
            }
        }
        jar = fromJar;
        local = mine;
        shapesLoaded = true;
    }

    public static final String SHAPES_FILE = "ntm-gunfeel-bullet-shapes.properties";

    // "key = <shape>" or "key = @n" with "@n = <shape>" written once; a shape is 1024 hex digits or "z" and the
    // deflated 512 bytes in base64, after "!" for a shell
    private static void readShapes(java.io.Reader r, Map<String, long[]> into) throws java.io.IOException {
        Properties p = new Properties();
        p.load(r);
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("@")) continue;
            String v = p.getProperty(k).trim();
            if (v.startsWith("@")) v = p.getProperty(v, "").trim();
            long[] bits = unpack(v);
            if (bits != null) into.put(k, bits);
        }
    }

    static long[] unpack(String v) {
        long[] bits = new long[65];
        if (v.startsWith("!")) {
            bits[64] = 1L;
            v = v.substring(1);
        }
        try {
            if (v.length() == 1024) {
                for (int j = 0; j < 64; j++) bits[j] = Long.parseUnsignedLong(v.substring(j * 16, j * 16 + 16), 16);
                return bits;
            }
            if (!v.startsWith("z")) return null;
            var inf = new java.util.zip.Inflater();
            inf.setInput(java.util.Base64.getDecoder().decode(v.substring(1)));
            byte[] b = new byte[512];
            int n = 0;
            while (n < 512 && !inf.finished()) {
                int got = inf.inflate(b, n, 512 - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += got;
            }
            inf.end();
            if (n != 512) return null;
            java.nio.ByteBuffer.wrap(b).asLongBuffer().get(bits, 0, 64);
            return bits;
        } catch (Exception e) {
            return null;
        }
    }

    public static String pack(long[] bits) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(512);
        buf.asLongBuffer().put(bits, 0, 64);
        var def = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        def.setInput(buf.array());
        def.finish();
        byte[] out = new byte[1024];
        int n = def.deflate(out);
        def.end();
        return (shell(bits) ? "!z" : "z") + java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(out, n));
    }

    public static String hex(long[] bits) {
        StringBuilder b = new StringBuilder(1024);
        for (long l : bits) b.append(String.format("%016x", l));
        return b.toString();
    }

    public static boolean cell(long[] bits, int x, int y, int z) {
        int i = x + 16 * (y + 16 * z);
        return (bits[i >> 6] >>> (i & 63) & 1L) != 0L;
    }

    // From where the bullet enters the block's hitbox, along its way, to the first set cell: the hit there, or null
    // when it leaves the block without meeting one.
    static BlockHitResult march(long[] bits, BlockHitResult hit, Vec3 way, int cells) {
        BlockPos pos = hit.getBlockPos();
        double len = way.length();
        if (len < 1e-9) return hit;
        double dx = way.x / len, dy = way.y / len, dz = way.z / len;
        double px = (hit.getLocation().x - pos.getX()) * 16, py = (hit.getLocation().y - pos.getY()) * 16, pz = (hit.getLocation().z - pos.getZ()) * 16;
        int x = Math.clamp((int) Math.floor(px), 0, 15), y = Math.clamp((int) Math.floor(py), 0, 15), z = Math.clamp((int) Math.floor(pz), 0, 15);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tx = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) - px) / dx;
        double ty = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) - py) / dy;
        double tz = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) - pz) / dz;
        double ddx = Math.abs(1 / dx), ddy = Math.abs(1 / dy), ddz = Math.abs(1 / dz);
        double t = 0;
        Direction face = hit.getDirection();
        for (int n = 0; n < cells; n++) {
            if (cell(bits, x, y, z)) {
                Vec3 at = new Vec3(pos.getX() + (px + dx * t) / 16, pos.getY() + (py + dy * t) / 16, pos.getZ() + (pz + dz * t) / 16);
                return new BlockHitResult(at, face, pos, false);
            }
            if (tx <= ty && tx <= tz) {
                t = tx; tx += ddx; x += sx; face = sx > 0 ? Direction.WEST : Direction.EAST;
            } else if (ty <= tz) {
                t = ty; ty += ddy; y += sy; face = sy > 0 ? Direction.DOWN : Direction.UP;
            } else {
                t = tz; tz += ddz; z += sz; face = sz > 0 ? Direction.NORTH : Direction.SOUTH;
            }
            if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) return null;
        }
        return null;
    }

    // From where the bullet meets a set cell, along `dir` (unit), to the first empty cell or the block's side: the
    // hit there on that far face, as exit() gives it
    static BlockHitResult leave(long[] bits, BlockHitResult in, Vec3 dir) {
        BlockPos pos = in.getBlockPos();
        // a little into the cell it met: on its boundary the floor may name the empty one before it
        double px = (in.getLocation().x - pos.getX()) * 16 + dir.x * 1e-3, py = (in.getLocation().y - pos.getY()) * 16 + dir.y * 1e-3,
                pz = (in.getLocation().z - pos.getZ()) * 16 + dir.z * 1e-3;
        int x = Math.clamp((int) Math.floor(px), 0, 15), y = Math.clamp((int) Math.floor(py), 0, 15), z = Math.clamp((int) Math.floor(pz), 0, 15);
        double dx = dir.x, dy = dir.y, dz = dir.z;
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tx = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) - px) / dx;
        double ty = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) - py) / dy;
        double tz = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) - pz) / dz;
        double ddx = Math.abs(1 / dx), ddy = Math.abs(1 / dy), ddz = Math.abs(1 / dz);
        double t = 0;
        Direction face = in.getDirection().getOpposite();
        for (int n = 0; n < 64 && cell(bits, x, y, z); n++) {
            if (tx <= ty && tx <= tz) {
                t = tx; tx += ddx; x += sx; face = sx > 0 ? Direction.EAST : Direction.WEST;
            } else if (ty <= tz) {
                t = ty; ty += ddy; y += sy; face = sy > 0 ? Direction.UP : Direction.DOWN;
            } else {
                t = tz; tz += ddz; z += sz; face = sz > 0 ? Direction.SOUTH : Direction.NORTH;
            }
            if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) break;
        }
        Vec3 at = new Vec3(pos.getX() + (px + dx * t) / 16, pos.getY() + (py + dy * t) / 16, pos.getZ() + (pz + dz * t) / 16);
        return new BlockHitResult(at, face, pos, false);
    }

    // Masks per block: [axis 0 x, 1 y, 2 z, 3 any face] -> 256 bits, rows from the top
    private static Map<net.minecraft.world.level.block.Block, long[][]> masks;

    private static synchronized Map<net.minecraft.world.level.block.Block, long[][]> masks() {
        if (masks != null) return masks;
        Map<net.minecraft.world.level.block.Block, long[][]> out = new HashMap<>();
        try (var in = PassableClip.class.getResourceAsStream("/ntmgunfeel-bullet-masks.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String key : p.stringPropertyNames()) {
                    int dot = key.lastIndexOf('.');
                    String axis = key.substring(dot + 1), hex = p.getProperty(key).trim();
                    Identifier id = Identifier.tryParse(key.substring(0, dot));
                    if (id == null || hex.length() != 64) continue;
                    var block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
                    if (block == null) continue;
                    long[] bits = new long[4];
                    for (int j = 0; j < 4; j++) bits[j] = Long.parseUnsignedLong(hex.substring(j * 16, j * 16 + 16), 16);
                    int slot = switch (axis) {
                        case "x" -> 0;
                        case "y" -> 1;
                        case "z" -> 2;
                        default -> 3;
                    };
                    out.computeIfAbsent(block, k -> new long[4][])[slot] = bits;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read the bullet masks", e);
        }
        return masks = out;
    }

    // the hit lies on the block's own face toward a neighbour that hides that face (it is not drawn)
    private static boolean hiddenFace(BlockGetter level, BlockHitResult hit, BlockState state) {
        Direction face = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        double at = switch (face.getAxis()) {
            case X -> hit.getLocation().x - pos.getX();
            case Y -> hit.getLocation().y - pos.getY();
            case Z -> hit.getLocation().z - pos.getZ();
        };
        double edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0;
        if (Math.abs(at - edge) > 1e-5) return false;
        return !net.minecraft.world.level.block.Block.shouldRenderFace(state, level.getBlockState(pos.relative(face)), face);
    }

    // true unless the block has a mask and the hit lands on one of its clear pixels
    private static boolean solid(BlockState state, BlockHitResult hit) {
        long[][] m = maskOf(state);
        if (m == null) return true;
        Direction face = hit.getDirection();
        int axis = face.getAxis().ordinal();
        long[] bits = m[axis] != null ? m[axis] : m[3];
        if (bits == null) return true;
        Vec3 local = hit.getLocation().subtract(hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ());
        // the texture across the face: u along it, v up it (top and bottom faces: x and z)
        double u = axis == 0 ? local.z : local.x;
        double v = axis == 1 ? local.z : 1.0 - local.y;
        // a full cube (copper grate) shows the texture upright and unmirrored on each of its six faces
        if (state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            switch (face) {
                case EAST -> u = 1.0 - local.z;
                case NORTH -> u = 1.0 - local.x;
                case DOWN -> v = 1.0 - local.z;
                default -> { }
            }
        }
        int col = Math.clamp((int) Math.floor(u * 16.0), 0, 15), row = Math.clamp((int) Math.floor(v * 16.0), 0, 15);
        int bit = row * 16 + col;
        return (bits[bit >> 6] >>> (63 - (bit & 63)) & 1L) != 0L;
    }
}
