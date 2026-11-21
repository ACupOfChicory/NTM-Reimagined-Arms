package acup.gunfeel;

import acup.gunfeel.host.PierceShots;

/** The blocks paid for along one bullet's path, rewound at creatures and repaid afterwards. */
public class PierceStepsBase {
    final Materials.Penetration penetration = new Materials.Penetration();
    private final PierceLedger<Object> steps = new PierceLedger<>();
    protected Object from;

    private static final class ShotsPort {
        static final PierceShots SHOTS = CoreServices.load(PierceShots.class, PierceShots.MISSING);
    }

    protected final void startOf(Object from) {
        this.from = from;
        steps.clear();
    }

    protected final void piercedOf(Object in, Object out, float before, double spentBefore) {
        piercedOf(in, out, before, spentBefore, ShotsPort.SHOTS);
    }

    final void piercedOf(Object in, Object out, float before, double spentBefore, PierceShots shots) {
        steps.pierced(shots.distance(shots.location(in), from), before, spentBefore, in, out);
    }

    protected final void enterOf(Object bullet, Object at) {
        enterOf(bullet, at, ShotsPort.SHOTS);
    }

    final void enterOf(Object bullet, Object at, PierceShots shots) {
        if (steps.size() == 0) return;
        int k = steps.firstFrom(shots.distance(at, from));
        if (steps.paidPast(k)) {
            shots.damage(bullet, steps.damageBefore(k));
            penetration.restore(steps.spentBefore(k));
            steps.unpayTo(k);
            if (TestLog.server) shots.logLater(shots.id(bullet), steps.size() - k, bullet);
        } else {
            payTo(bullet, k, shots);
        }
    }

    protected final void finishOf(Object bullet) {
        finishOf(bullet, ShotsPort.SHOTS);
    }

    final void finishOf(Object bullet, PierceShots shots) {
        payTo(bullet, steps.size(), shots);
        steps.clear();
    }

    private void payTo(Object bullet, int k, PierceShots shots) {
        Object level = shots.level(bullet);
        while (steps.owes(k) && !shots.removed(bullet)) {
            int i = steps.payNext();
            Object in = steps.wayIn(i), out = steps.wayOut(i);
            float wk = shots.water(bullet, in);
            float left = wk == 1F ? shots.pierced(level, in, out, shots.damage(bullet), bullet, penetration)
                    : wk <= 0F ? -1F : shots.pierced(level, in, out, shots.damage(bullet) * wk, bullet, penetration) / wk;
            if (TestLog.server) shots.logBlock(bullet, level, in, left);
            if (left < 0F) {
                shots.stopAt(bullet, in);
                return;
            }
            shots.damage(bullet, left);
        }
    }
}
