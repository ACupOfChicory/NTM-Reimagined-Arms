package acup.gunfeel;

import net.minecraft.util.math.Vec3d;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityThrowableNT;
import com.hbm.items.weapon.sedna.BulletConfig;
import net.minecraft.util.math.RayTraceResult;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

// port: 26.2's BulletCheck (0762086) on a 1.7.10 dedicated server (runServer -PcheckBullets): the same lanes, rounds
// and checks where 1.7.10 has the blocks; fences, gates and walls go by 26.2's family cells (FenceCells); the 26.2
// blocks with other model cells or masks (wooden structures, the bunker door's grille, copper grates) are left out or
// marked DIFF: 1.7.10 has no such cells yet and meets them by their collision boxes. Model hitboxes of mobs are the hitboxes' check. Added: a path past 200 cells in one
// tick (1.7.10's own ray stops there) and a point-blank shot.
// -Dntmgunfeel.checkBullets=<file>: on the first server tick the server puts blocks high in the air, each in a lane of
// its own, and fires a real NTM bullet (EntityBulletBaseMK4, no shooter) along the lane at a spot of the block. The
// bullet is ticked by hand on the server thread, so the mod's mixins and BulletServer run as in a game. Then: is the
// bullet still flying, with how much damage, and is the block still there. One line per check goes to the file (OK,
// FAIL, or DIFF: as 1.7.10 has it, not as 26.2) and the server stops.
public final class BulletCheck {
    public static final String PROPERTY = "ntmgunfeel.checkBullets";

