package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

// Bullets through the empty part of fences, walls, panes and bars: alone, in pairs, corners and T-joints, and a pair
// with one of them taken away. Rays from all round at points of the blocks, a third of them nearly along a rail. Each
// is checked against what the bullet is to meet (a Truth): the cells (BulletCheck, a server with no models). A path
// (PassableClip.clip) must not stop before the first of them away from them (in the air), and a real NTM bullet whose
// line passes 1/32 block clear of them all must fly on with all its damage.
// port: 26.2's FenceAirCheck (03ee2bd), its server's part, as the 1.12.2 port has it, with 1.7.10's blocks: the oak
// fence is minecraft:fence, no bamboo fence; a block's joins are read from its neighbours (the layout needs no updates).
// The cells are the port's (PassableClip.cells: the shape table's, a fence's family), the box the collision boxes
// (PassableClip.collision). Vectors are 1.7.10's Vec3 through V below (it has no add/scale, and its subtract is
// reversed). 26.2's client stand (FenceAirStand, the pixels drawn) has no port.
final class FenceAirCheck {
    static final String[] TYPES = {"minecraft:fence", "minecraft:cobblestone_wall", "minecraft:iron_bars", "minecraft:glass_pane",
            "hbm:fence_metal"};
    static final String[] LAYOUTS = {"single", "pair", "corner", "tee", "pair, one taken"};
    // a stop counts as on the surface when within this of it (a face against the cell it lies on)
    static final double NEAR = 0.02;
    static final float DAMAGE = 30F;

    private FenceAirCheck() {
    }

    // 26.2's Vec3 operations on 1.7.10's Vec3
    static final class V {
        static final Vec3 ZERO = of(0, 0, 0);

        static Vec3 of(double x, double y, double z) {
            return Vec3.createVectorHelper(x, y, z);
        }

        static Vec3 add(Vec3 a, Vec3 b) {
            return of(a.xCoord + b.xCoord, a.yCoord + b.yCoord, a.zCoord + b.zCoord);
        }

        static Vec3 sub(Vec3 a, Vec3 b) {
            return of(a.xCoord - b.xCoord, a.yCoord - b.yCoord, a.zCoord - b.zCoord);
        }

        static Vec3 scale(Vec3 a, double k) {
            return of(a.xCoord * k, a.yCoord * k, a.zCoord * k);
        }

        static Vec3 cross(Vec3 a, Vec3 b) {
            return of(a.yCoord * b.zCoord - a.zCoord * b.yCoord, a.zCoord * b.xCoord - a.xCoord * b.zCoord,
                    a.xCoord * b.yCoord - a.yCoord * b.xCoord);
        }

        static double dot(Vec3 a, Vec3 b) {
            return a.xCoord * b.xCoord + a.yCoord * b.yCoord + a.zCoord * b.zCoord;
        }

        static Vec3 norm(Vec3 a) {
            double l = Math.sqrt(dot(a, a));
            return l < 1e-4 ? of(0, 0, 0) : scale(a, 1 / l);
        }
    }

    static final class Ray {
        final Vec3 from, to, way;

        Ray(Vec3 from, Vec3 to, Vec3 way) {
            this.from = from;
            this.to = to;
            this.way = way;
        }
    }

    // what the bullet is to meet: how far along from -> to (0..1) it is first met (NaN: never), and how far a point
    // is from it
    interface Truth {
        double first(Vec3 from, Vec3 to);

        double off(Vec3 p);

        // it is the shape the path goes by (cells of a block without exact faces): a path going on past it is wrong too
        boolean exact();

        // the line met it at `met` going on along `way` and goes into it there, not along its edge: going on past
        // that point is going through it (cells: a point a little further on is inside them)
        default boolean firm(Vec3 met, Vec3 way) {
            return off(V.add(met, V.scale(way, NEAR))) == 0;
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
            Vec3 met = Double.isNaN(t) ? null : V.add(r.from, V.scale(V.sub(r.to, r.from), t));
            if (hit == null) {
                if (truth.exact() && met != null && truth.firm(met, r.way)) {
                    solid++;
                    example("through it: " + r.from + " -> " + r.to + ", met at " + met);
                }
                return;
            }
            Vec3 at = V.of(hit.ax, hit.ay, hit.az);
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
            Vec3 point = V.of(aim[0] + r.nextDouble(), aim[1] + r.nextDouble(), aim[2] + r.nextDouble());
            Vec3 way = V.of(r.nextGaussian(), r.nextGaussian() * 0.5, r.nextGaussian());
            if (i % 3 == 1) way = V.of(r.nextBoolean() ? 1 : -1, r.nextGaussian() * 0.05, r.nextGaussian() * 0.15);
            if (i % 3 == 2) way = V.of(r.nextGaussian() * 0.15, r.nextGaussian() * 0.05, r.nextBoolean() ? 1 : -1);
            way = V.norm(way);
            out.add(new Ray(V.sub(point, V.scale(way, 3.5)), V.add(point, V.scale(way, 3.5)), way));
        }
        return out;
    }

