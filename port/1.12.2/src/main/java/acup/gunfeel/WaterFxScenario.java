package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.particle.SpentCasing;

import acup.gunfeel.water.AmmoModels;
import acup.gunfeel.water.SunkBullets;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterEvents;
import acup.gunfeel.water.WaterFx;
import acup.gunfeel.water.WaterParticle;
import acup.gunfeel.water.WaterRings;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

// Dev (-Dntmgunfeel.scenario=waterfx, ShootScenario's flat world; 26.2's WaterFxStand, after the 1.7.10 port's): the
// water effects (acup.gunfeel.water) in a game, numbers only. Four pools are built on the integrated server in the air:
// still, a slope of flowing water, a waterfall over a basin, a thin sheet. Real NTM rounds are spawned on the server at
// each (the client gets them as any shot: the tracer), and what the effects made is counted per kind per shot. The
// surface audit runs all along (no drop, foam or pop more than 0.15 under the surface, none hanging over air). Then:
// shots from under water (one gas bubble each, a body on the bottom each), the NTM flash of a gun fired with its
// muzzle under water (hidden), a ring on the still pool seen from above before and after (waterfx-rings-{0,1}.png, for
// pixel numbers), the ring taken off when its water is drained, an NTM blast in the waterfall's basin told to the
// client (the bubble, the column), the rounds' models drawn in flight, and every particle gone in the end. Lines in
// run/forge/waterfx.txt and the log; quits.
final class WaterFxScenario {
    private WaterFxScenario() {
    }

    private static final int BUILD = 20, SHOTS = 120, POOL_TICKS = 70, SHOT_GAP = 15, UNDER = 420, UNDER_CHECK = 470, FLASH_GO = 480,
            FLASH_FIRE = 520, FLASH_STOP = 540, FLASH_CHECK = 560, LOOK = 580, RINGS0 = 620, RING_ADD = 625, RINGS1 = 640, DRAIN = 650,
            DRAIN_CHECK = 700, BLAST = 720, BLAST_CHECK = 760, LIVE_CHECK = 1000, DONE = 1020;
    private static final String[] AMMO = {"ammo_standard_p9_fmj", "ammo_standard_r762_fmj", "ammo_standard_g12", "ammo_standard_g12_slug"};
    private static final String[] POOLS = {"still", "slope", "fall", "shallow"};

    // the pools along x, twelve blocks apart, Y over the player's feet; the player hovers south of them
    private static int x0, y, z0;
    private static final List<String> lines = new ArrayList<String>();
    private static final List<String> failed = new ArrayList<String>();
    private static int[] before, eventsBefore;
    private static int ringsBefore, ringsLeft0, flash0, traced0, blasts0, plumes0, bodies0, maxFlying, maxBodies;

