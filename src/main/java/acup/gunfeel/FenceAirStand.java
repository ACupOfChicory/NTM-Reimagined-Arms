package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Vector3d;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PfenceAir: FenceAirCheck in a game, against what the blocks draw. Each layout is built on the
// integrated server; once the client has it, rays at it are checked against the drawn model triangles (faces a
// neighbour hides left out, their texture's see-through pixels too: what holes are put on). The client's path (its
// tracers and holes) and the server's path must not stop in the air, and real NTM bullets on the server clear of the
// pixels must fly on. Lines "[fenceair] ..." in the log, one PASS/FAIL per layout in the report.
final class FenceAirStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.fenceAir");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    // the player well away: no bullet of the check reaches it
    private static final Vec3 EYE = new Vec3(8.5, 101, -32.5);
    private static int type, layout, step, wait;
    private static List<BlockPos> blocks = List.of();
    private static final java.util.Random RANDOM = new java.util.Random(2L);

    private FenceAirStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.getSingleplayerServer().execute(() -> {
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(server.overworld(), EYE.x, EYE.y, EYE.z, Set.of(), 0F, 0F, false);
        });
        step = 0;
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (wait > 0) {
            wait--;
            return;
        }
        var server = mc.getSingleplayerServer();
        try {
            switch (step) {
                case 0 -> {
                    if (mc.player == null || mc.player.position().distanceTo(EYE) > 1) return;
                    if (type == FenceAirCheck.TYPES.length) {
                        server.submit(() -> FenceAirCheck.clear(server.overworld(), O)).join();
                        ClientSmoke.finish();
                        return;
                    }
                    blocks = server.submit(() -> FenceAirCheck.build(server.overworld(), O, FenceAirCheck.TYPES[type], FenceAirCheck.LAYOUTS[layout])).join();
                    wait = 3;
                    step = 1;
                }
                case 1 -> { // the client has it
                    var level = server.overworld();
                    for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++) {
                        BlockPos p = O.offset(x, y, z);
                        if (mc.level.getBlockState(p) != level.getBlockState(p)) return;
                    }
                    wait = 2;
                    step = 2;
                }
                case 2 -> {
                    check(mc, server);
                    if (++layout == FenceAirCheck.LAYOUTS.length) {
                        layout = 0;
                        type++;
                    }
                    step = 0;
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            GunFeel.LOGGER.error("[fenceair] failed", t);
            ClientSmoke.check(false, "заборы, пролёт: " + t);
            ClientSmoke.finish();
        }
    }

    private static void check(Minecraft mc, net.minecraft.client.server.IntegratedServer server) {
        String name = FenceAirCheck.TYPES[type].replace("minecraft:", "") + " " + FenceAirCheck.LAYOUTS[layout];
        StringBuilder states = new StringBuilder();
        for (BlockPos p : blocks) states.append(' ').append(mc.level.getBlockState(p));
        GunFeel.LOGGER.info("[fenceair] {}:{}", name, states);
        List<RenderedMesh.Tri> tris = RenderedMesh.models(mc.level, new AABB(O).inflate(3), O, new AABB(O), new boolean[1], new HashSet<>());
        // a block with neither cells nor a mask (glass panes) stops a bullet on its whole box, clear pixels and all
        // (glass is worn by bullets): that box is what it is to meet
        boolean box = true;
        for (BlockPos p : blocks) {
            var st = mc.level.getBlockState(p);
            box &= PassableClip.shape(st) == null && PassableClip.maskOf(st) == null;
        }
        FenceAirCheck.Truth truth = box ? FenceAirCheck.cells(mc.level, blocks) : drawn(tris);
        List<FenceAirCheck.Ray> rays = FenceAirCheck.rays(blocks, RANDOM, 1500);
        FenceAirCheck.Tally client = new FenceAirCheck.Tally(name + " (client's path, by " + (box ? "the box" : "the pixels") + ")");
        for (FenceAirCheck.Ray r : rays) client.path(r, truth, PassableClip.clip(mc.level, r.from(), r.to()));
        FenceAirCheck.Tally onServer = new FenceAirCheck.Tally(name + " (server's path, by " + (box ? "the box" : "the pixels") + ")");
        BulletConfig round = BulletCheck.pick();
        server.submit(() -> {
            var level = server.overworld();
            for (FenceAirCheck.Ray r : rays) onServer.path(r, truth, PassableClip.clip(level, r.from(), r.to()));
            FenceAirCheck.bullets(level, round, rays, truth, onServer, 300);
        }).join();
        for (FenceAirCheck.Tally t : List.of(client, onServer)) {
            GunFeel.LOGGER.info("[fenceair] {}", t.line());
            for (String e : t.examples) GunFeel.LOGGER.info("[fenceair]        {}", e);
            ClientSmoke.check(t.ok(), "заборы, пролёт: " + t.line().substring(5));
        }
    }

    // the first shown pixel along the line, and how far a point is from one
    static FenceAirCheck.Truth drawn(List<RenderedMesh.Tri> all) {
        List<RenderedMesh.Tri> tris = new ArrayList<>();
        for (RenderedMesh.Tri t : all) if (t.normal() != null) tris.add(t);
        return new FenceAirCheck.Truth() {
            public double first(Vec3 from, Vec3 to) {
                Vector3d o = new Vector3d(from.x, from.y, from.z), d = new Vector3d(to.x - from.x, to.y - from.y, to.z - from.z);
                double best = Double.NaN;
                for (RenderedMesh.Tri t : tris) {
                    Vector3d n = t.normal();
                    double den = n.dot(d);
                    if (Math.abs(den) < 1e-12) continue;
                    double s = new Vector3d(t.corner(0)).sub(o).dot(n) / den;
                    if (s < 0 || s > 1 || !Double.isNaN(best) && s >= best) continue;
                    Vector3d pt = new Vector3d(d).mul(s).add(o);
                    double[] w = t.weights(pt, 1e-7);
                    if (w != null && t.opaqueAt(w)) best = s;
                }
                return best;
            }

            public double off(Vec3 p) {
                Vector3d pt = new Vector3d(p.x, p.y, p.z);
                double best = Double.POSITIVE_INFINITY;
                for (RenderedMesh.Tri t : tris) {
                    Vector3d c = t.closest(pt);
                    double dist = c.distance(pt);
                    if (dist >= best) continue;
                    if (shown(t, c)) best = dist;
                }
                return best;
            }

            // the point or one 1/1000 block beside it in the triangle shows a pixel: a hit on a pixel's edge (12/16
            // met as 0.7499999) is on the pixel whichever way the rounding goes
            private boolean shown(RenderedMesh.Tri t, Vector3d c) {
                Vector3d e1 = new Vector3d(t.corner(1)).sub(t.corner(0)).normalize(1e-3), e2 = new Vector3d(t.corner(2)).sub(t.corner(0)).normalize(1e-3);
                for (Vector3d s : new Vector3d[] {new Vector3d(), e1, e2, new Vector3d(e1).negate(), new Vector3d(e2).negate()}) {
                    double[] w = t.weights(new Vector3d(c).add(s), 1e-6);
                    if (w != null && t.opaqueAt(w)) return true;
                }
                return false;
            }

            public boolean exact() {
                return true;
            }
        };
    }
}
