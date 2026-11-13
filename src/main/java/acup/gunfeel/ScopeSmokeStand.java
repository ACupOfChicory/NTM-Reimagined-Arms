package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import acup.gunfeel.smoke.client.SmokeRender;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

// runCheckClient -PgasStand=<dir> -PscopeSmoke: a scoped gun aimed in and out with no smoke about, with a cloud behind
// the player (in the render distance, out of the view) and with one in front, in the view. Every frame: the frame's
// time, the game's tick and partial tick, the aiming as NTM draws it, the gun's place as drawn and whether smoke was
// marched (<dir>/scope.tsv, the summary in <dir>/scope.txt). The gun has to go in and out as smoothly with smoke as
// without: its place and the drawn time ("tick + partial") never stepping back while it moves one way.
final class ScopeSmokeStand {
    static final boolean ON = ScopeStandHooks.ON;
    // -PscopeJitter: the frame rate jumping frame to frame (a busy wait of 0.5 or 5 ms in turn, randomly) and about a
    // thousand particles about, as in the owner's game with smoke (130 to 360 fps, 600 to 1100 particles)
    // -PscopeBots: AI bots with STG-77s instead of smoke, shooting at the player (in the view, then behind him)
    static final boolean BOTS = Boolean.getBoolean("ntmgunfeel.scopeBots");
    static final boolean JITTER = Boolean.getBoolean("ntmgunfeel.scopeJitter");
    private static final java.util.Random RANDOM = new java.util.Random(7);
    private static final int Y = 100;
    private static final String[] GUNS = {"hbm:gun_stg77", "hbm:gun_g3"};
    // the clouds come up only with the first gun; the G3's cases keep the names but have what is left of them about
    // (scope.txt's "marched" says how much smoke each one drew)
    private static final String[] CASES = {"none", "behind", "ahead"};
    private static final int CYCLES = 3, HOLD = 30;
    private static final List<String> ROWS = new ArrayList<>(), OUT = new ArrayList<>();

    private record Frame(int gun, int kase, int cycle, boolean aim, long nanos, long tick, float interp, float partial, float aiming,
            float cx, float cy, float cz, float px, float py, float pz, int marched, int hurt, float hudFov) {
    }

    private static final List<Frame> FRAMES = new ArrayList<>();
    private static int gun, kase, cycle, phase;
    private static boolean aim, recording, done;
    private static long at;

    private ScopeSmokeStand() {
    }

    private static MinecraftServer server() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    private static long now() {
        var l = Minecraft.getInstance().level;
        return l == null ? 0 : l.getGameTime();
    }