    static void step(Minecraft mc, int t) {
        maxFlying = Math.max(maxFlying, SunkBullets.drawnFlying);
        maxBodies = Math.max(maxBodies, SunkBullets.drawnBodies);
        if (t == BUILD) ShootScenario.onServer(WaterFxScenario::build);
        if (t == BUILD + 1) {
            mc.gameSettings.hideGUI = true;
            WaterFx.audit = true;
            sizes();
        }
        for (int p = 0; p < POOLS.length; p++) {
            int from = SHOTS + p * POOL_TICKS;
            if (t == from - 1) mark();
            for (int a = 0; a < AMMO.length; a++) if (t == from + a * SHOT_GAP) {
                final int pool = p, ammo = a;
                ShootScenario.onServer(() -> fire(pool, AMMO[ammo]));
            }
            if (t == from + POOL_TICKS - 2) pool(p);
        }
        if (t == UNDER - 1) {
            mark();
            bodies0 = SunkBullets.bodies();
        }
        for (int i = 0; i < 3; i++) if (t == UNDER + i * SHOT_GAP) ShootScenario.onServer(WaterFxScenario::fireUnder);
        if (t == UNDER_CHECK) {
            int gas = delta(WaterParticle.Kind.GAS), bodies = SunkBullets.bodies() - bodies0;
            lines.add(String.format(Locale.ROOT, "under water, 3 p9 shots: gas bubbles %d, rise %d, events %s; bodies %d (asleep %d, all %d), drawn %d, physics %.3f ms",
                    gas, delta(WaterParticle.Kind.RISE), events(), bodies, SunkBullets.asleep(), SunkBullets.bodies(), SunkBullets.drawnBodies,
                    SunkBullets.physicsMs()));
            check(gas == 3, "one gas bubble a shot fired under water");
            check(bodies >= 3, "a body for each round the water stopped");
            check(SunkBullets.drawnBodies > 0, "the bodies drawn (the lit particles' layer)");
        }
        if (t == FLASH_GO) ShootScenario.onServer(() -> {
            // feet on the still pool's floor: the eyes and the muzzle under water
            player().connection.setPlayerLocation(x0 + 2.5, y - 3, z0 + 0.6, 0F, 0F);
            ShootScenario.give(0, "gun_am180");
        });
        if (t == FLASH_GO + 1) {
            mc.player.inventory.currentItem = 0;
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
            lines.add(String.format(Locale.ROOT, "flash under water: hidden %d, shots %d, feet %.3f %.3f %.3f", hidden, WaterFx.tracedCount - traced0,
                    mc.player.posX, mc.player.posY, mc.player.posZ));
            check(hidden > 0, "NTM's flash not drawn with the muzzle under water");
        }
        if (t == LOOK) {
            mc.gameSettings.hideGUI = true;
            ShootScenario.onServer(() -> {
                EntityPlayerMP p = player();
                p.inventory.setInventorySlotContents(0, ItemStack.EMPTY);
                p.inventoryContainer.detectAndSendChanges();
                p.connection.setPlayerLocation(x0 + 2.5, y + 4, z0 + 2.5, 0F, 90F);
            });
        }
        if (t == RINGS0) {
            WaterRings.clear();
            mc.gameSettings.particleSetting = 2;
            shot(mc, "rings-0");
        }
        if (t == RING_ADD) {
            ringsLeft0 = WaterRings.leftWater;
            // a ring of 3 on the still pool's middle, long-lived
            WaterRings.add(mc.world, x0 + 2.5, y - 1 + 8 / 9.0, z0 + 2.5, 0, 1000, 3, 3, 0.75F, false, false);
        }
        if (t == RINGS1) shot(mc, "rings-1");
        if (t == DRAIN) ShootScenario.onServer(WaterFxScenario::drain);
        if (t == DRAIN_CHECK) {
            int left = WaterRings.leftWater - ringsLeft0;
            lines.add("rings taken off as their water went: " + left + ", alive now " + WaterRings.alive());
            check(left > 0 && WaterRings.alive() == 0, "a ring goes when its water goes");
        }
        if (t == BLAST) {
            mc.gameSettings.particleSetting = 0;
            blasts0 = WaterFx.blasts;
            plumes0 = WaterFx.plumes;
            ShootScenario.onServer(() -> {
                // NTM's blast with no processors: nothing broken or hurt, only told (MixinWaterBlast, WaterBlastMessage)
                new ExplosionVNT(player().world, x0 + 24.5, y - 2.5, z0 + 3.5, 2F).explode();
            });
        }
        if (t == BLAST_CHECK) {
            int b = WaterFx.blasts - blasts0, pl = WaterFx.plumes - plumes0;
            lines.add("an NTM blast 2.4 under the basin's surface: blasts " + b + ", columns " + pl);
            check(b == 1, "a blast under water told to the client: its bubble");
            check(pl == 1, "and its column on the surface");
        }
        if (t == LIVE_CHECK) {
            lines.add("surface audit: under the surface by more than 0.15: " + WaterFx.below + ", over air: " + WaterFx.overAir + ", deepest "
                    + String.format(Locale.ROOT, "%.3f", WaterFx.worstBelow));
            check(WaterFx.below == 0 && WaterFx.overAir == 0, "no particle under the surface by 0.15 or over air");
            lines.add("rounds drawn in flight under water, most in a frame: " + maxFlying + "; bodies drawn, most in a frame: " + maxBodies);
            check(maxFlying > 0, "the rounds' models drawn in flight under water");
            lines.add("particles of ours alive at the end: " + WaterParticle.live);
            check(WaterParticle.live == 0, "every particle gone, the count back to 0");
            List<String> wrong = new ArrayList<String>();
            lines.add("mixins: " + MixinCheck.report(wrong));
            check(wrong.isEmpty(), "every mixin's handlers in place (MixinCheck)" + (wrong.isEmpty() ? "" : ": " + wrong));
            WaterFx.audit = false;
            write();
        }
        if (t == DONE) {
            GunFeel.LOGGER.info("Scenario waterfx: done");
            mc.shutdown();
        }
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
    }

