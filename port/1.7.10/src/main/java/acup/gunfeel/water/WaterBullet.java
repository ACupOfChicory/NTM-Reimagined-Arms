package acup.gunfeel.water;

import net.minecraft.entity.Entity;

// What an NTM bullet (EntityBulletBaseMK4) keeps of water between ticks: its speed fraction f (1 in the air, NTM's
// motionMult is multiplied by it, so the tracer, the client and the server agree), how many times it skipped, and
// whether water stopped it, whether it has met water at all, and the shot it was fired in. Mixed into the bullet
// (MixinWaterBullet).
public interface WaterBullet {
    double gunfeel$waterF();

    void gunfeel$waterF(double f);

    int gunfeel$waterSkips();

    void gunfeel$waterSkips(int skips);

    // water stopped it (f under waterStopFraction): a bullet is gone, an explosive sinks (the explosives part)
    boolean gunfeel$waterStopped();

    void gunfeel$waterStopped(boolean stopped);

    // its path has gone through liquid this flight (WaterTick.end): only such a bullet touches another (WaterImpact)
    boolean gunfeel$waterMet();

    void gunfeel$waterMet(boolean met);

    // the shot it was fired in: the server's world time when NTM made it. Pellets of one shot are made in one loop, the
    // same tick, and share it (WaterImpact.sameShot)
    long gunfeel$shot();

    void gunfeel$shot(long shot);

    // where an impact this tick ended it (WaterImpact.impact), null: none did. NTM moves a bullet the whole tick's way
    // after its impact, also one that is dead, so its path ends here, not where it is (WaterImpact.touch)
    net.minecraft.util.Vec3 gunfeel$goneAt();

    void gunfeel$goneAt(net.minecraft.util.Vec3 at);

    // the level way it last flew, of length 1, for the model of a round that sinks (an impact fuze); null: it has
    // flown straight up or down only. NTM turns its yaw to atan2(dX, dZ) of every tick's move, so sinking straight
    // down leaves that 0 (SunkBullets.fuzed). port: kept on the client, from its move there (SunkBullets.track): NTM
    // 1.7.10's client copy does not tick, the server's moves of it come
    acup.gunfeel.phys.Vec3 gunfeel$sunkHeading();

    void gunfeel$sunkHeading(acup.gunfeel.phys.Vec3 heading);

    // port, the server: what of its motion was last sent to the players (WaterTick.announce): the world time << 2
    // | the kind (0 none, 1 flying, 2 sinking)
    long gunfeel$sent();

    void gunfeel$sent(long sent);

    // this tick's path through liquid (null: it met none), from the path test to the end of the tick
    WaterPath.Result gunfeel$waterTick();

    void gunfeel$waterTick(WaterPath.Result tick);

    WaterClass gunfeel$waterClass();

    // how it expands in water (hollow or soft point), null: it does not
    WaterClass.Expansion gunfeel$waterExpansion();

    // its path in water so far × the density: how far an expanding bullet has opened (WaterPath.Result.opened)
    double gunfeel$waterOpened();

    void gunfeel$waterOpened(double opened);

    static double f(Entity bullet) {
        return bullet instanceof WaterBullet w ? w.gunfeel$waterF() : 1.0;
    }

    // the bullet's path this tick went through liquid, or it is in one (for the effects: bubbles, the muzzle)
    static boolean inWater(Entity bullet) {
        if (!(bullet instanceof WaterBullet w)) return false;
        WaterPath.Result r = w.gunfeel$waterTick();
        return r != null && (r.water > 0 || r.inLiquid(r.end()));
    }

    // it has met water this flight, or water stopped it: a bullet that touches other bullets (WaterImpact.touch)
    static boolean met(Entity bullet) {
        return bullet instanceof WaterBullet w && (w.gunfeel$waterMet() || w.gunfeel$waterStopped());
    }

    static boolean stopped(Entity bullet) {
        return bullet instanceof WaterBullet w && w.gunfeel$waterStopped();
    }
}
