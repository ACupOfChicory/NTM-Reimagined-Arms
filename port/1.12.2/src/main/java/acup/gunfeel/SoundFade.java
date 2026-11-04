package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;

// port: 26.2's sounds.json fades some of the mod's sounds out over their own distance (attenuation_distance: the whizz
// over 8 blocks, a steel ricochet over 32); 1.12.2's has no such field and fades every sound out over 16 blocks (more
// only for a volume over 1, which is no louder). A sound set on the line from the listener's ears, 16/fade as far,
// fades as 26.2's and comes from the same side (the client's BulletFx, the server's Mc.playSoundFaded).
final class SoundFade {
    private SoundFade() {
    }

    // how far 26.2 fades a sound out: its sounds.json's attenuation_distance (16 without one; SoundsJsonTest checks
    // the table against it)
    static final Map<String, Float> TABLE = new HashMap<String, Float>();
    static {
        TABLE.put("ntmgunfeel:bullet.whizz", 8F);
        TABLE.put("ntmgunfeel:bullet.crack", 12F);
        TABLE.put("ntmgunfeel:bullet.ricochet.steel", 32F);
        for (String hit : new String[] {"wood", "dirt", "gravel", "stone", "concrete", "metal", "steel",
                "concrete.f1", "concrete.f2", "dirt.f1", "dirt.f2", "gravel.f1", "gravel.f2", "gravel.f3", "metal.f1", "metal.f2",
                "steel.f1", "steel.f2", "steel.f3", "steel.f4", "steel.f5", "stone.f1", "stone.f2", "wood.f1", "wood.f2"})
            TABLE.put("ntmgunfeel:bullet.hit." + hit, 24F);
    }

    static float of(String id) {
        Float f = TABLE.get(id);
        return f == null ? 16F : f;
    }

    // where a sound at `at` goes so that 1.12.2, fading it over 16 blocks, fades it as over `over` blocks: on the line
    // from the ears, 16/over as far (so from the same side)
    static double[] at(double[] ear, double[] at, double over) {
        double k = 16.0 / over;
        return new double[] {ear[0] + (at[0] - ear[0]) * k, ear[1] + (at[1] - ear[1]) * k, ear[2] + (at[2] - ear[2]) * k};
    }
}
