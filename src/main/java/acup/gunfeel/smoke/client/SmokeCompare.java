package acup.gunfeel.smoke.client;

import java.util.List;

/**
 * The stands only (SmokeCloudStand, -PsmokePoses=compare): the ways the same frame of smoke can be drawn, to set them
 * side by side. "old": as before 2026-11-18 (SmokeRender.standOld); "new": as it is drawn now; "old-rect", "old-depth",
 * "old-front", "old-through": new with one part drawn the old way.
 */
public final class SmokeCompare {
    public static final List<String> VARIANTS = List.of("old", "new", "old-rect", "old-depth", "old-front", "old-through");

    private SmokeCompare() {
    }

    public static void set(String variant) {
        reset();
        SmokeRender.standOld = switch (variant) {
            case "old" -> SmokeRender.OLD;
            case "old-rect" -> SmokeRender.OLD_RECT;
            case "old-depth" -> SmokeRender.OLD_DEPTH;
            case "old-front" -> SmokeRender.OLD_FRONT;
            case "old-through" -> SmokeRender.OLD_THROUGH;
            default -> 0;
        };
    }

    /** Back to the way it is drawn in the game. */
    public static void reset() {
        SmokeRender.standOld = 0;
    }
}
