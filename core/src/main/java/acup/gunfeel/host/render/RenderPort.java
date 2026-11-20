package acup.gunfeel.host.render;

/**
 * What core draws, said once at load; the version draws it in its own frame (notes/ports-base/render-step1.md). Handles
 * are ints, the same for the whole session; {@link #NONE} is none.
 */
public interface RenderPort {
    int NONE = 0;

    /** the same spec (equals) gives the same handle */
    int material(MaterialSpec spec);

    /** a buffer kept from frame to frame until {@link #free}: elements of {@code layout}, room for about {@code room} */
    int buffer(int layout, int room);

    /**
     * Writes a kept buffer anew from its start: the writer until {@link VertexWriter#done}. {@code room}: about how
     * many elements come (the version makes more room as they come, never fails); nothing written leaves the
     * buffer as it was.
     */
    VertexWriter fill(int buffer, int room);

    void free(int buffer);

    /** geometry the version has written when it wants it: at once in its own pass, or later from the game's queue */
    int part(Part part);

    /** a drawer of an intent ({@link Intent}): the version runs it at the place its table gives that intent */
    void drawer(int intent, Drawer drawer);

    /**
     * a buffer of a draw's parameters (the version's uniform buffer), written whole each frame it is used
     * ({@link RenderSink#upload}) and read by {@link RenderSink#drawInstanced} from an offset; until {@link #free}
     */
    int params(String name);

    /** where in a params buffer a draw's parameters may begin: offsets are multiples of it */
    int paramsAlignment();

    /**
     * A params buffer written in place this frame ({@code length} bytes from its start into the version's own memory,
     * no copy of core's): the writer until {@link ParamsWriter#done}. Mapped again before done, or a buffer written by
     * {@link RenderSink#upload} mapped (or the other way round): IllegalStateException. The version binds what was
     * written in its own passes; {@link #free} lets its memory go (made again by the next map).
     * Exactly length bytes must be written before done (otherwise IllegalStateException); an element beyond length
     * throws IndexOutOfBoundsException before any of that element is written.
     */
    ParamsWriter map(int params, int length);

    /** a texture of RGBA bytes, {@code width} × {@code height}, written by {@link #texels}; until {@link #free} */
    int texture(String name, int width, int height);

    /**
     * Texels written into a texture now (not in a frame: whenever core has them): {@code data} from its position to its
     * limit, {@code width} × {@code height} RGBA texels from ({@code x}, {@code y})
     */
    void texels(int texture, int x, int y, int width, int height, java.nio.ByteBuffer data);

    /** a texture still as written: false when freed, or the version lost it (closed under it); then make it again */
    boolean live(int texture);

    /** where no version gives one (core's own tests): each method throws */
    RenderPort MISSING = new RenderPort() {
        @Override
        public int material(MaterialSpec spec) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public int buffer(int layout, int room) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public VertexWriter fill(int buffer, int room) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public void free(int buffer) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public int part(Part part) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public void drawer(int intent, Drawer drawer) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public int params(String name) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public int paramsAlignment() {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public ParamsWriter map(int params, int length) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public int texture(String name, int width, int height) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public void texels(int texture, int x, int y, int width, int height, java.nio.ByteBuffer data) {
            throw new UnsupportedOperationException("no RenderPort here");
        }

        @Override
        public boolean live(int texture) {
            throw new UnsupportedOperationException("no RenderPort here");
        }
    };
}
