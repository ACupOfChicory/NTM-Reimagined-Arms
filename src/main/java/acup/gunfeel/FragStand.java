package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.hbm.extprop.HbmLivingProps;
import com.hbm.items.ModItems;
import com.hbm.util.EntityDamageUtil;

import acup.gunfeel.gl54.GrenadeKind;
import acup.gunfeel.gl54.frag.FragBurst;
import acup.gunfeel.gl54.frag.FragFx;
import acup.gunfeel.gl54.frag.FragSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PfragStand=<dir>: the GL-54's fragment grenades (airburst, armour-piercing airburst, incendiary) in the
// game, in numbers. Bursts are cast on the server with FragBurst (the grenade's own flight is the reflex branch's and
// the HE stand's business), husks stand where each case wants them, and the numbers go to <dir>/stand.tsv:
//   1-3 a husk 1.5 blocks ahead of the burst on the flight line, 3 ahead, 1 to the side (at 3 ahead): hits and damage
//   4   the cover: a wall of 2 blocks, 1 thick, at 20; a husk (and a baby one) behind it; burst 0.5 over the wall at 21
//   5   a husk in NTM titanium armour (physical threshold 3): airburst against armour-piercing airburst
//   6   the incendiary: burn time of a creature hit, the patch's area and time, the blocks it lights
//   7   the tracers on a frame: pixels that differ from the frame before the burst, counted by Python (frames in <dir>)
//   and the server time of each burst.
final class FragStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.fragStand");
    private static final Path DIR = Path.of(System.getProperty("ntmgunfeel.fragStandDir", "../frag-stand"));
    private static final BlockPos O = new BlockPos(8, 100, 8);
    // bursts per case, and the seeds they use
    private static final int N = 60;

    private static final Deque<BooleanSupplier> TODO = new ArrayDeque<>();
    private static final List<String> OUT = new ArrayList<>();
    private static boolean started;
    private static int wait;
    private static volatile int pending;
    private static volatile Object[] last;
    private static volatile float[] view;
    private static final List<Double> NANOS = new ArrayList<>();
    private static final java.util.Map<String, List<Double>> NANOS_BY = new java.util.LinkedHashMap<>();

    private FragStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        FragBurst.watch = o -> last = o;
        try {
            Files.createDirectories(DIR);
        } catch (java.io.IOException e) {
            ClientSmoke.check(false, "frag: папка " + e);
        }
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        float[] v = view;
        if (v != null && mc.player != null) {
            mc.player.setYRot(v[0]);
            mc.player.yRotO = v[0];
            mc.player.setXRot(v[1]);
            mc.player.xRotO = v[1];
        }
        if (!started) {
            started = true;
            plan(mc);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        var next = TODO.peek();
        try {
            if (next != null && next.getAsBoolean()) TODO.poll();
        } catch (Throwable t) {
            GunFeel.LOGGER.error("[frag] failed", t);
            ClientSmoke.check(false, "frag: " + t);
            TODO.clear();
            TODO.add(FragStand::done);
        }
    }

    private static void sleep(int frames) {
        TODO.add(() -> {
            wait = frames;
            return true;
        });
    }

    private static void run(Runnable r) {
        TODO.add(() -> {
            r.run();
            return true;
        });
    }

    private static void line(Object... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i++) {
            if (i > 0) b.append('\t');
            Object o = kv[i];
            b.append(o instanceof Double d ? String.format(Locale.ROOT, "%.3f", d) : o instanceof Float f ? String.format(Locale.ROOT, "%.3f", f) : String.valueOf(o));
        }
        OUT.add(b.toString());
        GunFeel.LOGGER.info("[frag] {}", b);
    }

    private static <T> T onServer(Minecraft mc, java.util.function.Supplier<T> job) {
        return mc.getSingleplayerServer().submit(job).join();
    }

    private static void onServer(Minecraft mc, Runnable job) {
        mc.getSingleplayerServer().submit(job).join();
    }

    private static long gameTime(Minecraft mc) {
        return mc.getSingleplayerServer().overworld().getGameTime();
    }

    // ---------------------------------------------------------------- the world

    // the player is out of reach of every burst; the arena: a floor, free air over it and the wall of case 4
    private static void arena(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            for (String c : new String[] {"time set noon", "weather clear", "gamerule advance_time false", "gamerule advance_weather false"}) {
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                } catch (RuntimeException e) {
                    GunFeel.LOGGER.warn("[frag] {}: {}", c, e.toString());
                }
            }
            ServerLevel level = server.overworld();
            for (int x = -9; x <= 9; x++) for (int z = -5; z <= 32; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.SNOW_BLOCK.defaultBlockState(), 3);
                for (int y = 0; y <= 14; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            // case 4's wall: 2 high, 1 thick, its front face at 20 blocks
            for (int x = -8; x <= 8; x++) for (int y = 0; y < 2; y++) level.setBlock(O.offset(x, y, 20), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            Config.cornerHud = false;
        }).join();
        park(mc);
    }

    private static void park(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        view = null;
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            p.getAbilities().invulnerable = true;
            p.teleportTo(server.overworld(), O.getX() - 40.0, O.getY(), O.getZ() + 0.5, Set.of(), 0F, 0F, false);
            p.getInventory().clearContent();
        }).join();
    }

    private static Husk husk(ServerLevel level, double x, double y, double z, boolean baby) {
        Husk h = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
        h.setNoAi(true);
        h.setPersistenceRequired();
        h.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        h.getAttribute(Attributes.ARMOR).setBaseValue(0);
        h.setHealth(1000);
        h.setBaby(baby);
        h.snapTo(x, y, z, 0F, 0F);
        level.addFreshEntity(h);
        return h;
    }

    private static void titanium(Husk h) {
        h.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.TITANIUM_HELMET.get()));
        h.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.TITANIUM_PLATE.get()));
        h.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModItems.TITANIUM_LEGS.get()));
        h.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.TITANIUM_BOOTS.get()));
    }

    private static void clearHusks(ServerLevel level) {
        for (var e : level.getEntitiesOfClass(Husk.class, new AABB(O).inflate(60))) e.discard();
        for (var e : level.getEntitiesOfClass(com.hbm.entity.effect.EntityFireLingering.class, new AABB(O).inflate(60))) e.discard();
    }

    // ---------------------------------------------------------------- the cases

    // N bursts of `spec` at `point` along `dir` at a fresh-healed husk: the average fragments that hit it (and the share of
    // the burst's rays), the damage and how often 20 health would be gone. `armour`: titanium on it. The server time of
    // each burst goes to NANOS.
    private static void trial(Minecraft mc, String name, FragSpec spec, Vec3 point, Vec3 dir, double hx, double hy, double hz, boolean baby, boolean armour) {
        run(() -> onServer(mc, () -> {
            ServerLevel level = mc.getSingleplayerServer().overworld();
            clearHusks(level);
            Husk h = husk(level, hx, hy, hz, baby);
            if (armour) titanium(h);
            int hitsSum = 0, kills = 0, zero = 0;
            double damageSum = 0;
            double[] damages = new double[N];
            for (int i = 0; i < N; i++) {
                h.setHealth(1000);
                h.invulnerableTime = 0;
                last = null;
                FragBurst.burst(spec, level, null, null, point, dir, 0x5EED_0000L + i * 7919L);
                Object[] w = last;
                LivingEntity[] near = (LivingEntity[]) w[3];
                int[] hits = (int[]) w[4];
                int mine = 0;
                for (int k = 0; k < near.length; k++) if (near[k] == h) mine = hits[k];
                double damage = 1000 - h.getHealth();
                hitsSum += mine;
                damageSum += damage;
                damages[i] = damage;
                if (damage >= 20) kills++;
                if (mine == 0) zero++;
                double ns = (Long) w[2];
                NANOS.add(ns);
                NANOS_BY.computeIfAbsent(spec.kind().key, k -> new ArrayList<>()).add(ns);
            }
            Arrays.sort(damages);
            line(name, "kind", spec.kind().key, "cone", spec.cone(), "ring", spec.ring(), "bursts", N, "hits/burst", hitsSum / (double) N,
                    "share%", 100.0 * hitsSum / N / Math.max(1, spec.rays()), "damage/burst", damageSum / N, "median", damages[N / 2],
                    "no-hit bursts%", 100.0 * zero / N, "kills(20hp)%", 100.0 * kills / N);
            clearHusks(level);
        }));
    }

    private static final Vec3 B = new Vec3(O.getX() + 0.5, O.getY() + 1.0, O.getZ() + 0.5);
    private static final Vec3 AHEAD = new Vec3(0, 0, 1);

    private static void cases(Minecraft mc) {
        FragSpec ab = FragSpec.AIRBURST, ap = FragSpec.AP, inc = FragSpec.INCENDIARY;
        FragSpec abCone = new FragSpec(GrenadeKind.AIRBURST, 65, 40, 0, 1.25F, 0F, 0F, 0), abRing = new FragSpec(GrenadeKind.AIRBURST, 65, 0, 40, 1.25F, 0F, 0F, 0);
        // 1-3: the husk's feet on the floor; the burst at its middle height
        for (FragSpec s : new FragSpec[] {ab, abCone, abRing, ap, inc}) {
            String tag = s == abCone ? "AB-cone" : s == abRing ? "AB-ring" : s.kind().code;
            trial(mc, "1 ahead 1.5 " + tag, s, B, AHEAD, B.x, O.getY(), B.z + 1.5, false, false);
            trial(mc, "2 ahead 3 " + tag, s, B, AHEAD, B.x, O.getY(), B.z + 3.0, false, false);
            trial(mc, "3 side 1 at 3 " + tag, s, B, AHEAD, B.x + 1.0, O.getY(), B.z + 3.0, false, false);
        }
        // 4: the cover; the burst 0.5 over the wall's top at 21 blocks, the husk 1.3 / 1.6 / 1.9 past the wall's front face
        Vec3 over = new Vec3(O.getX() + 0.5, O.getY() + 2.5, O.getZ() + 21.0);
        for (FragSpec s : new FragSpec[] {abCone, abRing, ab, ap, inc}) {
            String tag = s == abCone ? "AB-cone" : s == abRing ? "AB-ring" : s.kind().code;
            for (double past : new double[] {1.3, 1.6, 1.9})
                for (boolean baby : new boolean[] {false, true})
                    trial(mc, "4 cover " + (baby ? "baby " : "") + "past " + past + " " + tag, s, over, AHEAD, over.x, O.getY(), O.getZ() + 20.0 + past, baby, false);
        }
        // 5: armour
        run(() -> onServer(mc, () -> {
            ServerLevel level = mc.getSingleplayerServer().overworld();
            Husk h = husk(level, B.x, O.getY(), B.z + 1.5, false);
            titanium(h);
            DamageSource src = new DamageSource(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                    .getOrThrow(com.hbm.lib.ModDamageTypes.forClass(com.hbm.util.DamageClass.PHYSICAL)));
            for (float[] pierce : new float[][] {{0, 0}, {4, 0.1F}}) {
                float[] v = EntityDamageUtil.getDTDR(h, src, 1.25F, pierce[0], pierce[1]);
                line("5 armour", "titanium set", "threshold", v[0], "resistance", v[1], "pierceDT", pierce[0], "pierce", pierce[1]);
            }
            clearHusks(level);
        }));
        for (FragSpec s : new FragSpec[] {ab, ap, inc}) {
            trial(mc, "5 armour titanium ahead 1.5 " + s.kind().code, s, B, AHEAD, B.x, O.getY(), B.z + 1.5, false, true);
            trial(mc, "5 armour none ahead 1.5 " + s.kind().code, s, B, AHEAD, B.x, O.getY(), B.z + 1.5, false, false);
        }
    }

    // 6: the incendiary
    private static long t0;
    private static Husk burning;

    private static void incendiary(Minecraft mc) {
        FragSpec inc = FragSpec.INCENDIARY;
        // a creature hit, in the air out of the floor's reach (no patch): the fire it has at once and how long it burns
        run(() -> onServer(mc, () -> {
            ServerLevel level = mc.getSingleplayerServer().overworld();
            clearHusks(level);
            Vec3 high = new Vec3(B.x, O.getY() + 20.0, B.z);
            burning = husk(level, high.x, high.y - 1.0, high.z + 1.5, false);
            burning.setNoGravity(true);
            FragBurst.burst(inc, level, null, null, high, AHEAD, 99L);
            Object[] w = last;
            int[] hits = (int[]) w[4];
            line("6 burn", "fragment hits", Arrays.stream(hits).sum(), "fire ticks at once", burning.getRemainingFireTicks());
            t0 = level.getGameTime();
        }));
        TODO.add(() -> {
            if (burning.getRemainingFireTicks() > 0 && gameTime(mc) - t0 < 400) return false;
            line("6 burn", "ticks until it is out", gameTime(mc) - t0, "seconds", (gameTime(mc) - t0) / 20.0);
            return true;
        });
        // the patch: a creature alone at each place, a few ticks after the burst
        double[][] places = {{0.5, 0}, {1.0, 0}, {2.0, 0}, {2.4, 0}, {2.6, 0}, {3.0, 0}, {3.4, 0}, {1.5, 1.5}, {2.0, 2.0}, {2.4, 2.4}, {2.8, 2.8}};
        for (double[] pl : places) {
            run(() -> onServer(mc, () -> {
                ServerLevel level = mc.getSingleplayerServer().overworld();
                clearHusks(level);
                Vec3 at = new Vec3(B.x, O.getY() + 1.5, B.z);
                burning = husk(level, B.x + pl[0], O.getY(), B.z + pl[1], false);
                FragBurst.burst(new FragSpec(GrenadeKind.INCENDIARY, 65, 0, 0, 0.75F, 0F, 0F, 80), level, null, null, at, AHEAD, 1L);
                t0 = level.getGameTime();
            }));
            TODO.add(() -> {
                if (gameTime(mc) - t0 < 4) return false;
                var props = HbmLivingProps.getData(burning);
                double r = Math.hypot(pl[0], pl[1]);
                line("6 patch", "husk centre from the fire (dx, dz)", pl[0] + ", " + pl[1], "distance", r, "lit", props.fire > 0 || burning.getRemainingFireTicks() > 0,
                        "ntm fire", props.fire, "vanilla ticks", burning.getRemainingFireTicks());
                return true;
            });
        }
        // the patch's time, and what it lights: planks next to the burst light the air cells beside them inside the cube of 3
        run(() -> onServer(mc, () -> {
            ServerLevel level = mc.getSingleplayerServer().overworld();
            clearHusks(level);
            BlockPos c = BlockPos.containing(B.x, O.getY() + 0.01, B.z);
            level.setBlock(c.offset(1, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            level.setBlock(c.offset(3, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            level.setBlock(c.offset(-1, -1, 0), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            FragBurst.burst(new FragSpec(GrenadeKind.INCENDIARY, 65, 0, 0, 0.75F, 0F, 0F, 80), level, null, null, new Vec3(B.x, O.getY() + 1.5, B.z), AHEAD, 2L);
            List<String> fires = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, 3, 4)))
                if (level.getBlockState(p).is(Blocks.FIRE)) fires.add((p.getX() - c.getX()) + "," + (p.getY() - c.getY()) + "," + (p.getZ() - c.getZ()));
            line("6 blocks", "fire blocks (dx,dy,dz from the burst cell)", fires.size(), fires, "planks at (1,0,0) in the cube, (3,0,0) outside it, (-1,-1,0) is the floor's");
            t0 = level.getGameTime();
        }));
        TODO.add(() -> {
            ServerLevel level = mc.getSingleplayerServer().overworld();
            var fire = level.getEntitiesOfClass(com.hbm.entity.effect.EntityFireLingering.class, new AABB(O).inflate(60));
            if (!fire.isEmpty() && gameTime(mc) - t0 < 400) return false;
            line("6 patch", "ticks the fire entity lasted", gameTime(mc) - t0, "seconds", (gameTime(mc) - t0) / 20.0, "width", com.hbm.entity.effect.EntityFireLingering.class.getSimpleName());
            return true;
        });
        run(() -> onServer(mc, () -> {
            ServerLevel level = mc.getSingleplayerServer().overworld();
            clearHusks(level);
            BlockPos c = BlockPos.containing(B.x, O.getY() + 0.01, B.z);
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, 4, 4)))
                if (!level.getBlockState(p).isAir() && p.getY() >= O.getY()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }));
    }

    // 7: the tracers on a frame. The player stands 5 blocks behind the burst looking at it; the frame before the burst and
    // the frame with the streaks held at 0.1 s of age are compared by Python
    private static void tracers(Minecraft mc) {
        for (GrenadeKind kind : new GrenadeKind[] {GrenadeKind.AIRBURST, GrenadeKind.AP, GrenadeKind.INCENDIARY}) {
            FragSpec s = FragSpec.of(kind);
            run(() -> {
                FragFx.puffs = false;
                FragFx.holdAge = Double.NaN;
                var server = mc.getSingleplayerServer();
                view = new float[] {0F, 0F};
                server.submit(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                    p.teleportTo(server.overworld(), B.x, O.getY(), B.z - 5.0, Set.of(), 0F, 0F, false);
                }).join();
            });
            sleep(60);
            run(() -> shot("trace-" + kind.key + "-before.png"));
            TODO.add(() -> pending == 0);
            run(() -> {
                FragFx.holdAge = 0.2;
                onServer(mc, () -> {
                    ServerLevel level = mc.getSingleplayerServer().overworld();
                    // the eye is at 1.62 over the floor: the burst is at the eye's height, 4 blocks ahead of the player
                    FragBurst.burst(s, level, null, null, new Vec3(B.x, O.getY() + 1.62, B.z - 1.0), AHEAD, 424242L);
                });
            });
            TODO.add(() -> FragFx.drawn > 0);
            sleep(6);
            run(() -> {
                line("7 tracers", "kind", kind.key, "streaks drawn in the frame", FragFx.drawn);
                shot("trace-" + kind.key + "-after.png");
            });
            TODO.add(() -> pending == 0);
            run(() -> FragFx.holdAge = Double.NaN);
            sleep(30);
        }
    }

    // 8: the holes on a wall 2 blocks ahead of the burst (and on the floor 1.5 under it): the decals before and after, the
    // client's cost of the packet and the frames the queue took to empty
    private static int holesBefore;

    private static void holes(Minecraft mc) {
        run(() -> {
            FragFx.puffs = false;
            onServer(mc, () -> {
                ServerLevel level = mc.getSingleplayerServer().overworld();
                clearHusks(level);
                for (int x = -8; x <= 8; x++) for (int y = 0; y < 5; y++) level.setBlock(O.offset(x, y, 26), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            });
        });
        sleep(20);
        for (GrenadeKind kind : new GrenadeKind[] {GrenadeKind.AIRBURST, GrenadeKind.AP, GrenadeKind.INCENDIARY}) {
            FragSpec s = FragSpec.of(kind);
            run(() -> {
                holesBefore = Tracers.holeCount();
                onServer(mc, () -> FragBurst.burst(s, mc.getSingleplayerServer().overworld(), null, null, new Vec3(B.x, O.getY() + 1.5, O.getZ() + 24.0), AHEAD, 777L));
            });
            TODO.add(() -> Tracers.holeCount() > holesBefore);
            sleep(30);
            run(() -> line("8 holes", "kind", kind.key, "rays", s.rays(), "holes left", Tracers.holeCount() - holesBefore, "still queued", Tracers.pendingFragmentHits(),
                    "client packet ms", FragFx.lastHandleNanos / 1e6, "handed over", FragFx.lastHoles, "holes in total", Tracers.holeCount()));
        }
    }

    private static void plan(Minecraft mc) {
        run(() -> arena(mc));
        sleep(40);
        cases(mc);
        incendiary(mc);
        holes(mc);
        run(() -> park(mc));
        tracers(mc);
        run(FragStand::times);
        TODO.add(FragStand::done);
    }

    private static void times() {
        for (var e : NANOS_BY.entrySet()) {
            List<Double> v = new ArrayList<>(e.getValue());
            Collections.sort(v);
            double sum = 0;
            for (double d : v) sum += d;
            line("server time per burst, ms", "kind", e.getKey(), "bursts", v.size(), "mean", sum / v.size() / 1e6, "median", v.get(v.size() / 2) / 1e6,
                    "p95", v.get((int) (v.size() * 0.95)) / 1e6, "max", v.get(v.size() - 1) / 1e6, "first", e.getValue().get(0) / 1e6);
        }
    }

    private static boolean done() {
        if (pending != 0) return false;
        try {
            Files.write(DIR.resolve("stand.tsv"), OUT);
        } catch (java.io.IOException e) {
            ClientSmoke.check(false, "frag: " + e);
        }
        ClientSmoke.check(true, "frag: числа и кадры в " + DIR);
        ClientSmoke.finish();
        return true;
    }

    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        pending++;
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(DIR.resolve(name));
                OUT.add("#file\t" + name + "\t" + image.getWidth() + "x" + image.getHeight());
            } catch (java.io.IOException e) {
                GunFeel.LOGGER.warn("[frag] кадр не записан", e);
            } finally {
                pending--;
            }
        });
    }
}
