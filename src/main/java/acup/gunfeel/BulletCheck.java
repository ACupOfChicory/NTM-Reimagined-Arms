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

        // Точная поверхность многоблочной машины должна заменять даже сплошную грубую сетку.
        BlockState boilerState = state("hbm:machine_solar_boiler[facing=north]");
        long[] coarse = new long[65];
        java.util.Arrays.fill(coarse, 0, 64, -1L);
        ModelSurface.Face face = new ModelSurface.Face(new double[] {.1, .1, .5, .1, .9, .5, .9, .1, .5},
                new double[6], 1, 1, new long[] {1});
        PassableClip.putShapes(java.util.Map.of(Structures.key(boilerState, 0, 0, 0), ModelSurface.attach(coarse, List.of(face))));
        expect("multiblock exact: empty side of occupied cells", Want.PASS, single("hbm:machine_solar_boiler[facing=north]"), .8, .8, 30F);
        expect("multiblock exact: real face stops bullet", Want.STOP, single("hbm:machine_solar_boiler[facing=north]"), .2, .2, 30F);

        // ---- fences: a row of three, the middle one shot; rails at 6-9 and 12-15 pixels, the post in the middle
        String fence = "minecraft:oak_fence";
        expect("fence: between the rails", Want.PASS, fenceRow(fence), .25, .65, 30F);
        expect("fence: under the lower rail", Want.PASS, fenceRow(fence), .25, .2, 30F);
        expect("fence: the post", Want.PIERCE, fenceRow(fence), .5, .5, 30F);
        expect("fence: the upper rail", Want.PIERCE, fenceRow(fence), .25, .8, 30F);
        expect("fence: thin rail, a very weak bullet stops", Want.STOP, fenceRow(fence), .25, .8, 1.2F);

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
        float left = rule == null ? -2F : rule.pierced(30F, plank.getDestroySpeed(level, BlockPos.ZERO), 1.0);
        Shot s = expect("plank: pierced", Want.PIERCE, single("minecraft:oak_planks"), .5, .5, 30F);
        check("plank: damage after it, by its rule", left, s.damage);
        s = expect("concrete: stops in it", Want.STOP, single("minecraft:white_concrete"), .5, .5, 30F);
        check("concrete: still there", "minecraft:white_concrete", s.after);
        expect("stone bricks: stops in them", Want.STOP, single("minecraft:stone_bricks"), .5, .5, 30F);
        expect("stone slab, along it: a rifle round stops", Want.STOP, single("minecraft:smooth_stone_slab[type=bottom]"), .5, .25, 30F);
        expect("sandbags: a rifle round stops", Want.STOP, single("hbm:sandbags"), .5, .5, 37.5F);
        expect("sandbags: a .50 AP-like round goes through", Want.PIERCE, single("hbm:sandbags"), .5, .5, 90F);
        expect("iron door: a plain round stops", Want.STOP, single("minecraft:iron_door[facing=north,half=lower]"), .5, .5, 30F);
        // a door's path is 3/16 of a block: it takes far less than a block of planks
        Shot door = expect("oak door: a pellet goes through", Want.PIERCE, single("minecraft:oak_door[facing=north,half=lower]"), .5, .5, 8F);
        Shot block = expect("oak planks: a pellet goes through", Want.PIERCE, single("minecraft:oak_planks"), .5, .5, 8F);
        check("oak door keeps more than a block of planks", true, door.damage > block.damage + 2F);

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

        // ---- wear with a threshold: sandbags tear only by what passes 4 damage a bullet (600 in all)
        BlockPos bags = single("hbm:sandbags");
        for (int i = 0; i < 160; i++) shoot(bags, .5, .5, 4F);
        check("sandbags: 160 machine-gun-weak rounds of 4 leave them", "hbm:sandbags", after(bags));
        for (int i = 0; i < 17; i++) shoot(bags, .5, .5, 37.5F);
        check("sandbags: 17 rifle rounds leave them", "hbm:sandbags", after(bags));
        shoot(bags, .5, .5, 37.5F);
        check("sandbags: the 18th tears them", "minecraft:air", after(bags));

        // ---- concrete breaks only with bulletBreakConcrete (vanilla: 990 damage above 20 a bullet)
        BlockPos hard = single("minecraft:white_concrete");
        for (int i = 0; i < 12; i++) shoot(hard, .5, .5, 120F);
        check("concrete, switch off: 12 heavy rounds leave it", "minecraft:white_concrete", after(hard));
        Path serverFile = FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
        Files.writeString(serverFile, BulletRules.CONCRETE_KEY + " = true\n");
        Thread.sleep(2100); // both files are looked at once a second
        hard = single("minecraft:white_concrete");
        for (int i = 0; i < 9; i++) shoot(hard, .5, .5, 120F);
        check("concrete, switch on: 9 heavy rounds leave it", "minecraft:white_concrete", after(hard));
        shoot(hard, .5, .5, 120F);
        check("concrete, switch on: the 10th breaks it", "minecraft:air", after(hard));
        hard = single("minecraft:white_concrete");
        for (int i = 0; i < 60; i++) shoot(hard, .5, .5, 18F);
        check("concrete, switch on: 60 rounds of 18 leave it", "minecraft:white_concrete", after(hard));
        Files.writeString(serverFile, BulletRules.CONCRETE_KEY + " = false\n");
        Thread.sleep(2100);

        // ---- NTM's wooden trench blocks: their boards as drawn (the gaps flown through), not their solid hitboxes
        // ceiling: boards along x at 15-16 px (z 1-7, 9-15), beams along z at 14-15 px (x 0-2, 14-16)
        expect("wood ceiling: under the boards, between the beams", Want.PASS, single("hbm:wood_structure_ceiling"), .5, .905, 30F);
        expect("wood ceiling: along a beam", Want.PIERCE, single("hbm:wood_structure_ceiling"), .06, .905, 40F);
        expect("wood ceiling: across the boards", Want.PIERCE, single("hbm:wood_structure_ceiling"), .5, .97, 30F);
        // roof: rails along z at 0-2 px (x 0-2, 14-16), boards at 2-3 px
        expect("wood roof: under the boards, between the rails", Want.PASS, single("hbm:wood_structure_roof"), .5, .06, 30F);
        expect("wood roof: the boards", Want.PIERCE, single("hbm:wood_structure_roof"), .5, .15, 30F);
        expect("wood roof: above it", Want.PASS, single("hbm:wood_structure_roof"), .5, .5, 30F);
        // scaffold alone: four posts, rails west/east at 2-6 px, north/south at 8-12, a floor at 14-16
        expect("wood scaffold: between the posts, under the rails", Want.PASS, single("hbm:wood_structure_scaffold"), .5, .25, 30F);
        expect("wood scaffold: the north and south rails", Want.PIERCE, single("hbm:wood_structure_scaffold"), .5, .625, 30F);
        expect("wood scaffold: two posts", Want.PIERCE, single("hbm:wood_structure_scaffold"), .125, .25, 30F);
        // barrier facing north: its panel on the south side, boards at 1-7 and 9-15 px, a post at 7-9 px across
        expect("wood barrier: between its boards", Want.PASS, single("hbm:wood_barrier[facing=north]"), .25, .5, 30F);
        expect("wood barrier: a board", Want.PIERCE, single("hbm:wood_barrier[facing=north]"), .25, .25, 30F);
        expect("wood barrier: its post", Want.PIERCE, single("hbm:wood_barrier[facing=north]"), .5, .5, 30F);
        expect("wood barrier: over the boards, nothing above", Want.PASS, single("hbm:wood_barrier[facing=north]"), .25, .97, 30F);
        BlockPos roofed = single("hbm:wood_barrier[facing=north]");
        level.setBlock(roofed.above(), state("minecraft:stone"), Block.UPDATE_CLIENTS);
        expect("wood barrier: stone above, its ceiling boards", Want.PIERCE, roofed, .5, .97, 30F);

        // ---- NTM's bunker door, closed: a steel leaf 3-4 px thick, a grille of 1-pixel holes in the upper half
        // (pixel rows 6-8 from the bottom, in a chequer). A hole is flown through untouched, the steel round it holds
        // a plain round and lets a heavy one through with far less
        BlockPos bunker = single("hbm:door_bunker[facing=north,half=lower,hinge=left,open=false]");
        level.setBlock(bunker.above(), state("hbm:door_bunker[facing=north,half=upper,hinge=left,open=false]"), Block.UPDATE_CLIENTS);
        expect("bunker door: a grille hole", Want.PASS, bunker.above(), 4.5 / 16, 8.5 / 16, 60F);
        expect("bunker door: the steel beside the hole", Want.STOP, bunker.above(), 5.5 / 16, 8.5 / 16, 60F);
        expect("bunker door: 150 damage goes through the steel", Want.PIERCE, bunker.above(), .5, .2, 150F);

        // ---- .50 DU into an iron golem in the open: every bullet takes the same, 45 in the body, x1.25 in the head
        // (NTM's headshotMult); it goes through the golem and loses nothing by it
        // with the server's test log on: the hit is written with what it took and whether it was in the head
        Path log = TestLog.set(false, true);
        check("golem: .50 DU in the body takes 45", 45F, golemHit(1.0, 45F));
        check("golem: .50 DU in the head takes 56.25", 56.25F, golemHit(2.4, 45F));
        // a bullet from above through its head into the plank floor behind it, all in one tick: the golem gets the
        // damage from before the floor (NTM asks for the blocks of the tick's path before the creatures on it)
        float[] floor = golemOverFloor(45F);
        check("golem over a floor: the head shot takes 56.25, not what the floor behind left", 56.25F, floor[0]);
        check("golem over a floor: the floor behind it still costs the bullet", true, floor[1] < 45F);
        TestLog.set(false, false);
        String text = log == null ? "" : Files.readString(log);
        check("golem: the test log has the body hit", true, text.contains("head=false") && text.contains("took=45.000"));
        check("golem: the test log has the head hit", true, text.contains("head=true") && text.contains("took=56.250"));

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

    // the health a fresh iron golem (no AI, in the open) loses to one .50 DU bullet at height fy over its feet
    private static float golemHit(double fy, float damage) {
        BlockPos at = new BlockPos(2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z);
        lane++;
        var g = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        g.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        g.setNoAi(true);
        g.setNoGravity(true);
        level.addFreshEntity(g);
        BulletConfig du = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, du, 1F, 0F, 0F, 0F);
        b.setPos(at.getX() + .5, at.getY() + fy, at.getZ() - 3);
        b.setThrowableHeading(0, 0, 1, du.velocity, 0F);
        b.damage = damage;
        float before = g.getHealth();
        for (int i = 0; i < 3 && !b.isRemoved(); i++) b.tick();
        float took = before - g.getHealth();
        g.discard();
        return took;
    }

    // a golem on an oak plank floor, shot at 45 degrees down through its head into the floor behind it: the health it
    // lost and the bullet's damage after
    private static float[] golemOverFloor(float damage) {
        BlockPos at = new BlockPos(2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z);
        lane++;
        for (int dz = -1; dz <= 4; dz++) level.setBlock(at.offset(0, -1, dz), state("minecraft:oak_planks"), Block.UPDATE_CLIENTS);
        var g = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        g.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        g.setNoAi(true);
        g.setNoGravity(true);
        level.addFreshEntity(g);
        BulletConfig du = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, du, 1F, 0F, 0F, 0F);
        // into the hitbox (z from -0.2) at 2.4 high, down to the floor's top 2.4 further on
        b.setPos(at.getX() + .5, at.getY() + 4.4, at.getZ() - 2.2);
        b.setThrowableHeading(0, -1, 1, du.velocity, 0F);
        b.damage = damage;
        float before = g.getHealth();
        b.tick();
        float took = before - g.getHealth();
        g.discard();
        return new float[] {took, b.damage};
    }

    private static String after(BlockPos at) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString();
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