    private static void set(World w, int x, int yy, int z, IBlockState s) {
        w.setBlockState(new BlockPos(x, yy, z), s, 3);
    }

    // a stone box: the floor at fy, walls up to fy + h, the inside (w × d) water from fy + 1 for `water` layers
    private static void basin(World w, int bx, int bz, int wd, int dp, int fy, int h, int water) {
        for (int x = -1; x <= wd; x++) for (int z = -1; z <= dp; z++) {
            set(w, bx + x, fy, bz + z, Blocks.STONE.getDefaultState());
            for (int k = 1; k <= h; k++) {
                boolean wall = x < 0 || z < 0 || x >= wd || z >= dp;
                set(w, bx + x, fy + k, bz + z, wall ? Blocks.STONE.getDefaultState() : k <= water ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState());
            }
        }
    }

    private static void build() {
        EntityPlayerMP p = player();
        World w = p.world;
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        w.setWorldTime(6000);
        int fx = (int) Math.floor(p.posX), fy = (int) Math.floor(p.posY), fz = (int) Math.floor(p.posZ);
        x0 = fx - 18;
        y = fy + 12;
        z0 = fz + 6;
        // still: 5 × 5, 3 deep, the surface at y - 1 + 8/9
        basin(w, x0, z0, 5, 5, y - 4, 3, 3);
        // slope: a channel 3 wide and 9 long, one source at its north end (flowing: still water put by setBlockState stays put)
        basin(w, x0 + 12, z0, 3, 9, y - 2, 2, 0);
        set(w, x0 + 13, y - 1, z0, Blocks.FLOWING_WATER.getDefaultState());
        // waterfall: a basin as the still one; a source on a shelf north of its middle, walled but to the south, falls
        // into it from y + 4
        basin(w, x0 + 22, z0, 5, 5, y - 4, 3, 3);
        int cx = x0 + 24, cz = z0 + 2;
        set(w, cx, y + 3, cz - 1, Blocks.STONE.getDefaultState());
        set(w, cx - 1, y + 4, cz - 1, Blocks.STONE.getDefaultState());
        set(w, cx + 1, y + 4, cz - 1, Blocks.STONE.getDefaultState());
        set(w, cx, y + 4, cz - 2, Blocks.STONE.getDefaultState());
        set(w, cx, y + 4, cz - 1, Blocks.FLOWING_WATER.getDefaultState());
        // shallow: a channel as the slope's; its far end is a thin sheet
        basin(w, x0 + 36, z0, 3, 9, y - 2, 2, 0);
        set(w, x0 + 37, y - 1, z0, Blocks.FLOWING_WATER.getDefaultState());
        p.capabilities.isFlying = true;
        p.sendPlayerAbilities();
        p.connection.setPlayerLocation(x0 + 18.5, y + 6, z0 - 8.5, 0F, 30F);
        lines.add("pools from " + x0 + "," + y + "," + z0);
    }

    // the size of every round of the table in water, from NTM CE's casing of it (SunkBullets.model), once per round;
    // every round with a casing NTM draws is sized by it, and the task's numbers hold at waterBodyScale 1
    private static void sizes() {
        java.util.Map<String, String> out = new java.util.TreeMap<String, String>();
        java.util.Map<String, AmmoModels.Model> models = new java.util.HashMap<String, AmmoModels.Model>();
        int unsized = 0;
        for (BulletConfig c : BulletConfig.configs) {
            String a = c == null ? null : WaterFx.ammo(c);
            AmmoModels.Model m = a == null ? null : SunkBullets.model(c);
            if (m == null || out.containsKey(a)) continue;
            SpentCasing k = c.casing;
            // AmmoModels knows the straight, bottleneck and shotgun casings; CE's AR2 one is a cube, not a case
            boolean drawn = k != null && k.getType() != null && k.getType() != SpentCasing.CasingType.AR2;
            if (drawn && !m.byCasing) unsized++;
            models.put(a, m);
            out.put(a, String.format(Locale.ROOT, "%s len %.4f d %.4f %s", a, m.length, 2 * m.radius,
                    k == null ? "no casing" : (m.byCasing ? "casing " : "casing not sized ") + k.getType() + " " + k.getScaleX() + "/"
                            + k.getScaleY() + "/" + k.getScaleZ()));
        }
        int by = 0;
        for (AmmoModels.Model m : models.values()) if (m.byCasing) by++;
        lines.add("sizes: " + out.size() + " rounds, by casing " + by + ", waterBodyScale " + Config.waterBodyScale);
        for (String v : out.values()) lines.add("size " + v);
        check(by > 0 && unsized == 0, "every round with a drawn casing is sized by it (" + unsized + " not)");
        if (Config.waterBodyScale == 1F) {
            check(near(models.get("p9_fmj"), 0.0486) && near(models.get("r762_fmj"), 0.0510) && near(models.get("bmg50_fmj"), 0.0789),
                    "p9 / r762 / bmg50 FMJ in water 0.0486 / 0.0510 / 0.0789 long, as 26.2");
        }
    }

