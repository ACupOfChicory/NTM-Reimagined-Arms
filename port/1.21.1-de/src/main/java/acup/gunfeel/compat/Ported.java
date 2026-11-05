package acup.gunfeel.compat;

import java.util.EnumSet;
import java.util.Set;

// The systems ported to Minecraft 1.21.1 so far (notes/ports/de-1.21.1-progress.md): each part of the port adds its
// own here with its mixins. The rest are off from the start, the way Compat turns off a system NTM does not fit,
// but they are not NTM's doing, so the compatibility warning does not list them.
public final class Ported {
    public static final Set<Feature> DONE = EnumSet.of(Feature.CORE, Feature.RECOIL, Feature.ARMS, Feature.SPRINT_FIRE, Feature.HOLD_AIM, Feature.CLICK_AIM, Feature.BULLETS, Feature.MACHINE_MESH, Feature.TRACERS, Feature.RICOCHET_SOUND,
            Feature.MODEL_HITBOXES);

    private Ported() {
    }

    public static void apply() {
        for (Feature f : Feature.values()) if (!DONE.contains(f)) f.set(false);
    }
}
