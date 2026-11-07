package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * An AI mob's shots, whatever its gun's magazine (a belt's rounds are not counted in the gun) and whoever ticks the gun
 * (the game ticks a mob's held item before its goals): NTM works a shot's spread out once a projectile, and
 * MixinLegoSpread tells us. Server thread.
 */
public final class AiShots {
    private AiShots() {
    }

    /** NTM works out a shot's spread for this shooter */
    public static void spread(@Nullable LivingEntity e) {
        if (e == null || e instanceof Player || e.level().isClientSide() || !acup.gunfeel.compat.Feature.MOB_AI.on()) return;
        AiBot bot = AiBots.get(e);
        if (bot != null) bot.gunner.shot(e.level().getGameTime());
    }
}
