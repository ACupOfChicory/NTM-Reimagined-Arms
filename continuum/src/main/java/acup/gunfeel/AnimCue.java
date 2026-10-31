package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.client.anim.HbmAnimations;
import com.hbm.items.weapon.sedna.GunAnimation;

// port/continuum: NTM NEXT's HbmAnimations.getGunAnimation(rail), which the shared code asks (renamed to of(rail) in
// stonecutter.gradle.kts). Continuum's HbmAnimations.Animation does not keep which cue started it: GunAnimationHandler
// knows the cue (MixinGunAnimationHandler marks it pending while it handles the server's payload) and the animation it
// starts is tied to it here (MixinHbmAnimations, after HbmAnimations.start).
public final class AnimCue {
    // the animations started for a cue; weak: a finished animation is dropped from HbmAnimations and from here
    private static final Map<HbmAnimations.Animation, GunAnimation> CUES = new WeakHashMap<>();
    private static GunAnimation pending;

    private AnimCue() {
    }

    // the cue of the main-hand animation playing on this rail, or null
    public static GunAnimation of(int rail) {
        if (rail == 0 && ArmAnimationPreview.active()) return ArmAnimationPreview.type();
        HbmAnimations.Animation animation = HbmAnimations.getRelevantAnim(rail);
        return animation == null ? null : CUES.get(animation);
    }

    public static void handling(GunAnimation cue) {
        pending = cue;
    }

    public static GunAnimation pending() {
        return pending;
    }

    public static void started(HbmAnimations.Animation animation) {
        if (pending != null && animation != null) CUES.put(animation, pending);
    }
}
