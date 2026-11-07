package acup.gunfeel.ai;

import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The one goal an AI mob keeps besides swimming: through it the mob AI moves the mob, turns its head and works its
 * gun, every tick the mob's own AI runs (a mob in a chunk that does not tick thinks nothing). Far from every player
 * (aiRadius) the mob stands still and thinks nothing.
 */
final class AiGoal extends Goal {
    final AiBot bot;

    AiGoal(AiBot bot) {
        this.bot = bot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return bot.mob.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return bot.mob.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        AiBots.tickBot(bot);
    }

    @Override
    public void stop() {
        bot.gunner.release();
        bot.mover.stop();
    }
}