    private BulletCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.trim().isEmpty()) return;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new BulletCheck.Once(file));
    }

    public static final class Once {
        private final String file;
        private boolean done;

        Once(String file) {
            this.file = file;
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent e) {
            if (done || e.phase != TickEvent.Phase.END) return;
            done = true;
            List<String> out = new ArrayList<String>();
            try {
                run(out, net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance());
            } catch (Throwable t) {
                out.add("FAIL crashed: " + t);
                for (StackTraceElement s : t.getStackTrace()) out.add("    at " + s);
                ok = false;
            }
            try {
                Path f = Paths.get(file);
                if (f.toAbsolutePath().getParent() != null) Files.createDirectories(f.toAbsolutePath().getParent());
                Files.write(f, out, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                GunFeel.LOGGER.warn("Could not write the bullet check", ex);
            }
            for (String l : out) GunFeel.LOGGER.info("[bulletcheck] {}", l);
            net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().initiateShutdown();
        }
    }

    // lines in the server's own rules file, to see that path rules change what bullets do
    static final String RULES = "path minecraft:stone through\n";

    private static final int Y = 100, Z = 8; // the blocks; bullets start 1.5 blocks before them and fly +z
    private static List<String> lines;
    private static boolean ok;
    private static WorldServer level;
    private static BulletConfig round;
    private static int lane;

    private static void run(List<String> out, MinecraftServer server) throws Exception {
        lines = out;
        ok = true;
        level = server.getWorld(0);
        for (int cx = -1; cx < 6; cx++) for (int cz = -1; cz < 20; cz++) level.getChunkProvider().provideChunk(cx, cz);
        level.getGameRules().setOrCreateGameRule("mobGriefing", "true");
        clearFar();
        Files.write(PortPaths.config().resolve(BulletRules.FILE), RULES.getBytes(StandardCharsets.UTF_8));
        Files.write(PortPaths.config().resolve(ReaimTimes.FILE), new byte[0]);
        Thread.sleep(2100); // the files are looked at once a second
        handlers();
        round = pick();
        if (round == null) {
            fail("no plain NTM bullet to fire");
            return;
        }
        lines.add("     round " + round.id + " (" + round.ammo + "): velocity " + round.velocity + ", gravity " + round.gravity + ", ricochet below "
                + round.ricochetAngle + " degrees");
        aim();

        // DU Mr. Deuce: one sheet goes through, a second does not refill what ignores the threshold
        BulletConfig plainRound = round;
        round = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        // port: NTM 1.7.10's steel wall is 2/16 thick at the side of its meta (2: the south side, as 26.2's facing=north)
        expect("Mr Deuce DU: one steel panel", Want.PIERCE, single("hbm:steel_wall", 2), .5, .5, 11.25F, null);
        int[] panels = single("hbm:steel_wall", 2);
        Mc.setBlock(level, panels[0], panels[1], panels[2] + 1, block("hbm:steel_wall"), 2, 2);
        Shot secondPanel = shoot(panels, .5, .5, 11.25F);
        check("Mr Deuce DU: second steel panel holds", false, secondPanel.alive);
        expect("Mr Deuce DU: one metre of wired glass just passes", Want.PIERCE, single("hbm:reinforced_glass", 0), .5, .5, 11.25F, null);
        round = plainRound;

        // ---- the cargo elevator's parts named none by the server's rules still count as the machine (26.2: they once
        // got round the ban on piercing machines); in 1.7.10 a part is a dummy cell (meta 2) or the core (meta 12)
        Path machineSettings = PortPaths.config().resolve(ReaimTimes.FILE);
        Path bulletRules = PortPaths.config().resolve(BulletRules.FILE);
        Files.write(bulletRules, (RULES + "material hbm:cargo_elevator none\n"
                + "break hbm:cargo_elevator damage 1000000 pass pierce=0.001 keep=1\n").getBytes(StandardCharsets.UTF_8));
        for (boolean enabled : new boolean[] {false, true}) {
            Files.write(machineSettings, (BulletRules.MACHINES_KEY + " = " + enabled + "\n").getBytes(StandardCharsets.UTF_8));
            Thread.sleep(2100);
            for (String part : new String[] {"core", "cell"}) {
                int[] at = single("hbm:cargo_elevator", part.equals("core") ? 12 : 2);
                BlockRay.Hit in = new BlockRay.Hit(at[0], at[1], at[2], 2, at[0] + .5, at[1] + .5, at[2], false);
                BlockRay.Hit exit = new BlockRay.Hit(at[0], at[1], at[2], 3, at[0] + .5, at[1] + .5, at[2] + .125, false);
                check("cargo " + part + ": machine piercing " + enabled, enabled, PassableClip.pierced(level, in, exit, 1000F, round) >= 1F);
            }
        }
        Files.write(machineSettings, (BulletRules.MACHINES_KEY + " = false\n").getBytes(StandardCharsets.UTF_8));
        Files.write(bulletRules, RULES.getBytes(StandardCharsets.UTF_8));
        Thread.sleep(2100);

        // ---- a machine's window (MachineGlass: clear pixels of a chemical plant's model) is glass: a bullet pierces it,
        // losing a little, and the casing behind it holds; a grate (clear pixels of a texture listed as empty, no glass
        // there) still lets it through. The cells as ShapesScenario would make them, put in for the check.
        machineGlass(machineSettings);

        // ---- the cargo elevator's platform (ElevatorMesh): where the elevator has it now, not in its cells
        elevatorPlatform();

        // ---- fences, gates, walls: their posts and rails (26.2's family cells, FenceCells), not their collision boxes
        String cells = null;
        expect("fence: between the rails", Want.PASS, fenceRow(), .25, .65, 30F, cells);
        expect("fence: under the lower rail", Want.PASS, fenceRow(), .25, .2, 30F, cells);
        expect("fence: the post", Want.PIERCE, fenceRow(), .5, .5, 30F, null);
        expect("fence: the upper rail", Want.PIERCE, fenceRow(), .25, .8, 30F, null);
        expect("fence: thin rail, a very weak bullet stops", Want.STOP, fenceRow(), .25, .8, 1.2F, null);
        expect("gate closed: between the bars", Want.PASS, single("minecraft:fence_gate", 0), .25, .65, 30F, cells);
        expect("gate closed: under the bars", Want.PASS, single("minecraft:fence_gate", 0), .25, .2, 30F, cells);
        expect("gate closed: a bar", Want.PIERCE, single("minecraft:fence_gate", 0), .25, .8, 30F, null);
        expect("gate closed: the middle", Want.PIERCE, single("minecraft:fence_gate", 0), .5, .5, 30F, null);
        // open, facing south or north (meta 4 | 0): the gate's collision is gone
        expect("gate open: the middle", Want.PASS, single("minecraft:fence_gate", 4), .5, .5, 30F, null);
        expect("wall low: over it", Want.PASS, wallRow(), .5, .95, 30F, cells);
        expect("wall low: into it", Want.MEET, wallRow(), .5, .5, 30F, null);
        expect("wall post: beside it", Want.PASS, single("minecraft:cobblestone_wall", 0), .1, .5, 30F, null);
        expect("wall post: into it", Want.MEET, single("minecraft:cobblestone_wall", 0), .5, .5, 30F, null);

        // ---- blocks met by their drawn model (ModelCells): through the clear pixels, into the drawn ones. 26.2 has
        // these by its masks and model cells (its own BulletCheck has no such lanes)
        float px = 1 / 16F;
        expect("iron bars alone: between the bars", Want.PASS, single("minecraft:iron_bars", 0), 5.5 * px, 5.5 * px, 30F, null);
        // port: 1.12.2 draws lone bars as a post in the middle (pixels 7-9), as 26.2 does; 1.7.10 drew a cross of bars
        expect("iron bars alone: the post", Want.MEET, single("minecraft:iron_bars", 0), .5, .5, 30F, null);
        expect("ladder: between the rungs", Want.PASS, single("minecraft:ladder", 2), .5, 7.5 * px, 30F, null);
        expect("ladder: a rung", Want.MEET, single("minecraft:ladder", 2), .5, 5.5 * px, 30F, null);
        expect("chain-link fence: through the mesh", Want.PASS, row("hbm:fence_metal", 0), 1.5 * px, 14.5 * px, 30F, null);
        expect("chain-link fence: a wire, pierced", Want.PIERCE, row("hbm:fence_metal", 0), 8.5 * px, 15.5 * px, 30F, null);
        // a wooden door's top, closed across z (lower meta 1): its two windows, its frame
        expect("wooden door: through the window", Want.PASS, doorTop("minecraft:wooden_door", 1), 4.5 * px, 11.5 * px, 30F, null);
        expect("wooden door: the frame", Want.MEET, doorTop("minecraft:wooden_door", 1), .5, 14.5 * px, 30F, null);
        // NTM's bunker door, closed: a steel leaf 3 px thick, a grille of 1-pixel holes in its top half (pixel rows 6-8
        // from the bottom, in a chequer). A hole is flown through untouched, the steel round it holds a plain round and
        // lets a heavy one through. Seen from -z the 1.7.10 texture is mirrored against 26.2's: the hole one pixel over
        expect("bunker door: a grille hole", Want.PASS, doorTop("hbm:door_bunker", 1), 5.5 * px, 8.5 * px, 60F, null);
        expect("bunker door: the steel beside the hole", Want.STOP, doorTop("hbm:door_bunker", 1), 4.5 * px, 8.5 * px, 60F, null);
        expect("bunker door: 150 damage goes through the steel", Want.PIERCE, doorTop("hbm:door_bunker", 1), .5, .2, 150F, null);

        // ---- materials: a plank is pierced with the rule's loss, concrete holds
        int[] at = single("minecraft:planks", 0);
        BulletRules.Break rule = BulletRules.breakRule(level, at[0], at[1], at[2]);
        float left = rule == null ? -2F : rule.pierced(30F, PassableClip.hardness(level, Blocks.PLANKS, at[0], at[1], at[2]), 1.0);
        Shot s = expect("plank: pierced", Want.PIERCE, at, .5, .5, 30F, null);
        check("plank: damage after it, by its rule", left, s.damage);
        s = expect("concrete: stops in it", Want.STOP, single("hbm:concrete_smooth", 0), .5, .5, 30F, null);
        check("concrete: still there", "hbm:concrete_smooth", s.after);
        expect("stone bricks: stops in them", Want.STOP, single("minecraft:stonebrick", 0), .5, .5, 30F, null);
        expect("stone slab, along it: a rifle round stops", Want.STOP, single("minecraft:stone_slab", 0), .5, .25, 30F, null);
        expect("sandbags: a rifle round stops", Want.STOP, single("hbm:sandbags", 0), .5, .5, 37.5F, null);
        expect("sandbags: a .50 AP-like round goes through", Want.PIERCE, single("hbm:sandbags", 0), .5, .5, 90F, null);
        // doors, closed, lower half, meta 3: the leaf at the south side, 3/16 thick
        expect("iron door: a plain round stops", Want.STOP, single("minecraft:iron_door", 3), .5, .5, 30F, null);
        Shot door = expect("oak door: a pellet goes through", Want.PIERCE, single("minecraft:wooden_door", 3), .5, .5, 8F, null);
        Shot planks = expect("oak planks: a pellet goes through", Want.PIERCE, single("minecraft:planks", 0), .5, .5, 8F, null);
        check("oak door keeps more than a block of planks", true, door.damage > planks.damage + 2F);

        // ---- glass: worn by the damage of the bullets through it, broken at 10
        Object[][] glasses = {{"minecraft:glass", 0}, {"minecraft:glass_pane", 0}, {"minecraft:stained_glass", 14}};
        for (Object[] g : glasses) {
            String id = (String) g[0];
            int[] p = single(id, (Integer) g[1]);
            s = expect(id + ": 6 damage goes through", Want.PIERCE, p, .5, .5, 6F, null);
            check(id + ": 6 damage leaves it", id, s.after);
            s = shoot(p, .5, .5, 6F);
            check(id + ": 6 more break it", "minecraft:air", s.after);
            check(id + ": the second bullet flies on", true, s.alive);
            s = shoot(single(id, (Integer) g[1]), .5, .5, 12F);
            check(id + ": 12 at once break it", "minecraft:air", s.after);
        }

        // ---- wear with a threshold: sandbags tear only by what passes 4 damage a bullet (600 in all)
        int[] bags = single("hbm:sandbags", 0);
        for (int i = 0; i < 160; i++) shoot(bags, .5, .5, 4F);
        check("sandbags: 160 machine-gun-weak rounds of 4 leave them", "hbm:sandbags", after(bags));
        for (int i = 0; i < 17; i++) shoot(bags, .5, .5, 37.5F);
        check("sandbags: 17 rifle rounds leave them", "hbm:sandbags", after(bags));
        shoot(bags, .5, .5, 37.5F);
        check("sandbags: the 18th tears them", "minecraft:air", after(bags));

        // ---- concrete breaks only with bulletBreakConcrete (550 x its hardness, above 20 a bullet)
        int[] hard = single("hbm:concrete_smooth", 0);
        for (int i = 0; i < 12; i++) shoot(hard, .5, .5, 120F);
        check("concrete, switch off: 12 heavy rounds leave it", "hbm:concrete_smooth", after(hard));
        Path serverFile = PortPaths.config().resolve(ReaimTimes.FILE);
        Files.write(serverFile, (BulletRules.CONCRETE_KEY + " = true\n").getBytes(StandardCharsets.UTF_8));
        Thread.sleep(2100);
        hard = single("hbm:concrete_smooth", 0);
        float need = 550F * PassableClip.hardness(level, block("hbm:concrete_smooth"), hard[0], hard[1], hard[2]);
        int rounds = (int) Math.ceil(need / 100F);
        lines.add("     concrete: " + need + " damage over 20 a round, " + rounds + " rounds of 120");
        for (int i = 0; i < rounds - 1; i++) shoot(hard, .5, .5, 120F);
        check("concrete, switch on: one round short leaves it", "hbm:concrete_smooth", after(hard));
        shoot(hard, .5, .5, 120F);
        check("concrete, switch on: the last breaks it", "minecraft:air", after(hard));
        hard = single("hbm:concrete_smooth", 0);
        for (int i = 0; i < 60; i++) shoot(hard, .5, .5, 18F);
        check("concrete, switch on: 60 rounds of 18 leave it", "hbm:concrete_smooth", after(hard));
        Files.write(serverFile, (BulletRules.CONCRETE_KEY + " = false\n").getBytes(StandardCharsets.UTF_8));
        Thread.sleep(2100);

        // ---- NTM's wooden trench blocks (26.2's WoodBoxes): their boards as drawn, from the shapes table (ModelCells).
        // 1.7.10 has one wood_structure with meta 0 roof, 1 scaffold, 2 ceiling, and wood_barrier by side (meta 2: its
        // panel across z, posts and boards as in 26.2)
        String wood = "hbm:wood_structure";
        expect("wood ceiling: under the boards, between the beams", Want.PASS, single(wood, 2), .5, .905, 30F, null);
        expect("wood ceiling: along a beam", Want.PIERCE, single(wood, 2), .06, .905, 40F, null);
        expect("wood ceiling: across the boards", Want.PIERCE, single(wood, 2), .5, .97, 30F, null);
        expect("wood roof: under the boards, between the rails", Want.PASS, single(wood, 0), .5, .06, 30F, null);
        expect("wood roof: the boards", Want.PIERCE, single(wood, 0), .5, .15, 30F, null);
        expect("wood roof: above it", Want.PASS, single(wood, 0), .5, .5, 30F, null);
        expect("wood scaffold: between the posts, under the rails", Want.PASS, single(wood, 1), .5, .25, 30F, null);
        expect("wood scaffold: the north and south rails", Want.PIERCE, single(wood, 1), .5, .625, 30F, null);
        expect("wood scaffold: two posts", Want.PIERCE, single(wood, 1), .125, .25, 30F, null);
        expect("wood barrier: between its boards", Want.PASS, single("hbm:wood_barrier", 2), .25, .5, 30F, null);
        expect("wood barrier: a board", Want.PIERCE, single("hbm:wood_barrier", 2), .25, .25, 30F, null);
        expect("wood barrier: its post", Want.PIERCE, single("hbm:wood_barrier", 2), .5, .5, 30F, null);
        expect("wood barrier: over the boards, nothing above", Want.PASS, single("hbm:wood_barrier", 2), .25, .97, 30F, null);
        // its neighbours as the renderer sees them (WoodBoxes): a solid block above adds the ceiling boards, one beside it
        // that side's panel
        int[] roofed = single("hbm:wood_barrier", 2);
        Mc.setBlock(level, roofed[0], roofed[1] + 1, roofed[2], Blocks.STONE, 0, 2);
        expect("wood barrier: stone above, its ceiling boards", Want.PIERCE, roofed, .5, .97, 30F, null);
        expect("wood barrier: between the posts, nothing beside", Want.PASS, single("hbm:wood_barrier", 2), .06, .5, 30F, null);
        int[] walled = single("hbm:wood_barrier", 2);
        Mc.setBlock(level, walled[0] - 1, walled[1], walled[2], Blocks.STONE, 0, 2);
        expect("wood barrier: stone west, that side's post", Want.PIERCE, walled, .06, .5, 30F, null);

        // ---- .50 DU into an iron golem in the open: every bullet takes the same, 45 in the body, x1.75 in the head
        // (the mod's one head multiplier for every gun, HeadDamage; on this server no client sends the golem's pose, so
        // the head is NTM 1.7.10's height rule, as ModelHits keeps it without a pose)
        // with the server's test log on: the hit is written with what it took and whether it was in the head
        java.nio.file.Path golemLog = TestLog.set(false, true);
        check("golem: .50 DU in the body takes 45", 45F, golemHit(1.0, 45F));
        check("golem: .50 DU in the head takes 78.75", 78.75F, golemHit(2.4, 45F));
        float[] floor = golemOverFloor(45F);
        check("golem over a floor: the head shot takes 78.75, not what the floor behind left", 78.75F, floor[0]);
        check("golem over a floor: the floor behind it still costs the bullet", true, floor[1] < 45F);
        // port: the trace scenario keeps its test log on (TraceHooks); 26.2 turns it off here
        if (!Trace.ON) TestLog.set(false, false);
        String golemText = golemLog == null ? "" : new String(Files.readAllBytes(golemLog), StandardCharsets.UTF_8);
        check("golem: the test log has the body hit", true, golemText.contains("head=false") && golemText.contains("took=45.000"));
        check("golem: the test log has the head hit", true, golemText.contains("head=true") && golemText.contains("took=78.750"));

        // ---- path rules in the server's file
        s = expect("path through: stone is flown through", Want.PASS, single("minecraft:stone", 0), .5, .5, 30F, null);
        check("path through: the stone stays", "minecraft:stone", s.after);

        // ---- port: 1.7.10's own ray (func_147447_a) gives up after 200 cells: a block 250 blocks away in one tick
        far();
        richterBulletSpeed();
        spread();
        holdAim();
        explosives();
        doors();
        BulletCheckModels.run(level, BulletCheck::check);
        lines.add(ok ? "ALL OK" : "SOME FAILED");
    }

    // the mixins on the server: how many of our handlers each NTM class took
    private static void handlers() {
        for (Class<?> c : new Class<?>[] {EntityBulletBaseMK4.class, EntityThrowableNT.class}) {
            int n = 0;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) if (m.getName().contains("gunfeel$")) n++;
            lines.add("     mixin handlers in " + c.getSimpleName() + ": " + n);
            check(c.getSimpleName() + ": our mixin took", true, n > 0);
        }
    }

    // what far() left in earlier runs (before it took its blocks away): 250 blocks down every lane
    private static void clearFar() {
        for (int i = 0; i < 256; i++) Mc.setBlock(level, 2 + 4 * (i % 16), Y + 4 * (i / 16), Z + 250, Blocks.AIR, 0, 2);
    }

    private static void far() {
        // stone bricks: it stops (nothing else in the lane); glass: it breaks the pane and flies on. NTM moves a
        // stopped bullet on to its tick's end, so where it is says nothing
        Shot stone = farShot(Blocks.STONEBRICK);
        check("a block 250 blocks off in one tick stops the bullet", false, stone.alive);
        Shot glass = farShot(Blocks.GLASS);
        check("glass 250 blocks off in one tick is broken", "minecraft:air", glass.after);
        check("and the bullet flies on", true, glass.alive);
    }

    private static Shot farShot(Block block) {
        int[] at = {2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z};
        lane++;
        int[] far = {at[0], at[1], at[2] + 250};
        Mc.setBlock(level, far[0], far[1], far[2], block, 0, 2);
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, round, 1F, 0F, 0F, 0F);
        b.setPosition(at[0] + .5, at[1] + .5, at[2] - 1.5);
        // 300 blocks this tick (NTM's speed times bulletSpeed)
        double per = round.velocity * ReaimTimes.number("bulletSpeed", 1.6F);
        b.shoot(0, 0, 1, (float) (300.0 / per), 0F);
        b.damage = 30F;
        level.spawnEntity(b);
        b.onUpdate();
        Shot s = new Shot(!b.isDead, b.damage, b.posZ, after(far));
        b.setDead();
        // the world stays from run to run: a block left here would stop a later run's bullets in another lane
        Mc.setBlock(level, far[0], far[1], far[2], Blocks.AIR, 0, 2);
        return s;
    }

    // ---- explosive bullets (26.2: ExplosiveSmoke): NTM's whole onImpact on a pig of 1000 health without armour. At
    // the contact 60 % of the bullet's damage times the zone (here what HeadDamage says for the point), 40 % of the
    // blast; HEAT's own direct hit (x3) takes the zone. A blast on a block keeps 40 %; an explosion of the world is
    // left alone; a heavy round on a wall announces its scorch once.
    private static final class Probe extends EntityBulletBaseMK4 {
        Probe(BulletConfig config) {
            super(level, config, 1F, 0F, 0F, 0F);
            damage = 10;
            ticksExisted = 20;
            motionX = motionY = 0;
            motionZ = 1;
        }

        void impact(RayTraceResult hit) {
            onImpact(hit);
        }
    }

    @SuppressWarnings("unchecked")
    private static float measure(net.minecraft.entity.passive.EntityPig pig, BulletConfig config, RayTraceResult hit, boolean original)
            throws Exception {
        pig.setHealth(1000);
        pig.hurtResistantTime = 0;
        Probe bullet = new Probe(config);
        bullet.setPosition(hit.hitVec.x, hit.hitVec.y, hit.hitVec.z - .1);
        level.spawnEntity(bullet); // NTM's setDead tells the bullet's trackers
        java.lang.reflect.Field field = ExplosiveDamage.class.getDeclaredField("SUPPRESS");
        field.setAccessible(true);
        ThreadLocal<Boolean> guard = (ThreadLocal<Boolean>) field.get(null);
        boolean previous = guard.get();
        if (original) guard.set(true);
        try {
            bullet.impact(hit);
            return 1000 - pig.getHealth();
        } finally {
            guard.set(previous);
            bullet.setDead();
            pig.motionX = pig.motionY = pig.motionZ = 0;
        }
    }

    private static void explosives() {
        int px = 40, py = 120, pz = 40;
        net.minecraft.entity.passive.EntityPig pig = new net.minecraft.entity.passive.EntityPig(level);
        pig.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MAX_HEALTH).setBaseValue(1000);
        pig.setPosition(px + .5, py, pz + .5);
        Mc.setBlock(level, px, py - 1, pz, net.minecraft.init.Blocks.STONE);
        level.spawnEntity(pig);
        try {
            net.minecraft.util.math.Vec3d body = new Vec3d(pig.posX, pig.posY + pig.height * .5, pig.posZ - pig.width * .5);
            BulletConfig[] configs = {com.hbm.items.weapon.sedna.factory.XFactory10ga.g10_explosive, com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_he,
                    com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_he, com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_heat,
                    com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_demo, com.hbm.items.weapon.sedna.factory.XFactoryRocket.rocket_rpzb[0]};
            String[] names = {"10ga explosive", "50 BMG HE", "40mm HE", "40mm HEAT", "40mm demo", "rocket HE"};
            for (int i = 0; i < configs.length; i++) {
                RayTraceResult hit = new RayTraceResult(pig, body);
                float zone = HeadDamage.contactDamage(1, new Probe(configs[i]), pig, hit);
                float extra = configs[i] == com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_heat ? 30 : 0;
                float old = measure(pig, configs[i], hit, true);
                float now = measure(pig, configs[i], hit, false);
                float want = (old - extra) * .4F + (6 + extra) * zone;
                check("explosive " + names[i] + ": " + old + " -> " + now + " (want " + want + ", zone x" + zone + ")", true,
                        old > 0 && Math.abs(now - want) < .15F);
                check("explosive " + names[i] + ": context cleared", true, ExplosiveDamage.current() == null);
            }
            RayTraceResult onBlock = acup.gunfeel.Mc.blockHit(px, py - 1, pz, 1, body);
            float old = measure(pig, com.hbm.items.weapon.sedna.factory.XFactory10ga.g10_explosive, onBlock, true);
            float now = measure(pig, com.hbm.items.weapon.sedna.factory.XFactory10ga.g10_explosive, onBlock, false);
            check("explosive on a block: 40 % of the blast, " + old + " -> " + now, true, old > 0 && Math.abs(now - old * .4F) < .15F);
            pig.setHealth(1000);
            pig.hurtResistantTime = 0;
            com.hbm.explosion.vanillant.ExplosionVNT plain = new com.hbm.explosion.vanillant.ExplosionVNT(level, body.x, body.y, body.z, 3, null);
            plain.setEntityProcessor(new com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth(.5, 10));
            plain.setPlayerProcessor(new com.hbm.explosion.vanillant.standard.PlayerProcessorStandard());
            plain.explode();
            check("a plain explosion of the world is not weakened: " + (1000 - pig.getHealth()), true, Math.abs(1000 - pig.getHealth() - 10) < .15F);
            // a heavy round on a bedrock wall: it goes off, and its scorch is announced once
            int was = ExplosiveDamage.announced;
            int wx = px + 12;
            for (int x = -2; x <= 2; x++) for (int y = 0; y <= 3; y++) Mc.setBlock(level, wx + x, py + y, pz, net.minecraft.init.Blocks.BEDROCK);
            BulletConfig[] heavy = {com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_he, com.hbm.items.weapon.sedna.factory.XFactoryRocket.rocket_rpzb[0]};
            for (int i = 0; i < heavy.length; i++) {
                net.minecraft.util.math.Vec3d point = new Vec3d(wx + i + .5, py + 1.5, pz - .001);
                Probe bullet = new Probe(heavy[i]);
                bullet.setPosition(point.x, point.y, point.z - .1);
                level.spawnEntity(bullet);
                bullet.impact(acup.gunfeel.Mc.blockHit(wx + i, py + 1, pz, 2, point));
                check("heavy round " + heavy[i].id + " went off on the wall", true, bullet.isDead);
            }
            check("each heavy round announced its scorch once: " + (ExplosiveDamage.announced - was), 2, ExplosiveDamage.announced - was);
        } catch (Throwable e) {
            GunFeel.LOGGER.error("BulletCheck: explosives", e);
            check("explosives: " + e, true, false);
        } finally {
            pig.setDead();
        }
    }

    // ---- NTM's big doors (DoorCells): in each of the four facings their model's cells lie in NTM's box (a turn gone
    // wrong would put them across it); the cargo door shut lets a bullet through the slits between its slats and not
    // through a slat
    private static void doors() {
        Block cargo = block("hbm:cargo_door");
        net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        double[] boxes = new double[6 * 16];
        for (int f = 0; f < 4; f++) {
            int x = 20 + 12 * f, y = Y + 40, z = Z + 20;
            player.rotationYaw = 90F * f;
            // a door left from an earlier run takes the room NTM wants free
            for (int dx = -5; dx <= 5; dx++) for (int dy = -5; dy <= 5; dy++) for (int dz = -5; dz <= 5; dz++)
                Mc.setBlock(level, x + dx, y + dy, z + dz, Blocks.AIR, 0, 2);
            Mc.setBlock(level, x, y, z, cargo, 0, 3);
            cargo.onBlockPlacedBy(level, Mc.pos(x, y, z), Mc.state(level, x, y, z), player, new net.minecraft.item.ItemStack(cargo));
            int[] core = null;
            for (int dx = -4; dx <= 4 && core == null; dx++) for (int dy = -4; dy <= 4 && core == null; dy++) for (int dz = -4; dz <= 4 && core == null; dz++)
                if (Mc.te(level, x + dx, y + dy, z + dz) instanceof com.hbm.tileentity.TileEntityDoorGeneric) core = new int[] {x + dx, y + dy, z + dz};
            if (core == null) {
                fail("cargo door " + f + " set up");
                continue;
            }
            int in = 0, out = 0, blocks = 0;
            boolean alongZ = false;
            List<int[]> mine = new java.util.ArrayList<int[]>();
            for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++) for (int dz = -4; dz <= 4; dz++) {
                int bx = core[0] + dx, by = core[1] + dy, bz = core[2] + dz;
                if (Mc.block(level, bx, by, bz) != cargo) continue;
                long[] bits = ModelCells.of(level, cargo, Mc.meta(level, bx, by, bz), bx, by, bz);
                if (bits == null) continue;
                blocks++;
                mine.add(new int[] {bx, by, bz});
                int n = PassableClip.collision(level, cargo, bx, by, bz, boxes);
                if (n > 0 && boxes[5] - boxes[2] < .5) alongZ = true;
                for (int k = 0; k < 4096; k++) {
                    if ((bits[k >> 6] >>> (k & 63) & 1L) == 0) continue;
                    double cx = bx + ((k & 15) + .5) / 16, cy = by + ((k >> 4 & 15) + .5) / 16, cz = bz + ((k >> 8) + .5) / 16;
                    boolean inside = false;
                    for (int i = 0; i < n && !inside; i++)
                        inside = cx >= boxes[i * 6] - .07 && cx <= boxes[i * 6 + 3] + .07 && cy >= boxes[i * 6 + 1] - .07 && cy <= boxes[i * 6 + 4] + .07
                                && cz >= boxes[i * 6 + 2] - .07 && cz <= boxes[i * 6 + 5] + .07;
                    if (inside) in++;
                    else out++;
                }
            }
            check("cargo door facing " + (Mc.meta(level, core[0], core[1], core[2]) - com.hbm.blocks.BlockDummyable.offset) + ": " + blocks
                    + " blocks with cells, " + in + " cells in NTM's box, " + out + " outside", true, blocks == 9 && in > 5 * Math.max(1, out));
            if (!alongZ) continue;
            // a bullet along +z through the middle block's column: a slit (3 clear lines side by side; the slits are 2 cells
            // high) and a slat
            int[] mid = {core[0], core[1] + 1, core[2]};
            for (int[] m : mine) if (m[1] == core[1] + 1 && Math.abs(m[0] - core[0]) + Math.abs(m[2] - core[2]) == 0) mid = m;
            long[] bits = ModelCells.of(level, cargo, 0, mid[0], mid[1], mid[2]);
            int[] slit = null, slat = null;
            for (int cy = 1; cy < 15; cy++) for (int cx = 4; cx < 12; cx++) {
                boolean clear = true;
                for (int ox = -1; ox <= 1; ox++) clear &= !line(bits, cx + ox, cy);
                if (clear && slit == null) slit = new int[] {cx, cy};
                if (line(bits, cx, cy) && line(bits, cx, cy - 1) && line(bits, cx, cy + 1) && slat == null) slat = new int[] {cx, cy};
            }
            check("cargo door shut: a slit and a slat in its middle block", true, slit != null && slat != null);
            if (slit == null || slat == null) continue;
            Shot through = shoot(mid, (slit[0] + .5) / 16, (slit[1] + .5) / 16, 30F);
            // the slit's clear pixels are glass (26.2's RenderedMesh.windowed: an NTM texture not in MachineGlass' list):
            // the bullet goes on, a pixel of glass less
            check("cargo door shut: through a slit (glass, " + through.damage + " left)" + through.trail, true, through.alive && through.damage > 29F);
            Shot stopped = shoot(mid, (slat[0] + .5) / 16, (slat[1] + .5) / 16, 30F);
            check("cargo door shut: into a slat" + stopped.trail, true, !stopped.alive || stopped.damage < 30F);
        }
    }

    // a line of cells along z at (cx, cy) has a filled one
    private static boolean line(long[] bits, int cx, int cy) {
        for (int cz = 0; cz < 16; cz++) {
            int k = cx + 16 * (cy + 16 * cz);
            if ((bits[k >> 6] >>> (k & 63) & 1L) != 0) return true;
        }
        return false;
    }

    private static void richterBulletSpeed() {
        BulletConfig config = com.hbm.items.weapon.sedna.factory.XFactory9mm.p9_ap;
        EntityBulletBaseMK4 bullet = new EntityBulletBaseMK4(level, config, 1F, 0F, 0F, 0F);
        bullet.setPosition(90, 200, 90);
        // ItemGunBaseNT sets a unit speed; motionMult gives the real step
        bullet.shoot(0, 0, 1, 1F, 0F);
        double sx = bullet.posX, sy = bullet.posY, sz = bullet.posZ;
        level.spawnEntity(bullet);
        bullet.onUpdate();
        double dx = bullet.posX - sx, dy = bullet.posY - sy, dz = bullet.posZ - sz;
        double travelled = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double expected = config.velocity * ReaimTimes.number("bulletSpeed", 1.6F);
        check("Richter 9mm AP: real tick matches configured speed", true, Math.abs(travelled - expected) < 1e-4);
        lines.add("     Richter 9mm AP: " + travelled + " blocks/tick, " + travelled * 20 + " blocks/s at 20 TPS");
        bullet.setDead();
    }

    // the living spread (MixinLegoSpread, Spread) through NTM's own Lego.calcSpread, with the mod's settings (the server
    // file is empty here): standing it is NTM's, sneaking x spreadSneak, walking x spreadMove plus spreadSpeed, in the
    // air plus spreadAir. Each shooter is a new zombie: bloom is per shooter and starts at nothing.
    private static void spread() {
        net.minecraft.item.Item item = (net.minecraft.item.Item) net.minecraft.item.Item.REGISTRY.getObject(new net.minecraft.util.ResourceLocation("hbm", "gun_uzi"));
        if (!(item instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT)) {
            fail("spread: no hbm:item.gun_uzi");
            return;
        }
        net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(item);
        com.hbm.items.weapon.sedna.GunConfig gun = ((com.hbm.items.weapon.sedna.ItemGunBaseNT) item).getConfig(stack, 0);
        com.hbm.items.weapon.sedna.Receiver rec = gun.getReceivers(stack)[0];
        float ntm = rec.getInnateSpread(stack) + round.spread * rec.getAmmoSpread(stack) + rec.getHipfireSpread(stack);
        float walk = SpreadModel.WALK;
        float stand = shooterSpread(gun, stack, rec, false, 0, true), sneak = shooterSpread(gun, stack, rec, true, 0, true);
        float moving = shooterSpread(gun, stack, rec, false, walk, true), air = shooterSpread(gun, stack, rec, false, 0, false);
        lines.add("     spread uzi: NTM " + ntm + ", standing " + stand + ", sneaking " + sneak + ", walking " + moving + ", in the air " + air);
        check("spread: standing is NTM's", true, Math.abs(stand - ntm * ReaimTimes.number("spreadStand", 1F)) < 1e-6);
        check("spread: sneaking", true, Math.abs(sneak - ntm * ReaimTimes.number("spreadSneak", 1F)) < 1e-6);
        check("spread: walking", true, Math.abs(moving - (ntm * ReaimTimes.number("spreadMove", 1F) + ReaimTimes.number("spreadSpeed", 0F))) < 1e-6);
        check("spread: in the air", true, Math.abs(air - (ntm + ReaimTimes.number("spreadAir", 0F))) < 1e-6);
        check("spread: the mod's settings change it", true, sneak < stand && stand < moving && stand < air);
    }

    // hold to aim (MixinHoldAim) through NTM's own handleKeybind and onUpdate on the uzi: the scope key aims while held,
    // not while reloading, and the aim comes back as the reload ends with the key still held
    private static void holdAim() {
        net.minecraft.item.Item item = (net.minecraft.item.Item) net.minecraft.item.Item.REGISTRY.getObject(new net.minecraft.util.ResourceLocation("hbm", "gun_uzi"));
        if (!(item instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT)) {
            fail("hold aim: no hbm:item.gun_uzi");
            return;
        }
        com.hbm.items.weapon.sedna.ItemGunBaseNT gun = (com.hbm.items.weapon.sedna.ItemGunBaseNT) item;
        net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(item);
        net.minecraft.entity.monster.EntityZombie z = new net.minecraft.entity.monster.EntityZombie(level);
        z.setPosition(90.5, 200, 90.5);
        com.hbm.handler.HbmKeybinds.EnumKeybind scope = com.hbm.handler.HbmKeybinds.EnumKeybind.GUN_TERTIARY;
        com.hbm.items.weapon.sedna.ItemGunBaseNT.setState(stack, 0, com.hbm.items.weapon.sedna.ItemGunBaseNT.GunState.IDLE);
        gun.handleKeybind(z, null, stack, scope, true);
        check("hold aim: the key down aims", true, com.hbm.items.weapon.sedna.ItemGunBaseNT.getIsAiming(stack));
        gun.handleKeybind(z, null, stack, scope, false);
        check("hold aim: the key up stops aiming (NTM toggles)", false, com.hbm.items.weapon.sedna.ItemGunBaseNT.getIsAiming(stack));
        com.hbm.items.weapon.sedna.ItemGunBaseNT.setState(stack, 0, com.hbm.items.weapon.sedna.ItemGunBaseNT.GunState.RELOADING);
        com.hbm.items.weapon.sedna.ItemGunBaseNT.setLastAnim(stack, 0, com.hbm.render.anim.sedna.AnimationEnums.GunAnimation.RELOAD);
        com.hbm.items.weapon.sedna.ItemGunBaseNT.setTimer(stack, 0, 40);
        gun.handleKeybind(z, null, stack, scope, true);
        check("hold aim: no aiming while reloading", false, com.hbm.items.weapon.sedna.ItemGunBaseNT.getIsAiming(stack));
        check("hold aim: the held key is remembered", true, com.hbm.items.weapon.sedna.ItemGunBaseNT.getTertiary(stack, 0));
        com.hbm.items.weapon.sedna.ItemGunBaseNT.setState(stack, 0, com.hbm.items.weapon.sedna.ItemGunBaseNT.GunState.IDLE);
        com.hbm.items.weapon.sedna.ItemGunBaseNT.setTimer(stack, 0, 0);
        gun.onUpdate(stack, level, z, 0, true);
        check("hold aim: the aim comes back after the reload, key held", true, com.hbm.items.weapon.sedna.ItemGunBaseNT.getIsAiming(stack));
    }

    private static float shooterSpread(com.hbm.items.weapon.sedna.GunConfig gun, net.minecraft.item.ItemStack stack,
            com.hbm.items.weapon.sedna.Receiver rec, boolean sneak, double moved, boolean ground) {
        net.minecraft.entity.monster.EntityZombie z = new net.minecraft.entity.monster.EntityZombie(level);
        z.setPosition(90.5, 200, 90.5);
        z.lastTickPosX = z.posX - moved;
        z.lastTickPosZ = z.posZ;
        z.onGround = ground;
        z.setSneaking(sneak);
        return com.hbm.items.weapon.sedna.factory.Lego.calcSpread(new com.hbm.items.weapon.sedna.ItemGunBaseNT.LambdaContext(gun, z, null, 0), stack,
                rec, round, false, 0, false);
    }

    // what a bullet should do at the block: fly on untouched, go through with less damage, stop in it, or any of the
    // last two (it met the block)
    private enum Want {
        PASS, PIERCE, STOP, MEET
    }

    private static final class Shot {
        final boolean alive;
        final float damage;
        final double z;
        final String after;
        // each tick: where it ended and its motion along z
        String trail = "";

        Shot(boolean alive, float damage, double z, String after) {
            this.alive = alive;
            this.damage = damage;
            this.z = z;
            this.after = after;
        }

        @Override
        public String toString() {
            return (alive ? "flies, " : "stopped, ") + "damage " + damage + ", z " + String.format(java.util.Locale.ROOT, "%.2f", z - Z) + ", block " + after + (trail.isEmpty() ? "" : ", ticks" + trail);
        }
    }

    // a chemical plant's core alone (it is its own core), with the cells and glass its client would have: a window
    // across z at 8/16 from 1/16 to 15/16, maybe its casing at 13/16 behind it; a grate: a casing at 8/16 with the
    // bottom left quarter open and no glass
    private static void machineGlass(Path settings) throws Exception {
        long[] window = new long[65], casing = new long[65], grate = new long[65];
        for (int x = 1; x < 15; x++) for (int y = 1; y < 15; y++) {
            set(window, x, y, 8);
            set(casing, x, y, 13);
            if (x >= 8 || y >= 8) set(grate, x, y, 8);
        }
        java.util.Map<String, long[]> table = new java.util.HashMap<String, long[]>(ModelCells.table());
        // one core block per case; they share one key (a machine's type and the cell's place in it), so the table
        // is put again before each
        int[] win = plant(), winCase = plant(), gr = plant();
        if (win == null || winCase == null || gr == null) return;
        String key = MachineCells.keyAt(level, Mc.block(level, win[0], win[1], win[2]), win[0], win[1], win[2]);
        // the jar's exact faces (MachineFaces): the plant's core block gets its triangles, its window among them
        long[] jarCells = ModelCells.table().get(key);
        long[] withFaces = jarCells == null ? null : MachineFaces.with(jarCells, key);
        int all = 0, panes = 0;
        if (withFaces != null) for (ModelSurface.Face f : ModelSurface.faces(withFaces)) {
            all++;
            if (f.glass) panes++;
        }
        lines.add("     machine faces: " + MachineFaces.models() + " facings in the jar; chemplant core " + all + " faces, " + panes + " of glass");
        check("machine faces: chemplant core has its faces", true, all > 0);
        check("machine faces: chemplant core has its window", true, panes > 0);
        // the cases here set the plant's cells themselves: its faces from the jar (MachineFaces) would stand in for them
        MachineFaces.off = true;
        try {
            put(table, key, new long[65], window);
            Shot s = expect("chemplant window: pierced as glass", Want.PIERCE, win, .2, .2, 30F, null);
            check("chemplant window: loses as a pixel of glass", true, s.damage > 29F);
            expect("chemplant window: a weak round goes through too", Want.PIERCE, win, .2, .2, 2F, null);
            put(table, key, casing, window);
            s = shoot(winCase, .2, .2, 30F);
            check("chemplant window, casing behind: the casing stops it", false, s.alive);
            check("chemplant window, casing behind: the glass took its share first", true, s.damage < 30F);
            put(table, key, grate, null);
            expect("machine grate: through its clear pixel", Want.PASS, gr, .2, .2, 30F, null);
            expect("machine grate: its bar stops it", Want.STOP, gr, .6, .2, 30F, null);
            // exact faces (ModelSurface) stand in for even a solid coarse grid (26.2: multiblock exact)
            long[] coarse = new long[65];
            java.util.Arrays.fill(coarse, 0, 64, -1L);
            ModelSurface.Face face = new ModelSurface.Face(new double[] {.1, .1, .5, .1, .9, .5, .9, .1, .5}, new double[6], 1, 1, new long[] {1});
            put(table, key, ModelSurface.attach(coarse, java.util.Collections.singletonList(face)), null);
            expect("multiblock exact: empty side of occupied cells", Want.PASS, gr, .8, .8, 30F, null);
            expect("multiblock exact: real face stops bullet", Want.STOP, gr, .2, .2, 30F, null);
            // a window as a face of glass over the casing's cells
            ModelSurface.Face pane = new ModelSurface.Face(new double[] {0, 0, .5, 0, 1, .5, 1, 0, .5}, new double[6], 1, 1, new long[] {1}, true);
            ModelSurface.Face back = new ModelSurface.Face(new double[] {0, 0, .9, 0, 1, .9, 1, 0, .9}, new double[6], 1, 1, new long[] {1});
            put(table, key, ModelSurface.attach(new long[65], java.util.Arrays.asList(pane, back)), null);
            s = shoot(winCase, .2, .2, 30F);
            check("exact window, casing behind: the casing stops it", false, s.alive);
            check("exact window, casing behind: the glass took its share first", true, s.damage < 30F);
            put(table, key, ModelSurface.attach(new long[65], java.util.Collections.singletonList(pane)), null);
            expect("exact window: pierced as glass", Want.PIERCE, win, .2, .2, 30F, null);
            put(table, key, new long[65], window);
            // bulletMachineGlass = false: the window is air again
            Files.write(settings, (MachineGlass.KEY + " = false\n").getBytes(StandardCharsets.UTF_8));
            Thread.sleep(2100);
            expect("chemplant window, bulletMachineGlass off: flies through", Want.PASS, win, .2, .2, 30F, null);
        } finally {
            MachineFaces.off = false;
            Files.write(settings, (BulletRules.MACHINES_KEY + " = false\n").getBytes(StandardCharsets.UTF_8));
            Thread.sleep(2100);
            ModelCells.reloadOwn();
        }
    }

    // the cargo elevator's core alone (its base, platform down) met from above: the bullet stops on the platform's
    // top, which moves with the elevator (the mesh at another extension lies that much higher)
    private static void elevatorPlatform() {
        check("elevator mesh: read", true, ElevatorMesh.available());
        int[] at = single("hbm:cargo_elevator", 12);
        String key = MachineCells.keyAt(level, Mc.block(level, at[0], at[1], at[2]), at[0], at[1], at[2]);
        check("elevator core alone is its own machine", true, key != null);
        if (key == null) return;
        java.util.Map<String, long[]> table = new java.util.HashMap<String, long[]>(ModelCells.table());
        long[] had = table.get(key);
        MachineFaces.off = true;
        try {
            // its base's cells empty: only the platform is left to meet
            table.put(key, new long[65]);
            ModelCells.put(table);
            double x = at[0] + .5, z = at[2] + .5;
            BlockRay.Hit h = PassableClip.clip(level, new Vec3d(x, at[1] + 3, z), new Vec3d(x, at[1] - 1, z));
            lines.add("     elevator platform from above: " + h);
            check("elevator: the platform stops a bullet from above", true, h != null && h.y == at[1] && h.ay > at[1] + .74 && h.ay < at[1] + 1.0001);
            BlockRay.Hit up = ElevatorMesh.hit(at[0], at[1], at[2], 2.0, at[0], at[1] + 2, at[2], x, at[1] + 5, z, x, at[1] - 1, z);
            check("elevator: the platform raised by 2 is met 2 higher", true, h != null && up != null && Math.abs(up.ay - h.ay - 2.0) < 1e-6);
            BlockRay.Hit below = ElevatorMesh.hit(at[0], at[1], at[2], 2.0, at[0], at[1], at[2], x, at[1] + .99, z, x, at[1] - 1, z);
            check("elevator: the platform raised, the base block has no platform", true, below == null || below.ay < at[1] + .75);
        } finally {
            MachineFaces.off = false;
            if (had != null) table.put(key, had);
            else table.remove(key);
            ModelCells.put(table);
        }
    }

    // a chemical plant's core in the next lane; null when the block is not its own machine's core
    private static int[] plant() {
        int[] at = single("hbm:machine_chemical_plant", 12 + 2);
        String key = MachineCells.keyAt(level, Mc.block(level, at[0], at[1], at[2]), at[0], at[1], at[2]);
        check("chemplant core alone is its own machine", true, key != null);
        return key == null ? null : at;
    }

    // the machine's cells and glass (null: none) under its key, in use from now on
    private static void put(java.util.Map<String, long[]> table, String key, long[] cells, long[] glass) {
        table.put(key, cells);
        if (glass != null) table.put(MachineCells.glassKey(key), glass);
        else table.remove(MachineCells.glassKey(key));
        ModelCells.put(table);
    }

    private static void set(long[] bits, int x, int y, int z) {
        int k = x + 16 * (y + 16 * z);
        bits[k >> 6] |= 1L << (k & 63);
    }

    // `differs`: why 1.7.10 does other than 26.2 here (a DIFF line, not a failure), null when it must do the same
    private static Shot expect(String name, Want want, int[] at, double fx, double fy, float damage, String differs) {
        Shot s = shoot(at, fx, fy, damage);
        boolean beyond = s.z > Z + 1;
        boolean pass;
        switch (want) {
            case PASS: pass = s.alive && beyond && s.damage == damage; break;
            case PIERCE: pass = s.alive && beyond && s.damage < damage; break;
            // a stopped bullet is still moved to the end of its path that tick (NTM): where it is says nothing
            case STOP: pass = !s.alive && s.damage == damage; break;
            default: pass = !s.alive || s.damage < damage;
        }
        String w = want.name().toLowerCase(java.util.Locale.ROOT);
        if (!pass && differs != null) {
            lines.add("DIFF " + name + ": 26.2 " + w + ", 1.7.10 " + s + " (" + differs + ")");
            return s;
        }
        ok &= pass;
        lines.add((pass ? "OK   " : "FAIL ") + name + ": " + w + (pass ? "" : ", got " + s) + (pass && differs != null ? " (as 26.2 after all)" : ""));
        return s;
    }

    // a bullet from 1.5 blocks before the block, straight along +z at (fx, fy) of it, ticked till it stops (or 3 ticks)
    private static Shot shoot(int[] at, double fx, double fy, float damage) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, round, 1F, 0F, 0F, 0F);
        b.setPosition(at[0] + fx, at[1] + fy, at[2] - 1.5);
        b.shoot(0, 0, 1, round.velocity, 0F);
        b.damage = damage;
        // in the world, as NTM's own bullets (its setDead tells the entity tracker), ticked here
        level.spawnEntity(b);
        StringBuilder trail = new StringBuilder();
        for (int i = 0; i < 3 && !b.isDead; i++) {
            b.onUpdate();
            trail.append(String.format(java.util.Locale.ROOT, " z %.2f mz %.2f%s", b.posZ - Z, b.motionZ, b.isDead ? " dead" : ""));
        }
        Shot s = new Shot(!b.isDead, b.damage, b.posZ, after(at));
        s.trail = trail.toString();
        b.setDead();
        return s;
    }

    // the health a fresh iron golem (no AI, in the open) loses to one .50 DU bullet at height fy over its feet
    private static float golemHit(double fy, float damage) {
        int[] at = {2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z};
        lane++;
        EntityIronGolem g = golem(at);
        BulletConfig du = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, du, 1F, 0F, 0F, 0F);
        b.setPosition(at[0] + .5, at[1] + fy, at[2] - 3);
        b.shoot(0, 0, 1, du.velocity, 0F);
        b.damage = damage;
        level.spawnEntity(b);
        float before = g.getHealth();
        for (int i = 0; i < 3 && !b.isDead; i++) b.onUpdate();
        float took = before - g.getHealth();
        b.setDead();
        g.setDead();
        return took;
    }

    private static EntityIronGolem golem(int[] at) {
        EntityIronGolem g = new EntityIronGolem(level);
        g.setPosition(at[0] + .5, at[1], at[2] + .5);
        g.rotationYaw = g.renderYawOffset = 0F;
        level.spawnEntity(g);
        return g;
    }

    // a golem on an oak plank floor, shot at 45 degrees down through its head into the floor behind it: the health it
    // lost and the bullet's damage after
    private static float[] golemOverFloor(float damage) {
        // port: 100 blocks higher than the lanes, so that the rest of the tick's path (down at 45 degrees) ends in the
        // air: a bullet that stops in the ground below this tick does not pay the floor (PierceSteps.finish)
        int[] at = {2 + 4 * (lane % 16), Y + 100 + 4 * (lane / 16), Z};
        lane++;
        for (int dz = -1; dz <= 4; dz++) Mc.setBlock(level, at[0], at[1] - 1, at[2] + dz, Blocks.PLANKS, 0, 2);
        EntityIronGolem g = golem(at);
        BulletConfig du = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, du, 1F, 0F, 0F, 0F);
        // into the hitbox (z from -0.2) at 2.4 high, down to the floor's top 2.4 further on
        b.setPosition(at[0] + .5, at[1] + 4.4, at[2] - 2.2);
        b.shoot(0, -1, 1, du.velocity, 0F);
        b.damage = damage;
        level.spawnEntity(b);
        float before = g.getHealth();
        b.onUpdate();
        float took = before - g.getHealth();
        float left = b.damage;
        lines.add(String.format(java.util.Locale.ROOT, "     golem over a floor: took %.2f, bullet %s at %.2f %.2f %.2f, damage %.2f", took,
                b.isDead ? "stopped" : "flies", b.posX - at[0], b.posY - at[1], b.posZ - at[2], left));
        b.setDead();
        g.setDead();
        return new float[] {took, left};
    }

    private static String after(int[] at) {
        return BlockIds.canonical(Mc.name(Mc.block(level, at[0], at[1], at[2])));
    }

    // a plain, single, fast NTM bullet without an impact effect: the one with the least gravity and spread
    private static BulletConfig pick() {
        BulletConfig best = null;
        for (BulletConfig c : BulletConfig.configs) {
            // port: a gun's round (an ammo item), one that stops head-on (NTM's grenade fragments, the first config,
            // bounce at up to 90 degrees) and lives for more than the check's ticks
            if (c == null || c.ammo == null || c.velocity < 5F || c.onImpact != null || c.doesPenetrate || c.projectilesMax > 1
                    || c.onRicochet != BulletConfig.LAMBDA_STANDARD_RICOCHET || c.ricochetAngle >= 45F || c.expires < 10) continue;
            if (best == null || c.gravity + c.spread < best.gravity + best.spread) best = c;
        }
        return best;
    }

    private static Block block(String id) {
        Block b = Mc.blockOrNull(id);
        if (b == null || b == Blocks.AIR && !id.equals("minecraft:air")) throw new IllegalArgumentException("no block " + id);
        return b;
    }

    // the next lane: four blocks apart along x, sixteen in a row, rows four blocks apart up: no bullet meets another
    // lane's block
    private static int[] single(String id, int meta) {
        int[] at = {2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z};
        lane++;
        Mc.setBlock(level, at[0], at[1], at[2], block(id), meta, 2);
        return at;
    }

    // three fences joined along x; the middle one is shot (1.7.10 joins them by their neighbours)
    private static int[] fenceRow() {
        int[] at = single("minecraft:fence", 0);
        Mc.setBlock(level, at[0] - 1, at[1], at[2], Blocks.OAK_FENCE, 0, 2);
        Mc.setBlock(level, at[0] + 1, at[1], at[2], Blocks.OAK_FENCE, 0, 2);
        return at;
    }

    // three of a block joined along x; the middle one is shot
    private static int[] row(String id, int meta) {
        int[] at = single(id, meta);
        Mc.setBlock(level, at[0] - 1, at[1], at[2], block(id), meta, 2);
        Mc.setBlock(level, at[0] + 1, at[1], at[2], block(id), meta, 2);
        return at;
    }

    // a door's upper half (shot) over its lower half with this meta
    private static int[] doorTop(String id, int lower) {
        int[] at = single(id, 8);
        Mc.setBlock(level, at[0], at[1] - 1, at[2], block(id), lower, 2);
        return at;
    }

    // a wall joined east and west: its low sides, no post
    private static int[] wallRow() {
        int[] at = single("minecraft:cobblestone_wall", 0);
        Mc.setBlock(level, at[0] - 1, at[1], at[2], Blocks.COBBLESTONE_WALL, 0, 2);
        Mc.setBlock(level, at[0] + 1, at[1], at[2], Blocks.COBBLESTONE_WALL, 0, 2);
        return at;
    }

    // ---- where each gun's bullet starts and flies against the player's look (the owner: "the guns shoot a little above
    // the crosshair"). NTM's EntityBulletBaseMK4(entity, ...) as Lego.doStandardFire makes it, spread 0: from the eye
    // plus the receiver's projectile offset turned with the look, along the look. Information, no check: the start in
    // the view's axes (right, up, forward), the flight's angle off the look, and how far above (+) or below the look's
    // line it passes at 5, 20 and 50 blocks without gravity, then with NTM's gravity at the bullet's speed.
    private static void aim() {
        net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        int n = 0;
        double worst = 0;
        String worstGun = "-";
        for (Object o : net.minecraft.item.Item.REGISTRY) {
            if (!(o instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT)) continue;
            com.hbm.items.weapon.sedna.ItemGunBaseNT gun = (com.hbm.items.weapon.sedna.ItemGunBaseNT) o;
            net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(gun);
            String id = String.valueOf(net.minecraft.item.Item.REGISTRY.getNameForObject(gun));
            for (int variant = 0; variant < 3; variant++) {
                float pitch = variant == 2 ? 30F : 0F;
                boolean scoped = variant == 1;
                try {
                    player.setPositionAndRotation(100.5, Y + 100, 100.5, 37F, pitch);
                    player.prevRotationYaw = player.rotationYaw;
                    player.prevRotationPitch = player.rotationPitch;
                    com.hbm.items.weapon.sedna.Receiver rec = gun.getConfig(stack, 0).getReceivers(stack)[0];
                    BulletConfig bc = (BulletConfig) rec.getMagazine(stack).getType(stack, null);
                    net.minecraft.util.math.Vec3d off = scoped ? rec.getProjectileOffsetScoped(stack) : rec.getProjectileOffset(stack);
                    EntityBulletBaseMK4 b = new EntityBulletBaseMK4(player, bc, 1F, 0F, off.z, off.y, off.x);
                    double ex = player.posX, ey = player.posY + player.getEyeHeight(), ez = player.posZ;
                    net.minecraft.util.math.Vec3d look = player.getLookVec();
                    double[] f = {look.x, look.y, look.z};
                    // right = look x up, up = right x look
                    double[] r = {-f[2], 0, f[0]};
                    double rl = Math.sqrt(r[0] * r[0] + r[2] * r[2]);
                    r[0] /= rl;
                    r[2] /= rl;
                    double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
                    double[] d = {b.posX - ex, b.posY - ey, b.posZ - ez};
                    double sr = dot(d, r), su = dot(d, u), sf = dot(d, f);
                    double[] v = {b.motionX, b.motionY, b.motionZ};
                    double vf = dot(v, f), vu = dot(v, u) / vf, vr = dot(v, r) / vf;
                    // blocks a tick: NTM's velocity, faster for real bullets (MixinBulletServer.gunfeel$speed)
                    double speed = bc.velocity * (bc.velocity >= 5F ? ReaimTimes.number("bulletSpeed", 1.6F) : 1F);
                    StringBuilder at = new StringBuilder();
                    for (double dist : new double[] {5, 20, 50}) {
                        double up = su + (dist - sf) * vu, right = sr + (dist - sf) * vr;
                        double ticks = dist / Math.max(1e-6, speed);
                        double drop = bc.gravity * ticks * ticks / 2;
                        at.append(String.format(java.util.Locale.ROOT, "  %2.0f: up %+.3f right %+.3f (gravity %+.3f)", dist, up, right, up - drop));
                        if (dist == 50 && variant == 0 && Math.abs(up) > Math.abs(worst)) {
                            worst = up;
                            worstGun = id;
                        }
                    }
                    if (variant == 0) n++;
                    lines.add(String.format(java.util.Locale.ROOT, "INFO aim %s pitch %.0f%s: start right %+.3f up %+.3f forward %+.3f, flight up %+.4f right %+.4f (per block), offset %s %s %s, speed %.1f, gravity %.4f;%s",
                            id, pitch, scoped ? " aimed" : "", sr, su, sf, vu, vr, off.x, off.y, off.z, speed, bc.gravity, at));
                } catch (RuntimeException e) {
                    lines.add("INFO aim " + id + " variant " + variant + ": " + e);
                }
            }
        }
        lines.add(String.format(java.util.Locale.ROOT, "INFO aim: %d guns, the farthest from the look at 50 blocks (no gravity): %s %+.3f", n, worstGun, worst));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
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
}
