package acup.gunfeel.ai;

import acup.gunfeel.mixin.mc.MobGoalsAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

/**
 * Puts a mob under the mob AI: every mob tagged {@value #TAG} (the /armsai spawn command tags its mobs; /tag works on
 * any mob with goals), each time it comes into a level (its goals are made again when its chunk loads). Its own
 * goals but swimming go (attacks, wandering, looking about, NTM's gun and digging goals), and so do the goals that
 * pick its target; one {@link AiGoal} drives it, and its legs walk as a player's ({@link AiMoveControl}). Doing it
 * twice changes nothing.
 */
public final class AiAdapter {
    public static final String TAG = "armsai";
    /** the rounds a mob carries besides its gun's, in magazines */
    static final int RESERVE_MAGS = 4;
    /** where the reserve is kept on the mob, so it lasts over a reload of the chunk */
    static final String AMMO_KEY = "armsaiAmmo";

    private AiAdapter() {
    }

    public static void joined(Entity e) {
        if (!acup.gunfeel.compat.Feature.MOB_AI.on()) return;
        if (e instanceof Mob mob && e.level() instanceof ServerLevel && e.entityTags().contains(TAG)) adapt(mob);
    }

    public static AiBot adapt(Mob mob) {
        AiBot bot = AiBots.attach(mob, AiProfile.SOLDIER);
        strip(bot);
        var goals = ((MobGoalsAccess) mob).gunfeel$goals();
        boolean has = false;
        for (WrappedGoal w : goals.getAvailableGoals()) has |= w.getGoal() instanceof AiGoal a && a.bot == bot;
        if (!has) goals.addGoal(1, new AiGoal(bot));
        ((AiNavigation) mob.getNavigation()).gunfeel$setAiOwned(true);
        if (!(mob.getMoveControl() instanceof AiMoveControl)) ((MobGoalsAccess) mob).gunfeel$setMoveControl(new AiMoveControl(mob));
        mob.setTarget(null);
        if (bot.brain == null) bot.brain = new AiSoldier();
        int kept = mob.getPersistentData().getIntOr(AMMO_KEY, Integer.MIN_VALUE);
        if (kept != Integer.MIN_VALUE) {
            if (kept == AiAmmo.INFINITE) bot.gunner.ammo.setInfinite();
            else bot.gunner.ammo.set(kept);
        } else if (bot.gunner.ammo.reserve() == 0) {
            bot.gunner.ammo.give(bot.gunner.capacity() * RESERVE_MAGS);
        }
        AiRole.restore(bot);
        int nades = mob.getPersistentData().getIntOr(AiGrenades.STOCK_KEY, Integer.MIN_VALUE);
        bot.grenades = nades != Integer.MIN_VALUE ? nades : AiGrenades.stock();
        if (nades == Integer.MIN_VALUE) keepGrenades(bot);
        int smokes = mob.getPersistentData().getIntOr(AiSmoke.STOCK_KEY, Integer.MIN_VALUE);
        bot.smokes = smokes != Integer.MIN_VALUE ? smokes : AiSmoke.stock();
        if (smokes == Integer.MIN_VALUE) keepSmokes(bot);
        return bot;
    }

    /** the smoke grenades it has left, on the mob */
    static void keepSmokes(AiBot bot) {
        bot.mob.getPersistentData().putInt(AiSmoke.STOCK_KEY, bot.smokes);
    }

    /** the grenades it has left, on the mob */
    static void keepGrenades(AiBot bot) {
        bot.mob.getPersistentData().putInt(AiGrenades.STOCK_KEY, bot.grenades);
    }

    /**
     * takes away every goal but swimming and the AI's own, and every goal that picks a target. NTM gives a mob that
     * holds one of its guns a goal to shoot it when the mob loads, after this mod has taken the mob over: the level's
     * tick strips its AI mobs again once a second (never from a goal: a goal set must not change while it ticks).
     */
    static void strip(AiBot bot) {
        var mob = (MobGoalsAccess) bot.mob;
        var goals = mob.gunfeel$goals();
        if (goals.getAvailableGoals().size() > 2 || !mob.gunfeel$targets().getAvailableGoals().isEmpty()) {
            goals.removeAllGoals(g -> !(g instanceof FloatGoal) && !(g instanceof AiGoal a && a.bot == bot));
            mob.gunfeel$targets().removeAllGoals(g -> true);
        }
    }

    /** the reserve as it is now, on the mob */
    static void keep(AiBot bot) {
        bot.mob.getPersistentData().putInt(AMMO_KEY, bot.gunner.ammo.reserve());
    }
}
