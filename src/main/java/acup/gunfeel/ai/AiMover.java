package acup.gunfeel.ai;

import acup.gunfeel.EntityBody;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/**
 * 26.2's AI mob's legs (AiMoverBase in core): the mob passed as its Body, the tactical map as a WalkMap, so AiBot,
 * AiSoldier, AiHatch and the stands call it as before.
 */
public final class AiMover extends AiMoverBase {
    AiMover(Mob mob) {
        super(EntityBody.of(mob));
    }

    /** AiMoverBase.tick on this level's tactical map */
    void tick(ServerLevel level, TacticalMap map, long tick) {
        tick(map, tick);
    }

    /** the path it walks was searched on an older map than the one kept now */
    public boolean stale(TacticalMap map) {
        return super.stale(map);
    }

    static void wantMissing(TacticalMap map, PathSearch.Result r) {
        AiMoverBase.wantMissing((WalkMap) map, r);
    }
}
