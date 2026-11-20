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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
// Visual effects such as tracers, holes and sounds require a client-side check.
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
            break minecraft:hay_block hits 3 through
            """;

    private static final int Y = 100, Z = 8; // the blocks; bullets start 1.5 blocks before them and fly +z
    private static List<String> lines;
    private static boolean ok;
    private static ServerLevel level;
    private static BulletConfig round;
    private static BulletConfig beamRound;
    private static int lane;
    // the lasers' and water's lanes (zombieBeam, waterReach) in rows of their own under the lanes, in the same chunks
    // (loaded, so a zombie there is in the world): more lanes would move the later ones up into FenceAirCheck's place
    private static int ownLane;

    private static BlockPos ownLane(int dz) {
        BlockPos at = new BlockPos(2 + 4 * (ownLane % 16), Y - 40 + 4 * (ownLane / 16), Z + dz);
        ownLane++;
        return at;
    }

    private static boolean run(List<String> out, MinecraftServer server) throws Exception {
        lines = out;
        ok = true;
        level = server.overworld();
        for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) level.setChunkForced(cx, cz, true);
        Files.writeString(FMLPaths.CONFIGDIR.get().resolve(BulletRules.FILE), RULES);
        Thread.sleep(1100); // the rules file is looked at once a second
        BulletRules.reloadNow(); // the rules' file is looked at by the game's ticks, and none goes by here
        round = pick();
        if (round == null) {
            fail("no plain NTM bullet to fire");
            return false;
        }
        lines.add("     round " + round.id + ": velocity " + round.velocity + ", gravity " + round.gravity);

        // Точная поверхность многоблочной машины должна заменять даже сплошную грубую сетку.
        // Здесь формы машин подложены проверкой: пакет форм из jar (ShapePack) их бы перекрыл.
        ShapePack.off = true;
        BlockState boilerState = state("hbm:machine_solar_boiler[facing=north]");
        long[] coarse = new long[65];
        java.util.Arrays.fill(coarse, 0, 64, -1L);
        ModelSurface.Face face = new ModelSurface.Face(new double[] {.1, .1, .5, .1, .9, .5, .9, .1, .5},
                new double[6], 1, 1, new long[] {1});
        PassableClip.putShapes(java.util.Map.of(Structures.key(boilerState, 0, 0, 0), ModelSurface.attach(coarse, List.of(face))));
        expect("multiblock exact: empty side of occupied cells", Want.PASS, single("hbm:machine_solar_boiler[facing=north]"), .8, .8, 30F);
        expect("multiblock exact: real face stops bullet", Want.STOP, single("hbm:machine_solar_boiler[facing=north]"), .2, .2, 30F);

        // Окно машины (прозрачные пиксели модели chemplant) — стекло: пуля пробивает его с потерей, корпус за ним держит.
        // Решётка (прозрачные пиксели без стеклянной грани: текстура из списка исключений) по-прежнему пропускает.
        machineGlass(coarse, machineSettings());
        ShapePack.off = false;

        // DU Mr. Deuce: один лист проходит, второй не восстанавливает запас игнорирования порога.
        BulletConfig plainRound = round;
        round = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        expect("Mr Deuce DU: one steel panel", Want.PIERCE, single("hbm:steel_wall"), .5, .5, 11.25F);
        BlockPos panels = single("hbm:steel_wall");
        level.setBlock(panels.south(), state("hbm:steel_wall"), Block.UPDATE_CLIENTS);
        Shot secondPanel = shoot(panels, .5, .5, 11.25F);
        check("Mr Deuce DU: second steel panel holds", false, secondPanel.alive());
        expect("Mr Deuce DU: one metre of wired glass just passes", Want.PIERCE, single("hbm:reinforced_glass"), .5, .5, 11.25F);
        round = plainRound;

        // Части cargo-лифта обходили запрет машин через материал none/stone.
        Path machineSettings = FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
        Path bulletRules = FMLPaths.CONFIGDIR.get().resolve(BulletRules.FILE);
        Files.writeString(bulletRules, RULES + "material hbm:cargo_elevator none\n"
                + "break hbm:cargo_elevator damage 1000000 pass pierce=0.001 keep=1\n");
        for (boolean enabled : new boolean[] {false, true}) {
            Files.writeString(machineSettings, BulletRules.MACHINES_KEY + " = " + enabled + "\n");
            Thread.sleep(2100);
            BulletRules.reloadNow(); // the rules' file is looked at by the game's ticks, and none goes by here
            for (String part : new String[] {"core", "cell"}) {
                BlockPos at = single("hbm:cargo_elevator[facing=north,part=" + part + "]");
                var in = new net.minecraft.world.phys.BlockHitResult(
                        new net.minecraft.world.phys.Vec3(at.getX() + .5, at.getY() + .5, at.getZ()),
                        net.minecraft.core.Direction.NORTH, at, false);
                var exit = new net.minecraft.world.phys.BlockHitResult(in.getLocation().add(0, 0, .125),
                        net.minecraft.core.Direction.SOUTH, at, false);
                check("cargo " + part + ": machine piercing " + enabled, enabled,
                        PassableClip.pierced(level, in, exit, 1000F, round) >= 1F);
            }
        }
        Files.writeString(machineSettings, BulletRules.MACHINES_KEY + " = false\n");
        Files.writeString(bulletRules, RULES);
        Thread.sleep(2100);
        BulletRules.reloadNow(); // the rules' file is looked at by the game's ticks, and none goes by here

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
        BulletRuleText.Break rule = BulletRules.breakRule(plank);
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

        // ---- NTM's beams (BeamServer): glass is worn by them as by bullets; a creature behind a pierced block gets
        // what is left; beamRules off: NTM's beam, stopped by the glass
        BlockPos pane = single("minecraft:glass");
        float[] beam = beamShot(pane, .5, .5, 6F);
        check("beam: 6 damage through glass, glass left", "minecraft:glass", after(pane));
        beam = beamShot(pane, .5, .5, 6F);
        check("beam: 6 more break the glass", "minecraft:air", after(pane));
        check("beam: the second beam goes on beyond it", true, beam[0] > 1F);
        float free = golemBeam(false), behind = golemBeam(true);
        check("beam: a golem behind a plank takes less than in the open, but some (" + behind + " of " + free + ")", true,
                behind > 0F && behind < free);
        check("beam: glass behind the golem that takes the beam is left whole (two beams of 6)", "minecraft:glass", golemGlass());
        float fewer = glassRow(BeamServer.STEPS), more = glassRow(BeamServer.STEPS + 1);
        check("beam: past " + BeamServer.STEPS + " blocks in a row it gets on", 30F, fewer);
        check("beam: " + (BeamServer.STEPS + 1) + " blocks on its way stop it, not let it through the rest unlooked at", -1F, more);
        check("beam: a shot stopped after " + BeamServer.STEPS + " blocks does not wear what lies past them", true, wearPast());
        // ---- NTM's beam turrets and Tesla arcs (BeamTurrets): a golem 7 blocks off, a 3x3 wall 3 blocks before it.
        // Stone is "through" by this stand's rules: a bullet flies through it, NTM's look along the boxes does not
        float[] tauOpen = turret("hbm:turret_tauon", null), tauGlass = turret("hbm:turret_tauon", "minecraft:glass"),
                tauConcrete = turret("hbm:turret_tauon", "hbm:concrete_smooth");
        check("turret: a Tauon sees a golem in the open and hurts it (" + tauOpen[1] + ")", true, tauOpen[0] > 0 && tauOpen[1] > 0);
        check("turret: a Tauon sees a golem behind glass and hurts it through it (" + tauGlass[1] + ")", true, tauGlass[0] > 0 && tauGlass[1] > 0);
        check("turret: a Tauon does not see a golem behind concrete, and its shot does not get there", true, tauConcrete[0] == 0 && tauConcrete[1] == 0);
        float[] maxOpen = turret("hbm:turret_maxwell", null), maxStone = turret("hbm:turret_maxwell", "minecraft:stone"),
                maxGlass = turret("hbm:turret_maxwell", "minecraft:glass");
        check("turret: a Maxwell sees a golem in the open and hurts it (" + maxOpen[1] + ")", true, maxOpen[0] > 0 && maxOpen[1] > 0);
        check("turret: a Maxwell sees a golem behind a wall bullets fly through (" + maxStone[1] + ")", true, maxStone[0] > 0 && maxStone[1] > 0);
        check("turret: a Maxwell's maser goes through glass as a laser: as in the open (" + maxGlass[1] + ")", true,
                maxGlass[0] > 0 && maxGlass[1] == maxOpen[1]);
        float arcOpen = arc(null, false), arcGlass = arc("minecraft:glass", false), arcStone = arc("minecraft:stone", false),
                arcConcrete = arc("hbm:concrete_smooth", false), arcCoil = arc(null, true);
        check("tesla: an arc through glass hurts less than in the open, but some (" + arcGlass + " of " + arcOpen + ")", true,
                arcGlass > 0 && arcGlass < arcOpen);
        check("tesla: an arc through a wall bullets fly through hurts as in the open (" + arcStone + ")", arcOpen, arcStone);
        check("tesla: no arc through concrete", 0F, arcConcrete);
        check("tesla: an arc from a real coil is not stopped by the coil itself (" + arcCoil + ")", true, arcCoil > 0);
        // ---- lasers through glass (BeamLasers), beams in water (BeamWater): a fresh zombie hit once at 1.2 over its feet
        BulletConfig las = com.hbm.items.weapon.sedna.factory.XFactoryEnergy.energy_las,
                lasIr = com.hbm.items.weapon.sedna.factory.XFactoryEnergy.energy_las_ir,
                arcBolt = com.hbm.items.weapon.sedna.factory.XFactoryAccelerator.ni4ni_arc;
        float[] lasOpen = zombieBeam(las, 6F, null, 0), lasGlass = zombieBeam(las, 6F, "minecraft:glass", 1),
                lasPane = zombieBeam(las, 6F, "minecraft:glass_pane[east=true,west=true]", 1),
                lasLaminate = zombieBeam(las, 6F, "hbm:reinforced_laminate", 1), lasBoron = zombieBeam(las, 6F, "hbm:glass_boron", 3),
                lasConcrete = zombieBeam(las, 6F, "hbm:concrete_smooth", 1);
        float[] arcOpen6 = zombieBeam(arcBolt, 6F, null, 0), arcGlass6 = zombieBeam(arcBolt, 6F, "minecraft:glass", 1);
        lines.add("     zombie, one beam of 6: laser open " + lasOpen[0] + ", behind glass " + lasGlass[0] + ", pane " + lasPane[0] + ", laminate "
                + lasLaminate[0] + ", 3 boron glass " + lasBoron[0] + ", concrete " + lasConcrete[0] + "; N.I.4.N.I. open " + arcOpen6[0]
                + ", behind glass " + arcGlass6[0]);
        check("laser: a zombie behind glass takes as much as in the open (" + lasGlass[0] + ")", lasOpen[0], lasGlass[0]);
        check("laser: the glass is left whole and unworn", 1F, lasGlass[2]);
        check("laser: a zombie behind a glass pane takes as much as in the open", lasOpen[0], lasPane[0]);
        check("laser: a zombie behind bulletproof laminate takes as much as in the open", lasOpen[0], lasLaminate[0]);
        check("laser: a zombie behind 3 blocks of NTM's boron glass takes as much as in the open", lasOpen[0], lasBoron[0]);
        check("laser: concrete stops it as before", 0F, lasConcrete[0]);
        check("not a laser (N.I.4.N.I.): a zombie behind glass takes less than in the open, but some (" + arcGlass6[0] + " of " + arcOpen6[0] + ")",
                true, arcGlass6[0] > 0F && arcGlass6[0] < arcOpen6[0]);
        check("not a laser: the glass is worn", 0F, arcGlass6[2]);
        BlockPos lasPaneAt = ownLane(0);
        level.setBlock(lasPaneAt, state("minecraft:glass"), Block.UPDATE_CLIENTS);
        for (int i = 0; i < 4; i++) beamAt(las, lasPaneAt, .5, .5, 6F, 6);
        check("laser: four beams of 6 (two would break it as bullets) leave the glass", "minecraft:glass", after(lasPaneAt));
        BlockPos arcPaneAt = ownLane(0);
        level.setBlock(arcPaneAt, state("minecraft:glass"), Block.UPDATE_CLIENTS);
        for (int i = 0; i < 2; i++) beamAt(arcBolt, arcPaneAt, .5, .5, 6F, 6);
        check("not a laser: two beams of 6 break the glass", "minecraft:air", after(arcPaneAt));
        float[] irGlass = zombieBeam(lasIr, 6F, "minecraft:glass", 1);
        check("infrared laser (its own impact): through glass, the zombie is hit and set alight (" + irGlass[0] + ")", true,
                irGlass[0] > 0F && irGlass[3] > 0F);
        check("infrared laser: the glass is left whole and unworn", 1F, irGlass[2]);
        float[] w1 = zombieBeam(las, 15F, "minecraft:water", 1), w3 = zombieBeam(las, 15F, "minecraft:water", 3),
                w8 = zombieBeam(las, 15F, "minecraft:water", 8), wOpen = zombieBeam(las, 15F, null, 0);
        lines.add("     zombie, one laser of 15 through water: open " + wOpen[0] + ", 1 block " + w1[0] + ", 3 blocks " + w3[0] + ", 8 blocks " + w8[0]
                + " (beam " + w8[1] + " long, from 1.5 before the water)");
        check("water: 1 block leaves ~0.61 of the damage (" + w1[0] / wOpen[0] + ")", true, Math.abs(w1[0] / wOpen[0] - Math.exp(-0.5)) < .05);
        check("water: 3 blocks leave ~0.22 (" + w3[0] / wOpen[0] + ")", true, Math.abs(w3[0] / wOpen[0] - Math.exp(-1.5)) < .04);
        check("water: 8 blocks put it out, the zombie takes nothing", 0F, w8[0]);
        check("water: the beam ends 4.6 blocks into the water (" + w8[1] + ")", true, Math.abs(w8[1] - (1.5 + 2 * Math.log(10))) < .02);
        float reach3 = waterReach(3), reach8 = waterReach(8);
        check("water: a turret's beam of 30 through 3 blocks has ~6.7 (" + reach3 + ")", true, Math.abs(reach3 - 30 * Math.exp(-1.5)) < .01);
        check("water: a turret's beam does not get through 8 blocks", -1F, reach8);
        float[] w3Arc = zombieBeam(arcBolt, 15F, "minecraft:water", 3);
        check("water: not a laser loses the same (" + w3Arc[0] + " of " + wOpen[0] + ")", true, Math.abs(w3Arc[0] / wOpen[0] - Math.exp(-1.5)) < .04);

        // the mobs' laser shots (bullets, BeamLasers): BOT Prime's laser through glass, a plain bullet for the difference
        BulletConfig worm = com.hbm.items.weapon.sedna.factory.XFactoryNPC.worm_laser;
        float[] wormOpen = zombieShot(worm, 6F, null), wormGlass = zombieShot(worm, 6F, "minecraft:glass"),
                wormLaminate = zombieShot(worm, 6F, "hbm:reinforced_laminate"), plainOpen = zombieShot(round, 6F, null),
                plainGlass = zombieShot(round, 6F, "minecraft:glass");
        lines.add("     zombie, one shot of 6: worm_laser open " + wormOpen[0] + ", behind glass " + wormGlass[0] + ", laminate " + wormLaminate[0]
                + "; plain bullet open " + plainOpen[0] + ", behind glass " + plainGlass[0]);
        check("mob laser shot: a zombie behind glass takes as much as in the open (" + wormGlass[0] + ")", true, wormOpen[0] > 0F && wormGlass[0] == wormOpen[0]);
        check("mob laser shot: the glass is left whole and unworn", 1F, wormGlass[1]);
        check("mob laser shot: a zombie behind bulletproof laminate takes as much as in the open", wormOpen[0], wormLaminate[0]);
        check("plain bullet: a zombie behind glass takes less than in the open (" + plainGlass[0] + " of " + plainOpen[0] + "), glass worn", true,
                plainGlass[0] < plainOpen[0] && plainGlass[1] == 0F);

        Path beamFile = FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
        Files.writeString(beamFile, BeamLasers.KEY + " = false\n" + BeamWater.KEY + " = false\n");
        Thread.sleep(2100);
        float[] lasOff = zombieBeam(las, 6F, "minecraft:glass", 1);
        check("laserGlass off: a laser loses in glass and wears it as a bullet", true, lasOff[0] < lasOpen[0] && lasOff[2] == 0F);
        float[] wormOff = zombieShot(worm, 6F, "minecraft:glass");
        check("laserGlass off: a mob's laser shot loses in glass and wears it as a bullet", true, wormOff[0] < wormOpen[0] && wormOff[1] == 0F);
        check("beamWater off: through 8 blocks of water as in the open", wOpen[0], zombieBeam(las, 15F, "minecraft:water", 8)[0]);
        Files.writeString(beamFile, BeamServer.KEY + " = false\n");
        Thread.sleep(2100);
        beam = beamShot(single("minecraft:glass"), .5, .5, 30F);
        check("beam, beamRules off: NTM's beam stops at the glass", true, beam[0] < .01F && beam[0] > -.01F);
        beam = beamShot(fenceRow("minecraft:oak_fence"), .25, .65, 30F);
        check("beam, beamRules off: NTM's beam stops at a fence's gap", true, beam[0] < 1F);
        float[] tauOff = turret("hbm:turret_tauon", "minecraft:glass");
        check("turret, beamRules off: a Tauon does not see a golem behind glass", 0F, tauOff[0]);
        check("tesla, beamRules off: no arc through glass", 0F, arc("minecraft:glass", false));
        Files.writeString(beamFile, BeamServer.KEY + " = true\n");
        Thread.sleep(2100);

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
        BulletRules.reloadNow(); // the rules' file is looked at by the game's ticks, and none goes by here
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
        BulletRules.reloadNow(); // the rules' file is looked at by the game's ticks, and none goes by here

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

        // ---- .50 DU into an iron golem in the open: every bullet takes the same, 45 in the body, x1.75 in the head
        // (the mod's one head multiplier for every gun, as HeadSmoke checks); it goes through the golem and loses
        // nothing by it
        // with the server's test log on: the hit is written with what it took and whether it was in the head
        Path log = TestLog.set(false, true);
        check("golem: .50 DU in the body takes 45", 45F, golemHit(1.0, 45F));
        check("golem: .50 DU in the head takes 78.75", 78.75F, golemHit(2.4, 45F));
        // a bullet from above through its head into the plank floor behind it, all in one tick: the golem gets the
        // damage from before the floor (NTM asks for the blocks of the tick's path before the creatures on it)
        float[] floor = golemOverFloor(45F);
        check("golem over a floor: the head shot takes 78.75, not what the floor behind left", 78.75F, floor[0]);
        check("golem over a floor: the floor behind it still costs the bullet", true, floor[1] < 45F);
        TestLog.set(false, false);
        String text = log == null ? "" : Files.readString(log);
        check("golem: the test log has the body hit", true, text.contains("head=false") && text.contains("took=45.000"));
        check("golem: the test log has the head hit", true, text.contains("head=true") && text.contains("took=78.750"));

        // ---- path rules in the server's file
        expect("path hitbox: a spruce fence's gap is hit", Want.PIERCE, fenceRow("minecraft:spruce_fence"), .25, .65, 30F);
        s = expect("path through: stone is flown through", Want.PASS, single("minecraft:stone"), .5, .5, 30F);
        check("path through: the stone stays", "minecraft:stone", s.after);

        // ---- bullets through the empty part of fences, walls, panes and bars, and their joints
        ok &= FenceAirCheck.run(level, round, lines, 1L);

        modelHits();
        richterBulletSpeed();
        ricochets();
        blastSight(server);
        worldNumbers(server);
        selectorsAsBefore();
        tagsAnew(server);
        rulesByTicks();
        lines.add(ok ? "ALL OK" : "SOME FAILED");
        return ok;
    }

    // ---- the world's numbers for core (WorldView, BlockTable: core's WorldViewContract, here on the game's own
    // blocks): ours, by the state, one for every level. That the game renumbering its own changes nothing is by how
    // they are made (BlockStates reads no game number; StateNumbersTest holds it on a fake one): the game's registry is
    // not touched here.
    private static void worldNumbers(MinecraftServer server) {
        acup.gunfeel.host.WorldView w = Wiring.world(level);
        BlockPos air = new BlockPos(1, 200, 1), stone = air.above(), fence = stone.above();
        BlockState stoneState = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        level.setBlock(air, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
        level.setBlock(stone, stoneState, 2 | 16);
        level.setBlock(fence, state("minecraft:oak_fence[east=true]"), 2 | 16);
        check("world: plain air is 0", acup.gunfeel.host.BlockTable.AIR, w.state(1, 200, 1));
        check("world: 0's facts are plain air's", "minecraft:air", w.facts(acup.gunfeel.host.BlockTable.AIR).id());
        check("world: cave air is not 0", true, BlockStates.number(net.minecraft.world.level.block.Blocks.CAVE_AIR.defaultBlockState()) != 0);
        int s = w.state(stone.getX(), stone.getY(), stone.getZ()), f = w.state(fence.getX(), fence.getY(), fence.getZ());
        check("world: stone keeps its number", s, w.state(stone.getX(), stone.getY(), stone.getZ()));
        // one table for the game: the Nether's world and the table itself give stone the same number
        net.minecraft.server.level.ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
        nether.setBlock(new BlockPos(0, 100, 0), stoneState, 2 | 16);
        check("world: one number in every level", s + " " + s, Wiring.world(nether).state(0, 100, 0) + " " + BlockStates.number(stoneState));
        check("world: stone's facts and key", "minecraft:stone minecraft:stone", w.facts(s).id() + " " + w.key(s));
        check("world: a fence's key, its properties by name", "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]", w.key(f));
        check("world: the fence's facts", "minecraft:oak_fence", w.facts(f).id());
        // air as the game tells it (cave air too), the destroy speed there as the game's
        int cave = BlockStates.number(net.minecraft.world.level.block.Blocks.CAVE_AIR.defaultBlockState());
        check("world: air is the game's: plain and cave, not stone or a fence", "true true false false",
                w.air(acup.gunfeel.host.BlockTable.AIR) + " " + w.air(cave) + " " + w.air(s) + " " + w.air(f));
        check("world: stone's destroy speed there", stoneState.getDestroySpeed(level, stone), w.destroySpeed(s, stone.getX(), stone.getY(), stone.getZ()));
        // %mod's test (BlockTable.machineOrPartial): stone is a full cube drawn as a model, a fence is not a full cube,
        // air is drawn by none
        check("world: machine or partial: not stone, a fence, air", "false true true",
                w.machineOrPartial(s) + " " + w.machineOrPartial(f) + " " + w.machineOrPartial(acup.gunfeel.host.BlockTable.AIR));
        // the entity types (EntityTypeTable): a type's id the same String each time, its tags the game's, a tag name
        // that is no id in none, a number no type has throws
        var types = acup.gunfeel.CoreServices.load(acup.gunfeel.host.EntityTypeTable.class, acup.gunfeel.host.EntityTypeTable.MISSING);
        int zombie = EntityTypeNumbers.number(net.minecraft.world.entity.EntityTypes.ZOMBIE), pig = EntityTypeNumbers.number(net.minecraft.world.entity.EntityTypes.PIG);
        check("world: a type's id, one String", "minecraft:zombie true", types.name(zombie) + " " + (types.name(zombie) == types.name(zombie)));
        check("world: a type keeps its number", zombie, EntityTypeNumbers.number(net.minecraft.world.entity.EntityTypes.ZOMBIE));
        check("world: type tags the game's: a zombie undead, a pig not, a bad name none", "true false false false",
                types.tagged(zombie, "minecraft:undead") + " " + types.tagged(pig, "minecraft:undead") + " " + types.tagged(zombie, "minecraft:skeletons")
                        + " " + types.tagged(zombie, "Not An Id!"));
        boolean threw = false;
        try {
            types.name(1 << 20);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("world: a number no type has throws", true, threw);
    }

    // ---- the rules' file looked at by the game's ticks (BulletRuleText.tick), not by the clock at each question: a
    // change waits for a tick a second on; a tick within the second does not look; reloadNow looks at once. The ticks
    // are given here (none goes by while the check runs), a second past the clock; each file its own modified time
    private static void rulesByTicks() throws Exception {
        Path f = FMLPaths.CONFIGDIR.get().resolve(BulletRules.FILE);
        BlockState melon = state("minecraft:melon");
        String melonRule = "break minecraft:melon instant drop\n";
        long stamp = System.currentTimeMillis() + 60_000;
        BulletRules.reloadNow();
        String before = String.valueOf(BulletRules.breakRule(melon));
        Files.writeString(f, RULES + melonRule);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(stamp += 1000));
        check("rules: a change to the file waits for a tick", before, String.valueOf(BulletRules.breakRule(melon)));
        long t = System.currentTimeMillis() + 1001;
        BulletRuleText.tick(t);
        BulletRuleText.Break instant = BulletRules.breakRule(melon);
        check("rules: a tick a second on, and the next question has it", "1 true", instant == null ? "none" : instant.mode() + " " + instant.drop());
        Files.writeString(f, RULES);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(stamp += 1000));
        BulletRuleText.tick(t + 500);
        check("rules: a tick within the second does not look", String.valueOf(instant), String.valueOf(BulletRules.breakRule(melon)));
        BulletRuleText.tick(t + 1001);
        check("rules: the tick after it does", before, String.valueOf(BulletRules.breakRule(melon)));
        Files.writeString(f, RULES + melonRule);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(stamp += 1000));
        BulletRules.reloadNow();
        check("rules: reloadNow looks at once", String.valueOf(instant), String.valueOf(BulletRules.breakRule(melon)));
        Files.writeString(f, RULES);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(stamp += 1000));
        BulletRules.reloadNow();
        check("rules: the stand's own again", before, String.valueOf(BulletRules.breakRule(melon)));
    }

    // ---- NTM's standard ricochet and ours (Ricochet, core): a bullet grazing a floor at half its ricochetAngle bounces
    // off cobblestone and flies on up (stone is "through" in this stand's rules); off dirt (the rules: "ricochet $soil
    // ... no") it stops on it instead
    private static void ricochets() {
        lines.add("     ricochet: round " + round.id + ", ricochetAngle " + round.ricochetAngle + ", maxRicochetCount " + round.maxRicochetCount);
        for (String floor : new String[] {"minecraft:cobblestone", "minecraft:dirt"}) {
            BlockPos at = ownLane(0);
            for (int i = -1; i < 8; i++) level.setBlock(at.south(i), state(floor), Block.UPDATE_CLIENTS);
            double rad = Math.toRadians(round.ricochetAngle * 0.5F);
            EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, round, 1F, 0F, 0F, 0F);
            b.setPos(at.getX() + .5, at.getY() + 1 + 0.05, at.getZ() + .2);
            b.setThrowableHeading(0, -Math.sin(rad), Math.cos(rad), round.velocity, 0F);
            // below 1 it pierces nothing (Pierce): the block holds it and NTM's onImpact takes it, to bounce or stop
            b.damage = 0.5F;
            for (int i = 0; i < 3 && !b.isRemoved(); i++) b.tick();
            // where it stopped is not asked: NTM puts the bullet at its tick's end after onImpact
            String got = b.isRemoved() ? "stopped" : b.getDeltaMovement().y > 0 ? "bounced up" : "flies on down";
            check("ricochet: a grazing round off " + floor, floor.equals("minecraft:cobblestone") ? "bounced up" : "stopped", got);
            for (int i = -1; i < 8; i++) level.setBlock(at.south(i), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ---- what a blast by parts sees (BlastSight, core, on the world's ray and shapes: WorldView's clip and boxes)
    // against 95ce893's BlastParts.points, visibility and solid on the game's level, word for word below (OldSight):
    // a block of random shapes (slabs, stairs, fences, panes, walls, scaffolding and powder snow, whose shapes go by
    // the entity's context), random parts and nodes in it, the same share to the bit; the world's own answers (its ray's
    // every field, a shape's boxes, free room, its tick and side) against the game's as its callers ask it
    private static final class OldSight {
        /** the part's points the rays start from: its middle, then pairs along its longest edge */
        static List<Vec3> points(ModelBox b, int rays) {
            double lu = Math.sqrt(b.ux() * b.ux() + b.uy() * b.uy() + b.uz() * b.uz());
            double lv = Math.sqrt(b.vx() * b.vx() + b.vy() * b.vy() + b.vz() * b.vz());
            double lw = Math.sqrt(b.wx() * b.wx() + b.wy() * b.wy() + b.wz() * b.wz());
            int axis = lu >= lv && lu >= lw ? 0 : lv >= lw ? 1 : 2;
            List<Vec3> out = new ArrayList<>(rays);
            out.add(ModelContours.point(b, .5, .5, .5));
            for (int i = 1; out.size() < rays; i++) {
                // 0.15 and 0.85 of the length, then halfway in to the middle and so on
                double off = 0.35 / i;
                for (double t : new double[] { .5 - off, .5 + off }) {
                    if (out.size() >= rays) break;
                    out.add(ModelContours.point(b, axis == 0 ? t : .5, axis == 1 ? t : .5, axis == 2 ? t : .5));
                }
            }
            return out;
        }

        /** the share of the part's rays that reach the blast from its best node, as NTM's getBlockDensity's */
        static double visibility(Level level, ModelBox b, Vec3[] nodes, Entity entity, int rays) {
            // a point inside a block (a big model reaches into a wall) sends no ray: the part's others go by themselves
            List<Vec3> from = new ArrayList<>(rays);
            for (Vec3 p : points(b, rays)) if (!solid(level, p)) from.add(p);
            if (from.isEmpty()) return 0;
            double best = 0;
            for (Vec3 node : nodes) {
                int clear = 0;
                for (Vec3 p : from)
                    if (level.clip(new ClipContext(p, node, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType()
                            == HitResult.Type.MISS) clear++;
                best = Math.max(best, clear / (double) from.size());
                if (best >= 1) break;
            }
            return best;
        }

        static boolean solid(Level level, Vec3 p) {
            var pos = net.minecraft.core.BlockPos.containing(p);
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty()) return false;
            Vec3 in = p.subtract(pos.getX(), pos.getY(), pos.getZ());
            for (var box : shape.toAabbs()) if (box.contains(in)) return true;
            return false;
        }
    }

    private static void blastSight(MinecraftServer server) {
        java.util.SplittableRandom r = new java.util.SplittableRandom(1909);
        int x0 = 40, y0 = 180, z0 = 40, n = 12;
        String[] palette = {"minecraft:stone", "minecraft:smooth_stone_slab[type=bottom]", "minecraft:smooth_stone_slab[type=top]",
                "minecraft:oak_stairs[facing=east,half=bottom]", "minecraft:oak_stairs[facing=north,half=top,shape=outer_left]",
                "minecraft:oak_fence[east=true,north=true]", "minecraft:glass_pane[east=true,west=true]", "minecraft:iron_bars[north=true]",
                "minecraft:cobblestone_wall[up=true]", "minecraft:scaffolding[bottom=false,distance=0]", "minecraft:scaffolding[bottom=true,distance=1]",
                "minecraft:powder_snow", "minecraft:snow[layers=3]", "minecraft:soul_sand", "minecraft:lantern", "minecraft:iron_chain",
                "minecraft:ladder[facing=south]", "minecraft:water", "minecraft:hopper", "minecraft:anvil"};
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++)
                for (int z = 0; z < n; z++)
                    level.setBlock(new BlockPos(x0 + x, y0 + y, z0 + z),
                            r.nextInt(3) == 0 ? state(palette[r.nextInt(palette.length)]) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                            2 | 16);
        var zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        zombie.setPos(x0 + 6.5, y0 + 6, z0 + 6.5);
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        level.addFreshEntity(zombie);
        acup.gunfeel.host.WorldView w = Wiring.world(level);
        acup.gunfeel.host.Body body = EntityBody.of(zombie);
        try {
            // a point inside a block's shape: on the cells' edges and halves too
            double[] edges = {0, .5, 1 - 1e-9, .4999999999, .25, .75, .9375, .0625};
            int same = 0, solid = 0, points = 40000;
            for (int i = 0; i < points; i++) {
                double[] c = new double[3];
                for (int k = 0; k < 3; k++)
                    c[k] = new int[] {x0, y0, z0}[k] + r.nextInt(n) + (r.nextBoolean() ? edges[r.nextInt(edges.length)] : r.nextDouble());
                boolean was = OldSight.solid(level, new net.minecraft.world.phys.Vec3(c[0], c[1], c[2]));
                if (was == BlastSight.solid(w, new Vec3d(c[0], c[1], c[2]))) same++;
                if (was) solid++;
            }
            check("blast: a point inside a block's shape, as before (" + solid + " inside)", points + "", same + "");
            check("blast: points both inside and out", true, solid > points / 10 && solid < points * 9 / 10);
            // the parts' share in sight: random boxes and nodes in the block, 1 to 9 rays, by the zombie
            int parts = 4000, equal = 0, whole = 0, none = 0;
            for (int i = 0; i < parts; i++) {
                double ox = x0 + 1 + r.nextDouble() * (n - 2), oy = y0 + 1 + r.nextDouble() * (n - 2), oz = z0 + 1 + r.nextDouble() * (n - 2);
                double a = r.nextDouble() * Math.PI * 2, lu = .1 + r.nextDouble() * 1.4, lv = .1 + r.nextDouble() * 1.4, lw = .1 + r.nextDouble() * 1.4;
                double ca = Math.cos(a), sa = Math.sin(a);
                ModelBox b = new ModelBox("part", ox, oy, oz, ca * lu, 0, sa * lu, 0, lv, 0, -sa * lw, 0, ca * lw);
                int k = 1 + r.nextInt(3), rays = 1 + r.nextInt(9);
                net.minecraft.world.phys.Vec3[] nodes = new net.minecraft.world.phys.Vec3[k];
                Vec3d[] sight = new Vec3d[k];
                for (int j = 0; j < k; j++) {
                    nodes[j] = new net.minecraft.world.phys.Vec3(x0 + r.nextDouble() * n, y0 + r.nextDouble() * n, z0 + r.nextDouble() * n);
                    sight[j] = new Vec3d(nodes[j].x, nodes[j].y, nodes[j].z);
                }
                double was = OldSight.visibility(level, b, nodes, zombie, rays), is = BlastSight.visibility(w, body, b, sight, rays);
                if (Double.doubleToRawLongBits(was) == Double.doubleToRawLongBits(is)) equal++;
                if (was == 1) whole++;
                if (was == 0) none++;
            }
            check("blast: a part's share in sight, to the bit (" + whole + " whole, " + none + " none)", parts + "", equal + "");
            check("blast: parts seen whole, partly and not at all", true, whole > 0 && none > 0 && whole + none < parts);
            worldAsked(w, body, zombie, r, x0, y0, z0, n, server);
        } finally {
            zombie.discard();
            for (int x = 0; x < n; x++)
                for (int y = 0; y < n; y++)
                    for (int z = 0; z < n; z++)
                        level.setBlock(new BlockPos(x0 + x, y0 + y, z0 + z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
        }
    }

    // the world's (WorldView's) answers against the game's as its callers ask it: the ray (ClipContext with the entity,
    // or the empty context), a shape's boxes (getCollisionShape(level, pos) and its kin, with the entity's context),
    // noCollision, the tick and the side
    private static void worldAsked(acup.gunfeel.host.WorldView w, acup.gunfeel.host.Body body, net.minecraft.world.entity.Entity zombie,
            java.util.SplittableRandom r, int x0, int y0, int z0, int n, MinecraftServer server) {
        var shapes = net.minecraft.world.level.ClipContext.Block.values();
        var fluids = net.minecraft.world.level.ClipContext.Fluid.values();
        acup.gunfeel.host.RayHit out = new acup.gunfeel.host.RayHit();
        int rays = 20000, same = 0, hits = 0, inside = 0;
        int[] kinds = new int[4];
        for (int i = 0; i < rays; i++) {
            var from = new net.minecraft.world.phys.Vec3(x0 - 1 + r.nextDouble() * (n + 2), y0 - 1 + r.nextDouble() * (n + 2), z0 - 1 + r.nextDouble() * (n + 2));
            var to = r.nextInt(20) == 0 ? from : new net.minecraft.world.phys.Vec3(x0 - 1 + r.nextDouble() * (n + 2), y0 - 1 + r.nextDouble() * (n + 2),
                    z0 - 1 + r.nextDouble() * (n + 2));
            int shape = r.nextInt(4), fluid = r.nextInt(4);
            boolean by = r.nextBoolean();
            var hit = by ? level.clip(new net.minecraft.world.level.ClipContext(from, to, shapes[shape], fluids[fluid], zombie))
                    : level.clip(new net.minecraft.world.level.ClipContext(from, to, shapes[shape], fluids[fluid],
                            net.minecraft.world.phys.shapes.CollisionContext.empty()));
            boolean got = w.clip(from.x, from.y, from.z, to.x, to.y, to.z, shape, fluid, by ? body : null, out);
            boolean block = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
            if (got == block && out.block == block && out.x == hit.getLocation().x && out.y == hit.getLocation().y && out.z == hit.getLocation().z
                    && out.face == hit.getDirection().ordinal() && out.bx == hit.getBlockPos().getX() && out.by == hit.getBlockPos().getY()
                    && out.bz == hit.getBlockPos().getZ() && out.inside == hit.isInside()) same++;
            if (block) {
                hits++;
                kinds[shape]++;
            }
            if (hit.isInside()) inside++;
        }
        check("world: the game's ray, every field (" + hits + " hits, " + inside + " inside)", rays + "", same + "");
        check("world: rays hit by every shape kind, and some start inside", true, kinds[0] > 0 && kinds[1] > 0 && kinds[2] > 0 && kinds[3] > 0 && inside > 0);
        // a shape's boxes: each kind, with and without the zombie's context, as the game's own calls give them
        int cells = 0, boxesSame = 0, many = 0;
        double[] buf = new double[6 * 64];
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++)
                for (int z = 0; z < n; z++) {
                    BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
                    BlockState st = level.getBlockState(p);
                    var ctx = net.minecraft.world.phys.shapes.CollisionContext.of(zombie);
                    net.minecraft.world.phys.shapes.VoxelShape[] want = {st.getCollisionShape(level, p), st.getShape(level, p),
                            st.getVisualShape(level, p, net.minecraft.world.phys.shapes.CollisionContext.empty()), st.getCollisionShape(level, p, ctx),
                            st.getShape(level, p, ctx), st.getVisualShape(level, p, ctx)};
                    for (int k = 0; k < 6; k++) {
                        cells++;
                        var aabbs = want[k].toAabbs();
                        int got = w.boxes(p.getX(), p.getY(), p.getZ(), k % 3, k < 3 ? null : body, buf);
                        boolean eq = got == aabbs.size();
                        for (int i = 0; eq && i < got; i++) {
                            var b = aabbs.get(i);
                            eq = buf[i * 6] == b.minX && buf[i * 6 + 1] == b.minY && buf[i * 6 + 2] == b.minZ && buf[i * 6 + 3] == b.maxX
                                    && buf[i * 6 + 4] == b.maxY && buf[i * 6 + 5] == b.maxZ;
                        }
                        if (eq) boxesSame++;
                        if (got > 1) many++;
                        if (got > 0) {
                            double[] shortOut = new double[got * 6 - 1];
                            if (w.boxes(p.getX(), p.getY(), p.getZ(), k % 3, k < 3 ? null : body, shortOut) != -got) boxesSame--;
                        }
                    }
                }
        check("world: a shape's boxes as the game's, in its order (" + many + " of many boxes)", cells + "", boxesSame + "");
        String thrown;
        try {
            w.boxes(x0, y0, z0, acup.gunfeel.host.WorldView.FALLDAMAGE_RESETTING, null, buf);
            thrown = "none";
        } catch (IllegalArgumentException e) {
            thrown = "IllegalArgumentException";
        }
        check("world: no block shape of the fall damage kind", "IllegalArgumentException", thrown);
        // free room: noCollision with the zombie and without
        int rooms = 4000, freeSame = 0, free = 0;
        for (int i = 0; i < rooms; i++) {
            double ax = x0 - 1 + r.nextDouble() * (n + 2), ay = y0 - 1 + r.nextDouble() * (n + 2), az = z0 - 1 + r.nextDouble() * (n + 2);
            double bx = ax + r.nextDouble() * 1.5, by = ay + r.nextDouble() * 2, bz = az + r.nextDouble() * 1.5;
            boolean with = r.nextBoolean();
            boolean was = level.noCollision(with ? zombie : null, new net.minecraft.world.phys.AABB(ax, ay, az, bx, by, bz));
            if (was == w.free(with ? body : null, ax, ay, az, bx, by, bz)) freeSame++;
            if (was) free++;
        }
        check("world: free room as the game's noCollision (" + free + " free)", rooms + "", freeSame + "");
        check("world: rooms free and not", true, free > 0 && free < rooms);
        // the tick: the level's, the Nether's the same; the side: the server's
        check("world: the level's tick, the Nether's the same", level.getGameTime() + " " + level.getGameTime(),
                w.gameTime() + " " + Wiring.world(server.getLevel(net.minecraft.world.level.Level.NETHER)).gameTime());
        check("world: the server's side", false, w.client());
    }

    // ---- the rules' selectors on the blocks' facts (core: BlockSelectors) against b11fa9b's BulletRules.blockMatches
    // on the game's blocks live, word for word below: every state of every block, each selector of the rules read
    // (the jar's and this stand's) and of a set of every kind, alone and as the rules' lists
    private static final class OldMatch {
        private static final java.util.Map<String, net.minecraft.world.level.block.SoundType> SOUND_TYPES = soundTypes();

        private static java.util.Map<String, net.minecraft.world.level.block.SoundType> soundTypes() {
            java.util.Map<String, net.minecraft.world.level.block.SoundType> out = new java.util.HashMap<>();
            for (java.lang.reflect.Field f : net.minecraft.world.level.block.SoundType.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != net.minecraft.world.level.block.SoundType.class) continue;
                try {
                    out.put(f.getName().toLowerCase(java.util.Locale.ROOT), (net.minecraft.world.level.block.SoundType) f.get(null));
                } catch (IllegalAccessException e) {
                    // skip
                }
            }
            return out;
        }

        private static final java.util.Map<String, java.util.regex.Pattern> PATTERNS = new java.util.HashMap<>();

        private static boolean idMatches(String sel, net.minecraft.resources.Identifier id) {
            if (sel.startsWith("~")) {
                java.util.regex.Pattern p = PATTERNS.computeIfAbsent(sel, s -> {
                    try {
                        return java.util.regex.Pattern.compile(s.substring(1));
                    } catch (RuntimeException e) {
                        return java.util.regex.Pattern.compile("(?!)");
                    }
                });
                return p.matcher(id.toString()).matches();
            }
            net.minecraft.resources.Identifier want = net.minecraft.resources.Identifier.tryParse(sel);
            return want != null && want.equals(id);
        }

        static boolean blockMatches(List<String> sel, BlockState state) {
            net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
            for (String s : sel) {
                if (s.equals("*")) return true;
                if (s.startsWith("#")) {
                    net.minecraft.resources.Identifier t = net.minecraft.resources.Identifier.tryParse(s.substring(1));
                    if (t != null && state.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, t))) return true;
                } else if (s.startsWith("@")) {
                    net.minecraft.world.level.block.SoundType st = SOUND_TYPES.get(s.substring(1).toLowerCase(java.util.Locale.ROOT));
                    if (st != null && state.getSoundType() == st) return true;
                } else if (s.startsWith("%")) {
                    if (id.getNamespace().equals(s.substring(1)) && machineOrPartial(state)) return true;
                } else if (s.startsWith("$")) {
                    if (BulletRules.material(state).material().name().equalsIgnoreCase(s.substring(1))) return true;
                } else if (idMatches(s, id)) return true;
            }
            return false;
        }

        static boolean machineOrPartial(BlockState st) {
            if (st.hasBlockEntity() || st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) return true;
            try {
                return !Block.isShapeFullBlock(st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            } catch (RuntimeException e) {
                return true;
            }
        }
    }

    private static void selectorsAsBefore() {
        java.util.Set<String> one = new java.util.TreeSet<>(List.of("*", "stone", ":stone", "Stone", "..:stone", "hbm:", "#logs", "#minecraft:logs",
                "#c:glass_blocks", "#minecraft:mineable/pickaxe", "#", "#Bad Tag", "@stone", "@STONE", "@metal", "@netherite_block", "@?", "@",
                "@metal/netherite_block", "%hbm", "%minecraft", "%", "$machine", "$MACHINE", "$glass", "$none", "$", "~hbm:.*", "~.*fence.*", "~[",
                "~"));
        List<List<String>> lists = new ArrayList<>();
        for (BulletRuleText.Rule r : BulletRuleText.load()) {
            lists.add(r.selectors());
            one.addAll(r.selectors());
        }
        for (String s : one) lists.add(List.of(s));
        int states = 0, asked = 0, yes = 0, wrong = 0, facts = 0, threw = 0, machine = 0, kept = 0;
        String first = null, thrown = null;
        java.util.Set<String> mods = new java.util.TreeSet<>();
        for (Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            mods.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getNamespace());
            for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                states++;
                try {
                    // the facts made here as the selectors make them: one that throws would be a bullet's
                    if (BlockStates.factsOf(BlockStates.number(st)).machineOrPartial() != OldMatch.machineOrPartial(st)) facts++;
                    // the table's %mod (BlockTable.machineOrPartial) is the game's; its kept facts (material) are made anew
                    if (Wiring.world(level).machineOrPartial(BlockStates.number(st)) != OldMatch.machineOrPartial(st)) machine++;
                    if (!BlockStates.factsOf(BlockStates.number(st)).equals(BlockFactsOf.of(st))) kept++;
                } catch (RuntimeException e) {
                    threw++;
                    if (thrown == null) thrown = st + ": " + e;
                    continue;
                }
                for (List<String> sel : lists) {
                    boolean was = OldMatch.blockMatches(sel, st), now = BulletRules.blockMatches(sel, st);
                    asked++;
                    if (was) yes++;
                    if (was != now) {
                        wrong++;
                        if (first == null) first = sel + " on " + st + ": was " + was;
                    }
                }
            }
        }
        lines.add("     selectors: " + states + " states of " + mods.size() + " namespaces " + mods + ", " + lists.size() + " selector lists, "
                + asked + " asked, " + yes + " taken");
        check("selectors: every state's facts made" + (thrown == null ? "" : " (first: " + thrown + ")"), 0, threw);
        check("selectors: the table's machine-or-partial the game's on every state", 0, machine);
        check("selectors: the table's kept facts as made now on every state", 0, kept);
        lines.add("     selectors: the facts' machine-or-partial unlike the game's own (%mod asks the game's) on " + facts + " states");
        check("selectors: every state, every selector, as before" + (first == null ? "" : " (first: " + first + ")"), 0, wrong);
        check("selectors: enough of them taken and not", true, yes > 1000 && asked - yes > 1000);
    }

    // ---- the game's tags applied anew (/reload): the blocks' facts are made anew (TagsUpdatedEvent: BlockStates.forgetFacts)
    private static void tagsAnew(MinecraftServer server) {
        BlockState stoneState = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        int s = BlockStates.number(stoneState);
        BlockFacts before = BlockStates.factsOf(s);
        var reload = server.reloadResources(server.getPackRepository().getSelectedIds());
        long until = System.nanoTime() + 60_000_000_000L;
        server.managedBlock(() -> reload.isDone() || System.nanoTime() > until);
        check("tags: /reload done", true, reload.isDone() && !reload.isCompletedExceptionally());
        BlockFacts after = BlockStates.factsOf(s);
        check("tags: stone's facts made anew after /reload, its number kept", "true " + s, (after != before) + " " + BlockStates.number(stoneState));
        check("tags: stone's facts as before by value", before, after);
        check("tags: #minecraft:base_stone_overworld still takes stone", true,
                BulletRules.blockMatches(List.of("#minecraft:base_stone_overworld"), stoneState));
    }

    private static void richterBulletSpeed() {
        var config = com.hbm.items.weapon.sedna.factory.XFactory9mm.p9_ap;
        var bullet = new EntityBulletBaseMK4(level, config, 1F,0F,0F,0F);
        bullet.setPos(90,200,90);
        // ItemGunBaseNT задаёт единичную начальную скорость; реальное смещение даёт motionMult.
        bullet.setThrowableHeading(0,0,1,1F,0F);
        var start = bullet.position();
        bullet.tick();
        double travelled = bullet.position().distanceTo(start);
        double expected = config.velocity * ReaimTimes.number("bulletSpeed",1.6F);
        check("Richter 9mm AP: real tick matches configured speed", true, Math.abs(travelled - expected) < 1e-4);
        lines.add("     Richter 9mm AP: " + travelled + " blocks/tick, " + travelled*20 + " blocks/s at 20 TPS");
        bullet.discard();
    }

    private static void modelHits() {
        var g = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        g.setPos(20, 170, 20);
        g.setNoAi(true);
        g.setNoGravity(true);
        g.yBodyRot = 0;
        level.addFreshEntity(g);
        var origin = g.position();
        java.util.List<ModelBox> boxes = java.util.List.of(
                new ModelBox("left_leg", -.4, 0, -.1, .2, 0, 0, 0, 1, 0, 0, 0, .2),
                new ModelBox("right_leg", .2, 0, -.1, .2, 0, 0, 0, 1, 0, 0, 0, .2),
                new ModelBox("body", -.4, 1, -.2, .8, 0, 0, 0, 1, 0, 0, 0, .4),
                new ModelBox("wing", 2, 1.5, -.1, .5, 0, 0, 0, .3, 0, 0, 0, .2));
        ModelHits.put(level, g, 0, boxes);
        check("model: debug shows all collision parts", boxes.size(), ModelHits.outlines(g).size());
        var gap = origin.add(0, .5, -3);
        var gapEnd = gap.add(0, 0, 6);
        var leg = origin.add(.3, .5, -3);
        var legEnd = leg.add(0, 0, 6);
        var original = new net.minecraft.world.phys.AABB(g.getX() - g.getBbWidth() / 2, g.getY(), g.getZ() - g.getBbWidth() / 2,
                g.getX() + g.getBbWidth() / 2, g.getY() + g.getBbHeight(), g.getZ() + g.getBbWidth() / 2);
        check("model: real entity returns model-aware box", true, g.getBoundingBox() instanceof ModelAABB);
        check("model: movement dimensions unchanged", original.getSize(), g.getBoundingBox().getSize());
        check("model: gap stays a miss after NTM inflation", true, g.getBoundingBox().inflate(.3).clip(gap, gapEnd).isEmpty());
        check("model: leg hit after vanilla scalar inflation", true, g.getBoundingBox().inflate(.3).clip(leg, legEnd).isPresent());
        check("model: a point between legs is outside", false, g.getBoundingBox().contains(origin.add(0, .5, 0)));
        check("model: a point inside leg is inside", true, g.getBoundingBox().contains(origin.add(.3, .5, 0)));
        g.yBodyRot = 45;
        check("model: body turn rotates a limb with the entity", true, g.getBoundingBox().clip(
                leg.subtract(origin).yRot(-(float) Math.PI / 4).add(origin),
                legEnd.subtract(origin).yRot(-(float) Math.PI / 4).add(origin)).isPresent());
        check("model: body turn preserves gap", true, g.getBoundingBox().clip(
                gap.subtract(origin).yRot(-(float) Math.PI / 4).add(origin),
                gapEnd.subtract(origin).yRot(-(float) Math.PI / 4).add(origin)).isEmpty());
        g.yBodyRot = 0;
        java.util.function.Predicate<net.minecraft.world.entity.Entity> filter = e -> e == g;
        check("model: vanilla projectile misses gap", true,
                net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, (net.minecraft.world.entity.Entity) null,
                        gap, gapEnd, new net.minecraft.world.phys.AABB(gap, gapEnd).inflate(1), filter, .3F) == null);
        check("model: vanilla projectile hits leg", true,
                net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, (net.minecraft.world.entity.Entity) null,
                        leg, legEnd, new net.minecraft.world.phys.AABB(leg, legEnd).inflate(1), filter, .3F) != null);
        var wing = origin.add(2.2, 1.6, -3);
        var wingEnd = wing.add(0, 0, 6);
        check("model: vanilla query finds part outside vanilla box", true,
                net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, (net.minecraft.world.entity.Entity) null,
                        wing, wingEnd, new net.minecraft.world.phys.AABB(wing, wingEnd).inflate(.1), filter, .3F) != null);
        var shooter = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        shooter.setPos(origin.add(0, 0, -5));
        check("model: melee pick misses gap", true,
                net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(shooter, gap, gapEnd,
                        new net.minecraft.world.phys.AABB(gap, gapEnd).inflate(1), filter, 36) == null);
        check("model: melee pick hits leg", true,
                net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(shooter, leg, legEnd,
                        new net.minecraft.world.phys.AABB(leg, legEnd).inflate(1), filter, 36) != null);
        var du = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        for (int i = 0; i < 2; i++) {
            var arrow = net.minecraft.world.entity.EntityTypes.ARROW.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            arrow.setPos(i == 0 ? gap : leg);
            arrow.shoot(0, 0, 1, 6, 0);
            g.invulnerableTime = 0;
            float before = g.getHealth();
            arrow.tick();
            check("model: real vanilla arrow " + (i == 0 ? "misses gap" : "hits leg"), i != 0, g.getHealth() < before);
            arrow.discard();
        }
        for (int i = 0; i < 2; i++) {
            var start = i == 0 ? gap : leg;
            var bullet = new EntityBulletBaseMK4(level, du, 1F, 0F, 0F, 0F);
            bullet.setPos(start);
            bullet.setThrowableHeading(0, 0, 1, du.velocity, 0F);
            bullet.damage = 10;
            g.invulnerableTime = 0;
            float before = g.getHealth();
            bullet.tick();
            check("model: NTM bullet " + (i == 0 ? "misses gap" : "hits leg"), i != 0, g.getHealth() < before);
            bullet.discard();
        }
        var beamConfig = new BulletConfig().setBeam().setDamage(10).setOnBeamImpact(BulletConfig.LAMBDA_STANDARD_BEAM_HIT);
        for (int i = 0; i < 2; i++) {
            var start = i == 0 ? gap : leg;
            var beam = new com.hbm.entity.projectile.EntityBulletBeamBase(level, beamConfig, 10);
            beam.setPos(start);
            beam.setRotationsFromVector(new net.minecraft.world.phys.Vec3(0, 0, 1));
            g.invulnerableTime = 0;
            float before = g.getHealth();
            beam.performHitscanExternal(6);
            check("model: NTM beam " + (i == 0 ? "misses gap" : "hits leg"), i != 0, g.getHealth() < before);
            beam.discard();
        }
        g.setPos(origin.add(30, 0, 0));
        check("model: teleport invalidates old pose", true, g.getBoundingBox().clip(gap.add(30, 0, 0), gapEnd.add(30, 0, 0)).isPresent());
        ModelHits.clear(level);
        check("model: no fresh pose keeps vanilla debug box", true, ModelHits.outlines(g).isEmpty());
        check("model: missing pose uses original hitbox", true, g.getBoundingBox().clip(gap.add(30, 0, 0), gapEnd.add(30, 0, 0)).isPresent());
        g.discard();
        shooter.discard();
        maskmanModelHits();
        surfaceModelHits();
        retainedModelHits(net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND));
        retainedModelHits(com.hbm.entity.ModEntities.RAD_BEAST.get().create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND));
    }

    private static void maskmanModelHits() {
        var maskman = com.hbm.entity.ModEntities.MASK_MAN.get().create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        maskman.setPos(35, 170, 35);
        maskman.setNoAi(true);
        maskman.setNoGravity(true);
        maskman.yBodyRot = 0;
        level.addFreshEntity(maskman);
        var origin = maskman.position();
        // Контрольный снимок: узкое тело и рука за пределами исходной коробки шириной 2.
        ModelHits.put(level, maskman, 0, java.util.List.of(
                new ModelBox("body", -.4, 1, -.2, .8, 0, 0, 0, 3, 0, 0, 0, .4),
                new ModelBox("arm", 1.1, 2, -.1, .3, 0, 0, 0, 1, 0, 0, 0, .2)));
        var empty = origin.add(.7, 2.5, -3);
        check("maskman: no invisible 0.3 expansion beside body", true,
                maskman.getBoundingBox().inflate(.3).clip(empty, empty.add(0, 0, 6)).isEmpty());
        var arm = origin.add(1.25, 2.5, -3);
        check("maskman: arm beyond vanilla width is hit", true,
                maskman.getBoundingBox().clip(arm, arm.add(0, 0, 6)).isPresent());
        check("maskman: arrow query finds protruding arm", true,
                net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, (net.minecraft.world.entity.Entity) null,
                        arm, arm.add(0, 0, 6), new net.minecraft.world.phys.AABB(arm, arm.add(0, 0, 6)).inflate(.01),
                        e -> e == maskman, .3F) != null);
        check("maskman: debug uses model parts", 2, ModelHits.outlines(maskman).size());
        maskman.discard();
    }

    private static void surfaceModelHits() {
        var mesh = new ModelMesh(new float[] {
                0,0,0, 0,1,0, 1,0,0, 0,0,0, 1,0,0, 0,0,1,
                0,0,0, 0,0,1, 0,1,0, 1,0,0, 0,1,0, 0,0,1});
        new ModelMeshPayload(mesh.id(), mesh.triangles()).receive();
        check("mesh: server registers geometry blueprint", true, ModelMesh.get(mesh.id()) != null);
        var target = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        target.setPos(50, 175, 50); target.setNoAi(true); target.setNoGravity(true); target.yBodyRot = 0;
        level.addFreshEntity(target);
        var origin = target.position();
        ModelHits.put(level, target, 0, java.util.List.of(new ModelBox("mesh", 0,0,0, 1,0,0, 0,1,0, 0,0,1).withMesh(mesh.id())));
        check("mesh: debug draws six surface edges", 12, ModelHits.outlines(target).getFirst().length);
        var config = new BulletConfig().setBeam().setDamage(10).setOnBeamImpact(BulletConfig.LAMBDA_STANDARD_BEAM_HIT);
        for (int i = 0; i < 2; i++) {
            double xy = i == 0 ? .8 : .1;
            var start = origin.add(xy, xy, -3);
            var arrow = net.minecraft.world.entity.EntityTypes.ARROW.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            arrow.setPos(start); arrow.shoot(0, 0, 1, 6, 0); target.invulnerableTime = 0;
            float before = target.getHealth();
            arrow.tick();
            check("mesh: real arrow " + (i == 0 ? "misses empty corner" : "hits real surface"), i != 0, target.getHealth() < before);
            arrow.discard();
            var beam = new com.hbm.entity.projectile.EntityBulletBeamBase(level, config, 10);
            beam.setPos(start); beam.setRotationsFromVector(new net.minecraft.world.phys.Vec3(0,0,1));
            target.invulnerableTime = 0; before = target.getHealth();
            beam.performHitscanExternal(6);
            check("mesh: NTM beam " + (i == 0 ? "misses empty corner" : "hits real surface"), i != 0, target.getHealth() < before);
            beam.discard();
        }
        target.discard();
    }

    private static void retainedModelHits(net.minecraft.world.entity.LivingEntity target) {
        target.setPos(65, 180, 65); target.setNoGravity(true);
        if (target instanceof net.minecraft.world.entity.Mob mob) mob.setNoAi(true);
        target.yBodyRot = 0;
        level.addFreshEntity(target);
        String name = "retained " + BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        var boxes = java.util.List.of(
                new ModelBox("left", -.3,0,-.1, .2,0,0, 0,1,0, 0,0,.2),
                new ModelBox("right", .1,0,-.1, .2,0,0, 0,1,0, 0,0,.2),
                new ModelBox("wing", 2,.5,-.1, .2,0,0, 0,.2,0, 0,0,.2));
        var other = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        other.setPos(70,180,70); other.yBodyRot = 0;
        level.addFreshEntity(other);
        for (int age : new int[] {6, 18}) {
            ModelHits.putAt(level, target, 0, boxes, level.getGameTime() - age);
            ModelHits.put(level, other, 0, boxes);
            check(name + ": render gap " + age + " ticks keeps outlines", 3, ModelHits.outlines(target).size());
            var start = target.position().add(0,.5,-3);
            check(name + ": render gap " + age + " ticks keeps model miss", true,
                    target.getBoundingBox().clip(start, start.add(0,0,6)).isEmpty());
        }
        for (int i = 0; i < 25; i++) {
            target.setPos(target.position().add(.25,0,0));
            ModelHits.tick(level);
        }
        check(name + ": normal movement beyond four blocks keeps model", 3, ModelHits.outlines(target).size());
        var wing = target.position().add(2.1,.6,-3);
        check(name + ": stale pose still supplies protruding candidates", true,
                net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, (net.minecraft.world.entity.Entity) null,
                        wing, wing.add(0,0,6), new net.minecraft.world.phys.AABB(wing,wing.add(0,0,6)).inflate(.01),
                        e -> e == target, .3F) != null);
        target.yBodyRot = 135;
        check(name + ": large body turn keeps outlines", 3, ModelHits.outlines(target).size());
        var from = new net.minecraft.world.phys.Vec3(0,.5,-3).yRot((float) Math.toRadians(-135)).add(target.position());
        var to = new net.minecraft.world.phys.Vec3(0,.5,3).yRot((float) Math.toRadians(-135)).add(target.position());
        check(name + ": large body turn preserves real gap", true, target.getBoundingBox().clip(from,to).isEmpty());
        target.setPos(target.position().add(30,0,0));
        ModelHits.tick(level);
        check(name + ": teleport removes retained model", true, ModelHits.outlines(target).isEmpty());
        ModelHits.put(level,target,target.yBodyRot,boxes);
        target.setPose(net.minecraft.world.entity.Pose.CROUCHING);
        ModelHits.tick(level);
        target.setPose(net.minecraft.world.entity.Pose.STANDING);
        check(name + ": posture change cannot revive old geometry", true, ModelHits.outlines(target).isEmpty());
        target.discard(); other.discard();
        ModelHits.tick(level);
    }

    // what a bullet should do at the block: fly on untouched, go through with less damage, stop in it, or any of the
    // last two (it met the block)
    private static Path machineSettings() {
        return FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
    }

    // a chemical plant's block with the faces its client would send: a window (glass face) at z = .5 facing the
    // bullet, maybe its casing at z = .8 behind it; a grate: a casing face with a clear pixel where the bullet goes
    private static void machineGlass(long[] coarse, Path settings) throws Exception {
        String plant = "hbm:machine_chemical_plant[facing=north]";
        BlockState plantState = state(plant);
        String key = Structures.key(plantState, 0, 0, 0);
        double[] window = {.1, .1, .5, .1, .9, .5, .9, .1, .5}, casing = {.1, .1, .8, .1, .9, .8, .9, .1, .8};
        ModelSurface.Face glass = new ModelSurface.Face(window, new double[6], 1, 1, new long[] {1}, true);
        ModelSurface.Face wall = new ModelSurface.Face(casing, new double[6], 1, 1, new long[] {1});
        PassableClip.putShapes(java.util.Map.of(key, ModelSurface.attach(coarse, List.of(glass))));
        Shot s = expect("chemplant window: pierced as glass", Want.PIERCE, single(plant), .2, .2, 30F);
        check("chemplant window: loses as a pixel of glass", true, s.damage > 29F);
        expect("chemplant window: a weak round goes through too", Want.PIERCE, single(plant), .2, .2, 2F);

        PassableClip.putShapes(java.util.Map.of(key, ModelSurface.attach(coarse, List.of(glass, wall))));
        s = shoot(single(plant), .2, .2, 30F);
        check("chemplant window, casing behind: the casing stops it", false, s.alive());
        check("chemplant window, casing behind: the glass took its share first", true, s.damage < 30F);

        // a grate: 2 x 2 pixels, the one at the bottom left clear, no glass face over it
        ModelSurface.Face grate = new ModelSurface.Face(window, new double[] {0, 0, 0, 2, 2, 0}, 2, 2, new long[] {0b1110});
        PassableClip.putShapes(java.util.Map.of(key, ModelSurface.attach(coarse, List.of(grate))));
        expect("machine grate: through its clear pixel", Want.PASS, single(plant), .2, .2, 30F);
        expect("machine grate: its bar stops it", Want.STOP, single(plant), .6, .2, 30F);

        // bulletMachineGlass = false: the window is air again
        PassableClip.putShapes(java.util.Map.of(key, ModelSurface.attach(coarse, List.of(glass))));
        Files.writeString(settings, MachineGlass.KEY + " = false\n");
        Thread.sleep(2100);
        expect("chemplant window, bulletMachineGlass off: flies through", Want.PASS, single(plant), .2, .2, 30F);
        Files.writeString(settings, BulletRules.MACHINES_KEY + " = false\n");
        Thread.sleep(2100);
    }

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
        java.util.Map<BlockPos, BlockState> before = around(at);
        Object wear = BulletServer.wearNow();
        Shot s = shoot(at, fx, fy, damage);
        // the same shot as an NTM beam of the same damage on the blocks as they were; then back to what the bullet left
        java.util.Map<BlockPos, BlockState> left = around(at);
        Object worn = BulletServer.wearNow();
        put(before);
        BulletServer.wearBack(wear);
        beam(name, want, at, fx, fy, damage);
        put(before);
        BulletServer.wearBack(wear);
        reachRow(name, want, at, fx, fy, damage);
        put(left);
        BulletServer.wearBack(worn);
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

    // the blocks around a lane's block (its own, a fence row's, what is set behind it)
    private static java.util.Map<BlockPos, BlockState> around(BlockPos at) {
        java.util.Map<BlockPos, BlockState> m = new java.util.HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 3))) m.put(p.immutable(), level.getBlockState(p));
        return m;
    }

    private static void put(java.util.Map<BlockPos, BlockState> m) {
        m.forEach((p, st) -> {
            if (level.getBlockState(p) != st) level.setBlock(p, st, Block.UPDATE_CLIENTS);
        });
    }

    // a beam of the round's damage, threshold negation and armour piercing (BeamServer) along the bullet's lane: the
    // same verdict as the bullet's (its end beyond the block or at it, its damage there). beamRules off: NTM's beam,
    // stopped by the first block with a box
    private static void beam(String name, Want want, BlockPos at, double fx, double fy, float damage) {
        float[] b = beamShot(at, fx, fy, damage);
        boolean beyond = b[0] > 1;
        float left = b[1];
        boolean pass = switch (want) {
            case PASS -> beyond && left == damage;
            case PIERCE -> beyond && left < damage;
            case STOP -> !beyond && left == damage;
            case MEET -> !beyond || left < damage;
        };
        ok &= pass;
        lines.add((pass ? "OK   " : "FAIL ") + name + ", beam: " + want.name().toLowerCase()
                + (pass ? "" : ", got end z " + String.format("%.2f", b[0]) + ", damage " + left));
    }

    // the same for a beam with no entity of its own (a turret's, a Tesla arc: BeamServer.reach) of the round's damage,
    // threshold negation and armour piercing: stopped on the way (-1) or the damage it has 6 blocks on
    private static void reachRow(String name, Want want, BlockPos at, double fx, double fy, float damage) {
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(at.getX() + fx, at.getY() + fy, at.getZ() - 1.5);
        float left = BeamServer.reach(level, null, from, from.add(0, 0, 6), damage, round, true, null);
        boolean beyond = left >= 0F;
        boolean pass = switch (want) {
            case PASS -> beyond && left == damage;
            case PIERCE -> beyond && left < damage;
            case STOP -> !beyond;
            case MEET -> !beyond || left < damage;
        };
        ok &= pass;
        lines.add((pass ? "OK   " : "FAIL ") + name + ", turret beam: " + want.name().toLowerCase()
                + (pass ? "" : ", got " + (beyond ? "damage " + left : "stopped")));
    }

    // a beam from 1.5 blocks before the block along +z at (fx, fy) of it, 6 blocks long: where it ends (z from the
    // block's near face) and its damage there
    private static float[] beamShot(BlockPos at, double fx, double fy, float damage) {
        if (beamRound == null) beamRound = new BulletConfig().setBeam().setOnBeamImpact(BulletConfig.LAMBDA_STANDARD_BEAM_HIT);
        beamRound.armorThresholdNegation = round.armorThresholdNegation;
        beamRound.armorPiercingPercent = round.armorPiercingPercent;
        var beam = new com.hbm.entity.projectile.EntityBulletBeamBase(level, beamRound, damage);
        net.minecraft.world.phys.Vec3 start = new net.minecraft.world.phys.Vec3(at.getX() + fx, at.getY() + fy, at.getZ() - 1.5);
        beam.setPos(start);
        beam.setRotationsFromVector(new net.minecraft.world.phys.Vec3(0, 0, 1));
        beam.performHitscanExternal(6);
        double end = start.z + beam.getBeamLength();
        float left = BeamServer.damageAt(beam, new net.minecraft.world.phys.Vec3(start.x, start.y, end));
        beam.discard();
        return new float[] {(float) (end - at.getZ()), left};
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

    // a beam of NTM's round `cfg` from 1.5 blocks before the block along +z at (fx, fy) of it, `length` blocks long
    private static com.hbm.entity.projectile.EntityBulletBeamBase beamAt(BulletConfig cfg, BlockPos at, double fx, double fy, float damage, double length) {
        var beam = new com.hbm.entity.projectile.EntityBulletBeamBase(level, cfg, damage);
        beam.setPos(new net.minecraft.world.phys.Vec3(at.getX() + fx, at.getY() + fy, at.getZ() - 1.5));
        beam.setRotationsFromVector(new net.minecraft.world.phys.Vec3(0, 0, 1));
        beam.performHitscanExternal(length);
        beam.discard();
        return beam;
    }

    // a fresh zombie (no AI) on a lane, `n` blocks of `wall` (null: none; minecraft:water: still water) right before it
    // at the beam's height, one beam of `cfg` and `damage` at it 1.2 over its feet from 1.5 blocks before them: {the
    // health it lost, how long the beam is, 1 if the blocks are left whole and unworn else 0, NTM's fire on it}
    private static float[] zombieBeam(BulletConfig cfg, float damage, @org.jspecify.annotations.Nullable String wall, int n) {
        BlockPos at = ownLane(8);
        boolean water = "minecraft:water".equals(wall);
        for (int i = 1; i <= n && wall != null; i++)
            level.setBlock(at.north(i).above(), water ? net.minecraft.world.level.block.Blocks.WATER.defaultBlockState() : state(wall), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        var z = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        z.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        z.setNoAi(true);
        z.setNoGravity(true);
        level.addFreshEntity(z);
        float before = z.getHealth();
        Object wear = BulletServer.wearNow();
        var beam = beamAt(cfg, at.north(n), .5, 1.2, damage, n + 4);
        boolean whole = wear.equals(BulletServer.wearNow());
        for (int i = 1; i <= n && wall != null; i++) whole &= level.getBlockState(at.north(i).above()).equals(water ? net.minecraft.world.level.block.Blocks.WATER.defaultBlockState() : state(wall));
        BulletServer.wearBack(wear);
        float took = before - z.getHealth();
        float fire = com.hbm.extprop.HbmLivingProps.getData(z).fire;
        z.discard();
        // air, not removeBlock: that leaves water where water was
        for (int i = 1; i <= n; i++) level.setBlock(at.north(i).above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return new float[] {took, (float) beam.getBeamLength(), whole ? 1F : 0F, fire};
    }

    // a fresh zombie (no AI) on a lane, `wall` (null: none) right before it at 1.2 over its feet, one bullet of `cfg`
    // and `damage` at that height from 2.5 blocks before it, ticked till it is gone (20 ticks at most): {the health it
    // lost, 1 if the block is left whole and unworn else 0}
    private static float[] zombieShot(BulletConfig cfg, float damage, @org.jspecify.annotations.Nullable String wall) {
        BlockPos at = ownLane(8);
        if (wall != null) level.setBlock(at.north().above(), state(wall), Block.UPDATE_CLIENTS);
        var z = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        z.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        z.setNoAi(true);
        z.setNoGravity(true);
        level.addFreshEntity(z);
        float before = z.getHealth();
        Object wear = BulletServer.wearNow();
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, cfg, 1F, 0F, 0F, 0F);
        b.setPos(at.getX() + .5, at.getY() + 1.2, at.getZ() - 2.5);
        b.setThrowableHeading(0, 0, 1, Math.max(cfg.velocity, 1F), 0F);
        b.damage = damage;
        level.addFreshEntity(b);
        for (int i = 0; i < 20 && !b.isRemoved(); i++) b.tick();
        b.discard();
        boolean whole = wear.equals(BulletServer.wearNow()) && (wall == null || level.getBlockState(at.north().above()).equals(state(wall)));
        BulletServer.wearBack(wear);
        float took = before - z.getHealth();
        z.discard();
        level.setBlock(at.north().above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return new float[] {took, whole ? 1F : 0F};
    }

    // a beam with no entity of its own (a turret's) of 30 along a lane through `n` blocks of still water and 2 of air:
    // the damage it has beyond them, or -1 when it is gone in them
    private static float waterReach(int n) {
        BlockPos at = ownLane(0);
        for (int i = 0; i < n; i++) level.setBlock(at.south(i), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(at.getX() + .5, at.getY() + .5, at.getZ() - 1.5);
        float left = BeamServer.reach(level, null, from, from.add(0, 0, n + 3.5), 30F, null, true, null);
        for (int i = 0; i < n; i++) level.setBlock(at.south(i), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return left;
    }

    // the health a fresh iron golem (no AI) loses to an NTM beam of 30 at its middle, with a plank before it or not
    private static float golemBeam(boolean plank) {
        BlockPos at = new BlockPos(2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z);
        lane++;
        // the beam goes 1.2 over the golem's feet: through the upper of two planks
        if (plank) for (int dy = 0; dy < 2; dy++) level.setBlock(at.north(2).above(dy), state("minecraft:oak_planks"), Block.UPDATE_CLIENTS);
        var g = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        g.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        g.setNoAi(true);
        g.setNoGravity(true);
        level.addFreshEntity(g);
        float before = g.getHealth();
        beamShot(at.north(3), .5, 1.2, 30F);
        float took = before - g.getHealth();
        g.discard();
        return took;
    }

    // a golem 7 blocks along +z from a lane's spot, with a 3x3 wall of `wall` 3 blocks along (null: none)
    private static net.minecraft.world.entity.LivingEntity range(BlockPos at, net.minecraft.world.phys.Vec3 from, @org.jspecify.annotations.Nullable String wall) {
        BlockPos w = BlockPos.containing(from.x, at.getY(), from.z + 3);
        if (wall != null) for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 2; dy++) level.setBlock(w.offset(dx, dy, 0), state(wall), Block.UPDATE_CLIENTS);
        var g = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        g.setPos(from.x, at.getY(), from.z + 7);
        g.setNoAi(true);
        g.setNoGravity(true);
        level.addFreshEntity(g);
        return g;
    }

    // a beam turret on a lane (its core alone, on, with power and a tau round) and a golem behind `wall`: {1 if it sees
    // the golem, the health the golem lost to one shot at it}
    private static float[] turret(String id, @org.jspecify.annotations.Nullable String wall) {
        BlockPos at = new BlockPos(2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z);
        lane++;
        level.setBlock(at, state(id), Block.UPDATE_CLIENTS);
        var t = (com.hbm.blockentity.turret.BlockEntityTurretBaseNT) level.getBlockEntity(at);
        t.isOn = true;
        t.setPower(Long.MAX_VALUE / 4);
        t.setItem(1, com.hbm.items.weapon.sedna.factory.XFactoryAccelerator.tau_uranium.ammoStack().copyWithCount(64));
        var g = range(at, t.getTurretPos(), wall);
        float sees = t.entityInLOS(g) ? 1F : 0F;
        t.target = g;
        float before = g.getHealth();
        for (int i = 0; i < 5; i++) t.updateFiringTick();
        float took = before - g.getHealth();
        g.discard();
        level.removeBlock(at, false);
        return new float[] {sees, took};
    }

    // the health a golem behind `wall` loses to one Tesla arc (NTM's BlockEntityTesla.zap from a lane's spot, reach 10;
    // coil: from a Tesla coil on the lane, 1.75 over its block, as the coil zaps)
    private static float arc(@org.jspecify.annotations.Nullable String wall, boolean coil) {
        BlockPos at = new BlockPos(2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z);
        lane++;
        if (coil) level.setBlock(at, state("hbm:tesla"), Block.UPDATE_CLIENTS);
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(at.getX() + .5, at.getY() + (coil ? 1.75 : 1.35), at.getZ() + .5);
        var g = range(at, from, wall);
        float before = g.getHealth();
        com.hbm.blockentity.machine.BlockEntityTesla.zap(level, from.x, from.y, from.z, 10.0, null);
        float took = before - g.getHealth();
        g.discard();
        return took;
    }

    // a beam with no entity of its own along a lane through `n` glass blocks in a row, all of them its shooter's own
    // (gone through as air, each a step of its way), only looked at: the damage it has beyond them, or -1 when stopped
    private static float glassRow(int n) {
        BlockPos at = new BlockPos(2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z);
        lane++;
        for (int i = 0; i < n; i++) level.setBlock(at.south(i), state("minecraft:glass"), Block.UPDATE_CLIENTS);
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(at.getX() + .5, at.getY() + .5, at.getZ() - 1.5);
        float left = BeamServer.reach(level, null, from, from.add(0, 0, n + 3), 30F, null, false, p -> true);
        for (int i = 0; i < n; i++) level.removeBlock(at.south(i), false);
        return left;
    }

    // the shot (blocks worn) along a lane of STEPS glass blocks of its shooter's own, then air, a hay block (bullets fly
    // through it and wear it, RULES) and one more glass: it stops at the last of its STEPS, and the hay past them is left
    // unworn (a look that starts in the hay's cell would not count it)
    private static boolean wearPast() {
        BlockPos at = new BlockPos(2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z);
        lane++;
        int n = BeamServer.STEPS;
        for (int i = 0; i < n; i++) level.setBlock(at.south(i), state("minecraft:glass"), Block.UPDATE_CLIENTS);
        level.setBlock(at.south(n + 1), state("minecraft:hay_block"), Block.UPDATE_CLIENTS);
        level.setBlock(at.south(n + 2), state("minecraft:glass"), Block.UPDATE_CLIENTS);
        Object wear = BulletServer.wearNow();
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(at.getX() + .5, at.getY() + .5, at.getZ() - 1.5);
        float left = BeamServer.reach(level, null, from, from.add(0, 0, n + 5), 30F, null, true, p -> true);
        boolean unworn = wear.equals(BulletServer.wearNow());
        BulletServer.wearBack(wear);
        for (int i = 0; i < n + 3; i++) level.removeBlock(at.south(i), false);
        return left == -1F && unworn;
    }

    // a golem with glass right behind it at the beam's height, two beams of 6 at it (two would break glass): the glass
    private static String golemGlass() {
        BlockPos at = new BlockPos(2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z);
        lane++;
        BlockPos glass = at.south().above();
        level.setBlock(glass, state("minecraft:glass"), Block.UPDATE_CLIENTS);
        var g = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        g.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        g.setNoAi(true);
        g.setNoGravity(true);
        level.addFreshEntity(g);
        for (int i = 0; i < 2; i++) beamShot(at.north(3), .5, 1.2, 6F);
        g.discard();
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(glass).getBlock()).toString();
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
    static BulletConfig pick() {
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
