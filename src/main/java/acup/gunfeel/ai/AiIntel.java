package acup.gunfeel.ai;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.LevelEntities;
import net.minecraft.server.level.ServerLevel;

/** 26.2's side reports through core's AiIntelBase, with the game's entities and waking rules. */
public final class AiIntel extends AiIntelBase {
    private static final Scout SCOUT = new Scout() {
        @Override public double radius() { return AiBots.radius(); }
        @Override public boolean allAwake() { return AiBots.awake; }
    };

    /** the stands' comparison with the former code, null but on a stand */
    static @Nullable AiIntelAlike alike;

    private AiIntel() {
    }

    /** each level's entities as core reads them, made once (let go with the level, forget) */
    private static final acup.gunfeel.LevelMap<LevelEntities> VIEWS = new acup.gunfeel.LevelMap<>();

    public static void forget(ServerLevel level) {
        VIEWS.remove(level);
        forgetLevel(level);
        if (alike != null) AiIntelOld.forget(level);
    }

    static List<Group> groups(ServerLevel level, String team) { return groupsOf(level, team); }

    static long reports(ServerLevel level, String team) { return reportsOf(level, team); }

    static void lost(ServerLevel level, String team, int id, long tick) {
        lostTo(level, team, id, tick);
        if (alike != null) AiIntelOld.lost(level, team, id, tick);
    }

    static long tick(ServerLevel level, Map<String, List<AiBot>> sides, long now) {
        long soonest = tickSides(level, VIEWS.computeIfAbsent(level, l -> new LevelEntities(level)), SCOUT, sides, now);
        if (alike != null) alike.tick(level, sides, now, soonest);
        return soonest;
    }

    /** /armsai info */
    public static String info() {
        return String.format(java.util.Locale.ROOT, "staff's picture: %d reports, %.1f us each", reports, reports == 0 ? 0 : nanos / 1000.0 / reports);
    }
}
