package acup.gunfeel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityThrowableNT;
import com.hbm.items.weapon.sedna.BulletConfig;
import net.minecraft.util.MovingObjectPosition;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
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
        FMLCommonHandler.instance().bus().register(new BulletCheck.Once(file));
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
                run(out, MinecraftServer.getServer());
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
            MinecraftServer.getServer().initiateShutdown();
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
        level = server.worldServerForDimension(0);
        for (int cx = -1; cx < 6; cx++) for (int cz = -1; cz < 20; cz++) level.theChunkProviderServer.loadChunk(cx, cz);
        level.getGameRules().setOrCreateGameRule("mobGriefing", "true");
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

        // DU Mr. Deuce: one sheet goes through, a second does not refill what ignores the threshold
        BulletConfig plainRound = round;
        round = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        // port: NTM 1.7.10's steel wall is 2/16 thick at the side of its meta (2: the south side, as 26.2's facing=north)
        expect("Mr Deuce DU: one steel panel", Want.PIERCE, single("hbm:steel_wall", 2), .5, .5, 11.25F, null);
        int[] panels = single("hbm:steel_wall", 2);
        level.setBlock(panels[0], panels[1], panels[2] + 1, block("hbm:steel_wall"), 2, 2);
        Shot secondPanel = shoot(panels, .5, .5, 11.25F);
        check("Mr Deuce DU: second steel panel holds", false, secondPanel.alive);
        expect("Mr Deuce DU: one metre of wired glass just passes", Want.PIERCE, single("hbm:reinforced_glass", 0), .5, .5, 11.25F, null);
        round = plainRound;

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
        expect("iron bars alone: a bar", Want.MEET, single("minecraft:iron_bars", 0), 2.5 * px, .5, 30F, null);
        expect("ladder: between the rungs", Want.PASS, single("minecraft:ladder", 2), .5, 7.5 * px, 30F, null);
        expect("ladder: a rung", Want.MEET, single("minecraft:ladder", 2), .5, 5.5 * px, 30F, null);
        expect("chain-link fence: through the mesh", Want.PASS, row("hbm:fence_metal", 0), 1.5 * px, 14.5 * px, 30F, null);
        expect("chain-link fence: a wire, pierced", Want.PIERCE, row("hbm:fence_metal", 0), 8.5 * px, 15.5 * px, 30F, null);
        // a wooden door's top, closed across z (lower meta 1): its two windows, its frame
        expect("wooden door: through the window", Want.PASS, doorTop("minecraft:wooden_door", 1), 4.5 * px, 11.5 * px, 30F, null);
        expect("wooden door: the frame", Want.MEET, doorTop("minecraft:wooden_door", 1), .5, 14.5 * px, 30F, null);

        // ---- materials: a plank is pierced with the rule's loss, concrete holds
        int[] at = single("minecraft:planks", 0);
        BulletRules.Break rule = BulletRules.breakRule(level, at[0], at[1], at[2]);
        float left = rule == null ? -2F : rule.pierced(30F, PassableClip.hardness(level, Blocks.planks, at[0], at[1], at[2]), 1.0);
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

    private static void far() {
        // stone bricks: it stops (nothing else in the lane); glass: it breaks the pane and flies on. NTM moves a
        // stopped bullet on to its tick's end, so where it is says nothing
        Shot stone = farShot(Blocks.stonebrick);
        check("a block 250 blocks off in one tick stops the bullet", false, stone.alive);
        Shot glass = farShot(Blocks.glass);
        check("glass 250 blocks off in one tick is broken", "minecraft:air", glass.after);
        check("and the bullet flies on", true, glass.alive);
    }

    private static Shot farShot(Block block) {
        int[] at = {2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z};
        lane++;
        int[] far = {at[0], at[1], at[2] + 250};
        level.setBlock(far[0], far[1], far[2], block, 0, 2);
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, round, 1F, 0F, 0F, 0F);
        b.setPosition(at[0] + .5, at[1] + .5, at[2] - 1.5);
        // 300 blocks this tick (NTM's speed times bulletSpeed)
        double per = round.velocity * ReaimTimes.number("bulletSpeed", 1.6F);
        b.setThrowableHeading(0, 0, 1, (float) (300.0 / per), 0F);
        b.damage = 30F;
        level.spawnEntityInWorld(b);
        b.onUpdate();
        Shot s = new Shot(!b.isDead, b.damage, b.posZ, after(far));
        b.setDead();
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

        void impact(MovingObjectPosition hit) {
            onImpact(hit);
        }
    }

    @SuppressWarnings("unchecked")
    private static float measure(net.minecraft.entity.passive.EntityPig pig, BulletConfig config, MovingObjectPosition hit, boolean original)
            throws Exception {
        pig.setHealth(1000);
        pig.hurtResistantTime = 0;
        Probe bullet = new Probe(config);
        bullet.setPosition(hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord - .1);
        level.spawnEntityInWorld(bullet); // NTM's setDead tells the bullet's trackers
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
        pig.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.maxHealth).setBaseValue(1000);
        pig.setPosition(px + .5, py, pz + .5);
        level.setBlock(px, py - 1, pz, net.minecraft.init.Blocks.stone);
        level.spawnEntityInWorld(pig);
        try {
            net.minecraft.util.Vec3 body = net.minecraft.util.Vec3.createVectorHelper(pig.posX, pig.posY + pig.height * .5, pig.posZ - pig.width * .5);
            BulletConfig[] configs = {com.hbm.items.weapon.sedna.factory.XFactory10ga.g10_explosive, com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_he,
                    com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_he, com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_heat,
                    com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_demo, com.hbm.items.weapon.sedna.factory.XFactoryRocket.rocket_rpzb[0]};
            String[] names = {"10ga explosive", "50 BMG HE", "40mm HE", "40mm HEAT", "40mm demo", "rocket HE"};
            for (int i = 0; i < configs.length; i++) {
                MovingObjectPosition hit = new MovingObjectPosition(pig, body);
                float zone = HeadDamage.contactDamage(1, new Probe(configs[i]), pig, hit);
                float extra = configs[i] == com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_heat ? 30 : 0;
                float old = measure(pig, configs[i], hit, true);
                float now = measure(pig, configs[i], hit, false);
                float want = (old - extra) * .4F + (6 + extra) * zone;
                check("explosive " + names[i] + ": " + old + " -> " + now + " (want " + want + ", zone x" + zone + ")", true,
                        old > 0 && Math.abs(now - want) < .15F);
                check("explosive " + names[i] + ": context cleared", true, ExplosiveDamage.current() == null);
            }
            MovingObjectPosition onBlock = new MovingObjectPosition(px, py - 1, pz, 1, body);
            float old = measure(pig, com.hbm.items.weapon.sedna.factory.XFactory10ga.g10_explosive, onBlock, true);
            float now = measure(pig, com.hbm.items.weapon.sedna.factory.XFactory10ga.g10_explosive, onBlock, false);
            check("explosive on a block: 40 % of the blast, " + old + " -> " + now, true, old > 0 && Math.abs(now - old * .4F) < .15F);
            pig.setHealth(1000);
            pig.hurtResistantTime = 0;
            com.hbm.explosion.vanillant.ExplosionVNT plain = new com.hbm.explosion.vanillant.ExplosionVNT(level, body.xCoord, body.yCoord, body.zCoord, 3, null);
            plain.setEntityProcessor(new com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth(.5, 10));
            plain.setPlayerProcessor(new com.hbm.explosion.vanillant.standard.PlayerProcessorStandard());
            plain.explode();
            check("a plain explosion of the world is not weakened: " + (1000 - pig.getHealth()), true, Math.abs(1000 - pig.getHealth() - 10) < .15F);
            // a heavy round on a bedrock wall: it goes off, and its scorch is announced once
            int was = ExplosiveDamage.announced;
            int wx = px + 12;
            for (int x = -2; x <= 2; x++) for (int y = 0; y <= 3; y++) level.setBlock(wx + x, py + y, pz, net.minecraft.init.Blocks.bedrock);
            BulletConfig[] heavy = {com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_he, com.hbm.items.weapon.sedna.factory.XFactoryRocket.rocket_rpzb[0]};
            for (int i = 0; i < heavy.length; i++) {
                net.minecraft.util.Vec3 point = net.minecraft.util.Vec3.createVectorHelper(wx + i + .5, py + 1.5, pz - .001);
                Probe bullet = new Probe(heavy[i]);
                bullet.setPosition(point.xCoord, point.yCoord, point.zCoord - .1);
                level.spawnEntityInWorld(bullet);
                bullet.impact(new MovingObjectPosition(wx + i, py + 1, pz, 2, point));
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
        Block cargo = block("hbm:tile.cargo_door");
        net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        double[] boxes = new double[6 * 16];
        for (int f = 0; f < 4; f++) {
            int x = 20 + 12 * f, y = Y + 40, z = Z + 20;
            player.rotationYaw = 90F * f;
            // a door left from an earlier run takes the room NTM wants free
            for (int dx = -5; dx <= 5; dx++) for (int dy = -5; dy <= 5; dy++) for (int dz = -5; dz <= 5; dz++)
                level.setBlock(x + dx, y + dy, z + dz, Blocks.air, 0, 2);
            level.setBlock(x, y, z, cargo, 0, 3);
            cargo.onBlockPlacedBy(level, x, y, z, player, new net.minecraft.item.ItemStack(cargo));
            int[] core = null;
            for (int dx = -4; dx <= 4 && core == null; dx++) for (int dy = -4; dy <= 4 && core == null; dy++) for (int dz = -4; dz <= 4 && core == null; dz++)
                if (level.getTileEntity(x + dx, y + dy, z + dz) instanceof com.hbm.tileentity.TileEntityDoorGeneric) core = new int[] {x + dx, y + dy, z + dz};
            if (core == null) {
                fail("cargo door " + f + " set up");
                continue;
            }
            int in = 0, out = 0, blocks = 0;
            boolean alongZ = false;
            List<int[]> mine = new java.util.ArrayList<int[]>();
            for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++) for (int dz = -4; dz <= 4; dz++) {
                int bx = core[0] + dx, by = core[1] + dy, bz = core[2] + dz;
                if (level.getBlock(bx, by, bz) != cargo) continue;
                long[] bits = ModelCells.of(level, cargo, level.getBlockMetadata(bx, by, bz), bx, by, bz);
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
            check("cargo door facing " + (level.getBlockMetadata(core[0], core[1], core[2]) - com.hbm.blocks.BlockDummyable.offset) + ": " + blocks
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
            check("cargo door shut: through a slit" + through.trail, true, through.alive && through.damage == 30F);
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
        bullet.setThrowableHeading(0, 0, 1, 1F, 0F);
        double sx = bullet.posX, sy = bullet.posY, sz = bullet.posZ;
        level.spawnEntityInWorld(bullet);
        bullet.onUpdate();
        double dx = bullet.posX - sx, dy = bullet.posY - sy, dz = bullet.posZ - sz;
        double travelled = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double expected = config.velocity * ReaimTimes.number("bulletSpeed", 1.6F);
        check("Richter 9mm AP: real tick matches configured speed", true, Math.abs(travelled - expected) < 1e-4);
        lines.add("     Richter 9mm AP: " + travelled + " blocks/tick, " + travelled * 20 + " blocks/s at 20 TPS");
        bullet.setDead();
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
        b.setThrowableHeading(0, 0, 1, round.velocity, 0F);
        b.damage = damage;
        // in the world, as NTM's own bullets (its setDead tells the entity tracker), ticked here
        level.spawnEntityInWorld(b);
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
        b.setThrowableHeading(0, 0, 1, du.velocity, 0F);
        b.damage = damage;
        level.spawnEntityInWorld(b);
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
        level.spawnEntityInWorld(g);
        return g;
    }

    // a golem on an oak plank floor, shot at 45 degrees down through its head into the floor behind it: the health it
    // lost and the bullet's damage after
    private static float[] golemOverFloor(float damage) {
        // port: 100 blocks higher than the lanes, so that the rest of the tick's path (down at 45 degrees) ends in the
        // air: a bullet that stops in the ground below this tick does not pay the floor (PierceSteps.finish)
        int[] at = {2 + 4 * (lane % 16), Y + 100 + 4 * (lane / 16), Z};
        lane++;
        for (int dz = -1; dz <= 4; dz++) level.setBlock(at[0], at[1] - 1, at[2] + dz, Blocks.planks, 0, 2);
        EntityIronGolem g = golem(at);
        BulletConfig du = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, du, 1F, 0F, 0F, 0F);
        // into the hitbox (z from -0.2) at 2.4 high, down to the floor's top 2.4 further on
        b.setPosition(at[0] + .5, at[1] + 4.4, at[2] - 2.2);
        b.setThrowableHeading(0, -1, 1, du.velocity, 0F);
        b.damage = damage;
        level.spawnEntityInWorld(b);
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
        return BlockIds.canonical(Block.blockRegistry.getNameForObject(level.getBlock(at[0], at[1], at[2])));
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
        Block b = Block.getBlockFromName(id);
        if (b == null || b == Blocks.air) b = Block.getBlockFromName(id.replace(":", ":tile."));
        if (b == null || b == Blocks.air && !id.equals("minecraft:air")) throw new IllegalArgumentException("no block " + id);
        return b;
    }

    // the next lane: four blocks apart along x, sixteen in a row, rows four blocks apart up: no bullet meets another
    // lane's block
    private static int[] single(String id, int meta) {
        int[] at = {2 + 4 * (lane % 16), Y + 4 * (lane / 16), Z};
        lane++;
        level.setBlock(at[0], at[1], at[2], block(id), meta, 2);
        return at;
    }

    // three fences joined along x; the middle one is shot (1.7.10 joins them by their neighbours)
    private static int[] fenceRow() {
        int[] at = single("minecraft:fence", 0);
        level.setBlock(at[0] - 1, at[1], at[2], Blocks.fence, 0, 2);
        level.setBlock(at[0] + 1, at[1], at[2], Blocks.fence, 0, 2);
        return at;
    }

    // three of a block joined along x; the middle one is shot
    private static int[] row(String id, int meta) {
        int[] at = single(id, meta);
        level.setBlock(at[0] - 1, at[1], at[2], block(id), meta, 2);
        level.setBlock(at[0] + 1, at[1], at[2], block(id), meta, 2);
        return at;
    }

    // a door's upper half (shot) over its lower half with this meta
    private static int[] doorTop(String id, int lower) {
        int[] at = single(id, 8);
        level.setBlock(at[0], at[1] - 1, at[2], block(id), lower, 2);
        return at;
    }

    // a wall joined east and west: its low sides, no post
    private static int[] wallRow() {
        int[] at = single("minecraft:cobblestone_wall", 0);
        level.setBlock(at[0] - 1, at[1], at[2], Blocks.cobblestone_wall, 0, 2);
        level.setBlock(at[0] + 1, at[1], at[2], Blocks.cobblestone_wall, 0, 2);
        return at;
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
