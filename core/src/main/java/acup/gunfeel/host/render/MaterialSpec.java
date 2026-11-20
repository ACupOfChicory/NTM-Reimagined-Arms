package acup.gunfeel.host.render;

/**
 * How a material draws, not where (where is the drawer's intent). {@code program}: the shader's name; {@code blend}:
 * {@link Blend}; {@code alphaCutout} 0: none; {@code depthTest}: nearer or as near passes, in the version's direction;
 * {@code biasScale}, {@code biasConstant}: the depth bias towards the camera; {@code texture}: its resource, null none.
 */
public record MaterialSpec(String name, String program, int layout, int blend, float alphaCutout, boolean depthTest, boolean depthWrite,
        float biasScale, float biasConstant, boolean cull, String texture, boolean lightmap, boolean overlay) {
}
