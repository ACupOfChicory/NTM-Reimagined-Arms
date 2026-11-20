package acup.gunfeel.host.render;

/** Geometry registered once ({@link RenderPort#part}); what it writes it reads from core's fields set by its drawer. */
@FunctionalInterface
public interface Part {
    void write(VertexWriter out);
}
