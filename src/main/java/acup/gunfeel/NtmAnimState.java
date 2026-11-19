package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.BusAnimationSequence;
import com.hbm.render.anim.HbmAnimations;

/** The gun's animation for core (GunAnimState): NTM's relevant animation in slot 0, the arm editor's preview time. */
final class NtmAnimState implements GunAnimState {
    @Override
    public boolean playing() {
        return HbmAnimations.getRelevantAnim(0) != null;
    }

    @Override
    public @Nullable String type() {
        GunAnimation type = HbmAnimations.getGunAnimation(0);
        return type == null ? null : type.name();
    }

    @Override
    public float millis() {
        if (ArmAnimationPreview.active()) return ArmAnimationPreview.millis();
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        return anim == null ? 0F : com.hbm.util.GameTime.now() - anim.startMillis;
    }

    @Override
    public boolean hasBus(String bus) {
        return seq(bus) != null;
    }

    @Override
    public int busTime(String bus) {
        BusAnimationSequence seq = seq(bus);
        return seq == null ? 0 : seq.getTotalTime();
    }

    @Override
    public double @Nullable [] busAt(String bus, int ms) {
        BusAnimationSequence seq = seq(bus);
        return seq == null ? null : seq.getTransformation(ms);
    }

    @Override
    public double @Nullable [] busNow(String bus) {
        return HbmAnimations.getRelevantTransformation(bus);
    }

    private static @Nullable BusAnimationSequence seq(String bus) {
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        return anim == null ? null : anim.animation.getBus(bus);
    }
}
