// port/continuum: NTM NEXT's (HBM's Nuclear Tech Mod, 26.2) animation classes, so that a gun on NTM Continuum moves
// as on 26.2 (NextGunAnimations). Taken from NTM NEXT 1.0.3, LGPL-3.0-only: see continuum/third-party/ntm-next.txt.
package acup.gunfeel.next;

import java.util.HashMap;
import java.util.Map.Entry;

// a Golem animation to whoever reads it; its buses are this package's sequences
public class BusAnimation extends dev.golem.core.render.anim.BusAnimation {
    private final HashMap<String, BusAnimationSequence> animationBuses = new HashMap<>();
    private int totalTime = 0;

    public BusAnimation addBus(String name, BusAnimationSequence bus) {
        this.animationBuses.put(name, bus);
        int duration = bus.getTotalTime();
        if (duration > this.totalTime) {
            this.totalTime = duration;
        }

        return this;
    }

    @Override
    public void updateTime() {
        for (Entry<String, BusAnimationSequence> sequence : this.animationBuses.entrySet()) {
            int time = sequence.getValue().getTotalTime();
            if (time > this.totalTime) {
                this.totalTime = time;
            }
        }
    }

    @Override
    public BusAnimation addBus(String name, dev.golem.core.render.anim.BusAnimationSequence bus) {
        if (!(bus instanceof BusAnimationSequence sequence)) throw new IllegalArgumentException("a Golem sequence in an NTM NEXT animation: " + name);
        return this.addBus(name, sequence);
    }

    @Override
    public BusAnimationSequence getBus(String name) {
        return this.animationBuses.get(name);
    }

    @Override
    public void setTimeMult(double mult) {
        for (Entry<String, BusAnimationSequence> sequence : this.animationBuses.entrySet()) {
            sequence.getValue().multiplyTime(mult);
        }
    }

    @Override
    public double[] getTimedTransformation(String name, int millis) {
        return this.animationBuses.containsKey(name) ? this.animationBuses.get(name).getTransformation(millis) : null;
    }

    @Override
    public int getDuration() {
        return this.totalTime;
    }
}
