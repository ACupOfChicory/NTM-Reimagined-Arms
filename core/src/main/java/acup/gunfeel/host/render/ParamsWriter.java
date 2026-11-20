package acup.gunfeel.host.render;

/**
 * Writes a params buffer in place ({@link RenderPort#map}), std140, from its start: each call where the last left off,
 * aligned as std140 aligns it. Valid until {@link #done} or {@link #abort}: after either, writing or done throws
 * IllegalStateException; core keeps no reference to it past the frame.
 */
public interface ParamsWriter {
    ParamsWriter vec4(float x, float y, float z, float w);

    /** column by column, as {@code Matrix4fc.get(ByteBuffer)} */
    ParamsWriter mat4(org.joml.Matrix4fc m);

    /** written: the memory the version's again (unmapped) */
    void done();

    /** unmap without checking length; already done or aborted: nothing */
    void abort();
}
