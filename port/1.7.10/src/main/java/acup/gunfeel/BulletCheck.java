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

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

// port: 26.2's BulletCheck (0762086) on a 1.7.10 dedicated server (runServer -PcheckBullets): the same lanes, rounds
// and checks where 1.7.10 has the blocks; the 26.2 blocks with model cells or masks (fence and gate gaps, wooden
// structures, the bunker door's grille, copper grates) are left out or marked DIFF: 1.7.10 has no cells yet and meets
// them by their collision boxes. Model hitboxes of mobs are the hitboxes' check. Added: a path past 200 cells in one
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

        // ---- fences, gates, walls: 26.2 meets their posts and rails (cells); 1.7.10 their collision boxes
        String cells = "no fence/gate/wall cells on 1.7.10 yet: its collision box";
        expect("fence: between the rails", Want.PASS, fenceRow(), .25, .65, 30F, cells);
        expect("fence: the post", Want.PIERCE, fenceRow(), .5, .5, 30F, null);
        expect("gate closed: between the bars", Want.PASS, single("minecraft:fence_gate", 0), .25, .65, 30F, cells);
        expect("gate closed: the middle", Want.PIERCE, single("minecraft:fence_gate", 0), .5, .5, 30F, null);
        // open, facing south or north (meta 4 | 0): the gate's collision is gone
        expect("gate open: the middle", Want.PASS, single("minecraft:fence_gate", 4), .5, .5, 30F, null);
        expect("wall low: over it", Want.PASS, wallRow(), .5, .95, 30F, cells);
        expect("wall low: into it", Want.MEET, wallRow(), .5, .5, 30F, null);
        expect("wall post: beside it", Want.PASS, single("minecraft:cobblestone_wall", 0), .1, .5, 30F, null);
        expect("wall post: into it", Want.MEET, single("minecraft:cobblestone_wall", 0), .5, .5, 30F, null);

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

        // ---- .50 DU into an iron golem in the open: the body takes the bullet's damage, the head NTM's headshot
        // (port: NTM's height test and multiplier; 26.2 has the mod's head zones and x1.75)
        BulletConfig du = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        check("golem: .50 DU in the body takes 45", 45F, golemHit(1.0, 45F));
        check("golem: .50 DU in the head takes 45 x NTM's headshot", 45F * du.headshotMult, golemHit(2.4, 45F));
        float[] floor = golemOverFloor(45F);
        check("golem over a floor: the head shot takes the full headshot, not what the floor behind left", 45F * du.headshotMult, floor[0]);
        check("golem over a floor: the floor behind it still costs the bullet", true, floor[1] < 45F);

        // ---- path rules in the server's file
        s = expect("path through: stone is flown through", Want.PASS, single("minecraft:stone", 0), .5, .5, 30F, null);
        check("path through: the stone stays", "minecraft:stone", s.after);

        // ---- port: 1.7.10's own ray (func_147447_a) gives up after 200 cells: a block 250 blocks away in one tick
        far();
        richterBulletSpeed();
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
