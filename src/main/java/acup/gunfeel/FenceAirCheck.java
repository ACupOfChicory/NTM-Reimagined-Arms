package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Bullets through the empty part of fences, walls, panes and bars: alone, in pairs, corners and T-joints, and a pair
// with one of them taken away. Rays from all round at points of the blocks, a third of them nearly along a rail. Each
// is checked against what the bullet is to meet (a Truth): the cells (BulletCheck, a server with no models) or the
// pixels the blocks draw (FenceAirStand, a client). A path (PassableClip.clip) must not stop before the first of them
// away from them (in the air), and a real NTM bullet whose line passes 1/32 block clear of them all must fly on with
// all its damage.
final class FenceAirCheck {
    static final String[] TYPES = {"minecraft:oak_fence", "minecraft:cobblestone_wall", "minecraft:iron_bars",
            "minecraft:glass_pane", "minecraft:bamboo_fence", "hbm:fence_metal"};
    static final String[] LAYOUTS = {"single", "pair", "corner", "tee", "pair, one taken"};
    // a stop counts as on the surface when within this of it (a face against the cell it lies on)
    static final double NEAR = 0.02;
    static final float DAMAGE = 30F;

    private FenceAirCheck() {
    }

    record Ray(Vec3 from, Vec3 to, Vec3 way) {
    }

    // what the bullet is to meet: how far along from -> to (0..1) it is first met (NaN: never), and how far a point
    // is from it
    interface Truth {
        double first(Vec3 from, Vec3 to);

        double off(Vec3 p);

        // it is the shape the path goes by (cells of a block without exact faces): a path going on past it is wrong too
        boolean exact();
    }

    static final class Tally {
        final String name;
        int rays, air, solid, bullets, held;
        final List<String> examples = new ArrayList<>();

        Tally(String name) {
            this.name = name;
        }

        boolean ok() {
            return air == 0 && solid == 0 && held == 0;
        }

        String line() {
            return (ok() ? "OK   " : "FAIL ") + "fence air: " + name + ": " + rays + " rays, stopped in the air " + air + ", through it " + solid
                    + "; bullets clear of it " + bullets + ", held " + held;
        }

        void example(String s) {
            if (examples.size() < 8) examples.add(s);
        }

        // one ray's path through the blocks against the truth
        void path(Ray r, Truth truth, BlockHitResult hit) {
            rays++;
            double t = truth.first(r.from, r.to), len = r.to.distanceTo(r.from);
            Vec3 met = Double.isNaN(t) ? null : r.from.add(r.to.subtract(r.from).scale(t));
            if (hit.getType() == HitResult.Type.MISS) {
                if (truth.exact() && met != null && truth.off(met.add(r.way.scale(NEAR))) == 0) {
                    solid++;
                    example("through it: " + r.from + " -> " + r.to + ", met at " + met);
                }
                return;
            }
            double h = hit.getLocation().distanceTo(r.from) / len;
            if ((met == null || h < t - NEAR / len) && truth.off(hit.getLocation()) > NEAR) {
                air++;
                example("stopped in the air: " + r.from + " -> " + r.to + " at " + hit.getLocation() + " in " + hit.getBlockPos().toShortString()
                        + " face " + hit.getDirection() + ", " + (met == null ? "nothing on the line" : "first met at " + met));
            } else if (truth.exact() && met != null && h > t + NEAR / len && truth.off(met.add(r.way.scale(NEAR))) == 0) {
                solid++;
                example("through it: " + r.from + " -> " + r.to + " stopped at " + hit.getLocation() + ", met at " + met);
            }
        }
    }

