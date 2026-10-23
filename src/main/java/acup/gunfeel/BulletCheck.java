package acup.gunfeel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

// Bullets on real blocks, checked on a dedicated server with a small flat world and no client (a dev run only:
// ./gradlew runCheckBullets -PnoAssets). -Dntmgunfeel.checkBullets=<file>: once the world is up, the server puts
// fences, gates, walls, planks, concrete and glass high in the air, each in a lane of its own, and fires a real NTM
// bullet (EntityBulletBaseMK4, no shooter) along the lane at a spot of the block. The bullet is ticked by hand on the
// server thread, so the mod's mixins and BulletServer run as in a game, and nothing depends on players or chunk
// ticking. Then: is the bullet still flying, with how much damage, and is the block still there. One line per check
// goes to the file and the server stops (exit 1 if a check failed). Without the property nothing is registered.
// Not covered: what players see (tracers, holes, sounds): review/INGAME-CHECKS.md.
public final class BulletCheck {
    public static final String PROPERTY = "ntmgunfeel.checkBullets";

    private BulletCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> {
            List<String> out = new ArrayList<>();
            int code;
            try {
                code = run(out, e.getServer()) ? 0 : 1;
                memory(out, e.getServer());
            } catch (Throwable t) {
                out.add("FAIL crashed: " + t);
                for (StackTraceElement s : t.getStackTrace()) out.add("    at " + s);
                code = 1;
            }
            finish(file, out, code);
        });
    }

    // lines in the server's own rules file, to see that path rules change what bullets do
    static final String RULES = """
            path minecraft:spruce_fence hitbox
            path minecraft:stone through
            """;

    private static final int Y = 100, Z = 8; // the blocks; bullets start 1.5 blocks before them and fly +z
    private static List<String> lines;
    private static boolean ok;
    private static ServerLevel level;
    private static BulletConfig round;
    private static int lane;

    private static boolean run(List<String> out, MinecraftServer server) throws Exception {
        lines = out;
        ok = true;
        level = server.overworld();
        for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) level.setChunkForced(cx, cz, true);
        Files.writeString(FMLPaths.CONFIGDIR.get().resolve(BulletRules.FILE), RULES);
        Thread.sleep(1100); // the rules file is looked at once a second
        round = pick();
        if (round == null) {
            fail("no plain NTM bullet to fire");
            return false;
        }
        lines.add("     round " + round.id + ": velocity " + round.velocity + ", gravity " + round.gravity);

        // ---- fences: a row of three, the middle one shot; rails at 6-9 and 12-15 pixels, the post in the middle
        String fence = "minecraft:oak_fence";
        expect("fence: between the rails", Want.PASS, fenceRow(fence), .25, .65, 30F);
        expect("fence: under the lower rail", Want.PASS, fenceRow(fence), .25, .2, 30F);
        expect("fence: the post", Want.PIERCE, fenceRow(fence), .5, .5, 30F);
        expect("fence: the upper rail", Want.PIERCE, fenceRow(fence), .25, .8, 30F);
        expect("fence: thin rail, a weak bullet stops", Want.STOP, fenceRow(fence), .25, .8, 2F);

        // ---- gates: closed (bars at 6-9 and 12-15 pixels, the gap between them), open (the middle is free)
        String closed = "minecraft:oak_fence_gate[facing=north,open=false]";
        expect("gate closed: between the bars", Want.PASS, single(closed), .25, .65, 30F);
        expect("gate closed: under the bars", Want.PASS, single(closed), .25, .2, 30F);
        expect("gate closed: a bar", Want.PIERCE, single(closed), .25, .8, 30F);
        expect("gate closed: the middle", Want.PIERCE, single(closed), .5, .5, 30F);
        expect("gate open: the middle", Want.PASS, single("minecraft:oak_fence_gate[facing=north,open=true]"), .5, .5, 30F);

        // ---- walls: low sides 14 pixels high, tall ones 16, the post 4-12 pixels across
        expect("wall low: over it", Want.PASS, single("minecraft:cobblestone_wall[up=false,east=low,west=low]"), .5, .95, 30F);
        expect("wall low: into it", Want.MEET, single("minecraft:cobblestone_wall[up=false,east=low,west=low]"), .5, .5, 30F);
        expect("wall tall: at the top", Want.MEET, single("minecraft:cobblestone_wall[up=false,east=tall,west=tall]"), .5, .95, 30F);
        expect("wall post: beside it", Want.PASS, single("minecraft:cobblestone_wall[up=true]"), .1, .5, 30F);
        expect("wall post: into it", Want.MEET, single("minecraft:cobblestone_wall[up=true]"), .5, .5, 30F);

        // ---- materials: a plank is pierced with the rule's loss, concrete holds
        BlockState plank = state("minecraft:oak_planks");
        BulletRules.Break rule = BulletRules.breakRule(plank);
        float left = rule == null ? -2F : rule.pierced(30F, plank.getDestroySpeed(level, BlockPos.ZERO));
        Shot s = expect("plank: pierced", Want.PIERCE, single("minecraft:oak_planks"), .5, .5, 30F);
        check("plank: damage after it, by its rule", left, s.damage);
        s = expect("concrete: stops in it", Want.STOP, single("minecraft:white_concrete"), .5, .5, 30F);
        check("concrete: still there", "minecraft:white_concrete", s.after);
        expect("stone bricks: stops in them", Want.STOP, single("minecraft:stone_bricks"), .5, .5, 30F);

        // ---- glass: worn by the damage of the bullets through it, broken at 10
        for (String glass : new String[] {"minecraft:glass", "minecraft:glass_pane[east=true,west=true]", "minecraft:red_stained_glass"}) {
            BlockPos at = single(glass);
            String id = glass.replaceAll("\\[.*", "");
            s = expect(id + ": 6 damage goes through", Want.PIERCE, at, .5, .5, 6F);
            check(id + ": 6 damage leaves it", id, s.after);
            s = shoot(at, .5, .5, 6F);
            check(id + ": 6 more break it", "minecraft:air", s.after);
            check(id + ": the second bullet flies on", true, s.alive);
            s = shoot(single(glass), .5, .5, 12F);
            check(id + ": 12 at once break it", "minecraft:air", s.after);
        }

        // ---- path rules in the server's file
        expect("path hitbox: a spruce fence's gap is hit", Want.PIERCE, fenceRow("minecraft:spruce_fence"), .25, .65, 30F);
        s = expect("path through: stone is flown through", Want.PASS, single("minecraft:stone"), .5, .5, 30F);
        check("path through: the stone stays", "minecraft:stone", s.after);

        lines.add(ok ? "ALL OK" : "SOME FAILED");
        return ok;
    }

    // what a bullet should do at the block: fly on untouched, go through with less damage, stop in it, or any of the
    // last two (it met the block)
    private enum Want {
        PASS, PIERCE, STOP, MEET
    }

    private record Shot(boolean alive, float damage, double z, String after) {
        @Override
        public String toString() {
            return (alive ? "flies, " : "stopped, ") + "damage " + damage + ", z " + String.format("%.2f", z - Z) + ", block " + after;
        }
    }

    private static Shot expect(String name, Want want, BlockPos at, double fx, double fy, float damage) {
        Shot s = shoot(at, fx, fy, damage);
        boolean beyond = s.z > Z + 1;
        boolean pass = switch (want) {
            case PASS -> s.alive && beyond && s.damage == damage;
            case PIERCE -> s.alive && beyond && s.damage < damage;
            // a stopped bullet is still moved to the end of its path that tick (NTM): where it is says nothing
            case STOP -> !s.alive && s.damage == damage;
            case MEET -> !s.alive || s.damage < damage;
        };
        ok &= pass;
        lines.add((pass ? "OK   " : "FAIL ") + name + ": " + want.name().toLowerCase() + (pass ? "" : ", got " + s));
        return s;
    }

    // a bullet from 1.5 blocks before the block, straight along +z at (fx, fy) of it, ticked till it stops (or 3 ticks)
    private static Shot shoot(BlockPos at, double fx, double fy, float damage) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, round, 1F, 0F, 0F, 0F);
        b.setPos(at.getX() + fx, at.getY() + fy, at.getZ() - 1.5);
        b.setThrowableHeading(0, 0, 1, round.velocity, 0F);
        b.damage = damage;
        for (int i = 0; i < 3 && !b.isRemoved(); i++) b.tick();
        return new Shot(!b.isRemoved(), b.damage, b.getZ(), BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString());
    }

    // a plain, single, fast NTM bullet without an impact effect: the one with the least gravity and spread
    private static BulletConfig pick() {
        BulletConfig best = null;
        for (BulletConfig c : BulletConfig.configs) {
            if (c == null || c.velocity < 5F || c.onImpact != null || c.doesPenetrate || c.projectilesMax > 1
                    || c.onRicochet != BulletConfig.LAMBDA_STANDARD_RICOCHET) continue;
            if (best == null || c.gravity + c.spread < best.gravity + best.spread) best = c;
        }
        return best;
    }

    // the next lane: four blocks apart along x, sixteen in a row, rows four blocks apart up: no bullet meets another
    // lane's block
    private static BlockPos single(String text) {
        BlockPos at = new BlockPos(2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z);
        lane++;
        level.setBlock(at, state(text), Block.UPDATE_CLIENTS);
        return at;
    }

    // three fences joined along x; the middle one is shot
    private static BlockPos fenceRow(String id) {
        BlockPos at = single(id + "[east=true,west=true]");
        level.setBlock(at.west(), state(id + "[east=true]"), Block.UPDATE_CLIENTS);
        level.setBlock(at.east(), state(id + "[west=true]"), Block.UPDATE_CLIENTS);
        return at;
    }

    private static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), text, false).blockState();
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static void check(String name, Object want, Object got) {
        boolean pass = java.util.Objects.equals(want, got);
        ok &= pass;
        lines.add((pass ? "OK   " : "FAIL ") + name + (pass ? "" : ": want " + want + ", got " + got));
    }

    private static void fail(String name) {
        ok = false;
        lines.add("FAIL " + name);
    }

    static void memory(List<String> out, MinecraftServer server) throws Exception {
        Runtime r = Runtime.getRuntime();
        out.add("     heap used " + (r.totalMemory() - r.freeMemory()) / 1048576 + " MB of " + r.maxMemory() / 1048576);
        for (String l : Files.readAllLines(Path.of("/proc/self/status")))
            if (l.startsWith("VmHWM")) out.add("     process peak " + l.replaceAll("\\s+", " "));
    }

    static void finish(String file, List<String> out, int code) {
        try {
            Path f = Path.of(file);
            Files.createDirectories(f.getParent());
            Files.write(f, out, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            ex.printStackTrace();
            code = 1;
        }
        out.forEach(System.out::println);
        Runtime.getRuntime().halt(code);
    }
}
