package acup.gunfeel.smoke.client;

import java.util.List;

/**
 * The stands only (SmokeCloudStand, -PsmokePoses=compare): the ways the same frame of smoke can be drawn, to set them
 * side by side. "old": the four parts of 2026-11-18 drawn as before it (SmokeRender.standOld; the looks of 11-19 stay);
 * "new": as it is drawn now; "old-rect": now, but the whole frame copied and laid over, the reference a part of the
 * screen must match. The stand adds "none" last (no smoke, its quality off there): the frame to set the others against.
 */
public final class SmokeCompare {
    public static final List<String> VARIANTS = List.of("old", "new", "old-rect");

    private SmokeCompare() {
    }

    public static void set(String variant) {
        reset();
        SmokeRender.standOld = switch (variant) {
            case "old" -> SmokeRender.OLD;
            case "old-rect" -> SmokeRender.OLD_RECT;
            default -> 0;
        };
    }

    /** Back to the way it is drawn in the game. */
    public static void reset() {
        SmokeRender.standOld = 0;
    }
}
