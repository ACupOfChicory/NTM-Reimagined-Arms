package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.water.OldWaterPayload;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterEvents;
import acup.gunfeel.water.WaterFx;
import acup.gunfeel.phys.Vec3;
import acup.gunfeel.water.AmmoModels;
import acup.gunfeel.water.SunkBullets;
import acup.gunfeel.water.SunkPhysics;
import acup.gunfeel.water.WaterParticle;
import acup.gunfeel.water.WaterRings;
import acup.gunfeel.water.WaterTick;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;

// Dev (-Pscenario=waterfx, ShootScenario's flat world; 26.2's WaterFxStand): the water effects (acup.gunfeel.water) in
// a game, numbers only. Four pools are built on the integrated server in the air: still, a slope of flowing water, a
// waterfall over a basin, a thin sheet. Real NTM rounds are spawned on the server at each (the client gets them as any
// shot: the tracer), and what the effects made is counted per kind per shot. The surface audit runs all along (no
// drop, foam or pop more than 0.15 under the surface, none hanging over air). Then: shots from under water (one gas
// bubble each), the NTM flash of a gun fired with its muzzle under water (hidden), a ring on the still pool seen from
// above before and after (waterfx-rings-{0,1}.png, for pixel numbers: the ring is white, not grey), a foam patch drawn
// after the water and, for the control, before it (waterfx-foam-{late,early}.png), the ring taken off
// when its water is drained, and every particle gone in the end. The rounds the water stopped (SunkBullets, 2b): their
// size from NTM's casings (a line per round), rounds drawn in flight under water and lying on the bottom, and a round
// asleep on the waterfall basin's bottom going down when the stone under it is broken (X517). Blasts at water (2c): an
// explosive g12 into the slope pool (the server's WaterBlastPayload), then a blast of size 6 given to the listeners in
// the shallow pool's water and one on its surface (as 26.2's stand: the surface given 0.9 over the centre). X566, in a
// basin of its own: a g40 grenade and a rocket fired up from under water (one gas bubble each, through joined), a grenade
// fired east sinking with its heading east, and the S12s the server sent it in its life (WaterTick.announce: at most one
// a tick). Lines in run/client/waterfx.txt and the log; quits.
final class WaterFxScenario {
    private WaterFxScenario() {
    }

    private static final int BUILD = 20, SHOTS = 120, POOL_TICKS = 70, SHOT_GAP = 15, UNDER = 420, UNDER_CHECK = 470, FLASH_GO = 480,
            FLASH_FIRE = 520, FLASH_STOP = 540, FLASH_CHECK = 560, DRY_GO = 562, DRY_FIRE = 575, DRY_STOP = 595, DRY_CHECK = 605, LOOK = 610,
            RINGS0 = 650, RING_ADD = 655, RINGS1 = 670, FOAM = 672, FOAM_LATE = 690, FOAM_EARLY = 700, DRAIN = 730, DRAIN_CHECK = 780,
            BLAST_G12 = 785, BLAST_IN = 870, BLAST_TOP = 955, BLAST_WAIT = 80, FLOOR = 1045, FLOOR_WAIT = 240, LIVE_AFTER = 220, DONE_AFTER = 20;
    private static final String[] AMMO = {"ammo_standard_p9_fmj", "ammo_standard_r762_fmj", "ammo_standard_g12", "ammo_standard_g12_slug"};
    private static final String[] POOLS = {"still", "slope", "fall", "shallow"};

    // the pools along x, twelve blocks apart, Y over the player's feet; the player hovers south of them
    private static int x0, y, z0;
    private static final List<String> lines = new ArrayList<String>();
    private static final List<String> failed = new ArrayList<String>();
    private static int[] before, eventsBefore;
    private static int ringsBefore, ringsLeft0, flash0, traced0, late0, shotEnter0, blasts0, plumes0, bounces0;
    private static final int[] perShot = new int[AMMO.length];
    // the sunk rounds: the most at once, the physics' worst frame; the floor test's step (0 looking, 1 falling, 3
    // done), its round, the tick the end's checks come
    private static int bodiesMax, floorStep, floorTick, liveCheck = Integer.MAX_VALUE;
    private static double physMsMax, sleeperY;
    private static long since;
    private static SunkPhysics.Body sleeper;

