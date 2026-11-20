package acup.gunfeel;

import acup.gunfeel.host.Body;

/**
 * 26.2's NtmGuns as Wiring gives it, no call of NTM's of its own: ApiProbe counts a call of NTM's in the system of the
 * class it stands in, so each read is in its system's class, the AI's in AiGunner.Guns (MOB_AI), the ammo menu's in
 * AmmoLock.Reads (AMMO_CHOICE), the HUD's in Hud.Reads (HUD)
 */
final class NtmGunsPort extends acup.gunfeel.ai.AiGunner.Guns {
    @Override
    public int menuRounds(Body holder, int hand, int[] out) {
        return AmmoLock.Reads.menuRounds(holder, hand, out);
    }

    @Override
    public int reserve(Body holder, int round) {
        return AmmoLock.Reads.reserve(holder, round);
    }

    @Override
    public int loadedRound(Body holder, int hand) {
        return AmmoLock.Reads.loadedRound(holder, hand);
    }

    @Override
    public boolean choiceStats(Body holder, int hand, float[] out) {
        return AmmoLock.Reads.choiceStats(holder, hand, out);
    }

    @Override
    public void roundStats(int round, float[] out) {
        AmmoLock.Reads.roundStats(round, out);
    }

    @Override
    public int hudBlocks(Body holder, int hand, int[] ints, float[] floats, Object[] objects, String[] texts) {
        return Hud.Reads.hudBlocks(holder, hand, ints, floats, objects, texts);
    }

    @Override
    public int reach(Body holder, int hand) {
        net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.EMPTY;
        if (EntityBody.entity(holder) instanceof net.minecraft.world.entity.LivingEntity l)
            stack = hand == Body.MAIN_HAND ? l.getMainHandItem() : l.getOffhandItem();
        return EuphImmunity.reachOf(stack);
    }
}
