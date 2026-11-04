package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

// Dev (-Pscenario=fences, ShootScenario's flat world): holes where two fences, walls, panes or bars join (26.2:
// FenceSeamCheck). Pairs and corners of oak fences, oak against spruce, NTM's chain-link fence, cobblestone walls, glass
// panes and iron bars; the server fires real NTM bullets along the rails across the joint, a little aslant, and across
// the joint from the side. Every point of every hole's drawn quads must lie on a post's or a rail's surface: the
// bullet cell behind it set and the one in front of it clear (PassableClip.cells, or the collision boxes). A point
// between two set cells is on a face inside the wood (the joint), between two clear ones it hangs in the air.
// run/client/fences.txt gets the holes off the surface and the verdicts, "FENCES OK" or "FENCES FAIL"; the game quits.
final class FencesScenario {
    private FencesScenario() {
    }

    private static final String[][] TYPES = {{"minecraft:fence", "minecraft:fence"}, {"minecraft:fence", "minecraft:spruce_fence"},
            {"hbm:fence_metal", "hbm:fence_metal"}, {"minecraft:cobblestone_wall", "minecraft:cobblestone_wall"},
            {"minecraft:glass_pane", "minecraft:glass_pane"}, {"minecraft:iron_bars", "minecraft:iron_bars"}};
    // a pair along x; a corner: a third block south of the first
    private static final String[] LAYOUTS = {"pair", "corner"};
    private static final double[] HEIGHTS = {3 / 16.0, 7.5 / 16.0, 10.5 / 16.0, 13.5 / 16.0};
    private static final double[] ANGLES = {0, 3, -3};

    private static final int PLACE = 40, FIRST = 70, GAP = 15;
    private static final int CHECK = FIRST + GAP * TYPES.length * LAYOUTS.length + 60, DONE = CHECK + 20;

    private static int[] row;
    private static final List<String> lines = new ArrayList<String>();
    private static int failed;

    static void step(Minecraft mc, int t) {
        if (t == PLACE) ShootScenario.onServer(FencesScenario::place);
        for (int i = 0; i < TYPES.length * LAYOUTS.length; i++) if (t == FIRST + i * GAP) {
            final int n = i;
            ShootScenario.onServer(() -> fire(n));
        }
        if (t == CHECK) check(mc.world);
        if (t == DONE) {
            GunFeel.LOGGER.info("Scenario fences: {}", failed == 0 ? "FENCES OK" : "FENCES FAIL (" + failed + ")");
            mc.shutdown();
        }
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
    }

    // the first block of case n (type n / layouts, layout n % layouts)
    private static int[] at(int n) {
        return new int[] {row[0] + (n / LAYOUTS.length) * 5, row[1], row[2] + (n % LAYOUTS.length) * 6};
    }

    private static List<int[]> blocks(int n) {
        int[] a = at(n);
        List<int[]> out = new ArrayList<int[]>();
        out.add(a);
        out.add(new int[] {a[0] + 1, a[1], a[2]});
        if (LAYOUTS[n % LAYOUTS.length].equals("corner")) out.add(new int[] {a[0], a[1], a[2] + 1});
        return out;
    }

    private static String name(int n) {
        String[] ty = TYPES[n / LAYOUTS.length];
        return ty[0].replace("minecraft:", "") + (ty[0].equals(ty[1]) ? "" : "+" + ty[1].replace("minecraft:", "")) + " " + LAYOUTS[n % LAYOUTS.length];
    }

    private static void place() {
        EntityPlayerMP p = player();
        row = new int[] {(int) Math.floor(p.posX) - 14, (int) Math.floor(p.posY) + 1, (int) Math.floor(p.posZ) + 6};
        World w = p.world;
        for (int n = 0; n < TYPES.length * LAYOUTS.length; n++) {
            List<int[]> bs = blocks(n);
            for (int i = 0; i < bs.size(); i++) {
                Block b = Block.getBlockFromName(TYPES[n / LAYOUTS.length][i == 0 ? 0 : 1]);
                if (b == null) {
                    lines.add("FAIL no block " + TYPES[n / LAYOUTS.length][i == 0 ? 0 : 1]);
                    failed++;
                    b = Blocks.OAK_FENCE;
                }
                int[] c = bs.get(i);
                Mc.setBlock(w, c[0], c[1], c[2], b, 0, 3);
            }
        }
        lines.add("row " + row[0] + "," + row[1] + "," + row[2]);
    }

    private static void shoot(World w, double x, double y, double z, double dx, double dy, double dz) {
        BulletConfig round = com.hbm.items.weapon.sedna.factory.XFactory9mm.p9_ap;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(w, round, 1F, 0F, 0F, 0F);
        b.setPosition(x, y, z);
        b.shoot(dx, dy, dz, 1F, 0F);
        // weak: a wooden fence breaks from 45 damage in all, and every case takes some 40 bullets
        b.damage = 1F;
        w.spawnEntity(b);
    }

