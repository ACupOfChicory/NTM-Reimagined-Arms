package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BulletClockTest {
    @Test void pausedWorldDoesNotAdvanceEvenWithManyRenderedFrames() {
        var clock = new BulletClock();
        double born = clock.sample(100, .25);
        for (int frame = 0; frame < 1000; frame++) assertEquals(0, clock.sample(100,.25) - born);
        assertEquals(.5, clock.sample(100,.75) - born);
        assertEquals(1, clock.sample(101,.25) - born);
    }

    @Test void flightDistanceDependsOnSimulationTicksAtEitherTickrate() {
        for (int ticksPerSecond : new int[] {2,20}) {
            var clock = new BulletClock();
            double born = clock.sample(100,0);
            for (int frame = 1; frame <= 100; frame++) {
                double simulated = frame * ticksPerSecond / 100.0;
                double elapsed = clock.sample(100 + (long) simulated, simulated % 1) - born;
                assertEquals(16 * simulated, 16 * elapsed, 1e-8);
            }
        }
    }

    @Test void resyncCannotReverseFlightAndNewWorldCanStartAtEarlierTime() {
        var clock = new BulletClock();
        assertEquals(100.75, clock.sample(100,.75));
        assertEquals(100.75, clock.sample(99,.9));
        assertEquals(101, clock.sample(101,0));
        clock.reset();
        assertEquals(2.5, clock.sample(2,.5));
    }
}
