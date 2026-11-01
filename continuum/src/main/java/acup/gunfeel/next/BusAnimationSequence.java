// port/continuum: NTM NEXT's (HBM's Nuclear Tech Mod, 26.2) animation classes, so that a gun on NTM Continuum moves
// as on 26.2 (NextGunAnimations). Taken from NTM NEXT 1.0.3, LGPL-3.0-only: see continuum/third-party/ntm-next.txt.
package acup.gunfeel.next;

import java.util.ArrayList;
import java.util.List;

// a Golem sequence to whoever reads it (Continuum's HbmAnimations, the arms); the Golem fields are left unused
public class BusAnimationSequence extends dev.golem.core.render.anim.BusAnimationSequence {
    private final List<List<BusAnimationKeyframe>> transformKeyframes = new ArrayList<>(9);

    public BusAnimationSequence() {
        for (int i = 0; i < 9; i++) {
            this.transformKeyframes.add(new ArrayList<>());
        }
    }

    public BusAnimationSequence addKeyframe(BusAnimationSequence.Dimension dimension, BusAnimationKeyframe keyframe) {
        this.transformKeyframes.get(dimension.ordinal()).add(keyframe);
        return this;
    }

    public BusAnimationSequence addKeyframe(BusAnimationSequence.Dimension dimension, double value, int duration) {
        return this.addKeyframe(dimension, new BusAnimationKeyframe(value, duration));
    }

    public BusAnimationSequence setPos(double x, double y, double z) {
        return this.addPos(x, y, z, 0, BusAnimationKeyframe.IType.LINEAR);
    }

    public BusAnimationSequence addPos(double x, double y, double z, int duration) {
        return this.addPos(x, y, z, duration, BusAnimationKeyframe.IType.LINEAR);
    }

    public BusAnimationSequence addPos(double x, double y, double z, int duration, BusAnimationKeyframe.IType type) {
        this.addKeyframe(BusAnimationSequence.Dimension.TX, new BusAnimationKeyframe(x, duration, type));
        this.addKeyframe(BusAnimationSequence.Dimension.TY, new BusAnimationKeyframe(y, duration, type));
        this.addKeyframe(BusAnimationSequence.Dimension.TZ, new BusAnimationKeyframe(z, duration, type));
        return this;
    }

    public BusAnimationSequence addRot(double x, double y, double z, int duration) {
        this.addKeyframe(BusAnimationSequence.Dimension.RX, new BusAnimationKeyframe(x, duration));
        this.addKeyframe(BusAnimationSequence.Dimension.RY, new BusAnimationKeyframe(y, duration));
        this.addKeyframe(BusAnimationSequence.Dimension.RZ, new BusAnimationKeyframe(z, duration));
        return this;
    }

    public BusAnimationSequence hold(int duration) {
        this.addKeyframe(BusAnimationSequence.Dimension.TX, new BusAnimationKeyframe(this.getLast(BusAnimationSequence.Dimension.TX), duration));
        this.addKeyframe(BusAnimationSequence.Dimension.TY, new BusAnimationKeyframe(this.getLast(BusAnimationSequence.Dimension.TY), duration));
        this.addKeyframe(BusAnimationSequence.Dimension.TZ, new BusAnimationKeyframe(this.getLast(BusAnimationSequence.Dimension.TZ), duration));
        return this;
    }

    public BusAnimationSequence holdUntil(int end) {
        int duration = end - this.getTotalTime();
        return this.hold(duration);
    }

    public BusAnimationSequence multiplyTime(double mult) {
        for (BusAnimationSequence.Dimension dim : BusAnimationSequence.Dimension.values()) {
            for (BusAnimationKeyframe keyframe : this.transformKeyframes.get(dim.ordinal())) {
                keyframe.duration = (int)(keyframe.originalDuration * mult);
            }
        }

        return this;
    }

    private double getLast(BusAnimationSequence.Dimension dim) {
        BusAnimationKeyframe frame = this.getLastFrame(dim);
        return frame != null ? frame.value : 0.0;
    }

    private BusAnimationKeyframe getLastFrame(BusAnimationSequence.Dimension dim) {
        List<BusAnimationKeyframe> keyframes = this.transformKeyframes.get(dim.ordinal());
        return keyframes.isEmpty() ? null : keyframes.get(keyframes.size() - 1);
    }

    public double[] getTransformation(int millis) {
        double[] transform = new double[15];

        for (int i = 0; i < 9; i++) {
            transform[i] = this.value(i, millis);
        }

        transform[9] = this.offset[0];
        transform[10] = this.offset[1];
        transform[11] = this.offset[2];
        transform[12] = this.rotMode[0];
        transform[13] = this.rotMode[1];
        transform[14] = this.rotMode[2];
        return transform;
    }

    public double value(int dimension, int millis) {
        List<BusAnimationKeyframe> keyframes = this.transformKeyframes.get(dimension);
        BusAnimationKeyframe current = null;
        BusAnimationKeyframe previous = null;
        int start = 0;
        int end = 0;

        for (int i = 0; i < keyframes.size(); i++) {
            start = end;
            BusAnimationKeyframe keyframe = keyframes.get(i);
            end += keyframe.duration;
            previous = current;
            current = keyframe;
            if (millis < end) {
                break;
            }
        }

        if (current == null) {
            return dimension >= 6 ? 1.0 : 0.0;
        } else if (millis >= end || current.duration == 0) {
            return current.value;
        } else {
            return previous != null && previous.interpolationType == BusAnimationKeyframe.IType.CONSTANT
                ? previous.value
                : current.interpolate(start, millis, previous);
        }
    }

    public int getTotalTime() {
        int highestTime = 0;

        for (List<BusAnimationKeyframe> keyframes : this.transformKeyframes) {
            int time = 0;

            for (BusAnimationKeyframe frame : keyframes) {
                time += frame.duration;
            }

            highestTime = Math.max(time, highestTime);
        }

        return highestTime;
    }

    public static enum Dimension {
        TX,
        TY,
        TZ,
        RX,
        RY,
        RZ,
        SX,
        SY,
        SZ;
    }
}
