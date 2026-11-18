package acup.gunfeel.heat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** NTM's smoke after a shot (Lego.handleStandardSmoke, client) hangs on longer from a hot barrel: from SMOKE_FROM up to EXTRA ms more */
public final class OverheatSmoke {
    private OverheatSmoke() {
    }

    /** at the top heat, the smoke this much longer than NTM's (M2 2 s, AM-180 3 s), ms */
    static final int EXTRA = 8000;

    public static int extraMillis(ItemStack stack, LivingEntity entity) {
        if (entity == null || Overheat.Gun.of(stack) == null) return 0;
        float h = Overheat.heat(stack, entity.level().getGameTime());
        return Math.round(EXTRA * Overheat.ramp(h, Overheat.SMOKE_FROM, 1F));
    }
}
