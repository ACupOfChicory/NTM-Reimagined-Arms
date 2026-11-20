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

    /**
     * A params buffer ({@link RenderPort#params}) written anew from {@code data}'s position 0 to {@code length}, whatever
     * its position and limit (left as they were): one copy to the GPU now, the buffer made larger first when it is too
     * small.
     */
    void upload(int params, java.nio.ByteBuffer data, int length);

    /**
     * {@code n} instances of a kept mesh, each instance's parameters from {@code offset} in a params buffer, in the
     * material's instanced program. The draws of a place go to the GPU together, in one pass, at {@link #flush} (or the
     * drawer's end): a material changed between them changes the program within that pass. The meshes of one flush
     * are of one topology (the pass has one index buffer): another throws IllegalArgumentException.
     */
    void drawInstanced(int material, int mesh, int params, int offset, int n);

    /** the draws so far sent now (an error of the GPU's thrown here, to the drawer) */
    void flush();
}
