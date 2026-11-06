package acup.gunfeel.water;

import net.minecraft.entity.Entity;

// What an NTM bullet (EntityBulletBaseMK4) keeps of water between ticks: its speed fraction f (1 in the air, NTM's
// motionMult is multiplied by it, so the tracer, the client and the server agree), how many times it skipped, and
// whether water stopped it. Mixed into the bullet (MixinWaterBullet).
public interface WaterBullet {
    double gunfeel$waterF();

    void gunfeel$waterF(double f);

    int gunfeel$waterSkips();

    void gunfeel$waterSkips(int skips);

    // water stopped it (f under waterStopFraction): a bullet is gone, an explosive sinks (the explosives part)
    boolean gunfeel$waterStopped();

    void gunfeel$waterStopped(boolean stopped);

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

    static boolean stopped(Entity bullet) {
        return bullet instanceof WaterBullet w && w.gunfeel$waterStopped();
    }
}
