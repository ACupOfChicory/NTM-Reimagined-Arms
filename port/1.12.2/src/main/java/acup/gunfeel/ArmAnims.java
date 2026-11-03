package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.render.anim.sedna.AnimationEnums.GunAnimation;
import com.hbm.render.anim.sedna.HbmAnimationsSedna;
import com.hbm.util.Clock;

// Which NTM gun animation plays, and NTM's animation clock (26.2: HbmAnimationsSedna.getGunAnimation, GameTime.now).
// NTM 1.7.10's HbmAnimationsSedna.Animation keeps only its key, start and buses; the type comes from
// GunAnimationPacketSedna.Handler.handleSedna, which starts every gun animation (MixinHbmAnimationPacket).
public final class ArmAnims {
    private static final Map<HbmAnimationsSedna.Animation, GunAnimation> TYPES = new WeakHashMap<HbmAnimationsSedna.Animation, GunAnimation>();

    private ArmAnims() {
    }

    // handleSedna has run for this hotbar slot and receiver: the animation it put there (if any) is of this type
    public static void started(int slot, int index, GunAnimation type) {
        if (slot < 0 || slot >= HbmAnimationsSedna.hotbar.length || index < 0 || index >= HbmAnimationsSedna.hotbar[slot].length) return;
        HbmAnimationsSedna.Animation a = HbmAnimationsSedna.hotbar[slot][index];
        if (a != null && type != null) TYPES.put(a, type);
    }

    // the type of the animation playing on this receiver of the gun in hand, null = none (or not known)
    public static GunAnimation type(int index) {
        HbmAnimationsSedna.Animation a = HbmAnimationsSedna.getRelevantAnim(index);
        return a == null ? null : TYPES.get(a);
    }

    // NTM's animation clock, ms (com.hbm.util.Clock, once a frame; MixinClock while a trace scenario plays)
    public static long now() {
        return Clock.get_ms();
    }
}