    static void step(Minecraft mc, int t) {
        if (t == BUILD) ShootScenario.onServer(WaterFxScenario::build);
        if (t == BUILD + 1) {
            mc.gameSettings.hideGUI = true;
            WaterFx.audit = true;
            sizes();
        }
        bodiesMax = Math.max(bodiesMax, SunkBullets.bodies());
        physMsMax = Math.max(physMsMax, SunkBullets.physicsMs());
        for (int p = 0; p < POOLS.length; p++) {
            int from = SHOTS + p * POOL_TICKS;
            if (t == from - 1) mark();
            for (int a = 0; a < AMMO.length; a++) {
                // each round's own splash, not only the pool's sum (a shotgun's pellets splash several times)
                if (t == from + a * SHOT_GAP - 1) shotEnter0 = enters();
                if (t == from + a * SHOT_GAP) {
                    final int pool = p, ammo = a;
                    ShootScenario.onServer(() -> fire(pool, AMMO[ammo]));
                }
                if (t == from + (a + 1) * SHOT_GAP - 2) perShot[a] = enters() - shotEnter0;
            }
            if (t == from + POOL_TICKS - 2) pool(p);
        }
        // the rounds the pools stopped lie on their bottoms (26.2's stand: the still pool's)
        if (t == UNDER - 2) {
            lines.add(String.format(Locale.ROOT, "sunk rounds: bodies %d (most %d), asleep %d, physics most %.3f ms a frame; drawn in flight %d, lying %d",
                    SunkBullets.bodies(), bodiesMax, SunkBullets.asleep(), physMsMax, SunkBullets.flyingDrawn, SunkBullets.bodiesDrawn));
            check(bodiesMax > 0 && SunkBullets.bodiesDrawn > 0, "the rounds the water stopped lie on the bottom, drawn");
        }
        if (t == UNDER - 1) mark();
        for (int i = 0; i < 3; i++) if (t == UNDER + i * SHOT_GAP) ShootScenario.onServer(WaterFxScenario::fireUnder);
        if (t == UNDER_CHECK) {
            int gas = delta(WaterParticle.Kind.GAS);
            lines.add("under water, 3 p9 shots: gas bubbles " + gas + ", rise " + delta(WaterParticle.Kind.RISE) + ", events " + events());
            check(gas == 3, "one gas bubble a shot fired under water");
            // the rounds in flight under water drawn as models: counted after the shots fired under water, as the 1.12.2
            // port's stand (a round from the air into a pool is under water in flight for less than a tick)
            lines.add("rounds drawn in flight under water: " + SunkBullets.flyingDrawn);
            check(SunkBullets.flyingDrawn > 0, "the rounds in flight under water drawn");
        }
        // a p9 round bouncing off the still pool's floor under water: the tracer goes on at the speed and damage it
        // has where it bounced, not those of the tick's start (26.2 X555)
        if (t == UNDER_CHECK + 1) {
            bounces0 = Tracers.waterBounces;
            ShootScenario.onServer(WaterFxScenario::fireBounce);
        }
        if (t == FLASH_GO - 1) {
            double q = Tracers.waterBounceQ;
            lines.add(String.format(Locale.ROOT, "tracer bounced under water: %d, speed there / at the tick's start %.3f", Tracers.waterBounces - bounces0, q));
            check(Tracers.waterBounces > bounces0 && q > 0 && q < 0.999, "a tracer bounced off a block in water goes on at the speed it had there");
        }
        if (t == FLASH_GO) ShootScenario.onServer(() -> {
            // feet on the still pool's floor: the eyes and the muzzle under water
            player().playerNetServerHandler.setPlayerLocation(x0 + 2.5, y - 3, z0 + 0.6, 0F, 0F);
            ShootScenario.give(0, "gun_am180");
        });
        if (t == FLASH_GO + 1) {
            mc.thePlayer.inventory.currentItem = 0;
            // the hand (and NTM's flash with it) is drawn only with the GUI on
            mc.gameSettings.hideGUI = false;
        }
        if (t == FLASH_FIRE) {
            flash0 = WaterFx.flashHidden;
            traced0 = WaterFx.tracedCount;
            ShootScenario.trigger(true, mc);
        }
        if (t == FLASH_STOP) ShootScenario.trigger(false, mc);
        if (t == FLASH_CHECK) {
            int hidden = WaterFx.flashHidden - flash0;
            lines.add(String.format(Locale.ROOT, "flash under water: hidden %d, shots %d, eyes %.3f %.3f %.3f", hidden, WaterFx.tracedCount - traced0,
                    mc.thePlayer.posX, mc.thePlayer.posY,
                    mc.thePlayer.posZ));
            check(hidden > 0, "NTM's flash not drawn with the muzzle under water");
        }
        // the control: the same gun fired standing on the pool's wall, the muzzle in the air (the counter counts the
        // frames NTM's flash was cut, so with no control it would pass for a check that always cuts)
        if (t == DRY_GO) ShootScenario.onServer(() -> player().playerNetServerHandler.setPlayerLocation(x0 + 2.5, y, z0 - 0.5, 0F, 0F));
        if (t == DRY_FIRE) {
            flash0 = WaterFx.flashHidden;
            traced0 = WaterFx.tracedCount;
            ShootScenario.trigger(true, mc);
        }
        if (t == DRY_STOP) ShootScenario.trigger(false, mc);
        if (t == DRY_CHECK) {
            int hidden = WaterFx.flashHidden - flash0;
            lines.add(String.format(Locale.ROOT, "flash over water: hidden %d, shots %d, eyes %.3f %.3f %.3f", hidden, WaterFx.tracedCount - traced0,
                    mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ));
            check(hidden == 0 && WaterFx.tracedCount > traced0, "NTM's flash drawn with the muzzle in the air");
        }
        if (t == LOOK) mc.gameSettings.hideGUI = true;
        if (t == LOOK) ShootScenario.onServer(() -> {
            EntityPlayerMP p = player();
            p.inventory.setInventorySlotContents(0, null);
            p.inventoryContainer.detectAndSendChanges();
            p.playerNetServerHandler.setPlayerLocation(x0 + 2.5, y + 4, z0 + 2.5, 0F, 90F);
        });
        if (t == RINGS0) {
            WaterRings.clear();
            mc.gameSettings.particleSetting = 2;
            shot(mc, "rings-0");
        }
        if (t == RING_ADD) {
            ringsLeft0 = WaterRings.leftWater;
            // a ring of 3 on the still pool's middle, long-lived
            WaterRings.add(mc.theWorld, x0 + 2.5, y - 1 + 8 / 9.0, z0 + 2.5, 0, 1000, 3, 3, 0.75F, false, false);
        }
        if (t == RINGS1) shot(mc, "rings-1");
        // a still foam patch on the pool's corner, out of the ring: drawn after the water (on the camera's side) it
        // stays white; drawn before it, as the engine draws particles, the water comes over it (waterfx-foam-*.png)
        if (t == FOAM) {
            boolean made = WaterFx.standFoam(mc.theWorld, x0 + 0.6, y - 1 + 8 / 9.0 + 0.02, z0 + 4.4, 2.5, 0.6);
            check(made, "the stand's foam patch made");
        }
        if (t == FOAM_LATE) {
            late0 = WaterParticle.lateDrawn;
            shot(mc, "foam-late");
        }
        if (t == FOAM_LATE + 1) check(WaterParticle.lateDrawn > late0, "the foam drawn at the frame's end");
        if (t == FOAM_EARLY - 2) WaterParticle.lateOff = true;
        if (t == FOAM_EARLY) {
            shot(mc, "foam-early");
            WaterParticle.lateOff = false;
        }
        if (t == DRAIN) ShootScenario.onServer(WaterFxScenario::drain);
        if (t == DRAIN_CHECK) {
            int left = WaterRings.leftWater - ringsLeft0;
            lines.add("rings taken off as their water went: " + left + ", alive now " + WaterRings.alive());
            check(left > 0 && WaterRings.alive() == 0, "a ring goes when its water goes");
        }
        // blasts at water (2c, 26.2's stand the same): through the server, then given straight to the listeners
        if (t == BLAST_G12) {
            blastMark();
            ShootScenario.onServer(() -> fire(1, "ammo_standard_g12_explosive"));
        }
        if (t == BLAST_G12 + BLAST_WAIT) {
            lines.add("explosive g12 into the slope pool: blasts under water " + (WaterFx.blasts - blasts0) + ", columns " + (WaterFx.plumes - plumes0)
                    + ", particles " + kinds() + ", widest ring reach " + WaterRings.widest + " cells");
            check(WaterFx.plumes - plumes0 + WaterFx.blasts - blasts0 > 0, "a blast at water reaches the effects (WaterBlastPayload, listenBlasts)");
        }
        if (t == BLAST_IN) {
            blastMark();
            // the shallow pool, not the waterfall basin: its blasts would wake the rounds the floor test wants asleep
            Vec3 at = new Vec3(x0 + 37.5, y - 1 + 0.05, z0 + 6.5);
            WaterEvents.blast(mc.theWorld, new WaterEvents.Blast(at, 6F, false, true, new Vec3(at.x, at.y + 0.9, at.z), WaterEvents.Liquid.WATER));
        }
        if (t == BLAST_IN + BLAST_WAIT) {
            lines.add("blast of size 6 given in the shallow pool: blasts under water " + (WaterFx.blasts - blasts0) + ", columns "
                    + (WaterFx.plumes - plumes0) + ", particles " + kinds() + ", widest ring reach " + WaterRings.widest + " cells");
            check(WaterFx.blasts - blasts0 == 1 && WaterFx.plumes - plumes0 == 1, "a blast in water: bubbles and a push, a column over it");
            check(WaterRings.widest > 3, "a big blast's rings not cut at 3 cells");
        }
        // the same blast with its centre on the surface: the column only, no bubble under water
        if (t == BLAST_TOP) {
            blastMark();
            Vec3 at = new Vec3(x0 + 37.5, y - 1 + 0.95, z0 + 6.5);
            WaterEvents.blast(mc.theWorld, new WaterEvents.Blast(at, 6F, false, true, at, WaterEvents.Liquid.WATER));
        }
        if (t == BLAST_TOP + BLAST_WAIT) {
            lines.add("blast of size 6 on the shallow pool's surface: blasts under water " + (WaterFx.blasts - blasts0) + ", columns "
                    + (WaterFx.plumes - plumes0) + ", particles " + kinds());
            check(WaterFx.blasts - blasts0 == 0 && WaterFx.plumes - plumes0 == 1, "a blast on the surface: the column only, no bubble");
        }
        if (t == FLOOR) ShootScenario.onServer(WaterFxScenario::fireFloor);
        // ShootScenario.onServer holds one task: the next one two ticks after the last
        if (t > FLOOR + 2 && floorStep < 3) floorGone(t);
        if (x566Step > 0 && x566Step < 99) x566(mc, t);
        if (oldStep > 0 && oldStep < 99) old(t);
        if (t == liveCheck) {
            lines.add("surface audit: under the surface by more than 0.15: " + WaterFx.below + ", over air: " + WaterFx.overAir + ", deepest "
                    + String.format(Locale.ROOT, "%.3f", WaterFx.worstBelow));
            check(WaterFx.below == 0 && WaterFx.overAir == 0, "no particle under the surface by 0.15 or over air");
            lines.add("particles of ours alive at the end: " + WaterParticle.live);
            check(WaterParticle.live == 0, "every particle gone, the count back to 0");
            WaterFx.audit = false;
            write();
        }
        if (t == liveCheck + DONE_AFTER) {
            GunFeel.LOGGER.info("Scenario waterfx: done");
            mc.shutdown();
        }
    }

