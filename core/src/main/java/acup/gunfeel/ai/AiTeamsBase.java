package acup.gunfeel.ai;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;

/**
 * Who an AI mob fights, by the game's own teams (/team): one in a team fights the players and the AI mobs of the
 * other teams, and players with no team; one with no team fights every player, as before teams. AI mobs with no team
 * are nobody's enemy and fight no AI mob. Neither fights its own: they are kept out of its line of fire, and a hit from
 * one is no threat. The stands' tagged targets are everyone's enemy.
 *
 * <p>Over Bodies (26.2's AiTeams hands its entities' in): teams by name, which is the team itself (a name is one team
 * of the scoreboard, and an entity's team is the scoreboard's own of that name), so compared by equals. A body is one
 * of the AI mobs where the level's registry (AiBotsBase) has a bot by its id with this very Body: the version keeps one
 * Body on each entity (26.2: EntityBody.of, a field of the entity's mixin), so == on Bodies is == on entities.
 */
public final class AiTeamsBase {
    /** the stands' targets: everyone's enemy */
    public static final String TARGET_TAG = "armsai_target";

    private AiTeamsBase() {
    }

    /** whether the AI mob fights t */
    public static boolean enemy(Body mob, Body t) {
        if (t == mob) return false;
        @Nullable String mine = mob.team();
        if (t.player()) return mine == null || !mine.equals(t.team());
        if (t.tagged(TARGET_TAG)) return true;
        if (AiBotsBase.of(t) == null) return false;
        @Nullable String theirs = t.team();
        return mine != null && theirs != null && !theirs.equals(mine);
    }

    /** whether a shot of the AI mob's must not go through o: an AI mob it does not fight, or a player of its team */
    public static boolean own(Body mob, Body o) {
        if (o == mob) return false;
        if (o.player()) return mob.team() != null && Objects.equals(o.team(), mob.team());
        return AiBotsBase.of(o) != null && !enemy(mob, o);
    }
}
