package acup.gunfeel.host.render;

/** The element layouts. */
public final class Layouts {
    /** a vertex of a decal: position, colour, uv, light, normal (VertexWriter#decal) */
    public static final int DECAL = 1;
    /**
     * a vertex of an entity's model (the game's entity format: position, colour, uv, overlay, light, normal), written
     * by the version itself from its own source (a model of the game's or a mod's); core keeps only the handle
     */
    public static final int MESH_ENTITY = 2;
    public static final int COLOR = 3;
    public static final int PARTICLE = 4;

    private Layouts() {
    }
}