    // ---- X517: a round asleep on the waterfall basin's bottom (the still pool is drained by now), the stone under it
    // (3 × 3) taken away on the server: it goes down a block within a second (26.2's stand: the still pool's). The stone
    // is put back after, so the basin's water stops running out ----
    private static void floorGone(int t) {
        if (floorStep == 0) {
            sleeper = null;
            for (SunkPhysics.Body b : SunkBullets.list())
                if (b.asleep && b.px > x0 + 22 && b.px < x0 + 27 && b.pz > z0 && b.pz < z0 + 5 && b.py < y - 2.5) sleeper = b;
            if (sleeper == null) {
                if (t < FLOOR + FLOOR_WAIT) return;
                lines.add("floor gone: no round asleep on the waterfall basin's bottom");
                check(false, "a round asleep on the bottom");
                end(t);
                return;
            }
            sleeperY = sleeper.py;
            final int bx = (int) Math.floor(sleeper.px), bz = (int) Math.floor(sleeper.pz);
            lines.add(String.format(Locale.ROOT, "floor gone: a round asleep at %.2f %.3f %.2f; bodies %d, asleep %d, physics %.4f ms a frame", sleeper.px,
                    sleeper.py, sleeper.pz, SunkBullets.bodies(), SunkBullets.asleep(), SunkBullets.physicsMs()));
            floorX = bx;
            floorZ = bz;
            ShootScenario.onServer(() -> floor(bx, bz, Blocks.air));
            floorStep = 1;
            floorTick = t;
            since = 0;
            return;
        }
        // the time from when the client has the hole (not from the server's task: its tick and the packet are no
        // part of the physics)
        if (floorStep == 1 && since == 0 && Minecraft.getMinecraft().theWorld.getBlock(floorX, y - 4, floorZ) != Blocks.stone) since = System.nanoTime();
        if (floorStep == 1 && since == 0 && t > floorTick + 100) {
            lines.add("floor gone: the client never saw the stone go");
            check(false, "a round on the bottom falls when the block under it is broken");
            end(t);
            return;
        }
        if (floorStep == 1 && since != 0 && t > floorTick + 2) {
            double drop = sleeperY - sleeper.py, s = (System.nanoTime() - since) / 1e9;
            if (drop < 1 && s < 3 && SunkBullets.list().contains(sleeper)) return;
            lines.add(String.format(Locale.ROOT, "floor gone: the round went down %.2f blocks in %.2f s (asleep %b)", drop, s, sleeper.asleep));
            check(drop >= 1 && s <= 1, "a round on the bottom falls when the block under it is broken");
            // the stone back where it was taken (the round's block when it lay there)
            final int fx = floorX, fz = floorZ;
            ShootScenario.onServer(() -> floor(fx, fz, Blocks.stone));
            end(t);
        }
    }

