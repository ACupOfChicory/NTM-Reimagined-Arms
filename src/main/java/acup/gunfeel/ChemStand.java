package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.entity.ModEntities;
import com.hbm.entity.mob.EntityCreeperPhosgene;
import com.hbm.entity.projectile.EntityChemical;

import acup.gunfeel.gas.GasServer;
import acup.gunfeel.gas.GasState;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeNet;
import acup.gunfeel.smoke.client.SmokeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PchemStand=<dir>: NTM's phosgene creeper and chemical thrower in the game, in numbers, the same on a
// tree with and without the mod's clouds of them (a before and an after). Scenarios, one after another on a flat field:
// the creeper going off by a zombie; a chlorine stream and a hot steam stream, 10 s each, from an armour stand at a
// zombie where the stream ends; four streams at once (chlorine, phosgene, mustard, hot steam). Every scenario is 20 s:
// at 5, 9, 13 and 17 s a row of the clouds, NTM's mists, the blobs in the air, the server's ms a tick, the smoke's and
// the gases' own time, the packets a second to the client and the zombie's HP and doses (<dir>/chem.tsv); the frame
// times from 3 to 13 s (<dir>/frames.tsv). The camera looks down the range at high smoke quality.
final class ChemStand {
    static final String DIR = System.getProperty("ntmgunfeel.chemStand", "");
    static final boolean ENABLED = !DIR.isBlank();
    private static final int Y = 100;
    /** Ticks a stream fires: a blob a tick, as NTM's chemical thrower. */
    private static final int STREAM_TICKS = 200;
    private static final double[] SAMPLES = {5, 9, 13, 17};
    private static final double LENGTH = 20;

