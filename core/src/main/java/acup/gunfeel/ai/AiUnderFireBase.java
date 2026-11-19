package acup.gunfeel.ai;

import acup.gunfeel.ReaimTimes;

/** The AI mobs' suppression setting (26.2: AiUnderFire, which tells them the shots and blasts round them). */
public class AiUnderFireBase {
    /** the server setting: AI mobs are suppressed by the players' scale; off: three shots close past pin one, as before */
    public static final String KEY = "aiSuppression";

    protected AiUnderFireBase() {
    }

    public static boolean on() {
        return ReaimTimes.flag(KEY, true);
    }
}
