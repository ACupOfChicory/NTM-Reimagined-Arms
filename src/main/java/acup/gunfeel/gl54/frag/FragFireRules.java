package acup.gunfeel.gl54.frag;

// The incendiary grenade's fire on the ground, as NTM's 40 mm INC makes it (XFactory40mm.spawnFire): a lingering fire
// entity over a flat patch that sets creatures in it alight, and a fire block in each air cell near the burst that has a
// flammable neighbour (so only what can burn burns). Here the patch is 2.5 blocks round for 5 s (NTM's: 5 blocks wide
// for 10 s). No game classes here.
public final class FragFireRules {
    // the patch's width (twice the radius), its height above the floor, how long it burns, ticks
    public static final float WIDTH = 5F, HEIGHT = 2F;
    public static final int TICKS = 100;
    // how far below a burst in the air the floor may be for the fire to be lit on it
    public static final double FLOOR_REACH = 6.0;
    // the cells round the burst that may light, a cube of 3 each way (NTM's)
    public static final int CUBE = 1;

    private FragFireRules() {
    }

    // does a cell catch: it is air and one of its six neighbours is flammable
    public static boolean ignites(boolean air, int flammableNeighbours) {
        return air && flammableNeighbours > 0;
    }
}
