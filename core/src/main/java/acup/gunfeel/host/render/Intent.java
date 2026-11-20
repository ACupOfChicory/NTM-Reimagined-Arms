package acup.gunfeel.host.render;

/**
 * Where in the frame a drawer belongs, in core's words; the version's table turns it into its event, order and
 * target for the frame's {@link CameraFrame#translucency} (render-step1.md §2).
 */
public final class Intent {
    /** after the solid features, the drawers in the order registered (holes kept in batches) */
    public static final int AFTER_OPAQUE = 1;
    /** into the game's queue with the rest of the frame's geometry (holes sent each frame) */
    public static final int QUEUE = 2;
    /** over the glass: after the translucent terrain, and the clouds and rain behind it (holes on glass) */
    public static final int OVER_GLASS = 3;
    /**
     * after the solid features, first: solid geometry that belongs in the depth the frame's later passes copy (the
     * smoke's), NTM's casings
     */
    public static final int AFTER_OPAQUE_SOLID = 4;

    private Intent() {
    }
}
