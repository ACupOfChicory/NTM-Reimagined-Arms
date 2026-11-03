package acup.gunfeel;

import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;

// Dev (-Pscenario=scan, ShootScenario): machines set up one by one beside the player and taken in the game
// (MachineScan, the jar's machines taken again too), each taken one compared with the jar's table and faces, which the
// dev scenario made the same way: the same cells, the same triangles within the faces' grid. The log has one line a
// machine and "Scenario scan: ALL OK" or how many differ; the game quits.
final class ScanScenario {
    private ScanScenario() {
    }

    private static final String[] MACHINES = {"hbm:tile.machine_sawmill", "hbm:tile.machine_fluidtank", "hbm:tile.turret_arty", "hbm:tile.rbmk_rod",
            "hbm:tile.rail_large_curve", "hbm:tile.cargo_elevator", "hbm:tile.machine_industrial_turbine"};
    private static final int START = 40;

    private static int next, state, since, bad, checked;
    private static volatile int[] core;
    private static volatile boolean placed;
    private static int[] spot;

    static void step(Minecraft mc, int t) {
        if (t < START) return;
        if (spot == null) spot = new int[] {(int) Math.floor(mc.player.posX), (int) Math.floor(mc.player.getEntityBoundingBox().minY) + 4,
                (int) Math.floor(mc.player.posZ) + 20};
        String only = System.getProperty("ntmgunfeel.machines", "");
        String[] list = only.isEmpty() ? MACHINES : only.split(",");
        if (next >= list.length) {
            GunFeel.LOGGER.info("Scenario scan: the game's thread at most {} ms a frame taking machines, {} ms looking them over",
                    String.format(java.util.Locale.ROOT, "%.2f", MachineScan.longest / 1e6), String.format(java.util.Locale.ROOT, "%.2f", MachineScan.tickLongest / 1e6));
            GunFeel.LOGGER.info("Scenario scan: {} machines checked, {}", checked, bad == 0 ? "ALL OK" : bad + " differ");
            mc.shutdown();
            next++;
            return;
        }
        final Block b = Block.getBlockFromName(list[next]);
        final int[] at = spot;
        if (state == 0) {
            if (b == null) {
                GunFeel.LOGGER.info("Scenario scan: no block {}", list[next]);
                bad++;
                next++;
                return;
            }
            placed = false;
            core = null;
            since = t;
            state = 1;
            ShootScenario.onServer(() -> {
                ShapesScenario.clearMachine(at);
                core = ShapesScenario.placeMachine(b, 0, at);
                placed = true;
            });
            return;
        }
        int[] c = core;
        String key = c == null ? null : list[next] + "/" + Mc.meta(mc.world, c[0], c[1], c[2]);
        boolean there = c != null && Mc.block(mc.world, c[0], c[1], c[2]) == b;
        // as a bullet meeting it would: a machine without a tile entity (NTM's rails) is found that way only
        if (there && t - since == 20) MachineCells.of(ModelCells.table(), mc.world, b, c[0], c[1], c[2]);
        if (!placed || c != null && (!there || !MachineScan.done(key))) {
            if (t - since < 600) return;
            GunFeel.LOGGER.info("Scenario scan: {} not taken in 30 s", list[next]);
            bad++;
        } else if (c == null) {
            GunFeel.LOGGER.info("Scenario scan: {} not set up", list[next]);
            bad++;
        } else {
            checked++;
            if (!compare(list[next], key)) bad++;
        }
        state = 0;
        next++;
    }

    private static int bits(long[] v) {
        int n = 0;
        for (long l : v) n += Long.bitCount(l);
        return n;
    }

    // what the world has at a block of the machine (dx,dy,dz from the core, a glass key's too): the block and the core
    // it names
    private static String where(String offset) {
        int[] c = core;
        String[] d = offset.replace("/glass", "").split(",");
        if (c == null || d.length != 3) return "?";
        net.minecraft.world.World w = Minecraft.getMinecraft().world;
        int x = c[0] + Integer.parseInt(d[0]), y = c[1] + Integer.parseInt(d[1]), z = c[2] + Integer.parseInt(d[2]);
        Block b = Mc.block(w, x, y, z);
        String out = Mc.name(b) + "/" + Mc.meta(w, x, y, z);
        if (b instanceof com.hbm.blocks.BlockDummyable) {
            int[] f = ((com.hbm.blocks.BlockDummyable) b).findCore(w, x, y, z);
            out += f == null ? ", no core" : ", core at " + (f[0] - c[0]) + "," + (f[1] - c[1]) + "," + (f[2] - c[2]);
        }
        return out;
    }

    private static boolean compare(String id, String key) {
        String meta = key.substring(key.lastIndexOf('/') + 1);
        String prefix = id + "#machine/" + meta + "/";
        Map<String, long[]> jar = ModelCells.jarTable(), now = ModelCells.table();
        int keys = 0, same = 0, onlyJar = 0, onlyNow = 0;
        for (Map.Entry<String, long[]> e : jar.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            keys++;
            long[] n = now.get(e.getKey());
            if (n == null) onlyJar++;
            else if (java.util.Arrays.equals(e.getValue(), n)) same++;
            else GunFeel.LOGGER.info("Scenario scan: differs {}: {}; cells jar {} taken {}", e.getKey().substring(prefix.length()), where(e.getKey().substring(prefix.length())),
                    bits(e.getValue()), bits(n));
        }
        for (String k : now.keySet()) if (k.startsWith(prefix) && !jar.containsKey(k)) onlyNow++;
        MachineFaces.Model a = MachineFaces.jarModel(key), b = MachineFaces.takenModel(key);
        String faces;
        boolean facesOk;
        if (a == null || b == null) {
            facesOk = a == b;
            faces = "faces jar " + (a != null) + " taken " + (b != null);
        } else if (a.size() != b.size()) {
            facesOk = false;
            faces = "triangles jar " + a.size() + " taken " + b.size();
        } else {
            float dp = 0, du = 0;
            int tex = 0;
            for (int i = 0; i < a.size(); i++) {
                String ta = MachineFaces.jarTexture(a.tex[i]), tb = MachineFaces.takenTexture(b.tex[i]);
                if (ta == null ? tb != null : !ta.equals(tb)) tex++;
            }
            for (int i = 0; i < a.p.length; i++) dp = Math.max(dp, Math.abs(a.p[i] - b.p[i]));
            for (int i = 0; i < a.uv.length; i++) du = Math.max(du, Math.abs(a.uv[i] - b.uv[i]));
            facesOk = tex == 0 && dp < 1e-4F && du < 1e-4F;
            faces = a.size() + " triangles, corners within " + dp + ", UV within " + du + ", " + tex + " other textures";
        }
        boolean ok = keys > 0 && same == keys && onlyNow == 0 && facesOk;
        GunFeel.LOGGER.info("Scenario scan: {} {}: {} of {} blocks the same, {} only in the jar, {} only taken; {}", ok ? "OK  " : "DIFF", key, same, keys,
                onlyJar, onlyNow, faces);
        return ok;
    }
}
