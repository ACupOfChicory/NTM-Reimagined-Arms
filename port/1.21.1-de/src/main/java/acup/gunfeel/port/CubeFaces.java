package acup.gunfeel.port;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;

// 26.2's ModelPart.Cube.polygons: the cube's faces as 26.2 shapes them, records with vertices() and x(), y(), z(),
// u(), v(). In 1.21.1 the array is private and its Polygon and Vertex classes are package-private (a vertex keeps
// its point in pos), so the faces are read once a cube by reflection (NeoForge 1.21.1 runs with Mojang's names) and
// kept. The order of the faces and of their vertices is 1.21.1's own, as compile() draws them; the point is in
// pixels, as 26.2's.
public final class CubeFaces {
    public record Vertex(float x, float y, float z, float u, float v) {}
    public record Face(Vertex[] vertices, Vector3f normal) {}

    private static final Field POLYGONS, VERTICES, NORMAL, POS, U, V;
    private static final Map<ModelPart.Cube, Face[]> CACHE = new WeakHashMap<>();

    static {
        try {
            POLYGONS = field(ModelPart.Cube.class, "polygons");
            Class<?> polygon = Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon");
            Class<?> vertex = Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");
            VERTICES = field(polygon, "vertices");
            NORMAL = field(polygon, "normal");
            POS = field(vertex, "pos");
            U = field(vertex, "u");
            V = field(vertex, "v");
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CubeFaces() {
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    public static Face[] of(ModelPart.Cube cube) {
        synchronized (CACHE) {
            Face[] faces = CACHE.get(cube);
            if (faces == null) CACHE.put(cube, faces = read(cube));
            return faces;
        }
    }

    private static Face[] read(ModelPart.Cube cube) {
        try {
            Object[] polygons = (Object[]) POLYGONS.get(cube);
            Face[] out = new Face[polygons.length];
            for (int i = 0; i < polygons.length; i++) {
                Object[] vertices = (Object[]) VERTICES.get(polygons[i]);
                Vertex[] vs = new Vertex[vertices.length];
                for (int j = 0; j < vertices.length; j++) {
                    Vector3f p = (Vector3f) POS.get(vertices[j]);
                    vs[j] = new Vertex(p.x(), p.y(), p.z(), U.getFloat(vertices[j]), V.getFloat(vertices[j]));
                }
                out[i] = new Face(vs, new Vector3f((Vector3f) NORMAL.get(polygons[i])));
            }
            return out;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
