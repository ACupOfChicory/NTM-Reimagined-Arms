package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

// port: from 26.2's PassableClip (0762086), the part 1.7.10 can have now. A bullet's path through blocks, the same on
// the server (hits) and the client (tracers, holes). 26.2 bends vanilla's level.clip (a ClipContext subclass that
// empties a block's shape); 1.7.10's World.func_147447_a has no such hook and other limits (BlockRay), so the port walks
// the cells itself with 26.2's walk and meets each block by its collision boxes, as 26.2 does (ClipContext.Block.COLLIDER).
// Flown through: air, liquids, blocks without a collision box (as NTM asks func_147447_a), blocks that cannot be hit
// (canCollideCheck), and blocks with a "through" rule. Not yet on 1.7.10: masks of see-through blocks (bars, grates),
// model cells (doors, scaffold, fences' gaps) and the cells of NTM's big models (Structures): the tables
// (ntmgunfeel-bullet-masks/-shapes) are made for 26.2's block states and must be made anew for 1.7.10's (Block, meta).
// There, such blocks are met by their collision boxes ("hitbox").
public final class PassableClip {
    private static final org.apache.logging.log4j.Logger LOGGER = GunFeel.LOGGER;

    private PassableClip() {
    }

    // /gunfeel holedebug: the steps through blocks, to the log
    public static volatile boolean debug;

    // the first block that stops a bullet going from `from` to `to`; null: it flies on to `to`
    public static BlockRay.Hit clip(World world, Vec3 from, Vec3 to) {
        BlockRay.Hit r = clip0(world, from, to);
        if (debug && (r != null || !world.isRemote))
            LOGGER.info("[holedebug] {} clip {} -> {} {}", world.isRemote ? "client" : "server", from, to, r == null ? "flies on" : "stops at " + r);
        return r;
    }

    // as clip(), and every block the bullet always flies through (bullet rules "through") that it crossed before it
    // stopped, entered from outside: one hit each (a path starting inside such a block does not count it again)
    public static BlockRay.Hit clip(World world, Vec3 from, Vec3 to, Consumer<BlockRay.Hit> passed) {
        BlockRay.Hit r = clip(world, from, to);
        if (!BulletRules.anyThrough()) return r;
        double ex = r == null ? to.xCoord : r.ax, ey = r == null ? to.yCoord : r.ay, ez = r == null ? to.zCoord : r.az;
        double dx = ex - from.xCoord, dy = ey - from.yCoord, dz = ez - from.zCoord;
        if (dx * dx + dy * dy + dz * dz < 1e-12) return r;
        final double[] boxes = new double[6 * 16];
        BlockRay.walk(from.xCoord, from.yCoord, from.zCoord, ex, ey, ez, (x, y, z) -> {
            Block b = world.getBlock(x, y, z);
            if (b.getMaterial() == Material.air || !BulletRules.through(b, world.getBlockMetadata(x, y, z), world, x, y, z)) return null;
            int n = outline(world, b, x, y, z, boxes);
            BlockRay.Hit in = BlockRay.clip(boxes, n, from.xCoord, from.yCoord, from.zCoord, ex, ey, ez, x, y, z);
            if (in != null && !in.inside) passed.accept(in);
            return null;
        });
        return r;
    }

    private static BlockRay.Hit clip0(World world, Vec3 from, Vec3 to) {
        final double fx = from.xCoord, fy = from.yCoord, fz = from.zCoord, tx = to.xCoord, ty = to.yCoord, tz = to.zCoord;
        final double[] boxes = new double[6 * 16];
        return BlockRay.walk(fx, fy, fz, tx, ty, tz, (x, y, z) -> {
            Block b = world.getBlock(x, y, z);
            if (b.getMaterial() == Material.air) return null;
            int meta = world.getBlockMetadata(x, y, z);
            if (!b.canCollideCheck(meta, false) || BulletRules.through(b, meta, world, x, y, z)) return null;
            int n = collision(world, b, x, y, z, boxes);
            BlockRay.Hit h = BlockRay.clip(boxes, n, fx, fy, fz, tx, ty, tz, x, y, z);
            if (debug && h != null)
                LOGGER.info("[holedebug]   {} {} {} at {}", world.isRemote ? "client" : "server", BlockIds.state(Block.blockRegistry.getNameForObject(b), meta),
                        x + "," + y + "," + z, h);
            return h;
        });
    }

