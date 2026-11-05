package acup.gunfeel;

// Port copy (notes/ports/de-1.21.1.md, «Копии»). 26.2's ModelBodyGeometry adds a body layer's cubes (the breeze's wind,
// the sheep's wool, the slime's outer body) to a pose taken without them: the Flywheel and Vanillate snapshots and the
// main model alone. On DE a mob's layers are taken as they are drawn (NtmMeshHits, MixinLivingModelHits), its wool and
// shell named as there, so only the shell's name is kept.
public final class ModelBodyGeometry {
    public static final String SHELL_SUFFIX = "/shell";

    private ModelBodyGeometry() {
    }

    public static void clear() {
    }
}
