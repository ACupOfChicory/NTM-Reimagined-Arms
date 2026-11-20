package acup.gunfeel.host.render;

/** Writes the elements of one layout: a method a layout. */
public interface VertexWriter {
    /**
     * {@link Layouts#DECAL}: a position (from the buffer's origin, or from the camera for a queued part), ARGB, the
     * texture's u, v, the packed light, the normal
     */
    void decal(float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz);

    /** the elements written (a kept buffer: what it holds from now on) */
    int done();
}