    private static void fire(int n) {
        World w = player().world;
        int[] a = at(n);
        double seam = a[0] + 1, mid = a[2] + 0.5;
        for (double h : HEIGHTS) {
            double y = a[1] + h;
            for (double deg : ANGLES) {
                double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
                // along the rails into the joint, from the west and from the east, across it at z mid
                shoot(w, seam - 3.5, y, mid - s * 3.5, c, 0, s);
                shoot(w, seam + 3.5, y, mid - s * 3.5, -c, 0, s);
            }
            // from the north, beside the joint
            for (double dx : new double[] {-0.04, 0.04}) shoot(w, seam + dx, y, a[2] - 3, 0, 0, 1);
            // a corner: along the south rail into its joint, from the north and the south
            if (LAYOUTS[n % LAYOUTS.length].equals("corner")) {
                shoot(w, a[0] + 0.5, y, a[2] - 2.5, 0, 0, 1);
                shoot(w, a[0] + 0.5, y, a[2] + 4.5, 0, 0, -1);
            }
        }
    }

    // whether the bullets meet the block at that point (its cells, or its collision boxes)
    private static boolean filled(World w, double px, double py, double pz) {
        int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
        Block b = Mc.block(w, bx, by, bz);
        if (Mc.material(b) == Material.AIR) return false;
        long[] bits = PassableClip.cells(w, b, Mc.meta(w, bx, by, bz), bx, by, bz);
        if (bits != null) return FenceCells.cell(bits, cell(px - bx), cell(py - by), cell(pz - bz));
        List<AxisAlignedBB> list = new ArrayList<AxisAlignedBB>();
        Mc.collision(w, bx, by, bz, new AxisAlignedBB(px - 1e-4, py - 1e-4, pz - 1e-4, px + 1e-4, py + 1e-4, pz + 1e-4), list, null);
        return !list.isEmpty();
    }

    private static int cell(double local) {
        return Math.max(0, Math.min(15, (int) Math.floor(local * 16)));
    }

    private static void check(World world) {
        List<Tracers.HoleView> holes = Tracers.holesNow();
        int total = 0;
        for (int n = 0; n < TYPES.length * LAYOUTS.length; n++) {
            List<int[]> bs = blocks(n);
            int count = 0, off = 0, inside = 0, air = 0, samples = 0, logged = 0;
            for (Tracers.HoleView h : holes) {
                boolean mine = false;
                for (int[] c : bs) mine |= c[0] == h.x && c[1] == h.y && c[2] == h.z;
                if (!mine) continue;
                count++;
                int[] o = HoleMath.offset(h.face);
                boolean bad = false;
                for (double[][] q : h.quads) {
                    double cx = 0, cy = 0, cz = 0;
                    for (double[] v : q) {
                        cx += v[0] / q.length;
                        cy += v[1] / q.length;
                        cz += v[2] / q.length;
                    }
                    List<double[]> pts = new ArrayList<double[]>();
                    pts.add(new double[] {cx, cy, cz});
                    for (double[] v : q) pts.add(new double[] {v[0] + (cx - v[0]) * 0.3, v[1] + (cy - v[1]) * 0.3, v[2] + (cz - v[2]) * 0.3});
                    for (double[] p : pts) {
                        samples++;
                        double e = 1 / 32.0;
                        boolean behind = filled(world, p[0] - o[0] * e, p[1] - o[1] * e, p[2] - o[2] * e);
                        boolean front = filled(world, p[0] + o[0] * e, p[1] + o[1] * e, p[2] + o[2] * e);
                        // on a surface, facing either way (a two-sided hole on bars may lie on the far side)
                        if (behind != front) continue;
                        bad = true;
                        if (behind) inside++;
                        else air++;
                        if (logged++ < 6) lines.add(String.format(Locale.ROOT, "  %s: off the surface at %.4f %.4f %.4f (%s), hole on %d,%d,%d face %d at %.3f %.3f %.3f",
                                name(n), p[0], p[1], p[2], behind ? "inside, between set cells" : "in the air", h.x, h.y, h.z, h.face, h.pos[0], h.pos[1], h.pos[2]));
                    }
                }
                if (bad) off++;
            }
            total += count;
            lines.add(String.format(Locale.ROOT, "%s: holes %d, off the surface %d (%d points of %d: %d inside, %d in the air)", name(n), count, off,
                    inside + air, samples, inside, air));
            expect(name(n) + ": every hole on a post or a rail", off == 0);
        }
        expect("holes made: " + total, total > 100);
        lines.add(failed == 0 ? "FENCES OK" : "FENCES FAIL (" + failed + ")");
        try (PrintWriter pw = new PrintWriter(new File(Minecraft.getMinecraft().gameDir, "fences.txt"), StandardCharsets.UTF_8.name())) {
            for (String l : lines) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario fences: fences.txt", e);
        }
        for (String l : lines) GunFeel.LOGGER.info("Scenario fences: {}", l);
    }

    private static void expect(String what, boolean ok) {
        if (!ok) failed++;
        lines.add((ok ? "OK   " : "FAIL ") + what);
    }
}