    private static int floorX, floorZ;

    // the basin's floor (y - 4) under (bx, bz), 3 × 3
    private static void floor(int bx, int bz, Block b) {
        World w = player().worldObj;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(w, bx + x, y - 4, bz + z, b);
    }

    private static void end(int t) {
        floorStep = 3;
        // X566 next, the end's checks after it
        x566Step = 1;
    }

    // ---- X566: a grenade and a rocket shot from a metre and a half under water, straight up: NTM's own entity, no
    // tracer of ours, one gas bubble each (WaterFx.joined, once the server's motion of it came). Then a grenade shot
    // east along a channel 8 deep: water stops it, it sinks, and its model lies east (the way it moved, kept on the
    // client), not along NTM's yaw of a fall straight down; the S12s the server sent for it (WaterTick.announce), in
    // all and a tick ----
    private static int x566Step, x566Tick;
    private static volatile int sentId = -1;
    private static volatile EntityBulletBaseMK4 sent;
    private static int stoppedTick;
    private static Vec3 seenAt;
    private static final String[] GAS_AMMO = {"ammo_standard_g40_he", "ammo_standard_rocket_he"};

    // the channel north of the pools: 14 long (x), 3 wide, 8 deep, the floor at y - 9
    private static int chX() {
        return x0 + 2;
    }

    private static int chZ() {
        return z0 - 7;
    }