    // the block's collision boxes in the cell (world coordinates, 6 numbers each), at most out.length / 6: the shape
    // bullets meet. A box reaching out of the cell (a fence's 1.5 high post) counts where the bullet crosses the cell.
    static int collision(World world, Block b, int x, int y, int z, double[] out) {
        List<AxisAlignedBB> list = new ArrayList<AxisAlignedBB>(4);
        try {
            b.addCollisionBoxesToList(world, x, y, z, AxisAlignedBB.getBoundingBox(x - 1, y - 1, z - 1, x + 2, y + 2, z + 2), list, null);
        } catch (RuntimeException e) {
            // a block that needs an entity to tell: as a full block
            list.clear();
            list.add(AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1));
        }
        int n = Math.min(list.size(), out.length / 6);
        for (int i = 0; i < n; i++) put(out, i, list.get(i));
        return n;
    }

    // the boxes a bullet leaves a block through or is counted in: its collision boxes, else its outline (the bounds
    // the game picks it by), else the whole cell
    static int outline(World world, Block b, int x, int y, int z, double[] out) {
        int n = collision(world, b, x, y, z, out);
        if (n > 0) return n;
        try {
            b.setBlockBoundsBasedOnState(world, x, y, z);
            if (b.getBlockBoundsMaxX() > b.getBlockBoundsMinX() && b.getBlockBoundsMaxY() > b.getBlockBoundsMinY()
                    && b.getBlockBoundsMaxZ() > b.getBlockBoundsMinZ()) {
                put(out, 0, AxisAlignedBB.getBoundingBox(x + b.getBlockBoundsMinX(), y + b.getBlockBoundsMinY(), z + b.getBlockBoundsMinZ(),
                        x + b.getBlockBoundsMaxX(), y + b.getBlockBoundsMaxY(), z + b.getBlockBoundsMaxZ()));
                return 1;
            }
        } catch (RuntimeException e) {
            // the whole cell
        }
        put(out, 0, AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1));
        return 1;
    }

    private static void put(double[] out, int i, AxisAlignedBB a) {
        int o = i * 6;
        out[o] = a.minX;
        out[o + 1] = a.minY;
        out[o + 2] = a.minZ;
        out[o + 3] = a.maxX;
        out[o + 4] = a.maxY;
        out[o + 5] = a.maxZ;
    }

    // a bullet that pierces the block it hit (bullet rules "pierce"): where it comes out of the block's shape, going
    // along (dx, dy, dz) (unit), as a hit on that far face from outside; null when its shape gives no way out
    public static BlockRay.Hit exit(World world, BlockRay.Hit in, double dx, double dy, double dz) {
        Block b = world.getBlock(in.x, in.y, in.z);
        double[] boxes = new double[6 * 16];
        int n = outline(world, b, in.x, in.y, in.z, boxes);
        double fx = in.ax + dx * 2.0, fy = in.ay + dy * 2.0, fz = in.az + dz * 2.0;
        BlockRay.Hit out = BlockRay.clip(boxes, n, fx, fy, fz, in.ax, in.ay, in.az, in.x, in.y, in.z);
        return out == null || out.inside ? null : out;
    }

    // a bullet with this much damage goes into the block at `in` and would come out at `out` (exit()): the damage
    // left, else -1 (it stops in it). A break rule with pierce= says it; else the block's material (Materials, as
    // NTM's armour: its threshold and resistance over the path's length), against the round's threshold negation
    // and armour piercing. Less than 1 left stops it.
    public static float pierced(World world, BlockRay.Hit in, BlockRay.Hit out, float damage, BulletConfig round) {
        return pierced(world, in, out, damage, round, new Materials.Penetration());
    }

    public static float pierced(World world, BlockRay.Hit in, BlockRay.Hit out, float damage, BulletConfig round,
            Materials.Penetration penetration) {
        Block b = world.getBlock(in.x, in.y, in.z);
        if (b.getMaterial() == Material.air || !(damage >= 1F)) return -1F;
        int meta = world.getBlockMetadata(in.x, in.y, in.z);
        // port: NTM 1.7.10's multiblocks are one block (BlockDummyable) in all their cells: its own rules are the
        // machine's (26.2: Cells.ruleState)
        Materials.Verdict v = BulletRules.material(b, meta, world, in.x, in.y, in.z);
        // Запрет относится к машине, даже если её часть определилась как none/stone.
        // Проверяем до правил пробития, чтобы они тоже не обходили общий выключатель.
        if ((machine(b) || v.material() == Materials.Material.MACHINE) && !ReaimTimes.flag(BulletRules.MACHINES_KEY, false)) return -1F;
        double path = in.distance(out.ax, out.ay, out.az);
        BulletRules.Break rule = BulletRules.breakRule(b, meta, world, in.x, in.y, in.z);
        if (rule != null && rule.pierce() > 0F) return rule.pierced(damage, hardness(world, b, in.x, in.y, in.z), path);
        float negation = round == null ? 0F : round.armorThresholdNegation;
        float piercing = round == null ? 0F : round.armorPiercingPercent;
        float left = penetration.left(v, path, damage, negation, piercing);
        return left >= 1F ? left : -1F;
    }

    // port: an NTM multiblock (26.2: Cells.machine, BlockMultiblockCore / Cell)
    static boolean machine(Block b) {
        return b instanceof com.hbm.blocks.BlockDummyable;
    }

    static float hardness(World world, Block b, int x, int y, int z) {
        try {
            return b.getBlockHardness(world, x, y, z);
        } catch (RuntimeException e) {
            return 1F;
        }
    }
}
