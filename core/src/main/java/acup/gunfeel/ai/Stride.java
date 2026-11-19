package acup.gunfeel.ai;

/** An AI mob's walking as its lying and crouching ask of it (26.2: AiMover): whether its mind runs, and its pace. */
public interface Stride {
    /** the share of its step running low past low cover (AiStoop) */
    double STOOP = 0.75;

    /** its mind wants it running */
    boolean wantsRun();

    /** this tick's pace, a share of its step */
    void slow(double share);
}
