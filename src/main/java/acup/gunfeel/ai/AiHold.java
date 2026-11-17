package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * One holding its place in its squad's bounds (C3) is not shoved off it by its own side: the half going runs along the
 * same way and pushed through it. Of two of our soldiers of one side pushing each other, one holding and the other
 * not, only the other is pushed (and goes round); the player, others' mobs and two both holding or both going push as
 * ever. Server thread only (MixinAiHoldPush).
 */
public final class AiHold {
    /** a hold seen this many ticks ago at most still holds (a soldier steps every tick) */
    static final int FRESH = 1;
    /** the first ticks of a hold: still slowing from its run, not counted as moving (the stands) */
    static final int SETTLE = 5;

    /** the one not pushed in the push under way (Entity.push(Entity)); null: none */
    private static @Nullable Entity shielded;

    private AiHold() {
    }

    /** it holds its place this tick (AiCommander's leg) */
    static void held(AiBot bot, long tick) {
        if (bot.holdAt < tick - FRESH) bot.holdSince = tick;
        bot.holdAt = tick;
    }

    static boolean holds(AiBot bot, long tick) {
        return bot.holdAt >= tick - FRESH;
    }

    /** moving (its speed), not counting a hold's first SETTLE ticks: still slowing from its run */
    static boolean moving(AiBot bot) {
        long tick = bot.mob.level().getGameTime();
        if (holds(bot, tick) && tick - bot.holdSince < SETTLE) return false;
        return bot.mob.getDeltaMovement().horizontalDistance() >= 0.05;
    }

    /** a push between a and b begins: the one of them that holds against one of its side, if any */
    public static void begin(Entity a, Entity b) {
        // the server's alone: in single player the client's thread pushes its own copies at the same time
        if (a.level().isClientSide()) return;
        shielded = null;
        // cheap first: only two mobs, then the bots' map
        if (!(a instanceof Mob) || !(b instanceof Mob)) return;
        AiBot x = AiBots.get(a);
        if (x == null) return;
        AiBot y = AiBots.get(b);
        if (y == null) return;
        long tick = a.level().getGameTime();
        boolean hx = holds(x, tick), hy = holds(y, tick);
        if (hx == hy || !AiSquads.team(x).equals(AiSquads.team(y))) return;
        shielded = hx ? a : b;
    }

    public static void end(Entity a) {
        if (shielded != null && !a.level().isClientSide()) shielded = null;
    }

    /** this one is not pushed now */
    public static boolean shielded(Entity e) {
        return shielded == e;
    }
}
