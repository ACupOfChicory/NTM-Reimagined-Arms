package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.render.item.weapon.sedna.MuzzleFlash;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.water.SunkBullets;
import acup.gunfeel.water.WaterFx;
import acup.gunfeel.water.WaterParticle;
import acup.gunfeel.water.WaterRings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PwaterFx: the water effects (acup.gunfeel.water) in a game, numbers only. Four pools are built on the
// integrated server: still, a slope of flowing water, a waterfall over a basin, a thin sheet on a floor. Real NTM rounds
// are spawned on the server at each (the client gets them as any shot: the tracer and the bullet entity), and what the
// effects made is counted per kind per shot. The surface audit runs all along (no drop, foam or pop more than 0.15 under
// the surface, none hanging over air). Then: a shot from under water (the gas bubble), the NTM flash under water,
// rounds stopped by water lying on the bottom, a ring taken off when its water is gone, and frames a second before,
// during and after 30 shotgun shots. Lines "[waterfx] ..." in the log, the numbers in the report.
final class WaterFxStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.waterFx");
    private static final int Y = 100;
    // the pools along x, twelve blocks apart; the player above and south of them
    private static final int STILL = 0, SLOPE = 12, FALL = 24, SHALLOW = 36;
    private static final Vec3 EYE = new Vec3(18.5, 112, -14.5);
    private static int step, wait;
    private static long since;
    private static int[] before;
    private static final List<String> LINES = new ArrayList<>();
    // frames
    private static int frames;
    private static long frameFrom;
    private static double fpsBefore, fpsDuring, fpsAfter;
    private static int shots, ringsMade0, ringsLeft0, bodiesMax;
    private static double physMsMax;

    private WaterFxStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        WaterFx.audit = true;
        mc.getSingleplayerServer().execute(() -> {
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(server.overworld(), EYE.x, EYE.y, EYE.z, Set.of(), 0F, 50F, false);
        });
        step = 0;
        since = System.nanoTime();
    }

    private static double elapsed() {
        return (System.nanoTime() - since) / 1e9;
    }

    private static void next() {
        step++;
        since = System.nanoTime();
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        frames++;
        if (SunkBullets.bodies() > bodiesMax) bodiesMax = SunkBullets.bodies();
        if (SunkBullets.physicsMs() > physMsMax) physMsMax = SunkBullets.physicsMs();
        if (wait > 0) {
            wait--;
            return;
        }
        var server = mc.getSingleplayerServer();
        try {
            switch (step) {
                case 0 -> {
                    if (mc.player == null || mc.player.position().distanceTo(EYE) > 1) return;
                    server.submit(() -> build(server.overworld())).join();
                    sizes();
                    next();
                }
                // the water settles (a slope, a waterfall and a sheet flow out), the client has it
                case 1 -> {
                    if (elapsed() < 6) return;
                    if (!WaterFx.wetAt(mc.level, new Vec3(FALL + 3.5, Y + 4.5, 0.5)) || !WaterFx.wetAt(mc.level, new Vec3(SHALLOW + 2.5, Y - 0.9, 0.5)))
                        return;
                    reset();
                    next();
                }
                // per pool and round: five shots from above, at 60° down (the waterfall: from its side, level)
                case 2, 3, 4, 5, 6, 7, 8, 9 -> {
                    int pool = (step - 2) / 2;
                    String ammo = (step - 2) % 2 == 0 ? "p9_fmj" : "g12";
                    String name = new String[] {"still", "slope", "falling", "shallow"}[pool];
                    if (shots == 0) {
                        before = WaterFx.SPAWNED.clone();
                        eventsBefore = WaterFx.SPAWNED_EVENTS.clone();
                        ringsMade0 = WaterRings.made;
                        traced0 = WaterFx.tracedCount;
                    }
                    if (shots < 5) {
                        if (elapsed() < 0.5 * shots) return;
                        shots++;
                        fire(server, ammo, pool);
                        return;
                    }
                    if (elapsed() < 6) return;
                    line(name + " " + ammo + ", per shot: " + perShot(5) + "; rings " + (WaterRings.made - ringsMade0) / 5.0 + "; events " + events() + "; tracers " + (WaterFx.tracedCount - traced0) + " (round: speed " + config(ammo).velocity + ", pellets "
                            + config(ammo).projectilesMax + ")");
                    shots = 0;
                    next();
                }
                // a shot from under water, level, 1.5 below the still pool's surface
                case 10 -> {
                    if (shots == 0) {
                        before = WaterFx.SPAWNED.clone();
                        shots = 1;
                        BulletConfig c = config("p9_fmj");
                        server.submit(() -> spawn(server.overworld(), c, new Vec3(STILL + 1.5, Y - 1.5, 0.5), new Vec3(1, 0, 0))).join();
                        return;
                    }
                    if (elapsed() < 5) return;
                    line("from under water p9_fmj, per shot: " + perShot(1));
                    check(WaterFx.SPAWNED[WaterParticle.Kind.GAS.ordinal()] - before[WaterParticle.Kind.GAS.ordinal()] == 1, "одна газовая каверна на выстрел из-под воды");
                    // NTM's flash: under water it is not drawn (the call is cut before it draws), in air it is
                    boolean air = flash(mc, new Vec3(STILL + 3.5, Y + 3, 0.5));
                    boolean water = flash(mc, new Vec3(STILL + 3.5, Y - 2, 0.5));
                    line("NTM flash: hidden under water " + water + ", hidden in air " + air);
                    check(water && !air, "вспышка NTM под водой не рисуется, в воздухе рисуется");
                    shots = 0;
                    next();
                }
                // the rounds the still pool stopped lie on its bottom: how many, asleep after a while
                case 11 -> {
                    if (elapsed() < 8) return;
                    line("bodies on the bottom: " + SunkBullets.bodies() + " (most " + bodiesMax + "), asleep " + SunkBullets.asleep() + ", physics most "
                            + String.format("%.3f", physMsMax) + " ms a frame");
                    check(bodiesMax > 0, "остановленные водой пули лежат на дне");
                    next();
                }
                // frames: 3 s quiet, then 30 shotgun shots at the still pool over 3 s, 3 s after them
                case 12 -> {
                    if (elapsed() < 1) return;
                    frames = 0;
                    frameFrom = System.nanoTime();
                    next();
                }
                case 13 -> {
                    if (elapsed() < 3) return;
                    fpsBefore = frames / ((System.nanoTime() - frameFrom) / 1e9);
                    frames = 0;
                    frameFrom = System.nanoTime();
                    before = WaterFx.SPAWNED.clone();
                    shots = 0;
                    next();
                }
                case 14 -> {
                    if (shots < 30) {
                        if (elapsed() < 0.1 * shots) return;
                        shots++;
                        fire(server, "g12", 0);
                        return;
                    }
                    if (elapsed() < 3) return;
                    fpsDuring = frames / ((System.nanoTime() - frameFrom) / 1e9);
                    next();
                }
                case 15 -> {
                    if (elapsed() < 4) return;
                    frames = 0;
                    frameFrom = System.nanoTime();
                    next();
                }
                case 16 -> {
                    if (elapsed() < 3) return;
                    fpsAfter = frames / ((System.nanoTime() - frameFrom) / 1e9);
                    line(String.format("FPS: before %.1f, during 30 g12 shots %.1f, after %.1f; per shot %s; live particles now %d", fpsBefore, fpsDuring,
                            fpsAfter, perShot(30), WaterParticle.live));
                    shots = 0;
                    next();
                }
                // a ring taken off when its water is gone: a shot at the still pool, then the pool drained
                case 17 -> {
                    if (shots == 0) {
                        shots = 1;
                        ringsLeft0 = WaterRings.leftWater;
                        fire(server, "p9_fmj", 0);
                        return;
                    }
                    if (elapsed() < 0.8) return;
                    int alive = WaterRings.alive();
                    server.submit(() -> drain(server.overworld())).join();
                    line("rings alive before the pool was drained: " + alive);
                    next();
                }
                case 18 -> {
                    if (elapsed() < 2) return;
                    int left = WaterRings.leftWater - ringsLeft0;
                    line("rings taken off as their water went: " + left + ", alive now " + WaterRings.alive());
                    check(left > 0 && WaterRings.alive() == 0, "кольцо снимается, когда уходит с воды");
                    shots = 0;
                    next();
                }
                // blasts at water (part 3's WaterBlast): an explosive round into the slope pool, its surface fuze sends
                // the blast from the server; then one given straight to the listeners in the shallow pool's water
                case 19 -> {
                    if (shots == 0) {
                        shots = 1;
                        before = WaterFx.SPAWNED.clone();
                        blasts0 = WaterFx.blasts;
                        plumes0 = WaterFx.plumes;
                        WaterRings.widest = 0;
                        fire(server, "g12_explosive", 1);
                        return;
                    }
                    if (elapsed() < 4) return;
                    line("explosive g12 into the slope pool: blasts under water " + (WaterFx.blasts - blasts0) + ", columns " + (WaterFx.plumes - plumes0)
                            + ", particles " + perShot(1) + ", widest ring reach " + WaterRings.widest + " cells");
                    check(WaterFx.plumes - plumes0 + WaterFx.blasts - blasts0 > 0, "взрыв у воды доходит до эффектов (listenBlasts)");
                    shots = 0;
                    next();
                }
                case 20 -> {
                    if (shots == 0) {
                        shots = 1;
                        before = WaterFx.SPAWNED.clone();
                        blasts0 = WaterFx.blasts;
                        plumes0 = WaterFx.plumes;
                        WaterRings.widest = 0;
                        Vec3 at = new Vec3(SHALLOW + 2.5, Y - 0.9, 0.5);
                        acup.gunfeel.water.WaterEvents.blast(mc.level, new acup.gunfeel.water.WaterEvents.Blast(at, 6F, false, true,
                                new Vec3(at.x, Y + 0.0, at.z), acup.gunfeel.water.WaterEvents.Liquid.WATER));
                        return;
                    }
                    if (elapsed() < 4) return;
                    line("blast of size 6 given in the shallow pool: blasts under water " + (WaterFx.blasts - blasts0) + ", columns "
                            + (WaterFx.plumes - plumes0) + ", particles " + perShot(1) + ", widest ring reach " + WaterRings.widest + " cells");
                    check(WaterFx.blasts - blasts0 == 1 && WaterFx.plumes - plumes0 == 1, "взрыв в воде — пузыри и толчок, над ним столб");
                    check(WaterRings.widest > 3, "кольца большого взрыва не обрезаны на 3 клетках");
                    shots = 0;
                    next();
                }
                // the same blast with its centre on the surface: the column only, no bubble under water
                case 21 -> {
                    if (shots == 0) {
                        shots = 1;
                        before = WaterFx.SPAWNED.clone();
                        blasts0 = WaterFx.blasts;
                        plumes0 = WaterFx.plumes;
                        Vec3 at = new Vec3(SHALLOW + 2.5, Y + 0.0, 0.5);
                        acup.gunfeel.water.WaterEvents.blast(mc.level, new acup.gunfeel.water.WaterEvents.Blast(at, 6F, false, true, at,
                                acup.gunfeel.water.WaterEvents.Liquid.WATER));
                        return;
                    }
                    if (elapsed() < 4) return;
                    line("blast of size 6 on the shallow pool's surface: blasts under water " + (WaterFx.blasts - blasts0) + ", columns "
                            + (WaterFx.plumes - plumes0) + ", particles " + perShot(1));
                    check(WaterFx.blasts - blasts0 == 0 && WaterFx.plumes - plumes0 == 1, "взрыв на поверхности — только столб, без пузыря");
                    next();
                }
                case 22 -> {
                    if (ringColour(mc, server)) next();
                }
                // a big particle's first ticks, frame by frame: the water dust of a splash, the gas bubble of a shot
                // from under water
                case 23 -> {
                    if (firstTicks(mc, server, WaterParticle.Kind.MIST)) next();
                }
                case 24 -> {
                    if (firstTicks(mc, server, WaterParticle.Kind.GAS)) next();
                }
                // a round asleep on the bottom, the floor under it broken: it goes down
                case 25 -> {
                    if (floorGone(server)) next();
                }
                case 26 -> {
                    line("surface audit: under the surface by more than 0.15: " + WaterFx.below + ", over air: " + WaterFx.overAir + ", deepest "
                            + String.format("%.3f", WaterFx.worstBelow));
                    check(WaterFx.below == 0 && WaterFx.overAir == 0, "ни одна частица не ниже поверхности на 0.15 и не над воздухом");
                    WaterFx.audit = false;
                    shots = 0;
                    next();
                }
                // a grenade and a rocket shot from under water, straight up: NTM's own entity, no tracer of ours, one gas
                // bubble each (WaterFx.joined). Last, as they might go off: taken off the server after two ticks
                case 27, 28 -> {
                    String ammo = step == 27 ? "g40_he" : "rocket_he";
                    if (shots == 0) {
                        before = WaterFx.SPAWNED.clone();
                        shots = 1;
                        BulletConfig c = config(ammo);
                        server.submit(() -> {
                            sent = ntmShot(server.overworld(), c, new Vec3(STILL + 1.5, Y - 1.5, 0.5), new Vec3(0, 1, 0));
                        }).join();
                        return;
                    }
                    if (shots == 1 && elapsed() > 0.1) {
                        shots = 2;
                        EntityBulletBaseMK4 b = sent;
                        server.execute(() -> {
                            if (b != null && !b.isRemoved()) b.discard();
                        });
                    }
                    if (elapsed() < 3) return;
                    int gas = WaterFx.SPAWNED[WaterParticle.Kind.GAS.ordinal()] - before[WaterParticle.Kind.GAS.ordinal()];
                    line("from under water " + ammo + " (speed " + config(ammo).velocity + ", traced " + Tracers.traced(config(ammo)) + "): gas bubbles " + gas
                            + ", per shot: " + perShot(1));
                    check(gas == 1, "одна газовая каверна у " + ammo + " из-под воды");
                    shots = 0;
                    next();
                }
                // a grenade shot east along a water channel 14 long and 8 deep: water stops it, it sinks, and its model lies
                // east (the way it flew, kept when water stopped it), not along NTM's yaw of a fall straight down.
                // The last step: it goes off on the channel's floor
                case 29 -> {
                    if (sunk(mc, server)) {
                        ClientSmoke.finish();
                        step = 99;
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            WaterParticle.watchKind = null;
            WaterParticle.watched = null;
            GunFeel.LOGGER.error("[waterfx] failed", t);
            ClientSmoke.check(false, "эффекты воды: " + t);
            ClientSmoke.finish();
            step = 99;
        }
    }

    // ---- a ring's colour on the screen: at noon, the still pool filled again, the eye straight above it (and then
    // under its surface looking up), a frame without a ring and one with a ring of alpha 1 lasting long; the pixels the
    // ring changed are its own colour. White at noon is near 255; the owner saw grey.
    // Then the hole a fading ring left in the water (the owner, 2026-11-07): a frame of the pool drained (the bottom,
    // or the sky from below) and frames of the ring at lower alphas. A ring pixel is a hole when it is nearer (by more
    // than 24) the segment from the drained pool's pixel to the ring's at alpha 1 than the one from the water's ----
    private static int colourStep, holeK;
    private static int[] shotA, shotB, shotR, shot;
    private static boolean shooting;
    private static double ringAbove;
    private static final float[] FADING = {0.5F, 0.25F, 0.15F};

    private static void pool(net.minecraft.client.server.IntegratedServer server, BlockState st) {
        server.execute(() -> fill(server.overworld(), st));
    }

    // the ring at alpha a, lasting long, at the middle of the still pool
    private static void ring(Minecraft mc, float a) {
        WaterRings.clear();
        WaterRings.add(mc.level, STILL + 3.5, Y, 0.5, 0, 1000, 3, 3, a, false);
    }

    // an Iris shader pack in use: the thresholds above were taken under the game's own light
    private static boolean pack() {
        return acup.gunfeel.smoke.client.SmokeRender.irisPack();
    }

    private static boolean differ(int p, int q) {
        int d = 0;
        for (int k = 0; k < 24; k += 8) d = Math.max(d, Math.abs((p >> k & 255) - (q >> k & 255)));
        return d > 24;
    }

    // how far a pixel p is from the segment from pixel x to pixel y, in RGB
    private static double offSegment(int p, int x, int y) {
        double[] d = new double[3], w = new double[3];
        double ww = 0, dw = 0;
        for (int c = 0; c < 3; c++) {
            double xc = x >> (16 - 8 * c) & 255;
            d[c] = (p >> (16 - 8 * c) & 255) - xc;
            w[c] = (y >> (16 - 8 * c) & 255) - xc;
            ww += w[c] * w[c];
            dw += d[c] * w[c];
        }
        double t = ww == 0 ? 0 : Math.clamp(dw / ww, 0, 1), r = 0;
        for (int c = 0; c < 3; c++) r += (d[c] - t * w[c]) * (d[c] - t * w[c]);
        return Math.sqrt(r);
    }

    private static void hole(boolean below, float a) {
        long n = 0, holes = 0;
        for (int i = 0; i < shot.length && i < shotA.length && i < shotB.length && i < shotR.length; i++) {
            // the ring's pixels (at alpha 1) where the water and what is behind it differ
            if (!differ(shotR[i], shotA[i]) || !differ(shotA[i], shotB[i])) continue;
            n++;
            // a ring mixed into the water lies between the water's pixel and the ring's at alpha 1 (nearer the water
            // where its faint edge is cut away); over a hole, between what is behind the water and the ring. Clearly
            // nearer the second: the two meet at the ring, and the water's texture moves between frames
            if (offSegment(shot[i], shotB[i], shotR[i]) + 24 < offSegment(shot[i], shotA[i], shotR[i])) holes++;
        }
        String where = below ? "from under the surface" : "from above";
        double share = n == 0 ? 1 : (double) holes / n;
        line(String.format(java.util.Locale.ROOT, "ring hole %s, alpha %.2f: %d of %d ring px show what is behind the water (%.1f%%)",
                where, a, holes, n, 100 * share));
        // from below with a shader pack the ring is a few hundred pixels and the pack's water moves: 2% there is noise
        // (the same before the ring went after the water and after, BSL 2026-11-07)
        if (below && pack()) return;
        check(n > 0 && share <= 0.02, "гаснущее кольцо (альфа " + a + ") не оставляет дыры в воде " + (below ? "снизу" : "сверху")
                + " (" + holes + " из " + n + " px)");
    }

    private static boolean ringColour(Minecraft mc, net.minecraft.client.server.IntegratedServer server) {
        boolean below = colourStep >= 10;
        int sub = colourStep % 10;
        Vec3 eye = below ? new Vec3(STILL + 3.5, Y - 2.6, 0.5) : new Vec3(STILL + 3.5, Y + 5, 0.5);
        float pitch = below ? -90F : 90F;
        switch (sub) {
            // the pool drained: what is behind its water
            case 0 -> {
                pool(server, Blocks.AIR.defaultBlockState());
                server.execute(() -> {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "weather clear");
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.teleportTo(server.overworld(), eye.x, eye.y, eye.z, Set.of(), 0F, pitch, false);
                });
                WaterRings.clear();
                colourStep++;
                since = System.nanoTime();
            }
            case 1 -> {
                if (elapsed() < 3 || mc.player.position().distanceTo(eye) > 0.5) return false;
                mc.player.setYRot(0F);
                mc.player.setXRot(pitch);
                if (!screenshot(mc)) return false;
                shotB = shot;
                pool(server, Blocks.WATER.defaultBlockState());
                colourStep++;
                since = System.nanoTime();
            }
            case 2 -> {
                if (elapsed() < 3 || !WaterFx.wetAt(mc.level, new Vec3(STILL + 3.5, Y - 0.5, 0.5))) return false;
                mc.player.setYRot(0F);
                mc.player.setXRot(pitch);
                if (!screenshot(mc)) return false;
                shotA = shot;
                ring(mc, 1F);
                colourStep++;
                since = System.nanoTime();
            }
            case 3 -> {
                if (elapsed() < 0.5) return false;
                mc.player.setYRot(0F);
                mc.player.setXRot(pitch);
                if (!screenshot(mc)) return false;
                shotR = shot;
                WaterRings.clear();
                // the ring's pixels: changed by more than 24 in some channel
                long n = 0;
                double[] a = new double[3], b = new double[3];
                for (int i = 0; i < shot.length && i < shotA.length; i++) {
                    int p = shot[i], q = shotA[i];
                    int d = 0;
                    for (int k = 0; k < 24; k += 8) d = Math.max(d, Math.abs((p >> k & 255) - (q >> k & 255)));
                    if (d <= 24) continue;
                    n++;
                    // the screenshot's pixels are ARGB here: red in bits 16-23 (with the low byte read as red the water came out orange)
                    for (int c = 0; c < 3; c++) {
                        b[c] += p >> (16 - 8 * c) & 255;
                        a[c] += q >> (16 - 8 * c) & 255;
                    }
                }
                String where = below ? "from under the surface" : "from above";
                if (n == 0) {
                    line("ring colour " + where + ": the ring changed no pixel");
                    check(false, "кольцо видно на кадре " + (below ? "снизу" : "сверху"));
                } else {
                    for (int c = 0; c < 3; c++) {
                        a[c] /= n;
                        b[c] /= n;
                    }
                    double lum = (b[0] + b[1] + b[2]) / 3;
                    line(String.format(java.util.Locale.ROOT, "ring colour %s, noon, alpha 1: %d px, ring RGB %.0f %.0f %.0f (mean %.0f of 255), the water there %.0f %.0f %.0f",
                            where, n, b[0], b[1], b[2], lum, a[0], a[1], a[2]));
                    if (below) check(lum >= ringAbove * 0.3, "кольцо снизу не чёрное (" + Math.round(lum) + ")");
                    else {
                        ringAbove = lum;
                        // grey was 103; white comes out near 200, not 255: the texture's soft edges are mixed with the water
                        // a shader pack lights it its own way (BSL: 156, the same before the ring went after the water)
                        if (!pack()) check(lum >= 170, "кольцо сверху в полдень белое, а не серое (среднее " + Math.round(lum) + " из 255)");
                    }
                }
                holeK = 0;
                ring(mc, FADING[0]);
                colourStep++;
                since = System.nanoTime();
            }
            case 4 -> {
                if (elapsed() < 0.5) return false;
                mc.player.setYRot(0F);
                mc.player.setXRot(pitch);
                if (!screenshot(mc)) return false;
                WaterRings.clear();
                hole(below, FADING[holeK]);
                if (++holeK < FADING.length) {
                    ring(mc, FADING[holeK]);
                    since = System.nanoTime();
                    return false;
                }
                if (below) return true;
                colourStep = 10;
            }
            default -> {
            }
        }
        return false;
    }

    // ---- the first three ticks of one particle: the frames it was drawn in, its size, alpha and centre; a jump back
    // (the size or the alpha going down while they grow, the centre stepping back against its way) is the blinking
    // the owner saw. The gas bubble's centre is also measured against its size: it sits 0.85 of its radius ahead ----
    private static int tickStep;

    private static boolean firstTicks(Minecraft mc, net.minecraft.client.server.IntegratedServer server, WaterParticle.Kind kind) {
        switch (tickStep) {
            case 0 -> {
                server.execute(() -> server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), STILL + 3.5, Y + 3, -4, Set.of(), 0F, 30F,
                        false));
                tickStep++;
                since = System.nanoTime();
            }
            case 1 -> {
                if (elapsed() < 2) return false;
                WaterParticle.WATCH.clear();
                WaterParticle.watched = null;
                WaterParticle.watchKind = kind;
                if (kind == WaterParticle.Kind.GAS) {
                    BulletConfig c = config("p9_fmj");
                    server.execute(() -> spawn(server.overworld(), c, new Vec3(STILL + 1.5, Y - 1.5, 0.5), new Vec3(1, 0, 0)));
                } else fire(server, "p9_fmj", 0);
                tickStep++;
                since = System.nanoTime();
            }
            case 2 -> {
                List<double[]> w = WaterParticle.WATCH;
                if (elapsed() < 3 && (w.isEmpty() || w.getLast()[0] < 3)) return false;
                WaterParticle.watchKind = null;
                WaterParticle.watched = null;
                tickStep = 0;
                String name = kind.name().toLowerCase(java.util.Locale.ROOT);
                List<double[]> f = new ArrayList<>();
                for (double[] r : w) if (r[0] < 3) f.add(r);
                if (f.size() < 3) {
                    line(name + ": drawn in " + f.size() + " frames of its first 3 ticks");
                    check(false, "частица " + name + " видна в первые тики");
                    return true;
                }
                double[] first = f.getFirst();
                // the gas bubble shrinks on its own from the second tick
                double grows = kind == WaterParticle.Kind.GAS ? 2 : 3;
                double[] end = first;
                for (double[] r : f) if (r[0] < grows) end = r;
                double dx = end[3] - first[3], dy = end[4] - first[4], dz = end[5] - first[5];
                int sizeBack = 0, alphaBack = 0, posBack = 0, flat = 0;
                double alphaDrop = 0, lead = 0;
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < f.size(); i++) {
                    double[] r = f.get(i);
                    b.append(String.format(java.util.Locale.ROOT, "%s%.2f %.3f %.3f %.3f,%.3f,%.3f", i > 0 ? "; " : "", r[0], r[1], r[2], r[3], r[4], r[5]));
                    if (kind == WaterParticle.Kind.GAS && r[0] >= 0.5 && r[1] > 1e-3) lead = Math.max(lead, Math.abs((r[3] - r[6]) / r[1] - 0.85));
                    if (i == 0) continue;
                    double[] q = f.get(i - 1);
                    if (r[0] < grows && r[1] < q[1] - 1e-6) sizeBack++;
                    if (Math.abs(r[1] - q[1]) < 1e-7) flat++;
                    if (r[2] < q[2] - 1e-4) {
                        alphaBack++;
                        alphaDrop = Math.max(alphaDrop, q[2] - r[2]);
                    }
                    // the gas bubble's centre is ahead of its origin by its radius: it comes back as the bubble shrinks
                    if (r[0] < grows && (r[3] - q[3]) * dx + (r[4] - q[4]) * dy + (r[5] - q[5]) * dz < -1e-9) posBack++;
                }
                line(String.format(java.util.Locale.ROOT, "%s, first 3 ticks: %d frames; size back %d, size unchanged frame to frame %d, alpha back %d "
                        + "(most %.3f), centre back %d%s; frames (t size alpha centre): %s", name, f.size(), sizeBack, flat, alphaBack, alphaDrop, posBack,
                        kind == WaterParticle.Kind.GAS ? String.format(java.util.Locale.ROOT, ", centre ahead of 0.85 radius by up to %.2f radius", lead) : "",
                        b));
                check(sizeBack == 0 && alphaBack == 0 && posBack == 0, name + ": в первые тики размер, альфа и центр не прыгают назад");
                if (kind == WaterParticle.Kind.GAS) check(lead < 0.15, "газовый пузырь: центр идёт вместе с размером");
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    // ---- a round asleep on the still pool's bottom, the stone under it (3 × 3) taken away on the server: it goes
    // down at least a block within a second ----
    private static int floorStep;
    private static acup.gunfeel.water.SunkPhysics.Body sleeper;
    private static double sleeperY;

    private static boolean floorGone(net.minecraft.client.server.IntegratedServer server) {
        switch (floorStep) {
            case 0 -> {
                for (int i = 0; i < 3; i++) fire(server, "p9_fmj", 0);
                floorStep++;
                since = System.nanoTime();
            }
            case 1 -> {
                sleeper = null;
                for (var b : SunkBullets.list())
                    if (b.asleep && b.px > STILL + 2 && b.px < STILL + 5 && b.pz > -1 && b.pz < 2 && b.py < Y - 3) sleeper = b;
                if (sleeper == null) {
                    if (elapsed() < 12) return false;
                    line("floor gone: no round asleep on the still pool's bottom");
                    check(false, "пуля уснула на дне");
                    floorStep = 0;
                    return true;
                }
                sleeperY = sleeper.py;
                int bx = (int) Math.floor(sleeper.px), bz = (int) Math.floor(sleeper.pz);
                line(String.format(java.util.Locale.ROOT, "floor gone: a round asleep at %.2f %.3f %.2f; bodies %d, asleep %d, physics %.4f ms a frame", sleeper.px,
                        sleeper.py, sleeper.pz, SunkBullets.bodies(), SunkBullets.asleep(), SunkBullets.physicsMs()));
                server.execute(() -> {
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(server.overworld(), bx + x, Y - 5, bz + z, Blocks.AIR.defaultBlockState());
                });
                floorStep++;
                since = System.nanoTime();
            }
            case 2 -> {
                double drop = sleeperY - sleeper.py;
                if (drop < 1 && elapsed() < 3 && SunkBullets.list().contains(sleeper)) return false;
                double t = elapsed();
                line(String.format(java.util.Locale.ROOT, "floor gone: the round went down %.2f blocks in %.2f s (asleep %b)", drop, t, sleeper.asleep));
                check(drop >= 1 && t <= 1, "пуля на дне падает, когда блок под ней сломан");
                floorStep = 0;
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    private static boolean screenshot(Minecraft mc) {
        if (shooting) {
            if (shot == null) return false;
            shooting = false;
            return true;
        }
        shot = null;
        shooting = true;
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                shot = image.getPixels();
            }
        });
        return false;
    }

    private static void reset() {
        WaterFx.below = WaterFx.overAir = 0;
        WaterFx.worstBelow = 0;
    }

    private static void line(String s) {
        LINES.add(s);
        GunFeel.LOGGER.info("[waterfx] {}", s);
        ClientSmoke.check(true, "вода: " + s);
    }

    private static void check(boolean ok, String s) {
        ClientSmoke.check(ok, s);
    }

    private static int[] eventsBefore;
    private static int traced0, blasts0, plumes0;

    private static String events() {
        StringBuilder b = new StringBuilder();
        var types = acup.gunfeel.water.WaterEvents.Type.values();
        for (int i = 0; i < types.length; i++) {
            int d = WaterFx.SPAWNED_EVENTS[i] - eventsBefore[i];
            if (d > 0) b.append(b.length() > 0 ? ", " : "").append(types[i].name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(d);
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private static String perShot(int n) {
        StringBuilder b = new StringBuilder();
        for (WaterParticle.Kind k : WaterParticle.Kind.values()) {
            int d = WaterFx.SPAWNED[k.ordinal()] - before[k.ordinal()];
            if (d == 0) continue;
            if (b.length() > 0) b.append(", ");
            b.append(k.name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", d / (double) n));
        }
        return b.length() == 0 ? "nothing" : b.toString();
    }

    // NTM's own flash, called as a gun would at a point (camera-relative, world axes; far enough not to be read as
    // the gun in the hands): true if our check cut it (with no collector a flash that goes on may fail: no matter)
    private static boolean flash(Minecraft mc, Vec3 at) {
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        PoseStack pose = new PoseStack();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        int before = WaterFx.flashHidden;
        try {
            MuzzleFlash.NORMAL.submit(null, pose, 0L, 0, 0.0, 0);
        } catch (Throwable ignored) {
        }
        return WaterFx.flashHidden > before;
    }

    // the size of every round of the table in water, from NTM's casing of it (SunkBullets.model), once per round
    private static void sizes() {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (BulletConfig c : BulletConfig.configs) {
            String a = c == null ? null : WaterFx.ammo(c);
            var m = a == null ? null : SunkBullets.model(c);
            if (m == null) continue;
            var k = c.casing;
            out.putIfAbsent(a, String.format(java.util.Locale.ROOT, "%s len %.4f d %.4f %s", a, m.length, 2 * m.radius,
                    k == null ? "no casing" : (m.byCasing ? "casing " : "casing not sized ") + k.getType() + " " + k.getScaleX() + "/"
                            + k.getScaleY() + "/" + k.getScaleZ()));
        }
        line("sizes: " + out.size() + " rounds, by casing " + out.values().stream().filter(v -> v.contains(" casing ") && !v.contains("not sized")).count());
        for (String v : out.values()) line("size " + v);
    }

    private static BulletConfig config(String ammo) {
        for (BulletConfig c : BulletConfig.configs) {
            String a = c == null ? null : WaterFx.ammo(c);
            if (ammo.equals(a)) return c;
        }
        // shot: the first 12 gauge round with pellets
        for (BulletConfig c : BulletConfig.configs) {
            String a = c == null ? null : WaterFx.ammo(c);
            if (a != null && a.startsWith(ammo + "_") && c.projectilesMax > 1) return c;
        }
        throw new IllegalStateException("no round " + ammo);
    }

    // a shot at a pool: its target point on the surface, from 5 blocks up and 3 back (60° down); the waterfall from
    // its side, level
    private static void fire(net.minecraft.client.server.IntegratedServer server, String ammo, int pool) {
        BulletConfig c = config(ammo);
        int x0 = new int[] {STILL, SLOPE, FALL, SHALLOW}[pool];
        Vec3 to = switch (pool) {
            case 1 -> new Vec3(x0 + 3.5, Y - 0.4, 0.5);
            case 2 -> new Vec3(x0 + 3.5, Y + 4.3, 0.5);
            case 3 -> new Vec3(x0 + 2.5, Y - 0.9, 0.5);
            default -> new Vec3(x0 + 3.5, Y, 0.5);
        };
        Vec3 from = pool == 2 ? to.add(-6, 0, 0) : to.add(-3, 5.2, 0);
        Vec3 dir = to.subtract(from).normalize();
        // not waited for: the frames go on as in a game
        server.execute(() -> {
            int n = Math.max(1, c.projectilesMax);
            for (int i = 0; i < n; i++) spawn(server.overworld(), c, from, dir);
        });
    }

    private static @org.jspecify.annotations.Nullable EntityBulletBaseMK4 sent;
    private static double stoppedAt = -1;
    private static @org.jspecify.annotations.Nullable Vec3 seenAt;
    private static boolean clientStopped;

    // under the stand's eye: NTM's round farther off is out of the client's sight
    private static final int CHANNEL = 10, CHANNEL_Z = -10;

    private static boolean sunk(Minecraft mc, net.minecraft.client.server.IntegratedServer server) {
        if (shots == 0) {
            server.submit(() -> {
                ServerLevel lv = server.overworld();
                BlockState stone = Blocks.STONE.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
                for (int x = CHANNEL; x <= CHANNEL + 15; x++) for (int z = CHANNEL_Z - 2; z <= CHANNEL_Z + 2; z++) {
                    set(lv, x, Y - 9, z, stone);
                    for (int y = Y - 8; y <= Y - 1; y++) {
                        boolean wall = x == CHANNEL || x == CHANNEL + 15 || z == CHANNEL_Z - 2 || z == CHANNEL_Z + 2;
                        set(lv, x, y, z, wall ? stone : water);
                    }
                }
            }).join();
            shots = 1;
            return false;
        }
        // 8 deep: water stops a grenade after 5.7 blocks (L 3, waterStopFraction 0.15), it falls 2.6 meanwhile
        Vec3 from = new Vec3(CHANNEL + 1.2, Y - 3.5, CHANNEL_Z + 0.5);
        if (shots == 1) {
            if (elapsed() < 1 || !WaterFx.wetAt(mc.level, from) || !WaterFx.wetAt(mc.level, from.add(12, 0, 0))) {
                if (elapsed() > 10) {
                    check(false, "жёлоб для гранаты не дошёл до клиента");
                    return true;
                }
                return false;
            }
            BulletConfig c = config("g40_he");
            server.submit(() -> {
                sent = ntmShot(server.overworld(), c, from, new Vec3(1, 0, 0));
            }).join();
            shots = 2;
            stoppedAt = -1;
            seenAt = null;
            blasts0 = WaterFx.blasts;
            since = System.nanoTime();
            return false;
        }
        EntityBulletBaseMK4 s = sent;
        var e = s == null ? null : mc.level.getEntity(s.getId());
        if (e != null) seenAt = e.position();
        if (stoppedAt < 0 && e != null && WaterFx.wetAt(mc.level, e.position()) && SunkBullets.sinking(e)) {
            stoppedAt = elapsed();
            clientStopped = e instanceof acup.gunfeel.water.WaterBullet w && w.gunfeel$waterStopped();
        }
        // half a second after it stopped: sinking straight down by then, NTM's yaw turned
        if (stoppedAt < 0 || elapsed() < stoppedAt + 0.5) {
            if (elapsed() > 8) {
                line("grenade along the channel: not seen stopped by water (on the client now: " + (e != null) + ", last seen at " + seenAt
                        + ", blasts under water " + (WaterFx.blasts - blasts0) + ")");
                check(false, "граната в жёлобе остановлена водой");
                return true;
            }
            return false;
        }
        Vec3 h = e == null ? Vec3.ZERO : SunkBullets.headingOf(e);
        line(String.format(java.util.Locale.ROOT, "grenade along the channel, east: sinking after %.2f s at x %.2f (the client's bullet saw the stop: %s), sunk model heading %.3f %.3f %.3f, NTM yaw now %.1f",
                stoppedAt, e == null ? Double.NaN : e.getX(), clientStopped, h.x, h.y, h.z, e == null ? Float.NaN : e.getYRot()));
        check(h.x > 0.95, "тонущая граната лежит по курсу полёта (на восток)");
        server.execute(() -> {
            if (s != null && !s.isRemoved()) s.discard();
        });
        return true;
    }

    // a round as NTM's gun shoots it: a motion of length 1, the speed NTM's motionMult
    private static EntityBulletBaseMK4 ntmShot(ServerLevel level, BulletConfig c, Vec3 from, Vec3 dir) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, c, 10F, 0F, 0F, 0F);
        var player = level.getServer().getPlayerList().getPlayers().getFirst();
        b.setThrower(player);
        b.setOwner(player);
        b.setPos(from.x, from.y, from.z);
        b.setThrowableHeading(dir.x, dir.y, dir.z, 1F, 0F);
        level.addFreshEntity(b);
        return b;
    }

    private static void spawn(ServerLevel level, BulletConfig c, Vec3 from, Vec3 dir) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, c, 10F, 0F, 0F, 0F);
        // shot by the player, as from a gun: the pellets of one shot do not meet each other
        var player = level.getServer().getPlayerList().getPlayers().getFirst();
        b.setThrower(player);
        b.setOwner(player);
        b.setPos(from.x, from.y, from.z);
        b.setThrowableHeading(dir.x, dir.y, dir.z, c.velocity, c.projectilesMax > 1 ? 0.05F : 0F);
        level.addFreshEntity(b);
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState st) {
        level.setBlock(new BlockPos(x, y, z), st, Block.UPDATE_ALL);
    }

    // the four pools; water flows out on the server's own ticks
    private static void build(ServerLevel level) {
        BlockState stone = Blocks.STONE.defaultBlockState(), water = Blocks.WATER.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = -2; x <= 46; x++) for (int y = Y - 6; y <= Y + 10; y++) for (int z = -6; z <= 6; z++) set(level, x, y, z, air);
        // still and the waterfall's basin: 7 × 7 stone, 5 × 5 water 4 deep, its surface at Y (the top cell Y-1)
        for (int x0 : new int[] {STILL, FALL}) {
            for (int x = 0; x <= 6; x++) for (int z = -3; z <= 3; z++) {
                set(level, x0 + x, Y - 5, z, stone);
                for (int y = Y - 4; y <= Y - 1; y++) {
                    boolean wall = x == 0 || x == 6 || z == -3 || z == 3;
                    set(level, x0 + x, y, z, wall ? stone : water);
                }
            }
        }
        // the waterfall: a source high over the basin falls into it
        set(level, FALL + 3, Y + 8, 0, water);
        // the slope: a channel 3 wide on a floor at Y-1, sources at its west end, the water running east down to 0
        for (int x = SLOPE - 1; x <= SLOPE + 9; x++) for (int z = -2; z <= 2; z++) {
            set(level, x, Y - 2, z, stone);
            if (z == -2 || z == 2 || x == SLOPE - 1 || x == SLOPE + 9) set(level, x, Y - 1, z, stone);
        }
        for (int z = -1; z <= 1; z++) set(level, SLOPE, Y - 1, z, water);
        // the thin sheet: one source in the middle of a floor 9 × 9, flowing out a few blocks
        for (int x = SHALLOW - 1; x <= SHALLOW + 9; x++) for (int z = -5; z <= 5; z++) set(level, x, Y - 2, z, stone);
        set(level, SHALLOW + 4, Y - 1, 0, water);
    }

    // the still pool emptied
    private static void drain(ServerLevel level) {
        fill(level, Blocks.AIR.defaultBlockState());
    }

    // the still pool's water cells, all set to st
    private static void fill(ServerLevel level, BlockState st) {
        for (int x = 1; x <= 5; x++) for (int z = -2; z <= 2; z++) for (int y = Y - 4; y <= Y - 1; y++)
            level.setBlock(new BlockPos(STILL + x, y, z), st, Block.UPDATE_ALL);
    }
}
