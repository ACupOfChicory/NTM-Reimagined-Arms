package acup.gunfeel.host;

// The world's contract (WorldViewContract) on StateNumbers' fake world: air at 0 0 0, stone at 1 0 0, a fence with
// properties at 2 0 0. Each version's tests run it on its own world.
class StateNumbersWorldTest extends WorldViewContract {
    private final StateNumbersTest.FakeWorld world = new StateNumbersTest.FakeWorld();

    @Override
    protected WorldView world() {
        return world;
    }

    @Override
    protected int[] air() {
        return new int[] {0, 0, 0};
    }

    @Override
    protected int[] stone() {
        return new int[] {1, 0, 0};
    }

    @Override
    protected int[] withProperties() {
        return new int[] {2, 0, 0};
    }

    @Override
    protected int[] caveAir() {
        return new int[] {3, 0, 0};
    }

    @Override
    protected float stoneDestroySpeed() {
        return 1.5F;
    }
}