    private static void x566(Minecraft mc, int t) {
        Vec3 from = new Vec3(chX() + 1.2, y - 3.5, chZ() + 1.5), up = new Vec3(chX() + 7.5, y - 1.5, chZ() + 1.5);
        if (x566Step == 1) {
            WaterTick.sentCount = new java.util.concurrent.ConcurrentHashMap<Integer, Integer>();
            ShootScenario.onServer(() -> basin(player().worldObj, chX(), chZ(), 14, 3, y - 9, 8, 8));
            x566Step = 2;
            x566Tick = t;
            return;
        }
        if (x566Step == 2) {
            boolean wet = WaterFx.wetAt(mc.theWorld, from) && WaterFx.wetAt(mc.theWorld, from.add(12, 0, 0)) && WaterFx.wetAt(mc.theWorld, up);
            if (!wet || t < x566Tick + 20) {
                if (t > x566Tick + 200) {
                    check(false, "the grenades' channel reached the client");
                    x566Done(t);
                }
                return;
            }
            x566Step = 3;
            x566Tick = t;
        }
        // 3, 4: the gas bubbles
        if (x566Step == 3 || x566Step == 4) {
            String ammo = GAS_AMMO[x566Step - 3];
            BulletConfig c = config(ammo);
            if (c == null) {
                lines.add("MISSING config " + ammo);
                check(false, "one gas bubble of " + ammo + " shot from under water");
                x566Step++;
                x566Tick = t + 1; // the next shot fires on the next tick (t == x566Tick)
                return;
            }
            if (t == x566Tick) {
                mark();
                ShootScenario.onServer(() -> sent = ntmShot(c, up, 0, 1, 0));
            }
            // off the server before it goes off anywhere: a few ticks, for its motion to reach the client first
            if (t == x566Tick + 8) {
                EntityBulletBaseMK4 b = sent;
                ShootScenario.onServer(() -> {
                    if (b != null && !b.isDead) b.setDead();
                });
            }
            if (t < x566Tick + 60) return;
            int gas = delta(WaterParticle.Kind.GAS);
            lines.add("from under water " + ammo + " (speed " + c.velocity + ", traced " + Tracers.traced(c) + "): gas bubbles " + gas + ", particles " + kinds());
            check(gas == 1, "one gas bubble of " + ammo + " shot from under water");
            x566Step++;
            x566Tick = t + 1; // the next shot fires on the next tick (t == x566Tick)
            return;
        }
        if (x566Step == 5) {
            BulletConfig c = config(GAS_AMMO[0]);
            if (c == null) {
                x566Done(t);
                return;
            }
            sent = null;
            sentId = -1;
            stoppedTick = -1;
            seenAt = null;
            ShootScenario.onServer(() -> {
                sent = ntmShot(c, from, 1, 0, 0);
                sentId = sent.getEntityId();
            });
            x566Step = 6;
            x566Tick = t;
            return;
        }
        if (x566Step == 6) {
            Entity e = sentId < 0 ? null : mc.theWorld.getEntityByID(sentId);
            if (e != null) seenAt = new Vec3(e.posX, e.posY, e.posZ);
            if (stoppedTick < 0 && e != null && WaterFx.wetAt(mc.theWorld, seenAt) && SunkBullets.sinking(e)) stoppedTick = t;
            // half a second after it stopped: sinking straight down by then, NTM's yaw turned
            if (stoppedTick < 0 || t < stoppedTick + 10) {
                if (t > x566Tick + 160) {
                    lines.add("grenade along the channel: not seen stopped by water (on the client now: " + (e != null) + ", last seen at " + seenAt + ")");
                    check(false, "the grenade in the channel stopped by water");
                    x566Step = 7;
                    x566Tick = t;
                }
                return;
            }
            Vec3 h = e == null ? Vec3.ZERO : SunkBullets.headingOf(e);
            lines.add(String.format(Locale.ROOT, "grenade along the channel, east: sinking after %.2f s at x %.2f (from x %.2f), sunk model heading %.3f %.3f %.3f, "
                    + "NTM yaw now %.1f, motion %.3f %.3f %.3f", (stoppedTick - x566Tick) / 20.0, e == null ? Double.NaN : e.posX, from.x, h.x, h.y, h.z,
                    e == null ? Float.NaN : e.rotationYaw, e == null ? Double.NaN : e.motionX, e == null ? Double.NaN : e.motionY, e == null ? Double.NaN : e.motionZ));
            check(h.x > 0.95, "the sinking grenade lies the way it flew (east)");
            x566Step = 7;
            x566Tick = t;
            return;
        }
        if (x566Step == 7) {
            // its S12s over its whole life: till it goes off on the floor (or 10 s)
            EntityBulletBaseMK4 b = sent;
            if (b != null && !b.isDead && t < x566Tick + 200) return;
            Integer n = b == null || WaterTick.sentCount == null ? null : WaterTick.sentCount.get(b.getEntityId());
            int sent12 = n == null ? 0 : n, lived = b == null ? 0 : b.ticksExisted;
            lines.add(String.format(Locale.ROOT, "S12 for the sunk grenade: %d in its life of %d ticks (%.3f a tick), gone %b", sent12, lived,
                    lived == 0 ? Double.NaN : sent12 / (double) lived, b != null && b.isDead));
            // a start in water, a stop, and one each RESEND ticks after
            check(sent12 >= 2 && sent12 <= 3 + lived / 20, "the sunk grenade's motion sent when it changes and once a second, not each tick");
            ShootScenario.onServer(() -> {
                if (b != null && !b.isDead) b.setDead();
            });
            x566Done(t);
        }
    }

