package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

// Bullets through the empty part of fences, walls, panes and bars: alone, in pairs, corners and T-joints, and a pair
// with one of them taken away. Rays from all round at points of the blocks, a third of them nearly along a rail. Each
// is checked against what the bullet is to meet (a Truth): the cells (BulletCheck, a server with no models). A path
// (PassableClip.clip) must not stop before the first of them away from them (in the air), and a real NTM bullet whose
// line passes 1/32 block clear of them all must fly on with all its damage.
// port: 26.2's FenceAirCheck (03ee2bd), its server's part, with 1.12.2's blocks: the oak fence is minecraft:fence, no
// bamboo fence; a block's joins are its actual state (the layout needs no updates). The cells are the port's
// (PassableClip.cells: the shape table's, a fence's family), the box the collision boxes (PassableClip.collision).
// 26.2's client stand (FenceAirStand, the pixels drawn) has no port.
final class FenceAirCheck {
    static final String[] TYPES = {"minecraft:fence", "minecraft:cobblestone_wall", "minecraft:iron_bars", "minecraft:glass_pane",
            "hbm:fence_metal"};
    static final String[] LAYOUTS = {"single", "pair", "corner", "tee", "pair, one taken"};
    // a stop counts as on the surface when within this of it (a face against the cell it lies on)
    static final double NEAR = 0.02;
    static final float DAMAGE = 30F;

    private FenceAirCheck() {
    }

    static final class Ray {
        final Vec3d from, to, way;

        Ray(Vec3d from, Vec3d to, Vec3d way) {
            this.from = from;
            this.to = to;
            this.way = way;
        }
    }

    // what the bullet is to meet: how far along from -> to (0..1) it is first met (NaN: never), and how far a point
    // is from it
    interface Truth {
        double first(Vec3d from, Vec3d to);

        double off(Vec3d p);

        // it is the shape the path goes by (cells of a block without exact faces): a path going on past it is wrong too
        boolean exact();

        // the line met it at `met` going on along `way` and goes into it there, not along its edge: going on past
        // that point is going through it (cells: a point a little further on is inside them)
        default boolean firm(Vec3d met, Vec3d way) {
            return off(met.add(way.scale(NEAR))) == 0;
        }
    }

    static final class Tally {
        final String name;
        int rays, air, solid, bullets, held;
        final List<String> examples = new ArrayList<String>();

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

        // one ray's path through the blocks against the truth; hit null: it met nothing
        void path(Ray r, Truth truth, BlockRay.Hit hit) {
            rays++;
            double t = truth.first(r.from, r.to), len = r.to.distanceTo(r.from);
            Vec3d met = Double.isNaN(t) ? null : r.from.add(r.to.subtract(r.from).scale(t));
            if (hit == null) {
                if (truth.exact() && met != null && truth.firm(met, r.way)) {
                    solid++;
                    example("through it: " + r.from + " -> " + r.to + ", met at " + met);
                }
                return;
            }
            Vec3d at = new Vec3d(hit.ax, hit.ay, hit.az);
            double h = at.distanceTo(r.from) / len;
            if ((met == null || h < t - NEAR / len) && truth.off(at) > NEAR) {
                air++;
                example("stopped in the air: " + r.from + " -> " + r.to + " at " + at + " in " + hit.x + "," + hit.y + "," + hit.z
                        + " face " + hit.face + ", " + (met == null ? "nothing on the line" : "first met at " + met));
            } else if (truth.exact() && met != null && h > t + NEAR / len && truth.firm(met, r.way)) {
                solid++;
                example("through it: " + r.from + " -> " + r.to + " stopped at " + at + ", met at " + met);
            }
        }
    }