    private record Scenario(String name, boolean creeper, List<String> fluids, double camX, double camZ) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("creeper", true, List.of(), 0.5, 8.5),
            new Scenario("chlorine", false, List.of("chlorine"), 0.5, 12.5),
            new Scenario("hotsteam", false, List.of("hotsteam"), 0.5, 12.5),
            new Scenario("four", false, List.of("chlorine", "phosgene", "mustardgas", "hotsteam"), 0.5, 8.5));

    private static int scenario, phase, sample;
    private static long startedAt, last;
    private static final List<Long> TIMES = new ArrayList<>();
    private static final List<String> ROWS = new ArrayList<>(), FRAMES = new ArrayList<>();
    private static String savedQuality;
    // the server's: the throwers and their fluids firing, the ticks fired, the zombies
    private static final List<LivingEntity> THROWERS = new ArrayList<>();
    private static final List<Fluid> FLUIDS = new ArrayList<>();
    private static final List<Mob> ZOMBIES = new ArrayList<>();
    private static volatile int fired;

    private ChemStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        savedQuality = Config.smokeQuality;
        Config.smokeQuality = "high";
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            var source = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, "time set noon");
            server.getCommands().performPrefixedCommand(source, "weather clear");
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            for (int x = -40; x <= 40; x++) for (int z = -10; z <= 56; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIME).defaultBlockState(), 2);
                for (int y = Y; y <= Y + 16; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR);
        }).join();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level == level.getServer().overworld()) fire(level);
        });
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "химия: папка " + DIR + ": " + e);
        }
        ROWS.add("scenario\tt_s\tclouds\tmists\tblobs\tmspt\tsmoke\tgas_us\tpackets_s\tzombie_hp\tdoses");
        FRAMES.add("scenario\tframe_ms\tp95_ms\tfps\tclouds_drawn");
        scenario = 0;
        phase = 0;
        last = System.nanoTime();
    }

    // the server's tick: each thrower fires a blob of its fluid
    private static void fire(ServerLevel level) {
        if (THROWERS.isEmpty() || fired >= STREAM_TICKS) return;
        for (int i = 0; i < THROWERS.size(); i++) {
            EntityChemical chem = new EntityChemical(ModEntities.CHEMICAL.get(), level, THROWERS.get(i), 0, 0, 0);
            chem.setFluid(FLUIDS.get(i));
            level.addFreshEntity(chem);
        }
        fired++;
    }

    private static void setUp(Scenario s) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            if (s.creeper) {
                EntityCreeperPhosgene creeper = ModEntities.CREEPER_PHOSGENE.get().create(level, EntitySpawnReason.COMMAND);
                creeper.setNoAi(true);
                creeper.setPos(0.5, Y, 22.5);
                level.addFreshEntity(creeper);
                creeper.ignite();
                ZOMBIES.add(zombie(level, 3.5, 23.5));
            }
            int n = s.fluids.size();
            for (int i = 0; i < n; i++) {
                double x = n == 1 ? 0.5 : -11.5 + 8 * i;
                var stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                stand.snapTo(x, Y, 0.5, 0F, 0F);
                stand.setYHeadRot(0F);
                level.addFreshEntity(stand);
                THROWERS.add(stand);
                FLUIDS.add(BuiltInRegistries.FLUID.getValue(Identifier.fromNamespaceAndPath("hbm", s.fluids.get(i))));
                ZOMBIES.add(zombie(level, x + 2, 28.5));
            }
            fired = 0;
        }).join();
        startedAt = System.nanoTime();
    }

    private static Mob zombie(ServerLevel level, double x, double z) {
        Mob zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.setPos(x, Y, z);
        level.addFreshEntity(zombie);
        return zombie;
    }

    private static void tearDown() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            THROWERS.forEach(Entity::discard);
            ZOMBIES.forEach(Entity::discard);
            THROWERS.clear();
            FLUIDS.clear();
            ZOMBIES.clear();
            for (Entity e : level.getEntities(ModEntities.MIST.get(), e -> true)) e.discard();
            for (Entity e : level.getEntities(ModEntities.CHEMICAL.get(), e -> true)) e.discard();
            SmokeClouds all = SmokeClouds.get(level);
            if (all != null) for (var c : all.clouds()) {
                all.remove(c.id);
                SmokeNet.ended(level, c);
            }
        }).join();
    }

    private static void place(Scenario s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.setPos(s.camX, Y + 3.5 - mc.player.getEyeHeight(), s.camZ - 10);
        mc.player.setYRot(0F);
        mc.player.setXRot(8F);
        mc.player.yRotO = 0F;
        mc.player.xRotO = 8F;
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    static void frame() {
        long now = System.nanoTime();
        long dt = now - last;
        last = now;
        if (scenario >= SCENARIOS.size()) return;
        Scenario s = SCENARIOS.get(scenario);
        place(s);
        double t = (now - startedAt) / 1e9;
        switch (phase) {
            case 0 -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                server.submit(() -> {
                    var sp = server.getPlayerList().getPlayers().getFirst();
                    sp.teleportTo(server.overworld(), s.camX, Y + 3.5 - sp.getEyeHeight(), s.camZ - 10, Set.of(), 0F, 8F, false);
                }).join();
                // the last scenario's clouds gone from the client before the next begins
                SmokeClouds known = SmokeClient.current();
                if (known != null && !known.clouds().isEmpty()) return;
                setUp(s);
                TIMES.clear();
                sample = 0;
                phase = 1;
            }
            case 1 -> {
                if (t >= 3 && t < 13) TIMES.add(dt);
                if (t >= 13 && !TIMES.isEmpty()) frames(s);
                if (sample < SAMPLES.length && t >= SAMPLES[sample]) {
                    sample(s, SAMPLES[sample]);
                    sample++;
                }
                if (t >= LENGTH) {
                    tearDown();
                    scenario++;
                    phase = 0;
                    if (scenario >= SCENARIOS.size()) done();
                }
            }
            default -> {
            }
        }
    }

    private static void frames(Scenario s) {
        List<Long> t = new ArrayList<>(TIMES);
        TIMES.clear();
        t.sort(Long::compare);
        double avg = t.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p95 = t.get(Math.min(t.size() - 1, (int) (t.size() * 0.95))) / 1e6;
        SmokeClouds known = SmokeClient.current();
        String r = String.format(Locale.ROOT, "%s\t%.3f\t%.3f\t%.0f\t%d", s.name, avg, p95, avg > 0 ? 1000 / avg : 0,
                known == null ? 0 : known.clouds().size());
        FRAMES.add(r);
        GunFeel.LOGGER.info("[chemstand] {}", r);
    }

    private static void sample(Scenario s, double at) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        String[] row = new String[1];
        float packets = mc.getConnection() == null ? 0F : mc.getConnection().getConnection().getAverageReceivedPackets() * 20F;
        server.submit(() -> {
            ServerLevel level = server.overworld();
            SmokeClouds all = SmokeClouds.get(level);
            int clouds = all == null ? 0 : all.clouds().size();
            int mists = level.getEntities(ModEntities.MIST.get(), e -> true).size();
            int blobs = level.getEntities(ModEntities.CHEMICAL.get(), e -> true).size();
            StringBuilder hp = new StringBuilder(), doses = new StringBuilder();
            for (Mob z : ZOMBIES) {
                if (hp.length() > 0) {
                    hp.append(' ');
                    doses.append(' ');
                }
                hp.append(z.isAlive() ? String.format(Locale.ROOT, "%.1f", z.getHealth()) : "dead");
                GasState g = z.getExistingDataOrNull(acup.gunfeel.gas.GasAttach.STATE.get());
                doses.append(g == null ? "-" : String.format(Locale.ROOT, "%.0f/%.0f/%.0f", g.dose[GasState.CHLORINE], g.dose[GasState.PHOSGENE], g.dose[GasState.MUSTARD]));
            }
            String cost = SmokeClouds.serverCost();
            int cut = cost.indexOf(" ms");
            row[0] = String.format(Locale.ROOT, "%s\t%.0f\t%d\t%d\t%d\t%.3f\t%s\t%.1f\t%.0f\t%s\t%s", s.name, at, clouds, mists, blobs,
                    server.getAverageTickTimeNanos() / 1e6, cut > 0 ? cost.substring(0, cut) : "-", GasServer.lastNanos / 1e3, packets, hp, doses);
        }).join();
        ROWS.add(row[0]);
        GunFeel.LOGGER.info("[chemstand] {}", row[0]);
    }

    private static void done() {
        Config.smokeQuality = savedQuality;
        ROWS.add("# checks: matched " + SmokeClient.matched + ", apart " + SmokeClient.apart + ", grids " + SmokeClient.grids);
        try {
            Files.write(Path.of(DIR, "chem.tsv"), ROWS);
            Files.write(Path.of(DIR, "frames.tsv"), FRAMES);
        } catch (Exception e) {
            ClientSmoke.check(false, "химия: запись " + e);
        }
        ClientSmoke.check(SmokeClient.apart == 0, "химия: хеши клиента разошлись с сервером " + SmokeClient.apart + " раз");
        ClientSmoke.finish();
    }
}