    private static void x566Done(int t) {
        x566Step = 99;
        WaterTick.sentCount = null;
        // the old rounds next, the end's checks after them
        oldStep = 1;
        oldTick = t + 2;
    }

    // ---- port: NTM 1.7.10's old rounds (EntityBulletBaseNT) in water, the channel filled anew: the masked man's tracer
    // (no fuze) from the air at 30° gives the splash, the stop and the trail of bubbles from the server's ticks
    // (OldWaterPayload); the masked man's bolt (an impact fuze) no splash, its blast's column (WaterBlastPayload) ----
    private static int oldStep, oldTick, oldDrained0, oldTrail0;

    private static void old(int t) {
        if (t == oldTick) {
            ShootScenario.onServer(() -> basin(player().worldObj, chX(), chZ(), 14, 3, y - 9, 8, 8));
            // all particles (the rings' pictures left it at minimal): the trail's bubbles not scaled away
            Minecraft.getMinecraft().gameSettings.particleSetting = 0;
        }
        if (t == oldTick + 5) oldShot(com.hbm.handler.BulletConfigSyncingUtil.MASKMAN_TRACER);
        if (t == oldTick + 45) {
            int drained = OldWaterPayload.drained - oldDrained0;
            int trail = WaterFx.oldTrailMade - oldTrail0;
            lines.add("old tracer into water from the air: server ticks " + drained + ", events " + events() + ", trail bubbles " + trail + ", particles " + kinds());
            check(drained > 0 && evDelta(WaterEvents.Type.ENTER) >= 1 && evDelta(WaterEvents.Type.STOP) >= 1 && trail > 0,
                    "an old round's splash, stop and trail come from the server (OldWaterPayload)");
            oldShot(com.hbm.handler.BulletConfigSyncingUtil.MASKMAN_BOLT);
        }
        if (t == oldTick + 90) {
            int drained = OldWaterPayload.drained - oldDrained0;
            lines.add("old bolt (impact fuze) into water from the air: server ticks " + drained + ", events " + events() + ", columns "
                    + (WaterFx.plumes - plumes0) + ", blasts under water " + (WaterFx.blasts - blasts0) + ", particles " + kinds());
            check(drained > 0 && evDelta(WaterEvents.Type.ENTER) == 0 && WaterFx.plumes - plumes0 + WaterFx.blasts - blasts0 > 0,
                    "an old round with an impact fuze: no splash where it comes in, its blast's column");
            oldStep = 99;
            liveCheck = t + LIVE_AFTER;
        }
    }

    // from 3 blocks off over the channel, east and down at 30°, onto its surface 5 blocks in
    private static void oldShot(int id) {
        mark();
        blastMark();
        oldDrained0 = OldWaterPayload.drained;
        oldTrail0 = WaterFx.oldTrailMade;
        double a = Math.toRadians(30), dx = Math.cos(a), dy = -Math.sin(a);
        double tx = chX() + 5.5, ty = y - 1 + 8 / 9.0, tz = chZ() + 1.5;
        ShootScenario.onServer(() -> {
            World w = player().worldObj;
            com.hbm.entity.projectile.EntityBulletBaseNT b = new com.hbm.entity.projectile.EntityBulletBaseNT(w, id);
            b.setPosition(tx - dx * 3, ty - dy * 3, tz);
            b.setThrowableHeading(dx, dy, 0, 1F, 0F);
            w.spawnEntityInWorld(b);
        });
    }

    private static int evDelta(WaterEvents.Type type) {
        return WaterFx.SPAWNED_EVENTS[type.ordinal()] - eventsBefore[type.ordinal()];
    }

    // a round as NTM's gun shoots it: a motion of length 1, the speed NTM's motionMult
    private static EntityBulletBaseMK4 ntmShot(BulletConfig c, Vec3 at, double dx, double dy, double dz) {
        World w = player().worldObj;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(w, c, 10F, 0F, 0F, 0F);
        b.setPosition(at.x, at.y, at.z);
        b.setThrowableHeading(dx, dy, dz, 1F, 0F);
        w.spawnEntityInWorld(b);
        return b;
    }

    // three p9 rounds at the waterfall basin's west half, from the north at 60° down (clear of the falling column)
    private static void fireFloor() {
        BulletConfig c = config("ammo_standard_p9_fmj");
        if (c == null) return;
        double dy = -Math.sin(Math.toRadians(60)), dz = Math.cos(Math.toRadians(60));
        for (int i = 0; i < 3; i++) spawn(c, x0 + 22.8 + 0.4 * i, y - 1 + 8 / 9.0 - dy * 4, z0 + 3.0 - dz * 4, 0, dy, dz);
    }