    static List<Ray> rays(List<BlockPos> blocks, java.util.Random r, int n) {
        List<Ray> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos aim = blocks.get(r.nextInt(blocks.size()));
            Vec3 point = new Vec3(aim.getX() + r.nextDouble(), aim.getY() + r.nextDouble(), aim.getZ() + r.nextDouble());
            Vec3 way = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.5, r.nextGaussian());
            if (i % 3 == 1) way = new Vec3(r.nextBoolean() ? 1 : -1, r.nextGaussian() * 0.05, r.nextGaussian() * 0.15);
            if (i % 3 == 2) way = new Vec3(r.nextGaussian() * 0.15, r.nextGaussian() * 0.05, r.nextBoolean() ? 1 : -1);
            way = way.normalize();
            out.add(new Ray(point.subtract(way.scale(3.5)), point.add(way.scale(3.5)), way));
        }
        return out;
    }

    // the line and four beside it, 1/32 block off across it, meet nothing
    static boolean clear(Truth truth, Ray r) {
        Vec3 a = Math.abs(r.way.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = r.way.cross(a).normalize().scale(1.0 / 32), v = r.way.cross(u).normalize().scale(1.0 / 32);
        for (Vec3 o : new Vec3[] {Vec3.ZERO, u, u.scale(-1), v, v.scale(-1)})
            if (!Double.isNaN(truth.first(r.from.add(o), r.to.add(o)))) return false;
        return true;
    }

    // a real NTM bullet along the ray, one tick (it goes 16 blocks or more): what it has left, NaN when it stopped
    static float fire(ServerLevel level, BulletConfig round, Ray r) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, round, 1F, 0F, 0F, 0F);
        b.setPos(r.from.x, r.from.y, r.from.z);
        b.setThrowableHeading(r.way.x, r.way.y, r.way.z, round.velocity, 0F);
        b.damage = DAMAGE;
        b.tick();
        boolean stopped = b.isRemoved();
        b.discard();
        return stopped ? Float.NaN : b.damage;
    }

    // the bullet again with the server's test log on: its lines
    static List<String> logged(ServerLevel level, BulletConfig round, Ray r) {
        List<String> out = new ArrayList<>();
        java.nio.file.Path log = TestLog.set(false, true);
        long from = 0;
        try {
            if (log != null && java.nio.file.Files.exists(log)) from = java.nio.file.Files.size(log);
        } catch (java.io.IOException ignored) {
        }
        fire(level, round, r);
        TestLog.set(false, false);
        try {
            if (log != null) {
                byte[] all = java.nio.file.Files.readAllBytes(log);
                int start = (int) Math.min(from, all.length);
                for (String l : new String(all, start, all.length - start, java.nio.charset.StandardCharsets.UTF_8).split("\n"))
                    if (!l.isBlank() && !l.startsWith("#")) out.add("         " + l);
            }
        } catch (java.io.IOException e) {
            out.add("         no log: " + e);
        }
        return out;
    }

    // a player within 3 blocks of the bullet's 64 blocks: it would be met there, not held by the blocks
    static boolean player(ServerLevel level, Ray r) {
        Vec3 end = r.from.add(r.way.scale(64));
        for (var p : level.players()) {
            Vec3 c = p.getBoundingBox().getCenter(), d = end.subtract(r.from);
            double s = Math.clamp(c.subtract(r.from).dot(d) / d.lengthSqr(), 0, 1);
            if (r.from.add(d.scale(s)).distanceTo(c) < 3) return true;
        }
        return false;
    }

    static void bullets(ServerLevel level, BulletConfig round, List<Ray> rays, Truth truth, Tally tally, int most) {
        for (Ray r : rays) {
            if (tally.bullets >= most) return;
            if (!clear(truth, r) || player(level, r)) continue;
            tally.bullets++;
            float left = fire(level, round, r);
            if (left == DAMAGE) continue;
            tally.held++;
            if (tally.examples.size() < 8) {
                tally.example("bullet held: " + r.from + " along " + r.way + (Float.isNaN(left) ? ", stopped" : ", damage " + left));
                tally.examples.addAll(logged(level, round, r));
            }
        }
    }

    // the layout around o, built on the server with every block's state from its neighbours; the blocks left
    static List<BlockPos> build(ServerLevel level, BlockPos o, String type, String layout) {
        clear(level, o);
        List<BlockPos> at = new ArrayList<>(List.of(o));
        if (!layout.equals("single")) at.add(o.east());
        if (layout.equals("corner") || layout.equals("tee")) at.add(o.south());
        if (layout.equals("tee")) at.add(o.west());
        BlockState st = state(level, type);
        for (BlockPos p : at) level.setBlock(p, st, Block.UPDATE_ALL);
        for (int pass = 0; pass < 2; pass++) for (BlockPos p : at)
            level.setBlock(p, Block.updateFromNeighbourShapes(level.getBlockState(p), level, p), Block.UPDATE_ALL);
        if (layout.equals("pair, one taken")) {
            level.setBlock(o.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(o, Block.updateFromNeighbourShapes(level.getBlockState(o), level, o), Block.UPDATE_ALL);
            at.remove(o.east());
        }
        return at;
    }

    static void clear(ServerLevel level, BlockPos o) {
        for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++)
            level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static BlockState state(ServerLevel level, String text) {
        try {
            return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), text, false).blockState();
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    // ---- the server's check (BulletCheck): the cells the path goes by, else the collision box

    static boolean run(ServerLevel level, BulletConfig round, List<String> lines, long seed) {
        BlockPos o = new BlockPos(40, 200, 40);
        boolean ok = true;
        java.util.Random r = new java.util.Random(seed);
        for (String type : TYPES) for (String layout : LAYOUTS) {
            List<BlockPos> blocks = build(level, o, type, layout);
            Truth truth = cells(level, blocks);
            Tally tally = new Tally(type.replace("minecraft:", "") + " " + layout + " (cells)");
            List<Ray> rays = rays(blocks, r, 1500);
            for (Ray ray : rays) tally.path(ray, truth, PassableClip.clip(level, ray.from, ray.to));
            bullets(level, round, rays, truth, tally, 300);
            ok &= tally.ok();
            lines.add(tally.line());
            for (String e : tally.examples) lines.add("       " + e);
        }
        clear(level, o);
        return ok;
    }

    static Truth cells(net.minecraft.world.level.Level level, List<BlockPos> blocks) {
        List<AABB> boxes = new ArrayList<>();
        boolean exact = true;
        for (BlockPos pos : blocks) {
            BlockState st = level.getBlockState(pos);
            long[] cells = PassableClip.shape(st);
            // exact faces (bars) or a mask (chain-link fence on a server without its model): the cells or the box are
            // only round what is met
            if (cells != null && ModelSurface.present(cells) || cells == null && !BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace().equals("minecraft"))
                exact = false;
            if (cells != null) {
                for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                    if (PassableClip.cell(cells, x, y, z))
                        boxes.add(new AABB(x / 16.0, y / 16.0, z / 16.0, (x + 1) / 16.0, (y + 1) / 16.0, (z + 1) / 16.0).move(pos));
            } else {
                for (AABB b : st.getCollisionShape(level, pos).toAabbs()) boxes.add(b.move(pos));
            }
        }
        boolean isExact = exact;
        return new Truth() {
            public double first(Vec3 from, Vec3 to) {
                double best = Double.NaN;
                for (AABB b : boxes) {
                    if (b.contains(from)) return 0;
                    var p = b.clip(from, to);
                    if (p.isEmpty()) continue;
                    double t = p.get().distanceTo(from) / to.distanceTo(from);
                    if (Double.isNaN(best) || t < best) best = t;
                }
                return best;
            }

            public double off(Vec3 p) {
                double best = Double.POSITIVE_INFINITY;
                for (AABB b : boxes) {
                    double dx = Math.max(Math.max(b.minX - p.x, 0), p.x - b.maxX), dy = Math.max(Math.max(b.minY - p.y, 0), p.y - b.maxY),
                            dz = Math.max(Math.max(b.minZ - p.z, 0), p.z - b.maxZ);
                    best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
                return best;
            }

            public boolean exact() {
                return isExact;
            }
        };
    }
}
