package acup.gunfeel;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import acup.gunfeel.ai.AiSpawn;

/**
 * runCheckClient -PlevelLeakStand: does a level the player leaves go from memory with the AI's state on it? Two teams
 * of AI mobs fight on a stone floor for {@link #FIGHT_TICKS} (their squads' operations, the staff's intelligence on the
 * foes, their voices: the AI's per-level state, some of it holding the mobs); then the player leaves to the title
 * screen as the pause menu's button does, and every ServerLevel of that world, held only weakly here, must be
 * collected within {@link #GC_MILLIS} of garbage collections. A level still alive is reported with the AI's static
 * maps that still hold it as a key (the ones that would keep it). Lines "[levelleak] ..." in the log, PASS/FAIL in the
 * report.
 */
final class LevelLeakStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.levelLeakStand");
    /** the fight, server ticks */
    static final int FIGHT_TICKS = 600;
    /** how long the levels may take to go after the server stopped, ms */
    static final long GC_MILLIS = 20_000;
    private static final BlockPos O = new BlockPos(8, 100, 8);
    /** the AI's classes with state per level in static maps (charlie's inventory 2026-11-20) */
    private static final List<String> REGISTRIES = List.of("TacticalMap", "AiTerrain", "AiBots", "AiBotsBase", "AiBunker", "AiClearing",
            "AiCommander", "AiCrossing", "AiEntry", "AiGrenades", "AiHatch", "AiHqMap", "AiHqMapBase", "AiIntel", "AiReports", "AiReportsBase",
            "AiSquads", "AiSquadsBase", "AiSupply", "AiVoice", "acup.gunfeel.host.Worlds");

    private static int step;
    private static long fightUntil, leftAt;
    private static final List<WeakReference<ServerLevel>> levels = new ArrayList<>();
    private static final List<String> names = new ArrayList<>();
    private static WeakReference<Object> server = new WeakReference<>(null);

    private LevelLeakStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -20; x <= 20; x++) for (int z = -8; z <= 8; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 5; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            // a few blocks of cover between them
            for (int z = -6; z <= 6; z += 3) level.setBlock(O.offset(0, 0, z), Blocks.STONE.defaultBlockState(), 3);
            // the player watches from above, out of the fight
            server.getPlayerList().getPlayers().forEach(p -> p.setGameMode(GameType.SPECTATOR));
            for (Mob m : AiSpawn.spawn(level, Vec3.atBottomCenterOf(O.offset(-14, 0, 0)), AiSpawn.Kind.DUMMY, 4, null)) join(level, m, "leak_a");
            for (Mob m : AiSpawn.spawn(level, Vec3.atBottomCenterOf(O.offset(14, 0, 0)), AiSpawn.Kind.DUMMY, 4, null)) join(level, m, "leak_b");
            fightUntil = level.getGameTime() + FIGHT_TICKS;
        }).join();
        step = 0;
        GunFeel.LOGGER.info("[levelleak] the fight begins");
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        switch (step) {
            case 0 -> {
                var server = mc.getSingleplayerServer();
                if (server == null || server.overworld().getGameTime() < fightUntil) return;
                server.submit(() -> {
                    ServerLevel level = server.overworld();
                    List<String> held = holding(level);
                    GunFeel.LOGGER.info("[levelleak] after the fight the AI keeps the overworld in: {}", held);
                    ClientSmoke.check(!held.isEmpty(), "уровень: после боя у ИИ есть состояние уровня (" + held + ")");
                    LevelLeakStand.server = new WeakReference<>(server);
                    for (ServerLevel l : server.getAllLevels()) {
                        levels.add(new WeakReference<>(l));
                        names.add(l.dimension().identifier().toString());
                    }
                }).join();
                // the pause menu's "Save and Quit to Title"
                mc.disconnectFromWorld(Component.literal("level leak stand"));
                step = 1;
            }
            case 1 -> {
                if (mc.getSingleplayerServer() != null || mc.level != null || !(mc.gui.screen() instanceof TitleScreen)) return;
                leftAt = System.currentTimeMillis();
                step = 2;
            }
            case 2 -> {
                System.gc();
                List<String> alive = new ArrayList<>();
                for (int i = 0; i < levels.size(); i++) if (levels.get(i).get() != null) alive.add(names.get(i));
                long waited = System.currentTimeMillis() - leftAt;
                if (alive.isEmpty()) {
                    ClientSmoke.check(true, "уровень: после выхода в меню все " + levels.size() + " уровня мира собраны (" + waited + " мс)");
                    ClientSmoke.finish();
                    step = 3;
                } else if (waited > GC_MILLIS) {
                    Object srv = server.get();
                    ClientSmoke.check(srv == null, "уровень: сервер мира " + (srv == null ? "собран" : "жив (" + srv.getClass().getSimpleName() + ")"));
                    Map<String, Object> roots = Map.of("Minecraft.instance", mc, "NeoForge.EVENT_BUS", net.neoforged.neoforge.common.NeoForge.EVENT_BUS);
                    Object what = srv != null ? srv : levels.get(0).get();
                    if (what != null) {
                        List<String> paths = LevelLeakSearch.paths(what, List.of("ntmgunfeel", "hbm"), roots, 16);
                        for (String path : paths) GunFeel.LOGGER.info("[levelleak] {} kept by {}", what.getClass().getSimpleName(), path);
                        for (String path : paths) ClientSmoke.check(false, "уровень: " + what.getClass().getSimpleName() + " держит " + path);
                    }
                    for (int i = 0; i < levels.size(); i++) {
                        ServerLevel l = levels.get(i).get();
                        if (l != null) {
                            List<String> held = holding(l);
                            GunFeel.LOGGER.info("[levelleak] {} still alive; the AI's maps holding it: {}", names.get(i), held);
                            ClientSmoke.check(false, "уровень: " + names.get(i) + " жив через " + waited + " мс после выхода; держат карты ИИ: " + held);
                        }
                    }
                    ClientSmoke.finish();
                    step = 3;
                }
            }
            default -> {
            }
        }
    }

    /** the mob in the level's team of that name (AiTeams.join) */
    private static void join(ServerLevel level, Mob mob, String name) {
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.getPlayerTeam(name);
        if (team == null) team = board.addPlayerTeam(name);
        board.addPlayerToTeam(mob.getScoreboardName(), team);
    }

    /** the AI's static maps (Map or LevelMap fields) with this level as a key, "Class.FIELD" */
    private static List<String> holding(ServerLevel level) {
        List<String> out = new ArrayList<>();
        for (String name : REGISTRIES) {
            Class<?> c;
            try {
                c = Class.forName(name.contains(".") ? name : "acup.gunfeel.ai." + name);
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !(Map.class.isAssignableFrom(f.getType()) || f.getType() == LevelMap.class)) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    boolean has = v instanceof Map<?, ?> m ? m.containsKey(level) : v instanceof LevelMap<?> lm && lm.get(level) != null;
                    if (has) out.add(name + "." + f.getName());
                } catch (ReflectiveOperationException | RuntimeException e) {
                    out.add(name + "." + f.getName() + "?" + e.getClass().getSimpleName());
                }
            }
        }
        return out;
    }
}
