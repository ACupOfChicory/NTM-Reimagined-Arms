package acup.gunfeel;

// the fallback (no animation bus, core's own tests) is the contract's idle state, and core's tests get it
class GunAnimStateNoneTest extends GunAnimStateContract {
    @Override
    protected GunAnimState idle() {
        return GunAnimState.NONE;
    }

    @org.junit.jupiter.api.Test
    void coresTestsGetNone() {
        org.junit.jupiter.api.Assertions.assertSame(GunAnimState.NONE, ArmTimeline.ANIM);
    }
}
