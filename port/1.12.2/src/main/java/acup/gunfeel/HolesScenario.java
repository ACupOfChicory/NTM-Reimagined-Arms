package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

// Dev (-Pscenario=holes, ShootScenario's flat world): the bullet holes on plain blocks, checked by numbers. Lone
// blocks are put up in a row ahead of the player and the server fires real NTM bullets (a 9 mm AP round, 30 damage)
// at chosen points of them, straight along +z: the server's bullet is announced to the client as in the game, the
// client flies its tracer and makes the holes. Then the holes the client holds are checked:
// - a plank shot through its middle: a hole where the bullet went in (north face) and one where it came out (south);
// - a stone near its east edge: the hole wraps over the edge onto the east face (BlockDecal.wrap);
// - two stones side by side, shot near the seam: the hole goes on over the seam in the face's plane;
// - a plank near its east edge: in and out, both wrapped onto the east face.
// run/client/holes.txt gets every hole with its pieces and the verdicts, "HOLES OK" or "HOLES FAIL"; the game quits.
final class HolesScenario {
    private HolesScenario() {
    }

    private static final int PLACE = 40, FIRST = 80, GAP = 20, CHECK = 220, DONE = 240;
    private static final int NORTH = 2, SOUTH = 3, EAST = 5;

    // the blocks: {dx from the row's start, block (0 plank, 1 stone)}; the shots: {dx of the block, u across it}
    private static final int[][] BLOCKS = {{0, 0}, {3, 1}, {6, 1}, {7, 1}, {10, 0}};
    private static final double[][] SHOTS = {{0, 0.5}, {3, 0.95}, {6, 0.95}, {10, 0.95}};

    private static int[] row; // the row's first block
    private static final List<String> lines = new ArrayList<String>();
    private static int failed;

    static void step(Minecraft mc, int t) {
        if (t == PLACE) ShootScenario.onServer(HolesScenario::place);
        for (int i = 0; i < SHOTS.length; i++) if (t == FIRST + i * GAP) {
            final int n = i;
            ShootScenario.onServer(() -> fire(n));
        }
        if (t == CHECK) check(mc.world);
        if (t == DONE) {
            GunFeel.LOGGER.info("Scenario holes: {}", failed == 0 ? "HOLES OK" : "HOLES FAIL (" + failed + ")");
            mc.shutdown();
        }
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
    }

    private static void place() {
        EntityPlayerMP p = player();
        row = new int[] {(int) Math.floor(p.posX) - 5, (int) Math.floor(p.posY) + 1, (int) Math.floor(p.posZ) + 6};
        World w = p.world;
        for (int[] b : BLOCKS) Mc.setBlock(w, row[0] + b[0], row[1], row[2], b[1] == 0 ? Blocks.PLANKS : Blocks.STONE, 0, 3);
        lines.add("row " + row[0] + "," + row[1] + "," + row[2]);
    }

    private static void fire(int n) {
        World w = player().world;
        BulletConfig round = com.hbm.items.weapon.sedna.factory.XFactory9mm.p9_ap;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(w, round, 1F, 0F, 0F, 0F);
        b.setPosition(row[0] + SHOTS[n][0] + SHOTS[n][1], row[1] + 0.5, row[2] - 3.0);
        b.shoot(0, 0, 1, 1F, 0F);
        b.damage = 30F;
        w.spawnEntity(b);
        lines.add(String.format(Locale.ROOT, "shot %d id %d at %.3f %.3f %.3f", n, b.getEntityId(), b.posX, b.posY, b.posZ));
    }

    private static void check(World world) {
        List<Tracers.HoleView> holes = Tracers.holesNow();
        for (Tracers.HoleView h : holes)
            lines.add(String.format(Locale.ROOT, "hole %d,%d,%d face %d at %.3f %.3f %.3f wrapped %s", h.x, h.y, h.z, h.face, h.pos[0], h.pos[1],
                    h.pos[2], Tracers.pieces(h.pieces)));
        int x0 = row[0], y = row[1], z = row[2];
        // 1: a plank through its middle: in on the north face, out on the south one, both flat
        expect("plank: hole where it went in", find(holes, x0, y, z, NORTH) != null);
        expect("plank: hole where it came out", find(holes, x0, y, z, SOUTH) != null);
        // 2: a lone stone near its east edge: wrapped onto the east face
        Tracers.HoleView stone = find(holes, x0 + 3, y, z, NORTH);
        expect("stone: hole made", stone != null);
        expect("stone: wrapped over the edge onto the east face", stone != null && hasNormal(stone.pieces, 1, 0, 0));
        expect("stone: nothing came out of it", find(holes, x0 + 3, y, z, SOUTH) == null);
        // 3: two stones: over the seam, every piece on the north plane
        Tracers.HoleView seam = find(holes, x0 + 6, y, z, NORTH);
        expect("seam: hole made", seam != null);
        expect("seam: wrapped in the face's plane", seam != null && seam.pieces != null && seam.pieces.size() >= 2 && allNormal(seam.pieces, 0, 0, -1));
        // 4: a plank near its east edge: in and out, both wrapped onto the east face
        Tracers.HoleView in = find(holes, x0 + 10, y, z, NORTH), out = find(holes, x0 + 10, y, z, SOUTH);
        expect("plank edge: in, wrapped onto the east face", in != null && hasNormal(in.pieces, 1, 0, 0));
        expect("plank edge: out, wrapped onto the east face", out != null && hasNormal(out.pieces, 1, 0, 0));
        lines.add(failed == 0 ? "HOLES OK" : "HOLES FAIL (" + failed + ")");
        try (PrintWriter pw = new PrintWriter(new File(Minecraft.getMinecraft().gameDir, "holes.txt"), StandardCharsets.UTF_8.name())) {
            for (String l : lines) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario holes: holes.txt", e);
        }
        for (String l : lines) GunFeel.LOGGER.info("Scenario holes: {}", l);
    }

    private static void expect(String what, boolean ok) {
        if (!ok) failed++;
        lines.add((ok ? "OK   " : "FAIL ") + what);
    }

    private static Tracers.HoleView find(List<Tracers.HoleView> holes, int x, int y, int z, int face) {
        for (Tracers.HoleView h : holes) if (h.x == x && h.y == y && h.z == z && h.face == face) return h;
        return null;
    }

    private static boolean hasNormal(List<double[]> pieces, double nx, double ny, double nz) {
        if (pieces == null) return false;
        for (double[] pc : pieces) if (pc[0] == nx && pc[1] == ny && pc[2] == nz) return true;
        return false;
    }

    private static boolean allNormal(List<double[]> pieces, double nx, double ny, double nz) {
        for (double[] pc : pieces) if (pc[0] != nx || pc[1] != ny || pc[2] != nz) return false;
        return true;
    }
}
