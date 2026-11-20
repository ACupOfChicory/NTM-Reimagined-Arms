package acup.gunfeel.ai;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.Vec3d;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * AI mobs of one side near one another fight as a squad (core's {@link AiSquadsBase}): its squads, the constants and
 * the stands' switches are core's; here the squad of 26.2's own (its AiBots) and what core asks of the staff not yet in
 * core (AiCommander's leash and press, AiSupply). Server thread.
 */
public final class AiSquads extends AiSquadsBase {
    /** a squad of 26.2's AI mobs */
    static final class Squad extends AiSquadsBase.Squad<AiBot> {
    }

    private static final Staff<AiBot> STAFF = new Staff<>() {
        @Override
        public Squad squad() {
            return new Squad();
        }

        @Override
        public void supply(List<AiBot> bots, long now) {
            AiSupply.squad(bots, now);
        }

        @Override
        public boolean leashed(AiBot bot, int target, long tick) {
            return AiCommander.leashed(bot, target, tick);
        }

        @Override
        public boolean presses(AiBot bot, long tick) {
            return AiCommander.presses(bot, tick);
        }
    };

    private AiSquads() {
    }

    /** squads made again on the next tick (the stands); a level unloaded, or with no AI mob left, is let go */
    public static void forget(ServerLevel level) {
        forgetLevel(level);
    }

    /** every server tick, from AiBots.tick: squads made again every EVERY ticks */
    static void tick(ServerLevel level, java.util.Collection<AiBot> all) {
        make(STAFF, level, acup.gunfeel.Wiring.world(level), all);
    }

    /** how far apart two are for the squads, squared: as they stand, or BASE_REACH times nearer with a base of fire */
    static double apart(double distSqr, boolean base) {
        return apartBy(distSqr, base);
    }

    /** the level's squads as last made (AiCommander) */
    @SuppressWarnings("unchecked")
    static List<Squad> squads(ServerLevel level) {
        return (List<Squad>) (List<?>) squadsOf(level);
    }

    static String team(AiBot b) {
        return teamOf(b);
    }

    /** the one of the bot's squad that holds this role's slot and is alive (itself, alone or with squads off, if that is its role), or null */
    static @Nullable AiBot holder(AiBot bot, AiRole role) {
        return holderOf(bot, role);
    }

    /** the level's AI mobs counted by role, as "rifle 5, base 1" */
    static String roles(java.util.Collection<AiBot> all) {
        return rolesOf(all);
    }

    static double cos(double ax, double az, double bx, double bz) {
        return cosOf(ax, az, bx, bz);
    }

    static @Nullable Squad of(AiBot bot) {
        return (Squad) squadOf(bot);
    }

    /** its squad's base of fire (AiSquadsBase.baseOfFire) */
    static boolean base(AiBot bot) {
        return baseOfFire(bot);
    }

    /** whether it is the one of its squad that goes round the side of this target */
    static boolean flanks(AiBot bot, int target) {
        return flanksTarget(STAFF, bot, target);
    }

    /** flanking cover against this target: the squad's middle (AiSquadsBase.middleOf); null with no squad */
    static @Nullable Vec3 middle(AiBot bot) {
        Vec3d m = middleOf(bot);
        return m == null ? null : new Vec3(m.x(), m.y(), m.z());
    }

    /** alive, in the level and thinking: one dead or asleep since the squad was made counts for nothing */
    static boolean live(AiBot b) {
        return alive(b);
    }

    /** on its way: to cover, or after a target */
    static boolean moving(AiBot b) {
        return onItsWay(b);
    }

    /** in its cover (or out at its peek), gun loaded: ready to fire for those on the move */
    static boolean covering(AiBot b) {
        return coveringOthers(b);
    }

    /** whether it may leave its cover now (AiSquadsBase.mayMoveNow) */
    static boolean mayMove(AiBot bot) {
        return mayMoveNow(bot);
    }

    /** whether it may go after this lost target (AiSquadsBase.mayHuntNow) */
    static boolean mayHunt(AiBot bot, int target) {
        return mayHuntNow(STAFF, bot, target);
    }

    /** whether HUNTERS of its side within HUNT_NEAR go after this lost target already (AiSquadsBase.othersHuntIt) */
    static boolean othersHunt(AiBot bot, int target) {
        return othersHuntIt(bot, target);
    }

    /** one of the rest of its squad falls back now (AiSoldier.fallBack): it looks out and fires to cover it */
    static boolean coversFallBack(AiBot bot) {
        return coversFallingBack(bot);
    }

    /** whether it keeps the place where it lost this target under fire now (AiSquadsBase.suppressesNow) */
    static boolean suppresses(AiBot bot, TargetMemory.Contact c, long tick) {
        return suppressesNow(STAFF, bot, c, tick);
    }
}
