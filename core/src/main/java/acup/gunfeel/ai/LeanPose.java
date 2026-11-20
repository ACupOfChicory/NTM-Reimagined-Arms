package acup.gunfeel.ai;

/** The live pose fields used by the lean. */
public interface LeanPose {
    byte stance();
    byte lean();
    byte prevLean();
    int leanTick();
}
