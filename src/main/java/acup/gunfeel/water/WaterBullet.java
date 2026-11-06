package acup.gunfeel.water;

import net.minecraft.world.entity.Entity;

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

    static double f(Entity bullet) {
        return bullet instanceof WaterBullet w ? w.gunfeel$waterF() : 1.0;
    }

    static boolean stopped(Entity bullet) {
        return bullet instanceof WaterBullet w && w.gunfeel$waterStopped();
    }
}
