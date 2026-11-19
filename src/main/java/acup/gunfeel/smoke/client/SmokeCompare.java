package acup.gunfeel.smoke.client;

import java.util.List;

/**
 * The stands only (SmokeCloudStand, -PsmokePoses=compare): the ways the same frame of smoke can be drawn, to set them
 * side by side. "old": as before 2026-11-18 (SmokeRender.standOld); "new": as it is drawn now; "a", "b", "c" and
 * "abc": the looks for the owner to choose from (SmokeRender.look).
 */
public final class SmokeCompare {
    public static final List<String> VARIANTS = List.of("old", "new", "a", "b", "c", "abc");

    private SmokeCompare() {
    }

    public static void set(String variant) {
        reset();
        if ("old".equals(variant)) SmokeRender.standOld = SmokeRender.OLD;
        SmokeRender.look = switch (variant) {
            case "a" -> 1;
            case "b" -> 2;
            case "c" -> 4;
            case "abc" -> 7;
            default -> 0;
        };
    }

    /** Back to the way it is drawn in the game. */
    public static void reset() {
        SmokeRender.standOld = 0;
        SmokeRender.look = 0;
    }
}
