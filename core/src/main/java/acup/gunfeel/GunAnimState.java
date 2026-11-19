package acup.gunfeel;

import org.jspecify.annotations.Nullable;

/**
 * The animation NTM plays now on the gun in the main hand (the arms follow it: ArmTimeline). On 26.2
 * {@code NtmAnimState} reads HbmAnimations (slot 0) and the arm editor's preview; a version whose NTM has no animation
 * bus ({@code NtmFeature.ANIM_BUS}), and core's own tests, get {@link #NONE}: no animation, ever. Asked on the render
 * thread only.
 */
public interface GunAnimState {
    /** NTM's names of the animations core tells apart ({@link #type}). */
    String RELOAD = "RELOAD", RELOAD_CYCLE = "RELOAD_CYCLE", RELOAD_END = "RELOAD_END", INSPECT = "INSPECT";

    /** An animation is playing (NTM's relevant animation). */
    boolean playing();

    /** Its type's name as NTM has it (RELOAD, RELOAD_CYCLE, INSPECT…), null = none; asked apart from {@link #playing}. */
    @Nullable String type();

    /** Milliseconds since it started, or the arm editor's preview time while it previews; 0 = none. */
    float millis();

    /** When it started, on NTM's clock ({@link #now}); meaningful only while {@link #playing}. */
    long startMillis();

    /** NTM's clock, milliseconds (not GunClock): what the animations' times are on. */
    long now();

    /** The arm editor previews an animation (its time is the preview's, not the clock's). */
    boolean previewing();

    /** The playing animation has this bus. */
    boolean hasBus(String bus);

    /** The total time of that bus in the playing animation, 0 = no animation or no such bus. */
    int busTime(String bus);

    /** The bus's transformation (x, y, z, then NTM's other channels) at {@code ms}; null = no animation or no such bus. */
    double @Nullable [] busAt(String bus, int ms);

    /** The bus's transformation now, as NTM gives it for the playing animation; null = none. */
    double @Nullable [] busNow(String bus);

    /** No animation at all. */
    GunAnimState NONE = new GunAnimState() {
        @Override
        public boolean playing() {
            return false;
        }

        @Override
        public @Nullable String type() {
            return null;
        }

        @Override
        public float millis() {
            return 0F;
        }

        @Override
        public long startMillis() {
            return 0L;
        }

        @Override
        public long now() {
            return 0L;
        }

        @Override
        public boolean previewing() {
            return false;
        }

        @Override
        public boolean hasBus(String bus) {
            return false;
        }

        @Override
        public int busTime(String bus) {
            return 0;
        }

        @Override
        public double @Nullable [] busAt(String bus, int ms) {
            return null;
        }

        @Override
        public double @Nullable [] busNow(String bus) {
            return null;
        }
    };
}
