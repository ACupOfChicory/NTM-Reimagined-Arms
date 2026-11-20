package acup.gunfeel.ai;

import acup.gunfeel.CoreServices;
import acup.gunfeel.ReaimTimes;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;

/**
 * A mob's role by the gun in its hand, what core decides of it: an automatic that fires fast, a real bullet (not a
 * laser) and a big magazine makes a base of fire (AiRole.BASE in 26.2; the rest of the roles, the grenadier's and the
 * marksman's, are 26.2's still).
 */
public final class AiRoleBase {
    private static final NtmGuns GUNS = CoreServices.load(NtmGuns.class, NtmGuns.NONE);

    /** the server setting: a base of fire's magazine from this many rounds */
    public static final String MAG_KEY = "aiBaseMagazine";
    static final int MAG = 40;
    /** a base of fire's gun fires at least this often, ticks a shot */
    static final int FAST = 4;

    private AiRoleBase() {
    }

    /**
     * The gun in its main hand (one of NTM's: the caller has seen it is) makes it a base of fire. A magazine that wants
     * an inventory does not.
     */
    public static boolean base(Body body) {
        return base(GUNS, body);
    }

    static boolean base(NtmGuns guns, Body body) {
        float[] t = new float[NtmGuns.T_COUNT];
        int capacity = guns.fireTraits(body, Body.MAIN_HAND, t);
        if (capacity == NtmGuns.NO_ROUNDS || t[NtmGuns.T_REFIRE] == 0F || t[NtmGuns.T_DELAY] > FAST) return false;
        if (t[NtmGuns.T_BULLET] == 0F || t[NtmGuns.T_VELOCITY] < 5F || t[NtmGuns.T_LASER] != 0F) return false;
        int need;
        try {
            need = Math.max(1, Math.round(ReaimTimes.number(MAG_KEY, MAG)));
        } catch (RuntimeException e) {
            return false;
        }
        return t[NtmGuns.T_SINGLE] == 0F || capacity >= need;
    }
}
