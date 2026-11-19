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
        if (vox != null && !ShapeCells.shell(vox)) return Shapes.block();
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
        // a machine's window: out a pixel further on, into the machine (what is inside it is met on its own)
        if (in instanceof ModelSurface.GlassHit)
            return new BlockHitResult(in.getLocation().add(dir.scale(MachineGlass.PATH)), in.getDirection().getOpposite(), pos, false);
        BlockState state = level.getBlockState(pos);
        // a model's cells (a scaffold's post, a board): out where its pixels end along the way, not through the
        // whole hitbox
        long[] vox = shape(state);
        long[] cells = vox != null ? (ShapeCells.shell(vox) ? null : vox) : Structures.cells(level, pos, state);
        if (vox != null && ModelSurface.present(vox)) {
            BlockHitResult out = ModelSurface.hit(vox, pos, in.getLocation(), in.getLocation().add(dir.scale(2)), true);
            if (out != null) return out;
        }
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
    // a hit that is paid for once per block (a bullet that pierced it is not stopped by it again): not a machine's
    // window, behind which the rest of the machine still stands in the same block
    public static boolean paidOnce(BlockHitResult hit) {
        return !(hit instanceof ModelSurface.GlassHit);
    }

    public static float pierced(Level level, BlockHitResult in, BlockHitResult out, float damage,
            @Nullable BulletConfig round) {
        return pierced(level, in, out, damage, round, new Materials.Penetration());
    }

    // the sum is core's (Pierce.left); here the round's numbers, the path's length and what the bullet rules and NTM's
    // machines say of the block, asked as core needs them
    public static float pierced(Level level, BlockHitResult in, BlockHitResult out, float damage,
            @Nullable BulletConfig round, Materials.Penetration penetration) {
        BlockPos pos = in.getBlockPos();
        return Pierce.left(Wiring.world(level), pos.getX(), pos.getY(), pos.getZ(), damage, RoundSpecs.fill(round),
                in instanceof ModelSurface.GlassHit, in.getLocation().distanceTo(out.getLocation()), penetration, new PierceBlock(level, pos));
    }

    // what pierced asks of the block beyond the world: its rules' material (a machine's cell: its machine's), NTM's
    // machines, its break rule; the state the one core read (its number), the machine's rule state once
    private static final class PierceBlock implements Pierce.Block {
        private final Level level;
        private final BlockPos pos;
        private @Nullable BlockState ruleState;

        PierceBlock(Level level, BlockPos pos) {
            this.level = level;
            this.pos = pos;
        }

        private BlockState ruleState(int state) {
            if (ruleState == null) ruleState = Cells.ruleState(level, pos, BlockStates.state(state));
            return ruleState;
        }

        @Override
        public Materials.Verdict material(int state) {
            return BulletRules.material(ruleState(state));
        }

        @Override
        public boolean machine(int state) {
            return Cells.machine(BlockStates.state(state)) || Cells.machine(ruleState(state));
        }

        @Override
        public BulletRuleText.@Nullable Break breakRule(int state) {
            return BulletRules.breakRule(BlockStates.state(state));
        }
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

    // level.clip (BlockGetter.clip) as 26.2 has it: its walk of the blocks in core (BlockWalk, the same blocks in the
    // same order), each block's hit as the game's: the context's shape and its interaction override, the fluid's
    // shape (none here: Fluid.NONE), the nearer of the two; past the end its miss
    private static BlockHitResult walk(Level level, PassableClip c) {
        Vec3 from = c.getFrom(), to = c.getTo();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return BlockWalk.walk(from.x, from.y, from.z, to.x, to.y, to.z, c, (ctx, x, y, z) -> blockHit(level, ctx, pos.set(x, y, z)), PassableClip::miss);
    }

    private static @Nullable BlockHitResult blockHit(Level level, PassableClip c, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        net.minecraft.world.level.material.FluidState fluid = level.getFluidState(pos);
        Vec3 from = c.getFrom(), to = c.getTo();
        VoxelShape shape = c.getBlockShape(state, level, pos);
        BlockHitResult block = level.clipWithInteractionOverride(from, to, pos, shape, state);
        BlockHitResult wet = c.getFluidShape(fluid, level, pos).clip(from, to, pos);
        double b = block == null ? Double.MAX_VALUE : c.getFrom().distanceToSqr(block.getLocation());
        double w = wet == null ? Double.MAX_VALUE : c.getFrom().distanceToSqr(wet.getLocation());
        return b <= w ? block : wet;
    }

    private static BlockHitResult miss(PassableClip c) {
        Vec3 d = c.getFrom().subtract(c.getTo());
        return BlockHitResult.miss(c.getTo(), Direction.getApproximateNearest(d.x, d.y, d.z), BlockPos.containing(c.getTo()));
    }

    private static BlockHitResult clip0(Level level, Vec3 from, Vec3 to) {
        Set<BlockPos> skip = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            BlockHitResult hit = walk(level, new PassableClip(from, to, skip));
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
                BlockHitResult in = ModelSurface.present(cells) ? ModelSurface.hit(cells, hit.getBlockPos(), from, to, false, true)
                        : march(cells, hit, to.subtract(from), 64);
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
                BlockHitResult in = ModelSurface.present(vox) ? ModelSurface.hit(vox, hit.getBlockPos(), from, to, false, false, hiddenSides(level, hit.getBlockPos(), state))
                        : !ShapeCells.shell(vox) ? march(vox, hit, to.subtract(from), 64)
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
            if (WorldLog.on(level) && TestLog.shot() >= 0)
                WorldLog.at(level, "gap", "block", state, "pos", hit.getBlockPos(), "at", hit.getLocation(),
                        "how", cells != null ? "cells" : vox != null ? (ShapeCells.shell(vox) ? "shell" : "model") : maskOf(state) != null ? "mask" : "open");
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
    static long[] @Nullable [] maskOf(BlockState state) {
        String path = BulletRules.path(state);
        if (path != null && !path.equals("pixels")) return null;
        return masks().get(state.getBlock());
    }

    // У узкой модели UV разных граней и точка встречи с лучом не совпадают с её collision box.
    // Одна плоская маска пригодна для полного куба, но для прутьев нужны настоящие грани.
    static boolean needsModelMask(BlockState state) {
        return BulletRules.path(state)==null && masks().containsKey(state.getBlock())
                && !state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
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

    public static boolean shell(BlockState state) {
        long[] v = shape(state);
        return v != null && ShapeCells.shell(v);
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
                long[] bits = ShapeCells.unpack(e.getValue());
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
            if (!java.util.Arrays.equals(jar.get(e.getKey()), e.getValue())) out.put(e.getKey(), ShapePacking.SHARED.pack(e.getValue()));
        }
        return out;
    }

    // the cells under a key (block shapes and the cells of big models: see Structures), as bullets meet them; a machine
    // the jar's pack has (made with this game's NTM) by the pack: the config's may be from an older mod
    static long @org.jspecify.annotations.Nullable [] byKey(String key) {
        long[] packed = ShapePack.get(key);
        return packed != null ? packed : shapes().get(key);
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
            long[] bits = ShapeCells.unpack(v);
            if (bits != null) into.put(k, bits);
        }
    }

    // From where the bullet enters the block's hitbox, along its way, to the first set cell: the hit there, or null
    // when it leaves the block without meeting one (the walk: ShapeCells.march).
    static BlockHitResult march(long[] bits, BlockHitResult hit, Vec3 way, int cells) {
        BlockPos pos = hit.getBlockPos();
        double len = way.length();
        if (len < 1e-9) return hit;
        double dx = way.x / len, dy = way.y / len, dz = way.z / len;
        double px = (hit.getLocation().x - pos.getX()) * 16, py = (hit.getLocation().y - pos.getY()) * 16, pz = (hit.getLocation().z - pos.getZ()) * 16;
        double[] t = new double[1];
        int face = ShapeCells.march(bits, px, py, pz, dx, dy, dz, cells, t);
        if (face < 0) return null;
        Vec3 at = new Vec3(pos.getX() + (px + dx * t[0]) / 16, pos.getY() + (py + dy * t[0]) / 16, pos.getZ() + (pz + dz * t[0]) / 16);
        return new BlockHitResult(at, face == ShapeCells.KEPT ? hit.getDirection() : FACES[face], pos, false);
    }

    // From where the bullet meets a set cell, along `dir` (unit), to the first empty cell or the block's side: the
    // hit there on that far face, as exit() gives it (the walk: ShapeCells.leave)
    static BlockHitResult leave(long[] bits, BlockHitResult in, Vec3 dir) {
        BlockPos pos = in.getBlockPos();
        // a little into the cell it met: on its boundary the floor may name the empty one before it
        double px = (in.getLocation().x - pos.getX()) * 16 + dir.x * 1e-3, py = (in.getLocation().y - pos.getY()) * 16 + dir.y * 1e-3,
                pz = (in.getLocation().z - pos.getZ()) * 16 + dir.z * 1e-3;
        double[] t = new double[1];
        int face = ShapeCells.leave(bits, px, py, pz, dir.x, dir.y, dir.z, t);
        Vec3 at = new Vec3(pos.getX() + (px + dir.x * t[0]) / 16, pos.getY() + (py + dir.y * t[0]) / 16, pos.getZ() + (pz + dir.z * t[0]) / 16);
        return new BlockHitResult(at, face == ShapeCells.KEPT ? in.getDirection().getOpposite() : FACES[face], pos, false);
    }

    // the sides by ShapeCells' numbers (Direction's own order)
    private static final Direction[] FACES = Direction.values();

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

    // the block's sides its neighbours hide (bit Direction.ordinal): the game does not draw its faces there
    static int hiddenSides(BlockGetter level, BlockPos pos, BlockState state) {
        int out = 0;
        for (Direction d : Direction.values())
            if (!net.minecraft.world.level.block.Block.shouldRenderFace(state, level.getBlockState(pos.relative(d)), d)) out |= 1 << d.ordinal();
        return out;
    }

    // the hit lies on the block's own face toward a neighbour that hides that face (it is not drawn)
    private static boolean hiddenFace(BlockGetter level, BlockHitResult hit, BlockState state) {
        Direction face = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        Vec3 at = hit.getLocation();
        if (!MaskPixels.onFace(face.ordinal(), at.x - pos.getX(), at.y - pos.getY(), at.z - pos.getZ())) return false;
        return !net.minecraft.world.level.block.Block.shouldRenderFace(state, level.getBlockState(pos.relative(face)), face);
    }

    // true unless the block has a mask and the hit lands on one of its clear pixels (MaskPixels)
    private static boolean solid(BlockState state, BlockHitResult hit) {
        long[][] m = maskOf(state);
        if (m == null) return true;
        Direction face = hit.getDirection();
        long[] bits = MaskPixels.side(m, face.ordinal());
        if (bits == null) return true;
        Vec3 at = hit.getLocation();
        BlockPos pos = hit.getBlockPos();
        return MaskPixels.shown(bits, face.ordinal(), at.x - pos.getX(), at.y - pos.getY(), at.z - pos.getZ(),
                state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }
}
