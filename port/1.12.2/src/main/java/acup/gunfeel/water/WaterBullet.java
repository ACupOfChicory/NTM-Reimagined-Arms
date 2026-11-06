package acup.gunfeel.water;

import net.minecraft.entity.Entity;

// 26.2's WaterBullet, the ballistics' part: what an NTM bullet (EntityBulletBaseMK4) keeps of water between ticks: its
// speed fraction f (1 in the air, NTM's motionMult is multiplied by it), how many times it skipped, whether water
// stopped it. Mixed into the bullet (MixinWaterBullet). port: only the server's bullet flies (NTM CE moves the client's
// by the server's updates); the client's tracer keeps its own.
public interface WaterBullet {
    double gunfeel$waterF();

    void gunfeel$waterF(double f);

    int gunfeel$waterSkips();

    void gunfeel$waterSkips(int skips);

    // water stopped it (f under waterStopFraction): a bullet is gone, an explosive goes on at that speed till it
    // touches something
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
        return bullet instanceof WaterBullet ? ((WaterBullet) bullet).gunfeel$waterF() : 1.0;
    }

    static boolean stopped(Entity bullet) {
        return bullet instanceof WaterBullet && ((WaterBullet) bullet).gunfeel$waterStopped();
    }
}
