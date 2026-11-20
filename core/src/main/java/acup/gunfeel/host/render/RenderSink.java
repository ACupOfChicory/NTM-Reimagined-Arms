package acup.gunfeel.host.render;

/** Handed to a drawer at its place in the frame. */
public interface RenderSink {
    CameraFrame frame();

    /**
     * A kept buffer laid from {@code (ox, oy, oz)}: the version's model offset is that point less the camera, its
     * view scaled by {@code pull} about the camera (1: as it is). {@code first}, {@code count}: elements of its layout.
     */
    void drawAt(int material, int buffer, int first, int count, double ox, double oy, double oz, float pull);

    /**
     * A part into this drawer's place with this material: written now into the version's own pass, or into the
     * game's queue and written when the game draws it. {@code room}: about how many elements it writes.
     */
    void queue(int material, int part, int room);
}
