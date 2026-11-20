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
    };
}