    // the size of every round in water, from NTM's casing of it (SunkBullets.model), once per round; the four the
    // pools are shot with sized by their casing
    private static void sizes() {
        Map<String, String> out = new TreeMap<String, String>();
        int byCasing = 0;
        for (BulletConfig c : new java.util.ArrayList<BulletConfig>(BulletConfig.configs)) {
            String a = c == null ? null : WaterFx.ammo(c);
            AmmoModels.Model m = a == null ? null : SunkBullets.model(c);
            if (m == null || out.containsKey(a)) continue;
            if (m.byCasing) byCasing++;
            out.put(a, String.format(Locale.ROOT, "%s len %.4f d %.4f %s", a, m.length, 2 * m.radius,
                    c.casing == null ? "no casing" : (m.byCasing ? "casing " : "casing not sized ") + c.casing.getType()));
        }
        lines.add("sizes: " + out.size() + " rounds, by casing " + byCasing);
        for (String v : out.values()) lines.add("size " + v);
        boolean ours = true;
        for (String a : AMMO) {
            BulletConfig c = config(a);
            AmmoModels.Model m = c == null ? null : SunkBullets.model(c);
            ours &= m != null && m.byCasing;
        }
        check(ours, "the pools' four rounds sized by NTM's casings");
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
    }

    private static void set(World w, int x, int yy, int z, Block b) {
        w.setBlock(x, yy, z, b, 0, 3);
    }

    // a stone box: the floor at fy, walls up to fy + h, the inside (w × d) water from fy + 1 for `water` layers
    private static void basin(World w, int bx, int bz, int wd, int dp, int fy, int h, int water) {
        for (int x = -1; x <= wd; x++) for (int z = -1; z <= dp; z++) {
            set(w, bx + x, fy, bz + z, Blocks.stone);
            for (int k = 1; k <= h; k++) {
                boolean wall = x < 0 || z < 0 || x >= wd || z >= dp;
                set(w, bx + x, fy + k, bz + z, wall ? Blocks.stone : k <= water ? Blocks.water : Blocks.air);
            }
        }
    }

    private static void build() {
        EntityPlayerMP p = player();
        World w = p.worldObj;
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        w.setWorldTime(6000);
        int fx = (int) Math.floor(p.posX), fy = (int) Math.floor(p.posY), fz = (int) Math.floor(p.posZ);
        x0 = fx - 18;
        y = fy + 12;
        z0 = fz + 6;
        // still: 5 × 5, 3 deep, the surface at y - 1 + 8/9
        basin(w, x0, z0, 5, 5, y - 4, 3, 3);
        // slope: a channel 3 wide and 9 long, one source at its north end (flowing: still water put by setBlock stays put)
        basin(w, x0 + 12, z0, 3, 9, y - 2, 2, 0);
        set(w, x0 + 13, y - 1, z0, Blocks.flowing_water);
        // waterfall: a basin as the still one; a source on a shelf north of its middle, walled but to the south, falls
        // into it from y + 4
        basin(w, x0 + 22, z0, 5, 5, y - 4, 3, 3);
        int cx = x0 + 24, cz = z0 + 2;
        set(w, cx, y + 3, cz - 1, Blocks.stone);
        set(w, cx - 1, y + 4, cz - 1, Blocks.stone);
        set(w, cx + 1, y + 4, cz - 1, Blocks.stone);
        set(w, cx, y + 4, cz - 2, Blocks.stone);
        set(w, cx, y + 4, cz - 1, Blocks.flowing_water);
        // shallow: a channel as the slope's; its far end is a thin sheet
        basin(w, x0 + 36, z0, 3, 9, y - 2, 2, 0);
        set(w, x0 + 37, y - 1, z0, Blocks.flowing_water);
        p.capabilities.isFlying = true;
        p.sendPlayerAbilities();
        p.playerNetServerHandler.setPlayerLocation(x0 + 18.5, y + 6, z0 - 8.5, 0F, 30F);
        lines.add("pools from " + x0 + "," + y + "," + z0);
    }

    private static BulletConfig config(String ammo) {
        for (BulletConfig k : BulletConfig.configs) if (k != null && WaterClass.ammoName(k).equals(ammo)) return k;
        return null;
    }

    private static EntityBulletBaseMK4 spawn(BulletConfig c, double x, double yy, double z, double dx, double dy, double dz) {
        World w = player().worldObj;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(w, c, 1F, 0F, 0F, 0F);
        b.setPosition(x, yy, z);
        // NTM's speed (the server's motionMult adds bulletSpeed, as for a gun's shot)
        b.setThrowableHeading(dx, dy, dz, c.velocity, 0F);
        b.damage = 10F;
        w.spawnEntityInWorld(b);
        return b;
    }