    private static void line(String s) {
        OUT.add(s);
        GunFeel.LOGGER.info("[scopesmoke] {}", s);
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        server().submit(() -> {
            var level = server().overworld();
            server().getCommands().performPrefixedCommand(server().createCommandSourceStack().withSuppressedOutput(), "time set noon");
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server());
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server());
            for (int x = -20; x <= 20; x++) for (int z = -40; z <= 40; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY).defaultBlockState(), 2);
                for (int y = Y; y <= Y + 12; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            ServerPlayer p = server().getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.SURVIVAL);
            p.teleportTo(level, 0.5, Y, 0.5, java.util.Set.of(), 0F, 0F, false);
        }).join();
        ROWS.add("gun\tcase\tcycle\taim\tframe_ms\ttick\tinterp\tpartial\taiming\tcam_x\tcam_y\tcam_z\tpose_x\tpose_y\tpose_z\tmarched\thurt\thud_fov");
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, e -> record());
        if (JITTER) {
            NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, e -> {
                long until = System.nanoTime() + (RANDOM.nextBoolean() ? 500_000L : 5_000_000L);
                while (System.nanoTime() < until) Thread.onSpinWait();
            });
            NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> {
                var l = Minecraft.getInstance().level;
                if (l == null || !recording) return;
                for (int i = 0; i < 8; i++)
                    l.addParticle(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, -6 + RANDOM.nextDouble() * 12,
                            Y + RANDOM.nextDouble() * 2, 4 + RANDOM.nextDouble() * 8, 0, 0.02, 0);
            });
        }
        phase = 0;
        at = now() + 20;
    }

    // after each frame: what the hand pass drew in it
    private static void record() {
        if (!recording || done) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var g = Arms.standGun();
        float interp = ItemRenderWeaponBase.interp;
        float aiming = ItemGunBaseNT.prevAimingProgress + (ItemGunBaseNT.aimingProgress - ItemGunBaseNT.prevAimingProgress) * interp;
        FRAMES.add(new Frame(gun, kase, cycle, aim, System.nanoTime(), now(), interp, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), aiming,
                g[0].x, g[0].y, g[0].z, g[1].x, g[1].y, g[1].z, SmokeRender.drawnLast()[0], mc.player.hurtTime, ScopeStandHooks.hudFov));
    }

    private static void setAim(boolean on) {
        aim = on;
        var mc = Minecraft.getInstance();
        ItemGunBaseNT.setIsAiming(mc.player.getMainHandItem(), on);
        server().submit(() -> ItemGunBaseNT.setIsAiming(server().getPlayerList().getPlayers().getFirst().getMainHandItem(), on)).join();
    }

    private static void hold(String id) {
        server().submit(() -> {
            ServerPlayer p = server().getPlayerList().getPlayers().getFirst();
            p.getInventory().setSelectedSlot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))));
            p.teleportTo(server().overworld(), 0.5, Y, 0.5, java.util.Set.of(), 0F, 0F, false);
        }).join();
    }

    private static void command(String c) {
        server().submit(() -> server().getCommands().performPrefixedCommand(server().createCommandSourceStack().withSuppressedOutput(), c)).join();
    }

    // a case's bots: none, four in the view 14 blocks off, four behind; the player kept alive, the bots' ammo endless
    private static void bots(int k) {
        command("armsai clear");
        command("armsai ammo infinite");
        command("effect give @a minecraft:resistance infinite 255 true");
        command("effect give @a minecraft:regeneration infinite 255 true");
        command("effect give @a minecraft:saturation infinite 255 true");
        if (k == 2) command("execute positioned 0.5 " + Y + " 14.5 run armsai spawn fbi 4 hbm:gun_stg77");
        if (k == 1) command("execute positioned 0.5 " + Y + " -16.5 run armsai spawn fbi 4 hbm:gun_stg77");
    }

    private static void spawn(Vec3 at) {
        server().submit(() -> SmokeClouds.spawn(server().overworld(), at, new Vec3(0, -1, 0), SmokeKind.SMOKE, 1F)).join();
    }

    static void frame() {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || now() < at) return;
        try {
            switch (phase) {
                case 0 -> {
                    // a case: its cloud, then the gun in hand
                    if (BOTS) bots(kase);
                    else {
                        if (gun == 0 && kase == 1) spawn(new Vec3(0.5, Y + 0.5, -18.5));
                        if (gun == 0 && kase == 2) spawn(new Vec3(0.5, Y + 0.5, 14.5));
                    }
                    hold(GUNS[gun]);
                    mc.player.setYRot(0F);
                    mc.player.setXRot(0F);
                    cycle = 0;
                    // a cloud takes a few seconds to come up
                    at = now() + (BOTS ? (kase > 0 ? 60 : 30) : gun == 0 && kase > 0 ? 100 : 30);
                    phase = 1;
                }
                case 1 -> {
                    // aim in, hold, aim out, hold
                    recording = true;
                    setAim(!aim);
                    at = now() + HOLD;
                    if (!aim && ++cycle >= CYCLES) phase = 2;
                }
                case 2 -> {
                    recording = false;
                    if (++kase >= CASES.length) {
                        kase = 0;
                        gun++;
                    }
                    phase = gun < GUNS.length ? 0 : 3;
                    at = now() + 5;
                }
                default -> {
                    if (BOTS) command("armsai clear");
                    finish();
                }
            }
        } catch (RuntimeException e) {
            GunFeel.LOGGER.error("[scopesmoke]", e);
            ClientSmoke.check(false, "прицел в дыму: " + e);
            finish();
        }
    }

    private static void report() {
        long last = 0;
        for (Frame f : FRAMES) {
            float frameMs = last == 0 ? 0F : (f.nanos - last) / 1e6F;
            last = f.nanos;
            ROWS.add(String.format(Locale.ROOT, "%s\t%s\t%d\t%b\t%.3f\t%d\t%.4f\t%.4f\t%.4f\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t%d\t%d\t%.4f", GUNS[f.gun], CASES[f.kase],
                    f.cycle, f.aim, frameMs, f.tick, f.interp, f.partial, f.aiming, f.cx, f.cy, f.cz, f.px, f.py, f.pz, f.marched, f.hurt, f.hudFov));
        }
        for (int g = 0; g < GUNS.length; g++) for (int k = 0; k < CASES.length; k++) {
            int frames = 0, back = 0, flips = 0, marched = 0, fovBack = 0;
            float fovWorst = 0F, aimLo = 1F, aimHi = 0F, fovLo = Float.MAX_VALUE, fovHi = 0F;
            double ms = 0, worst = 0;
            Frame prev = null;
            float dir = 0F;
            for (Frame f : FRAMES) {
                if (f.gun != g || f.kase != k) continue;
                frames++;
                if (f.marched > 0) marched++;
                aimLo = Math.min(aimLo, f.aiming);
                aimHi = Math.max(aimHi, f.aiming);
                fovLo = Math.min(fovLo, f.hudFov);
                fovHi = Math.max(fovHi, f.hudFov);
                if (prev != null && prev.aim == f.aim) {
                    double dt = (f.nanos - prev.nanos) / 1e6;
                    ms += dt;
                    worst = Math.max(worst, dt);
                    // the drawn time steps back
                    if (f.tick + (double) f.interp < prev.tick + (double) prev.interp - 1e-4) back++;
                    // the aiming as drawn turns round while it moves one way
                    float d = f.aiming - prev.aiming;
                    if (Math.abs(d) > 1e-4F) {
                        float s = Math.signum(d);
                        if (dir != 0F && s != dir) flips++;
                        dir = s;
                    }
                    // the model's FOV: aiming in it only falls, out only rises
                    float df = f.hudFov - prev.hudFov;
                    if (Math.abs(df) > 1e-4F && df * (f.aim ? -1F : 1F) < 0F) {
                        fovBack++;
                        fovWorst = Math.max(fovWorst, Math.abs(df));
                    }
                } else dir = 0F;
                prev = f;
            }
            line(String.format(Locale.ROOT, "%s %s: %d frames, %.1f ms a frame (worst %.1f), marched %d; drawn time back %d, aiming turned %d, model FOV back %d (worst %.3f)",
                    GUNS[g], CASES[k], frames, frames > 1 ? ms / (frames - 1) : 0, worst, marched, back, flips, fovBack, fovWorst));
            ClientSmoke.check(back == 0 && flips == 0, "прицел в дыму: " + GUNS[g] + " " + CASES[k] + " время назад " + back + ", разворотов " + flips);
            ClientSmoke.check(fovBack == 0, "прицел в дыму: " + GUNS[g] + " " + CASES[k] + " FOV модели назад " + fovBack + " (до " + fovWorst + "°)");
            // not a pass for nothing: the gun went all the way in and out, and the STG-77's model FOV followed it (70 to 5)
            ClientSmoke.check(aimHi - aimLo > 0.9F, "прицел в дыму: " + GUNS[g] + " " + CASES[k] + " прицел прошёл " + aimLo + ".." + aimHi);
            if (g == 0) ClientSmoke.check(fovHi - fovLo > 30F, "прицел в дыму: " + GUNS[g] + " " + CASES[k] + " FOV модели прошёл " + fovLo + ".." + fovHi);
            // the smoke in front comes up with the first gun; by the second it may be gone
            if (k == 2 && g == 0 && !BOTS) ClientSmoke.check(marched > frames / 2, "прицел в дыму: дым впереди нарисован " + marched + "/" + frames);
        }
    }

    private static void finish() {
        if (done) return;
        done = true;
        recording = false;
        try {
            report();
            Files.write(Path.of(GasStand.DIR, "scope.tsv"), ROWS);
            Files.write(Path.of(GasStand.DIR, "scope.txt"), OUT);
        } catch (Exception e) {
            ClientSmoke.check(false, "прицел в дыму: запись " + e);
        }
        ClientSmoke.finish();
    }
}
