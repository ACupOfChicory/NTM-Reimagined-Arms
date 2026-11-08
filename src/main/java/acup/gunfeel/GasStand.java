package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.handler.ArmorUtil;
import com.hbm.lib.ModDamageTypes;
import com.hbm.util.DamageResistanceHandler;

import acup.gunfeel.gas.GasAttach;
import acup.gunfeel.gas.GasGrenades;
import acup.gunfeel.gas.GasKind;
import acup.gunfeel.gas.GasServer;
import acup.gunfeel.gas.GasState;
import acup.gunfeel.gas.client.GasClient;
import acup.gunfeel.gl54.Gl54Ammo;
import acup.gunfeel.gl54.Gl54Grenade;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import acup.gunfeel.smoke.SmokeNet;
import acup.gunfeel.smoke.client.SmokeClient;
import acup.gunfeel.smoke.client.SmokeFog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

// runCheckClient -PgasStand=<dir>: the GL-54's gas grenades in the game, in numbers. Each gas is fired (a real grenade, off the
// gun's own ammunition) into a walled pen with a doorway where a zombie with no protection, a zombie with it and the player
// stand; then the player once more with protection. Every second the stand logs HP, the doses, the filter left, K and the
// cloud's cell counts to <dir>/gas.tsv; <dir>/summary.txt has the time to death or to 4 HP and the filter at the end.
// Then a pit beside a burst (heavy chlorine against neutral acid), and 8 clouds at once with and without creatures in them
// (the server's time on the gases a tick). The player is kept alive by the stand: its HP here is 20 less the gases' damage.
final class GasStand {
    static final String DIR = System.getProperty("ntmgunfeel.gasStand", "");
    static final boolean ENABLED = !DIR.isBlank();
    // -PgasStandVis: only the looks (the scenarios, the pit and the cost are skipped)
    private static final boolean VIS_ONLY = Boolean.getBoolean("ntmgunfeel.gasVisOnly");
    private static final int Y = 100;
    // the pen: inside x -4..4, z 1..8; a doorway at z 0; walls 4 high
    private static final int PEN_Z0 = 1, PEN_Z1 = 8;

    private record Scenario(String name, GasKind gas, int ticks, boolean player, boolean protectedPlayer) {
    }

    private static final List<Scenario> SCENARIOS = new ArrayList<>();
    static {
        int[] ticks = {900, 1500, 900, 900};
        for (GasKind g : GasKind.values()) {
            SCENARIOS.add(new Scenario(g.key + "-A", g, ticks[g.ordinal()], true, false));
            SCENARIOS.add(new Scenario(g.key + "-B", g, ticks[g.ordinal()] * 2 / 3, true, true));
        }
    }

    private static final class Sub {
        final String name;
        final LivingEntity e;
        final boolean player;
        float minHp = 20F, dealt;
        int death = -1, four = -1, firstHurt = -1;
        final float[] maxDose = new float[GasState.N];
        float maxK, filter = 100F, dtMin = 99F, dt0 = -1F, dr0 = -1F, drMin = 99F;
        float lastFilterRow = 100F;
        int worn;
        boolean gone;

        Sub(String name, LivingEntity e, boolean player) {
            this.name = name;
            this.e = e;
            this.player = player;
        }
    }

    private static volatile boolean finished, started;
    private static int index = -1, counter, burstTick = -1, stage;
    private static Scenario sc;
    private static final List<Sub> SUBS = new ArrayList<>();
    private static final List<String> ROWS = new ArrayList<>(), SUMMARY = new ArrayList<>();
    private static int lastCloudId = -1;
    // client side checks
    private static float clientMaxChlorine, clientMaxK;
    private static boolean clientCough, clientNoSprint;
    // the pit and the cost
    private static final float[] PIT = new float[2];
    private static long costTicks, costNanos, costMax, costN;

    private GasStand() {
    }

