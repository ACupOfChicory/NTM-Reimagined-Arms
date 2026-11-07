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

    // water stopped it (f under waterStopFraction): a bullet is gone, one with an impact fuze sinks
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
    // after its impact, also one that is gone, so its path ends here, not where it is (WaterImpact.touch)
    net.minecraft.util.math.Vec3d gunfeel$goneAt();

    void gunfeel$goneAt(net.minecraft.util.math.Vec3d at);

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

    // it has met water this flight, or water stopped it: a bullet that touches other bullets (WaterImpact.touch)
    static boolean met(Entity bullet) {
        return bullet instanceof WaterBullet && (((WaterBullet) bullet).gunfeel$waterMet() || ((WaterBullet) bullet).gunfeel$waterStopped());
    }

    static boolean stopped(Entity bullet) {
        return bullet instanceof WaterBullet && ((WaterBullet) bullet).gunfeel$waterStopped();
    }
}
