package acup.gunfeel.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;

/**
 * AiTeams.enemy and own as they were before core's AiTeamsBase, for the stands to compare with it (AiTeams.alike).
 */
final class AiTeamsOld {
    private AiTeamsOld() {
    }

    /** whether the AI mob fights t */
    static boolean enemy(LivingEntity mob, LivingEntity t) {
        if (t == mob) return false;
        Team mine = mob.getTeam();
        if (t instanceof Player) return mine == null || t.getTeam() != mine;
        if (t.entityTags().contains(AiBots.TARGET_TAG)) return true;
        if (AiBots.get(t) == null) return false;
        Team theirs = t.getTeam();
        return mine != null && theirs != null && theirs != mine;
    }

    /** whether a shot of the AI mob's must not go through o: an AI mob it does not fight, or a player of its team */
    static boolean own(LivingEntity mob, LivingEntity o) {
        if (o == mob) return false;
        if (o instanceof Player) return mob.getTeam() != null && o.getTeam() == mob.getTeam();
        return AiBots.get(o) != null && !enemy(mob, o);
    }
}