    static void start() {
        // -PgasSoil: poisoned ground and the stirred gas instead (GasSoilStand)
        if (GasSoilStand.ON) {
            GasSoilStand.start();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set midnight");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "weather clear");
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            var block = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
            for (int x = -90; x <= 90; x++) for (int z = -40; z <= 60; z++) {
                for (int y = Y - 5; y < Y; y++) level.setBlock(new BlockPos(x, y, z), block, 2);
                for (int y = Y; y <= Y + 12; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            // the pen
            for (int x = -5; x <= 5; x++) for (int z = PEN_Z0 - 1; z <= PEN_Z1 + 1; z++) for (int y = Y; y < Y + 4; y++) {
                boolean wall = x == -5 || x == 5 || z == PEN_Z0 - 1 || z == PEN_Z1 + 1;
                boolean door = z == PEN_Z0 - 1 && Math.abs(x) <= 1 && y < Y + 3;
                if (wall && !door) level.setBlock(new BlockPos(x, y, z), Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState(), 2);
            }
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.SURVIVAL);
        }).join();
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "газ: папка " + DIR + ": " + e);
        }
        Gl54Grenade.watch = o -> {
            if (burstTick < 0 && o[2] instanceof Boolean b && b) burstTick = counter;
        };
        ROWS.add("scenario\tt_s\tsubject\thp\tchlorine\tphosgene\tmustard\tacid\tk\tfilter_pct\tcells_c03\tcells_c005\tcloud_total\tdt\tdr");
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> serverTick(e.getServer()));
        started = true;
        if (VIS_ONLY) finished = true;
    }

    static void frame() {
        if (GasSoilStand.ON) {
            GasSoilStand.frame();
            return;
        }
        // the client's side of the player's doses and coughs: what the packets brought
        clientMaxChlorine = Math.max(clientMaxChlorine, GasClient.dose(GasState.CHLORINE));
        clientMaxK = Math.max(clientMaxK, GasClient.k());
        clientCough |= GasClient.coughing();
        clientNoSprint |= GasClient.noSprint();
        if (finished) visuals();
    }

    // ---- the looks: each gas let out in the open, shot from 8 blocks outside it, from 2 and 5 blocks of it from the edge,
    // and from inside it; each shot has a reference of the same view with no cloud (gas-vis.py counts the pixels)

    private static final String[] POSES = {"outside8", "through2", "through5", "inside"};
    private static final Vec3 BURST = new Vec3(60.5, Y + 0.5, -20.5);
    private static int vGas, vPose, vStep, vFrames, vLightWait;
    private static long vSpawnTick;
    private static double vEdge = 4;
    private static final List<String> VIS = new ArrayList<>();
    private static String savedQuality;
    private static boolean visStarted;

    // the camera of a pose: x, y (eyes), z; looking +z, level
    private static double[] camera(int pose) {
        double eye = Y + 1.6;
        return switch (pose) {
            case 0 -> new double[] {BURST.x, eye, BURST.z - vEdge - 8};
            case 1 -> new double[] {BURST.x, eye, BURST.z + vEdge - 2};
            case 2 -> new double[] {BURST.x, eye, BURST.z + vEdge - 5};
            default -> new double[] {BURST.x, eye, BURST.z};
        };
    }

    private static void place(double[] c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.setPos(c[0], c[1] - mc.player.getEyeHeight(), c[2]);
        mc.player.setYRot(0F);
        mc.player.setXRot(0F);
        mc.player.yRotO = 0F;
        mc.player.xRotO = 0F;
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    private static void teleport(double[] c) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            var sp = server.getPlayerList().getPlayers().getFirst();
            sp.teleportTo(server.overworld(), c[0], c[1] - sp.getEyeHeight(), c[2], Set.of(), 0F, 0F, false);
        }).join();
    }

    private static void shoot(String name) {
        Path file = Path.of(DIR, name + ".png");
        net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
            } catch (Exception e) {
                GunFeel.LOGGER.error("[gasstand] {}", file, e);
            }
        });
    }

    private static void visuals() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (!visStarted) {
            visStarted = true;
            savedQuality = Config.smokeQuality;
            Config.smokeQuality = "high";
            server.submit(() -> {
                ServerLevel level = server.overworld();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
                server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR);
                endAll(level);
                var white = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
                var black = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
                // the backdrop: a wall of black and white stripes far behind the cloud (gas-vis.py measures their contrast)
                for (int x = 30; x <= 90; x++) for (int y = Y; y < Y + 14; y++) level.setBlock(new BlockPos(x, y, 24), (x >> 1 & 1) == 0 ? black : white, 2);
            }).join();
            vGas = vPose = vStep = vFrames = 0;
        }
        if (vGas >= GasKind.values().length + 1) {
            Config.smokeQuality = savedQuality;
            try {
                Files.write(Path.of(DIR, "vis.tsv"), VIS);
            } catch (Exception e) {
                ClientSmoke.check(false, "газ: запись " + e);
            }
            done();
            return;
        }
        // the gases, then plain smoke for comparison
        SmokeKind kind = vGas < GasKind.values().length ? GasKind.values()[vGas].smoke() : SmokeKind.SMOKE;
        String name = kind.name() + "-" + POSES[vPose];
        switch (vStep) {
            case 0 -> {
                // the cleared field's sky light comes in late (with -PgasStandVis no scenario runs first): wait for it
                // in the cloud along the shots' line, 4 min at most
                boolean dark = false;
                for (int dz = -3; dz <= 10; dz++) dark |= mc.level.getMaxLocalRawBrightness(BlockPos.containing(BURST.x, Y + 1.6, BURST.z + dz)) < 15;
                if (dark && ++vLightWait < 14400) return;
                // a cloud, and 2.5 s for it to fill
                server.submit(() -> {
                    endAll(server.overworld());
                    GasGrenades.spawn(server.overworld(), kind, BURST, new Vec3(0, -0.3, 1).normalize());
                    vSpawnTick = server.overworld().getGameTime();
                }).join();
                vStep = 1;
            }
            case 1 -> {
                if (mc.level.getGameTime() - vSpawnTick < 50) return;
                // how far it reaches along the shot's line at eye height: the edge, where C falls under 0.1
                double[] edge = {0};
                server.submit(() -> {
                    var all = SmokeClouds.get(server.overworld());
                    var c = all == null || all.clouds().isEmpty() ? null : all.clouds().get(0);
                    if (c == null) return;
                    for (int dz = 0; dz < 12; dz++) if (c.cell(60, Y + 1, -21 + dz) >= 0.1F) edge[0] = dz + 1;
                }).join();
                vEdge = Math.max(3, edge[0]);
                double[] cam = camera(vPose);
                teleport(cam);
                vFrames = 0;
                vStep = 2;
            }
            case 2 -> {
                place(camera(vPose));
                if (++vFrames < 10) return;
                var all = SmokeClient.current();
                var c = all == null || all.clouds().isEmpty() ? null : all.clouds().get(0);
                double[] cam = camera(vPose);
                VIS.add(String.format(Locale.ROOT, "%s	%s	shot	fog=%.3f	cloud_age=%d	edge=%.0f	density_at_camera=%.3f\tlight=%.3f\traw=%d\ttop=%d\tlight_wait=%d", kind.name(), POSES[vPose],
                        SmokeFog.atCamera(), c == null ? -1 : c.grid.age(), vEdge, c == null ? -1F : c.density(cam[0], cam[1], cam[2]),
                        acup.gunfeel.smoke.client.SmokeLight.brightness(mc.level, BlockPos.containing(cam[0], cam[1], cam[2])),
                        mc.level.getMaxLocalRawBrightness(BlockPos.containing(cam[0], cam[1], cam[2])),
                        mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(cam[0]), (int) Math.floor(cam[2])), vLightWait));
                shoot(name + "-cloud");
                vFrames = 0;
                vStep = 3;
            }
            case 3 -> {
                if (++vFrames < 12) return;
                // the cloud away, and the same view empty
                var server2 = server;
                server2.submit(() -> endAll(server2.overworld())).join();
                vFrames = 0;
                vStep = 4;
            }
            case 4 -> {
                place(camera(vPose));
                SmokeClouds known = SmokeClient.current();
                if (SmokeFog.atCamera() > 0.002F || known != null && !known.clouds().isEmpty()) {
                    vFrames = 0;
                    return;
                }
                if (++vFrames < 15) return;
                shoot(name + "-ref");
                vFrames = 0;
                vStep = 5;
            }
            case 5 -> {
                if (++vFrames < 12) return;
                vStep = 0;
                if (++vPose >= POSES.length) {
                    vPose = 0;
                    vGas++;
                }
            }
            default -> {
            }
        }
    }

    private static boolean done;

    private static void done() {
        if (done) return;
        done = true;
        Gl54Grenade.watch = null;
        SUMMARY.add(String.format(Locale.ROOT, "# client: max chlorine dose seen %.2f, max K seen %.2f, cough seen %s, noSprint seen %s", clientMaxChlorine, clientMaxK, clientCough, clientNoSprint));
        try {
            Files.write(Path.of(DIR, "gas.tsv"), ROWS);
            Files.write(Path.of(DIR, "summary.txt"), SUMMARY);
        } catch (Exception e) {
            ClientSmoke.check(false, "газ: запись " + e);
        }
        if (!VIS_ONLY) ClientSmoke.check(clientMaxChlorine > 0.5F, "газ: клиент получил дозу хлора " + clientMaxChlorine);
        if (!VIS_ONLY) ClientSmoke.check(clientCough, "газ: клиент получил кашель");
        ClientSmoke.finish();
    }

    // ---- the server

    private static void serverTick(MinecraftServer server) {
        if (finished || !started) return;
        ServerLevel level = server.overworld();
        try {
            if (stage == 0) scenarioTick(server, level);
            else if (stage == 1) pitTick(level);
            else if (stage == 2) costTick(server, level);
        } catch (RuntimeException e) {
            GunFeel.LOGGER.error("[gasstand]", e);
            ClientSmoke.check(false, "газ: " + e);
            finished = true;
        }
    }

    private static void endAll(ServerLevel level) {
        SmokeClouds all = SmokeClouds.get(level);
        if (all != null) for (var c : all.clouds()) {
            all.remove(c.id);
            SmokeNet.ended(level, c);
        }
    }

    private static void scenarioTick(MinecraftServer server, ServerLevel level) {
        if (sc == null) {
            if (++index >= SCENARIOS.size()) {
                stage = 1;
                counter = 0;
                return;
            }
            sc = SCENARIOS.get(index);
            setup(server, level);
            counter = 0;
            burstTick = -1;
            return;
        }
        counter++;
        if (counter == 10) fire(level);
        if (burstTick >= 0) {
            int t = counter - burstTick;
            observe(level, t);
            if (t % 20 == 0) row(level, t);
            if (t >= sc.ticks) {
                summarize(level);
                sc = null;
            }
        } else if (counter > 400) {
            ClientSmoke.check(false, "газ: граната " + sc.name + " не разорвалась");
            sc = null;
        }
    }

    private static ItemStack stack(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
    }

    private static void wear(LivingEntity e, String set) {
        for (var s : new Object[][] {{EquipmentSlot.HEAD, "helmet"}, {EquipmentSlot.CHEST, "plate"}, {EquipmentSlot.LEGS, "legs"}, {EquipmentSlot.FEET, "boots"}})
            e.setItemSlot((EquipmentSlot) s[0], stack("hbm:" + set + "_" + s[1]));
    }

    private static void mask(LivingEntity e) {
        ItemStack mask = stack("hbm:gas_mask");
        ArmorUtil.installGasMaskFilter(mask, stack("hbm:gas_mask_filter"));
        e.setItemSlot(EquipmentSlot.HEAD, mask);
    }

    private static void protect(LivingEntity e, GasKind g) {
        switch (g) {
            case CHLORINE, PHOSGENE -> mask(e);
            case MUSTARD -> wear(e, "fau");
            case ACID -> wear(e, "steel");
        }
    }

    private static Mob zombie(ServerLevel level, double x, double z) {
        Mob z0 = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        z0.setNoAi(true);
        z0.setPersistenceRequired();
        z0.setPos(x, Y, z);
        z0.setYRot(0F);
        level.addFreshEntity(z0);
        return z0;
    }

    private static void setup(MinecraftServer server, ServerLevel level) {
        endAll(level);
        for (var e : level.getEntitiesOfClass(Mob.class, new AABB(-90, Y - 2, -40, 90, Y + 12, 60))) e.discard();
        for (var e : level.getEntitiesOfClass(EntityBulletBaseMK4.class, new AABB(-90, Y - 2, -40, 90, Y + 12, 60))) e.discard();
        SUBS.clear();
        ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
        for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) p.setItemSlot(s, ItemStack.EMPTY);
        p.removeData(GasAttach.STATE.get());
        p.setHealth(20F);
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(20F);
        p.removeAllEffects();
        p.teleportTo(level, -1.5, Y, 3.5, Set.of(), 0F, 0F, false);
        // the player's own flat floor is the stand's: keep away from the burst's wall at z 9 by 5 blocks
        if (sc.protectedPlayer) protect(p, sc.gas);
        SUBS.add(new Sub(sc.protectedPlayer ? "player-protected" : "player-bare", p, true));
        if (!sc.protectedPlayer) {
            Mob bare = zombie(level, -3.0, 4.5);
            SUBS.add(new Sub("zombie-bare", bare, false));
            Mob guarded = zombie(level, 3.0, 4.5);
            protect(guarded, sc.gas);
            SUBS.add(new Sub("zombie-protected", guarded, false));
            if (sc.gas == GasKind.MUSTARD) {
                // half the kit: the helmet and nothing else
                Mob half = zombie(level, -3.0, 6.5);
                half.setItemSlot(EquipmentSlot.HEAD, stack("hbm:fau_helmet"));
                SUBS.add(new Sub("zombie-helmet-only", half, false));
            }
            if (sc.gas == GasKind.ACID) {
                Mob masked = zombie(level, 3.0, 6.5);
                mask(masked);
                SUBS.add(new Sub("zombie-mask-only", masked, false));
            }
        }
        for (Sub s : SUBS) s.e.removeData(GasAttach.STATE.get());
    }

    private static void fire(ServerLevel level) {
        ServerPlayer p = (ServerPlayer) SUBS.get(0).e;
        Vec3 from = new Vec3(0.5, Y + 1.5, -9.0);
        Vec3 dir = new Vec3(0, 0.0, 1);
        var bullet = new EntityBulletBaseMK4(level, p, Gl54Ammo.type(sc.gas.key), Gl54Grenade.BASE_DAMAGE, 0F, from.x, from.y, from.z, dir.x, dir.y, dir.z);
        level.addFreshEntity(bullet);
    }

    private static float hp(Sub s) {
        if (!s.player) return s.e.getHealth();
        GasState st = s.e.getExistingDataOrNull(GasAttach.STATE.get());
        if (st != null) s.dealt = Math.max(s.dealt, st.dealt);
        return Math.max(0F, 20F - s.dealt);
    }

    private static float[] dtdr(ServerLevel level, LivingEntity e) {
        var src = level.damageSources().cactus();
        return DamageResistanceHandler.getDTDR(e, src, 1.25F, 0F, 0F);
    }

    private static void observe(ServerLevel level, int t) {
        for (Sub s : SUBS) {
            if (s.gone) continue;
            if (!s.e.isAlive()) {
                if (s.death < 0) s.death = t;
                s.gone = true;
                continue;
            }
            float hp = hp(s);
            s.minHp = Math.min(s.minHp, hp);
            if (s.firstHurt < 0 && hp < 19.99F) s.firstHurt = t;
            if (s.four < 0 && hp <= 4F) s.four = t;
            if (s.player && hp <= 0F && s.death < 0) s.death = t;
            if (s.player && s.e.getHealth() < 6F) s.e.setHealth(20F);
            GasState st = s.e.getExistingDataOrNull(GasAttach.STATE.get());
            if (st != null) {
                for (int i = 0; i < GasState.N; i++) s.maxDose[i] = Math.max(s.maxDose[i], st.dose[i]);
                s.maxK = Math.max(s.maxK, st.k);
            }
            if (st != null) s.worn = Math.max(s.worn, st.worn);
            s.filter = GasServer.filterLeft(s.e.getItemBySlot(EquipmentSlot.HEAD)) * 100F;
            float[] v = dtdr(level, s.e);
            if (s.dt0 < 0F) {
                s.dt0 = v[0];
                s.dr0 = v[1];
            }
            s.dtMin = Math.min(s.dtMin, v[0]);
            s.drMin = Math.min(s.drMin, v[1]);
        }
    }

    private static void row(ServerLevel level, int t) {
        int c03 = 0, c005 = 0;
        float total = 0F;
        SmokeClouds all = SmokeClouds.get(level);
        if (all != null) for (var c : all.clouds()) {
            for (int i = 0; i < acup.gunfeel.smoke.SmokeGrid.CELLS; i++) {
                float d = c.grid.density(i);
                if (d > 0.3F) c03++;
                if (d > 0.05F) c005++;
                total += d;
            }
        }
        for (Sub s : SUBS) {
            GasState st = s.e.getExistingDataOrNull(GasAttach.STATE.get());
            float[] d = st == null ? new float[GasState.N] : st.dose;
            float[] v = s.e.isAlive() ? dtdr(level, s.e) : new float[] {0F, 0F};
            ROWS.add(String.format(Locale.ROOT, "%s\t%.1f\t%s\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.1f\t%d\t%d\t%.1f\t%.2f\t%.3f", sc.name, t / 20F, s.name,
                    s.e.isAlive() ? hp(s) : 0F, d[0], d[1], d[2], d[3], st == null ? 0F : st.k, s.filter, c03, c005, total, v[0], v[1]));
        }
    }

    private static void summarize(ServerLevel level) {
        for (Sub s : SUBS) {
            String end = s.death >= 0 ? "dead at " + String.format(Locale.ROOT, "%.1f", s.death / 20F) + " s" : "alive";
            String four = s.four >= 0 ? String.format(Locale.ROOT, "%.1f s", s.four / 20F) : "never";
            String first = s.firstHurt >= 0 ? String.format(Locale.ROOT, "%.1f s", s.firstHurt / 20F) : "never";
            GasState st = s.e.getExistingDataOrNull(GasAttach.STATE.get());
            String line = String.format(Locale.ROOT, "%s / %s: %s; first damage %s; 4 HP at %s; HP min %.1f end %.1f; max doses CL %.1f PH %.1f MU %.1f AC %.1f; max K %.2f; filter left %.1f%% (worn %d); DT %.2f->%.2f DR %.3f->%.3f",
                    sc.name, s.name, end, first, four, s.minHp, s.e.isAlive() ? hp(s) : 0F, s.maxDose[0], s.maxDose[1], s.maxDose[2], s.maxDose[3], s.maxK,
                    s.filter, s.worn, s.dt0, s.dtMin, s.dr0, s.drMin);
            SUMMARY.add(line);
            GunFeel.LOGGER.info("[gasstand] {}", line);
        }
        SUMMARY.add("");
    }

    // ---- the pit

    private static int pitStart, pitKind;
    private static final BlockPos PIT_AT = new BlockPos(34, Y - 3, 8);

    private static void pitTick(ServerLevel level) {
        counter++;
        if (counter == 1) {
            // a pit 5 x 5 x 3 east of the burst point at (30.5, Y, 10.5)
            for (int x = 34; x <= 38; x++) for (int z = 8; z <= 12; z++) for (int y = Y - 3; y < Y; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            for (var e : level.getEntitiesOfClass(Mob.class, new AABB(-90, Y - 6, -40, 90, Y + 12, 60))) e.discard();
            endAll(level);
            pitKind = 0;
            pitStart = 1;
            ROWS.add("# pit (sum of C in the pit's 75 cells; burst 4 blocks from its edge)");
        }
        GasKind kind = pitKind == 0 ? GasKind.CHLORINE : GasKind.ACID;
        int t = counter - pitStart;
        if (t == 0) {
            endAll(level);
            GasGrenades.spawn(level, kind.smoke(), new Vec3(30.5, Y + 0.5, 10.5), new Vec3(1, 0, 0));
        }
        if (t > 0 && t % 20 == 0) {
            float sum = 0F;
            SmokeClouds all = SmokeClouds.get(level);
            var cl = all == null || all.clouds().isEmpty() ? null : all.clouds().get(0);
            if (cl != null) for (int x = 34; x <= 38; x++) for (int z = 8; z <= 12; z++) for (int y = Y - 3; y < Y; y++) sum += cl.cell(x, y, z);
            ROWS.add(String.format(Locale.ROOT, "pit-%s\t%.1f\tpit_sum\t%.2f", kind.key, t / 20F, sum));
            PIT[pitKind] = Math.max(PIT[pitKind], sum);
        }
        if (t >= 400) {
            SUMMARY.add(String.format(Locale.ROOT, "pit %s: most gas in the pit %.1f (C summed over its 75 cells)", kind.key, PIT[pitKind]));
            if (pitKind == 0) {
                pitKind = 1;
                pitStart = counter + 1;
            } else {
                SUMMARY.add("");
                stage = 2;
                counter = 0;
            }
        }
    }

    // ---- the cost of eight clouds

    private static final List<Mob> CROWD = new ArrayList<>();
    private static int costPhase;

    private static void costTick(MinecraftServer server, ServerLevel level) {
        counter++;
        if (counter == 1) {
            for (var e : level.getEntitiesOfClass(Mob.class, new AABB(-90, Y - 6, -40, 90, Y + 12, 60))) e.discard();
            endAll(level);
            for (int i = 0; i < 8; i++) GasGrenades.spawn(level, GasKind.CHLORINE.smoke(), new Vec3(-70.5 + 20 * i, Y + 0.5, 40.5), new Vec3(0, -0.3, 1));
            costPhase = 0;
            resetCost();
        }
        long n = GasServer.ticks;
        if (n > costTicks) {
            long spent = GasServer.lastNanos;
            costNanos += spent;
            costMax = Math.max(costMax, spent);
            costTicks = n;
            costN++;
        }
        if (counter == 200 && costPhase == 0) {
            SUMMARY.add(String.format(Locale.ROOT, "8 chlorine clouds, nobody in them: gases %.3f ms a tick on average, %.3f at most (over %d ticks); smoke system: %s",
                    costNanos / 1e6 / Math.max(1, costN), costMax / 1e6, costN, SmokeClouds.serverCost()));
            // four zombies in each cloud's core
            for (int i = 0; i < 8; i++) for (int k = 0; k < 4; k++) CROWD.add(zombie(level, -70.5 + 20 * i + (k - 1.5) * 1.2, 41.5 + (k % 2)));
            costPhase = 1;
            resetCost();
        }
        if (counter == 420 && costPhase == 1) {
            SUMMARY.add(String.format(Locale.ROOT, "8 chlorine clouds with 32 zombies in them: gases %.3f ms a tick on average, %.3f at most (over %d ticks); smoke system: %s",
                    costNanos / 1e6 / Math.max(1, costN), costMax / 1e6, costN, SmokeClouds.serverCost()));
            for (Mob m : CROWD) m.discard();
            endAll(level);
            finished = true;
        }
    }

    private static void resetCost() {
        costTicks = GasServer.ticks;
        costNanos = costMax = costN = 0L;
    }
}