    private static boolean near(AmmoModels.Model m, double length) {
        return m != null && Math.abs(m.length - length) < 5e-4;
    }

    private static BulletConfig config(String ammo) {
        for (BulletConfig k : BulletConfig.configs) if (k != null && ammo.equals(WaterClass.ammoName(k))) return k;
        return null;
    }

    // as NTM fires it: the heading at 1, the speed is motionMult's (BoomScenario)
    private static void spawn(BulletConfig c, double x, double yy, double z, double dx, double dy, double dz) {
        World w = player().world;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(w, c, 1F, 0F, 0F, 0F);
        b.setPosition(x, yy, z);
        b.shoot(dx, dy, dz, 1F, 0F);
        b.damage = 10F;
        w.spawnEntity(b);
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
            case 2:
                spawn(c, x0 + 21.0, y + 1.5, z0 + 2.5, 1, 0, 0);
                return;
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

    private static void drain() {
        World w = player().world;
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) for (int k = 1; k <= 3; k++) set(w, x0 + x, y - 4 + k, z0 + z, Blocks.AIR.getDefaultState());
    }

    private static void mark() {
        before = WaterFx.SPAWNED.clone();
        eventsBefore = WaterFx.SPAWNED_EVENTS.clone();
        ringsBefore = WaterRings.made;
        traced0 = WaterFx.tracedCount;
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
        int enter = WaterFx.SPAWNED_EVENTS[WaterEvents.Type.ENTER.ordinal()] - eventsBefore[WaterEvents.Type.ENTER.ordinal()];
        lines.add(POOLS[p] + ", " + AMMO.length + " shots (tracers " + (WaterFx.tracedCount - traced0) + "): " + kinds() + "; rings " + rings + "; events "
                + events());
        check(enter >= AMMO.length, POOLS[p] + ": a splash for every shot");
        check(delta(WaterParticle.Kind.DROP) > 0 && delta(WaterParticle.Kind.FOAM) > 0 && delta(WaterParticle.Kind.MIST) > 0,
                POOLS[p] + ": drops, foam and water dust");
        // the waterfall's burst has no ring of its own; its drops landing in the basin below make small ones
        if (p != 2) check(rings >= AMMO.length, POOLS[p] + ": rings on the surface");
    }

    private static void shot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.gameDir, "waterfx-" + name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        lines.add(String.format(Locale.ROOT, "screenshot waterfx-%s.png camera %.3f %.3f %.3f pitch %.1f, rings alive %d", name, mc.player.posX,
                mc.player.posY, mc.player.posZ, mc.player.rotationPitch, WaterRings.alive()));
    }

    private static void check(boolean ok, String what) {
        lines.add((ok ? "OK   " : "FAIL ") + what);
        if (!ok) failed.add(what);
    }

    private static void write() {
        lines.add(failed.isEmpty() ? "ALL OK" : "FAILED " + failed.size() + ": " + Arrays.toString(failed.toArray()));
        try (PrintWriter pw = new PrintWriter(new File(Minecraft.getMinecraft().gameDir, "waterfx.txt"), StandardCharsets.UTF_8.name())) {
            for (String l : lines) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario waterfx: waterfx.txt", e);
        }
        for (String l : lines) GunFeel.LOGGER.info("Scenario waterfx: {}", l);
    }
}