    static List<Ray> rays(List<int[]> blocks, java.util.Random r, int n) {
        List<Ray> out = new ArrayList<Ray>(n);
        for (int i = 0; i < n; i++) {
            int[] aim = blocks.get(r.nextInt(blocks.size()));
            Vec3d point = new Vec3d(aim[0] + r.nextDouble(), aim[1] + r.nextDouble(), aim[2] + r.nextDouble());
            Vec3d way = new Vec3d(r.nextGaussian(), r.nextGaussian() * 0.5, r.nextGaussian());
            if (i % 3 == 1) way = new Vec3d(r.nextBoolean() ? 1 : -1, r.nextGaussian() * 0.05, r.nextGaussian() * 0.15);
            if (i % 3 == 2) way = new Vec3d(r.nextGaussian() * 0.15, r.nextGaussian() * 0.05, r.nextBoolean() ? 1 : -1);
            way = way.normalize();
            out.add(new Ray(point.subtract(way.scale(3.5)), point.add(way.scale(3.5)), way));
        }
        return out;
    }

    // the line and four beside it, 1/32 block off across it, meet nothing
    static boolean clear(Truth truth, Ray r) {
        Vec3d a = Math.abs(r.way.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d u = r.way.crossProduct(a).normalize().scale(1.0 / 32), v = r.way.crossProduct(u).normalize().scale(1.0 / 32);
        for (Vec3d o : new Vec3d[] {Vec3d.ZERO, u, u.scale(-1), v, v.scale(-1)})
            if (!Double.isNaN(truth.first(r.from.add(o), r.to.add(o)))) return false;
        return true;
    }

    // a real NTM bullet along the ray, one tick (it goes 16 blocks or more): what it has left, NaN when it stopped
    static float fire(WorldServer level, BulletConfig round, Ray r) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, round, 1F, 0F, 0F, 0F);
        b.setPosition(r.from.x, r.from.y, r.from.z);
        b.shoot(r.way.x, r.way.y, r.way.z, round.velocity, 0F);
        b.damage = DAMAGE;
        level.spawnEntity(b); // as BulletCheck's shots: NTM's setDead tells the bullet's trackers
        b.onUpdate();
        boolean stopped = b.isDead;
        float left = b.damage;
        b.setDead();
        return stopped ? Float.NaN : left;
    }

    // the bullet again with the server's test log on: its lines
    static List<String> logged(WorldServer level, BulletConfig round, Ray r) {
        List<String> out = new ArrayList<String>();
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
                    if (!l.trim().isEmpty() && !l.startsWith("#")) out.add("         " + l);
            }
        } catch (java.io.IOException e) {
            out.add("         no log: " + e);
        }
        return out;
    }

    // a player within 3 blocks of the bullet's 64 blocks: it would be met there, not held by the blocks
    static boolean player(WorldServer level, Ray r) {
        Vec3d end = r.from.add(r.way.scale(64)), d = end.subtract(r.from);
        for (EntityPlayer p : level.playerEntities) {
            AxisAlignedBB bb = p.getEntityBoundingBox();
            Vec3d c = new Vec3d((bb.minX + bb.maxX) / 2, (bb.minY + bb.maxY) / 2, (bb.minZ + bb.maxZ) / 2);
            double s = Math.clamp(c.subtract(r.from).dotProduct(d) / d.lengthSquared(), 0, 1);
            if (r.from.add(d.scale(s)).distanceTo(c) < 3) return true;
        }
        return false;
    }

    // the world's own blocks on the bullet's tick past the layout (other checks stand within its 160 blocks): it would
    // be held there, not by the layout
    static boolean world(WorldServer level, Ray r) {
        return level.rayTraceBlocks(r.to, r.to.add(r.way.scale(256)), false, false, false) != null;
    }

    static void bullets(WorldServer level, BulletConfig round, List<Ray> rays, Truth truth, Tally tally, int most) {
        for (Ray r : rays) {
            if (tally.bullets >= most) return;
            if (!clear(truth, r) || player(level, r) || world(level, r)) continue;
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

    // the layout around o; the blocks left
    static List<int[]> build(WorldServer level, int[] o, String type, String layout) {
        clear(level, o);
        List<int[]> at = new ArrayList<int[]>();
        at.add(o);
        int[] east = {o[0] + 1, o[1], o[2]};
        if (!layout.equals("single")) at.add(east);
        if (layout.equals("corner") || layout.equals("tee")) at.add(new int[] {o[0], o[1], o[2] + 1});
        if (layout.equals("tee")) at.add(new int[] {o[0] - 1, o[1], o[2]});
        Block b = Mc.block(type);
        if (b == null) throw new IllegalArgumentException(type);
        for (int[] p : at) Mc.setBlock(level, p[0], p[1], p[2], b, 0, 3);
        if (layout.equals("pair, one taken")) {
            Mc.setBlock(level, east[0], east[1], east[2], Blocks.AIR, 0, 3);
            at.remove(east);
        }
        return at;
    }

    static void clear(WorldServer level, int[] o) {
        for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++)
            Mc.setBlock(level, o[0] + x, o[1] + y, o[2] + z, Blocks.AIR, 0, 3);
    }

    // ---- the server's check (BulletCheck): the cells the path goes by, else the collision box

    static boolean run(WorldServer level, BulletConfig round, List<String> lines, long seed) {
        int[] o = {40, 200, 40};
        boolean ok = true;
        java.util.Random r = new java.util.Random(seed);
        for (String type : TYPES) for (String layout : LAYOUTS) {
            List<int[]> blocks = build(level, o, type, layout);
            Truth truth = cells(level, blocks);
            Tally tally = new Tally(type.replace("minecraft:", "") + " " + layout + " (cells)");
            List<Ray> rays = rays(blocks, r, 1500);
            for (Ray ray : rays) tally.path(ray, truth, PassableClip.clip(level, ray.from, ray.to));
            bullets(level, round, rays, truth, tally, 300);
            ok &= tally.ok();
            // the check itself: a path meeting nothing is seen going through the cells
            Tally blind = new Tally("");
            for (Ray ray : rays) blind.path(ray, truth, null);
            if (truth.exact() && blind.solid <= 100) {
                ok = false;
                lines.add("FAIL fence air: " + type + " " + layout + ": the check sees no path through it (" + blind.solid + ")");
            }
            lines.add(tally.line());
            for (String e : tally.examples) lines.add("       " + e);
        }
        clear(level, o);
        return ok;
    }

    static Truth cells(World level, List<int[]> blocks) {
        final List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
        boolean exact = true;
        double[] box = new double[6 * 16];
        for (int[] p : blocks) {
            Block b = Mc.block(level, p[0], p[1], p[2]);
            int meta = Mc.meta(level, p[0], p[1], p[2]);
            long[] cells = PassableClip.cells(level, b, meta, p[0], p[1], p[2]);
            String id = Mc.name(b);
            // exact faces (bars) or a block not the game's own without cells: the cells or the box are only round
            // what is met
            if (cells != null && ModelSurface.present(cells) || cells == null && (id == null || !id.startsWith("minecraft:"))) exact = false;
            if (cells != null) {
                for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                    if (FenceCells.cell(cells, x, y, z))
                        boxes.add(new AxisAlignedBB(p[0] + x / 16.0, p[1] + y / 16.0, p[2] + z / 16.0, p[0] + (x + 1) / 16.0, p[1] + (y + 1) / 16.0,
                                p[2] + (z + 1) / 16.0));
            } else {
                int n = PassableClip.collision(level, b, p[0], p[1], p[2], box);
                for (int i = 0; i < n; i++)
                    boxes.add(new AxisAlignedBB(box[i * 6], box[i * 6 + 1], box[i * 6 + 2], box[i * 6 + 3], box[i * 6 + 4], box[i * 6 + 5]));
            }
        }
        final boolean isExact = exact;
        return new Truth() {
            public double first(Vec3d from, Vec3d to) {
                double best = Double.NaN;
                for (AxisAlignedBB b : boxes) {
                    // 26.2's AABB.contains: min inclusive, max exclusive
                    if (from.x >= b.minX && from.x < b.maxX && from.y >= b.minY && from.y < b.maxY && from.z >= b.minZ && from.z < b.maxZ) return 0;
                    RayTraceResult p = b.calculateIntercept(from, to);
                    if (p == null) continue;
                    double t = p.hitVec.distanceTo(from) / to.distanceTo(from);
                    if (Double.isNaN(best) || t < best) best = t;
                }
                return best;
            }

            public double off(Vec3d p) {
                double best = Double.POSITIVE_INFINITY;
                for (AxisAlignedBB b : boxes) {
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