    // at the pool's spot from 4 blocks off: down at 60° from the north into the still, slope and shallow ones,
    // level from the west into the waterfall
    private static void fire(int pool, String ammo) {
        BulletConfig c = config(ammo);
        if (c == null) {
            lines.add("MISSING config " + ammo);
            return;
        }
        double tx, ty, tz;
        switch (pool) {
            case 0:
                tx = x0 + 2.5;
                ty = y - 1 + 8 / 9.0;
                tz = z0 + 2.5;
                break;
            case 1:
                tx = x0 + 13.5;
                ty = y - 1 + 0.5;
                tz = z0 + 4.5;
                break;
            case 2: {
                spawn(c, x0 + 21.0, y + 1.5, z0 + 2.5, 1, 0, 0);
                return;
            }
            default:
                tx = x0 + 37.5;
                ty = y - 1 + 0.25;
                tz = z0 + 6.5;
        }
        double dy = -Math.sin(Math.toRadians(60)), dz = Math.cos(Math.toRadians(60));
        spawn(c, tx, ty - dy * 4, tz - dz * 4, 0, dy, dz);
    }

    // a p9 round from a metre under the still pool's surface, along it
    private static void fireUnder() {
        BulletConfig c = config("ammo_standard_p9_fmj");
        if (c != null) spawn(c, x0 + 0.5, y - 2.0, z0 + 2.5, 1, 0, 0.1);
    }

    // a p9 round 8 cm over the still pool's floor, east and down at 2.3°: it meets the floor in 2 blocks, before the
    // water stops it, and glances off (NTM's ricochetAngle 5°)
    private static void fireBounce() {
        BulletConfig c = config("ammo_standard_p9_fmj");
        if (c != null) spawn(c, x0 + 0.5, y - 3 + 0.08, z0 + 2.5, 1, -0.04, 0);
    }

    private static void drain() {
        World w = player().worldObj;
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) for (int k = 1; k <= 3; k++) set(w, x0 + x, y - 4 + k, z0 + z, Blocks.air);
    }

    private static int enters() {
        return WaterFx.SPAWNED_EVENTS[WaterEvents.Type.ENTER.ordinal()];
    }

    private static void mark() {
        late0 = WaterParticle.lateDrawn;
        before = WaterFx.SPAWNED.clone();
        eventsBefore = WaterFx.SPAWNED_EVENTS.clone();
        ringsBefore = WaterRings.made;
        traced0 = WaterFx.tracedCount;
    }

    private static void blastMark() {
        mark();
        blasts0 = WaterFx.blasts;
        plumes0 = WaterFx.plumes;
        WaterRings.widest = 0;
    }

    private static int delta(WaterParticle.Kind k) {
        return WaterFx.SPAWNED[k.ordinal()] - before[k.ordinal()];
    }

    private static String events() {
        StringBuilder sb = new StringBuilder();
        for (WaterEvents.Type t : WaterEvents.Type.values()) {
            int n = WaterFx.SPAWNED_EVENTS[t.ordinal()] - eventsBefore[t.ordinal()];
            if (n > 0) sb.append(sb.length() > 0 ? " " : "").append(t.name().toLowerCase(Locale.ROOT)).append(' ').append(n);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String kinds() {
        StringBuilder sb = new StringBuilder();
        for (WaterParticle.Kind k : WaterParticle.Kind.values()) {
            int n = delta(k);
            if (n > 0) sb.append(sb.length() > 0 ? " " : "").append(k.name().toLowerCase(Locale.ROOT)).append(' ').append(n);
        }
        return sb.toString();
    }

    private static void pool(int p) {
        int rings = WaterRings.made - ringsBefore;
        lines.add(POOLS[p] + ", " + AMMO.length + " shots (tracers " + (WaterFx.tracedCount - traced0) + "): " + kinds() + "; rings " + rings + "; events "
                + events() + "; splashes a shot " + Arrays.toString(perShot) + "; drawn after the water " + (WaterParticle.lateDrawn - late0));
        boolean each = true;
        for (int n : perShot) each &= n > 0;
        check(each, POOLS[p] + ": a splash for every shot");
        check(WaterParticle.lateDrawn > late0, POOLS[p] + ": splash particles over the water drawn after it");
        check(delta(WaterParticle.Kind.DROP) > 0 && delta(WaterParticle.Kind.FOAM) > 0 && delta(WaterParticle.Kind.MIST) > 0,
                POOLS[p] + ": drops, foam and water dust");
        // the waterfall's burst has no ring of its own; its drops landing in the basin below make small ones
        if (p != 2) check(rings >= AMMO.length, POOLS[p] + ": rings on the surface");
    }

    private static void shot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, "waterfx-" + name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        lines.add(String.format(Locale.ROOT, "screenshot waterfx-%s.png camera %.3f %.3f %.3f pitch %.1f, rings alive %d", name, mc.thePlayer.posX,
                mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.rotationPitch, WaterRings.alive()));
    }

    private static void check(boolean ok, String what) {
        lines.add((ok ? "OK   " : "FAIL ") + what);
        if (!ok) failed.add(what);
    }

    private static void write() {
        lines.add(failed.isEmpty() ? "ALL OK" : "FAILED " + failed.size() + ": " + Arrays.toString(failed.toArray()));
        try (PrintWriter pw = new PrintWriter(new File(Minecraft.getMinecraft().mcDataDir, "waterfx.txt"), StandardCharsets.UTF_8.name())) {
            for (String l : lines) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario waterfx: waterfx.txt", e);
        }
        for (String l : lines) GunFeel.LOGGER.info("Scenario waterfx: {}", l);
    }
}
