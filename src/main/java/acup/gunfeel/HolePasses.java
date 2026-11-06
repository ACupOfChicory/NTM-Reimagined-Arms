package acup.gunfeel;

// Which pass draws a bullet hole (Tracers), for the holes kept in batches and the ones sent each frame alike. Early:
// after the solid features, before the translucent terrain. Late: after the translucent terrain (holes on glass).
// A late hole on a block of the translucent terrain (under: NTM's laminate, stained glass) is drawn early as well:
// the glass's near face writes depth, which hides a late hole on its far face, so the hole is seen from behind
// only through the glass laid over its early copy; from in front the late one covers that copy.
final class HolePasses {
    private HolePasses() {
    }

    static boolean early(boolean late, boolean under) {
        return !late || under;
    }

    // a batch holds holes of one texture drawn the same way: under or not, late or not, cut (mesh) or flat
    static int batchKey(int kind, boolean under, boolean late, boolean mesh) {
        return kind * 8 + (under ? 4 : 0) + (late ? 2 : 0) + (mesh ? 1 : 0);
    }
}
