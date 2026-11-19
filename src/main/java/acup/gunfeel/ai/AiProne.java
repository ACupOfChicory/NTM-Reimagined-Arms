package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.EntityBody;
import acup.gunfeel.Wiring;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Mob;

/**
 * 26.2's lying AI mob (AiProneBase in core): its size, and the mob, its level and the shooters passed as core's Body
 * and WorldView, so AiBot, AiBots, AiSoldier and AiUnderFire call it as before.
 */
public final class AiProne extends AiProneBase {
    /** a crawling player's size (Player's crawling pose) */
    public static final EntityDimensions SIZE = EntityDimensions.scalable(Stance.PRONE_WIDTH, Stance.PRONE_HEIGHT).withEyeHeight(Stance.PRONE_EYE);

    AiProne(Mob mob) {
        super(EntityBody.of(mob), acup.gunfeel.Prone::enabled);
    }

    /** AiProneBase.tick for this mob in its level */
    void tick(ServerLevel level, AiMover mover, long tick, boolean stooped, AiDash dash) {
        tick(Wiring.world(level), mover, tick, stooped, dash);
    }

    /** on the terms a player lies down on: the server's prone key asked first, as ever */
    static boolean may(Mob mob) {
        return may(EntityBody.of(mob), acup.gunfeel.Prone.enabled());
    }

    /** AiProneBase.pass, its shooter as its Body */
    void pass(@Nullable Entity owner, double d, double speed, boolean scaleOn, long tick) {
        pass(owner == null ? null : EntityBody.of(owner), d, speed, scaleOn, tick);
    }

    /** AiProneBase.watchShots: who fired the last new near miss, or null */
    @Nullable Entity watchShots(ServerLevel level, long tick) {
        return EntityBody.entity(watchShots(tick));
    }
}