    // the line and four beside it, 1/32 block off across it, meet nothing
    static boolean clear(Truth truth, Ray r) {
        Vec3 a = Math.abs(r.way.yCoord) < 0.9 ? V.of(0, 1, 0) : V.of(1, 0, 0);
        Vec3 u = V.scale(V.norm(V.cross(r.way, a)), 1.0 / 32), v = V.scale(V.norm(V.cross(r.way, u)), 1.0 / 32);
        for (Vec3 o : new Vec3[] {V.ZERO, u, V.scale(u, -1), v, V.scale(v, -1)})
            if (!Double.isNaN(truth.first(V.add(r.from, o), V.add(r.to, o)))) return false;
        return true;
    }

    // a real NTM bullet along the ray, one tick (it goes 16 blocks or more): what it has left, NaN when it stopped
    static float fire(WorldServer level, BulletConfig round, Ray r) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, round, 1F, 0F, 0F, 0F);
        b.setPosition(r.from.xCoord, r.from.yCoord, r.from.zCoord);
        b.setThrowableHeading(r.way.xCoord, r.way.yCoord, r.way.zCoord, round.velocity, 0F);
        b.damage = DAMAGE;
        level.spawnEntityInWorld(b); // as BulletCheck's shots: NTM's setDead tells the bullet's trackers
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
        Vec3 end = V.add(r.from, V.scale(r.way, 64)), d = V.sub(end, r.from);
        for (Object o : level.playerEntities) {
            EntityPlayer p = (EntityPlayer) o;
            AxisAlignedBB bb = p.boundingBox;
            Vec3 c = V.of((bb.minX + bb.maxX) / 2, (bb.minY + bb.maxY) / 2, (bb.minZ + bb.maxZ) / 2);
            double s = Math.max(0, Math.min(1, V.dot(V.sub(c, r.from), d) / V.dot(d, d)));
            if (V.add(r.from, V.scale(d, s)).distanceTo(c) < 3) return true;
        }
        return false;
    }

    // the world's own blocks on the bullet's tick past the layout (other checks stand within its 160 blocks): it would
    // be held there, not by the layout (port: 1.7.10's ray moves its start, so it gets copies, and gives up after 200
    // cells, the length of a bullet's tick here)
    static boolean world(WorldServer level, Ray r) {
        return level.func_147447_a(V.add(r.to, V.ZERO), V.add(r.to, V.scale(r.way, 256)), false, false, false) != null;
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
        Block b = BulletCheck.block(type);
        for (int[] p : at) level.setBlock(p[0], p[1], p[2], b, 0, 3);
        if (layout.equals("pair, one taken")) {
            level.setBlock(east[0], east[1], east[2], Blocks.air, 0, 3);
            at.remove(east);
        }
        return at;
    }

    static void clear(WorldServer level, int[] o) {
        for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++)
            level.setBlock(o[0] + x, o[1] + y, o[2] + z, Blocks.air, 0, 3);
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
            Block b = level.getBlock(p[0], p[1], p[2]);
            int meta = level.getBlockMetadata(p[0], p[1], p[2]);
            long[] cells = PassableClip.cells(level, b, meta, p[0], p[1], p[2]);
            String id = BlockIds.canonical(Block.blockRegistry.getNameForObject(b));
            // exact faces (bars) or a block not the game's own without cells: the cells or the box are only round
            // what is met
            if (cells != null && ModelSurface.present(cells) || cells == null && (id == null || !id.startsWith("minecraft:"))) exact = false;
            if (cells != null) {
                for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                    if (FenceCells.cell(cells, x, y, z))
                        boxes.add(AxisAlignedBB.getBoundingBox(p[0] + x / 16.0, p[1] + y / 16.0, p[2] + z / 16.0, p[0] + (x + 1) / 16.0, p[1] + (y + 1) / 16.0,
                                p[2] + (z + 1) / 16.0));
            } else {
                int n = PassableClip.collision(level, b, p[0], p[1], p[2], box);
                for (int i = 0; i < n; i++)
                    boxes.add(AxisAlignedBB.getBoundingBox(box[i * 6], box[i * 6 + 1], box[i * 6 + 2], box[i * 6 + 3], box[i * 6 + 4], box[i * 6 + 5]));
            }
        }
        final boolean isExact = exact;
        return new Truth() {
            public double first(Vec3 from, Vec3 to) {
                double best = Double.NaN;
                for (AxisAlignedBB b : boxes) {
                    // 26.2's AABB.contains: min inclusive, max exclusive
                    if (from.xCoord >= b.minX && from.xCoord < b.maxX && from.yCoord >= b.minY && from.yCoord < b.maxY && from.zCoord >= b.minZ
                            && from.zCoord < b.maxZ) return 0;
                    MovingObjectPosition p = b.calculateIntercept(from, to);
                    if (p == null) continue;
                    double t = p.hitVec.distanceTo(from) / to.distanceTo(from);
                    if (Double.isNaN(best) || t < best) best = t;
                }
                return best;
            }

            public double off(Vec3 p) {
                double best = Double.POSITIVE_INFINITY;
                for (AxisAlignedBB b : boxes) {
                    double dx = Math.max(Math.max(b.minX - p.xCoord, 0), p.xCoord - b.maxX),
                            dy = Math.max(Math.max(b.minY - p.yCoord, 0), p.yCoord - b.maxY),
                            dz = Math.max(Math.max(b.minZ - p.zCoord, 0), p.zCoord - b.maxZ);
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
