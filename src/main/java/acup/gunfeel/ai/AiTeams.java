package acup.gunfeel.ai;

import acup.gunfeel.EntityBody;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Who an AI mob fights, by the game's own teams (/team): one in a team fights the players and the AI mobs of the
 * other teams, and players with no team; one with no team fights every player, as before teams. AI mobs with no team
 * are nobody's enemy and fight no AI mob. Neither fights its own: they are kept out of its line of fire, and a hit from
 * one is no threat. The stands' tagged targets are everyone's enemy.
 */
public final class AiTeams {
    private AiTeams() {
    }

    /** whether the AI mob fights t (AiTeamsBase.enemy) */
    public static boolean enemy(LivingEntity mob, LivingEntity t) {
        return AiTeamsBase.enemy(EntityBody.of(mob), EntityBody.of(t));
    }

    /** whether a shot of the AI mob's must not go through o (AiTeamsBase.own) */
    static boolean own(LivingEntity mob, LivingEntity o) {
        return AiTeamsBase.own(EntityBody.of(mob), EntityBody.of(o));
    }

    /** puts the mob in the level's team of that name, made if there is none */
    static void join(ServerLevel level, Mob mob, String name) {
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.getPlayerTeam(name);
        if (team == null) team = board.addPlayerTeam(name);
        board.addPlayerToTeam(mob.getScoreboardName(), team);
    }
}
