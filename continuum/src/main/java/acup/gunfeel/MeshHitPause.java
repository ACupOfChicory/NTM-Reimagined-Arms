package acup.gunfeel;

import java.lang.reflect.Field;

// port/continuum: an effect a mob draws in its own submit (the arcs of a tesla crab, BeamPronter) is not part of its
// body. NtmMeshHits would read it as model geometry: hundreds of little quads, and the whole capture is refused
// (more than ModelPosePayload.MAX_BOXES). While such an effect is submitted, the mob's frame is set aside, so its
// geometry is drawn but not captured. Render thread only; nested calls keep the outermost frame.
public final class MeshHitPause {
    private static final ThreadLocal<?> SUBMIT = submit();
    private static int depth;
    private static Object held;

    private MeshHitPause() {
    }

    private static ThreadLocal<?> submit() {
        try {
            Field f = NtmMeshHits.class.getDeclaredField("SUBMIT");
            f.setAccessible(true);
            return (ThreadLocal<?>) f.get(null);
        } catch (ReflectiveOperationException e) {
            GunFeel.LOGGER.warn("NtmMeshHits.SUBMIT not found: mob effects are captured with the body", e);
            return null;
        }
    }

    public static void begin() {
        if (SUBMIT == null || depth++ > 0) return;
        held = SUBMIT.get();
        SUBMIT.remove();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void end() {
        if (SUBMIT == null || depth == 0 || --depth > 0) return;
        if (held != null) ((ThreadLocal) SUBMIT).set(held);
        held = null;
    }
}
