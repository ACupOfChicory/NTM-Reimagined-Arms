package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.RayHit;
import acup.gunfeel.host.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 26.2's world for core (WorldView): a level's states by the game's one table, BlockStates; its tick and side, its ray,
 * its shapes and free room as the game answers them (a Body is this version's EntityBody: another's is a mistake, the
 * cast says so).
 */
final class LevelWorld implements WorldView {
    // a place per thread: bullets ask on the server's thread, tracers on the client's
    private static final ThreadLocal<BlockPos.MutableBlockPos> AT = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final BlockStates TABLE = new BlockStates();
    private static final ClipContext.Block[] SHAPES = ClipContext.Block.values();
    private static final ClipContext.Fluid[] FLUIDS = ClipContext.Fluid.values();
    private static final net.minecraft.world.level.levelgen.Heightmap.Types[] HEIGHTS = net.minecraft.world.level.levelgen.Heightmap.Types.values();
    private final Level level;
    private @Nullable GameRandom random;

    LevelWorld(Level level) {
        this.level = level;
    }

    private static @Nullable Entity entity(@Nullable Body by) {
        return by == null ? null : ((EntityBody) by).entity();
    }

    private static CollisionContext context(@Nullable Body by) {
        return by == null ? CollisionContext.empty() : CollisionContext.of(((EntityBody) by).entity());
    }

    @Override
    public int state(int x, int y, int z) {
        return BlockStates.number(level.getBlockState(AT.get().set(x, y, z)));
    }

    @Override
    public float destroySpeed(int state, int x, int y, int z) {
        BlockPos at = AT.get().set(x, y, z);
        return BlockStates.state(state).getDestroySpeed(level, at);
    }

    @Override
    public long gameTime() {
        return level.getGameTime();
    }

    @Override
    public GameRandom random() {
        if (random == null) random = GameRandoms.of(level.getRandom());
        return random;
    }

    @Override
    public boolean client() {
        return level.isClientSide();
    }

    @Override
    public boolean clip(double fx, double fy, double fz, double tx, double ty, double tz, int shape, int fluid, @Nullable Body by, RayHit out) {
        BlockHitResult hit = level.clip(new ClipContext(new Vec3(fx, fy, fz), new Vec3(tx, ty, tz), SHAPES[shape], FLUIDS[fluid], context(by)));
        Vec3 at = hit.getLocation();
        BlockPos pos = hit.getBlockPos();
        out.block = hit.getType() == HitResult.Type.BLOCK;
        out.x = at.x;
        out.y = at.y;
        out.z = at.z;
        out.face = hit.getDirection().ordinal();
        out.bx = pos.getX();
        out.by = pos.getY();
        out.bz = pos.getZ();
        out.inside = hit.isInside();
        return out.block;
    }

    @Override
    public int boxes(int x, int y, int z, int kind, @Nullable Body by, double[] out) {
        BlockPos at = AT.get().set(x, y, z);
        var state = level.getBlockState(at);
        CollisionContext c = context(by);
        VoxelShape shape = switch (kind) {
            case COLLIDER -> state.getCollisionShape(level, at, c);
            case OUTLINE -> state.getShape(level, at, c);
            case VISUAL -> state.getVisualShape(level, at, c);
            default -> throw new IllegalArgumentException("no block shape of kind " + kind);
        };
        var boxes = shape.toAabbs();
        int n = boxes.size();
        if (out.length < n * 6) return -n;
        for (int i = 0; i < n; i++) {
            AABB b = boxes.get(i);
            out[i * 6] = b.minX;
            out[i * 6 + 1] = b.minY;
            out[i * 6 + 2] = b.minZ;
            out[i * 6 + 3] = b.maxX;
            out[i * 6 + 4] = b.maxY;
            out[i * 6 + 5] = b.maxZ;
        }
        return n;
    }

    @Override
    public boolean free(@Nullable Body by, double x0, double y0, double z0, double x1, double y1, double z1) {
        return level.noCollision(entity(by), new AABB(x0, y0, z0, x1, y1, z1));
    }

    @Override
    public boolean air(int state) {
        return TABLE.air(state);
    }

    @Override
    public BlockFacts facts(int state) {
        return TABLE.facts(state);
    }

    @Override
    public String key(int state) {
        return TABLE.key(state);
    }

    @Override
    public boolean machineOrPartial(int state) {
        return TABLE.machineOrPartial(state);
    }

    @Override
    public int height(int x, int z, int kind) {
        if (kind < 0 || kind >= HEIGHTS.length) throw new IllegalArgumentException("no heightmap of kind " + kind);
        return level.getHeight(HEIGHTS[kind], x, z);
    }

    @Override
    public boolean hasChunk(int cx, int cz) {
        return level.hasChunk(cx, cz);
    }

    @Override
    public int minY() {
        return level.getMinY();
    }
}
