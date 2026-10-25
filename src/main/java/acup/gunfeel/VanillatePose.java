package acup.gunfeel;

import java.lang.reflect.Field;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

// Необязательный адаптер к неизменяемому снимку Vanillate. Нет жёсткой зависимости от его private record.
public final class VanillatePose {
    public record Data(Matrix4f root, double x, double y, double z, float[] transforms, boolean[] draw, Identifier texture, int variant) {}
    private record Fields(Field root, Field x, Field y, Field z, Field transforms, Field draw, Field texture, Field variant) {}
    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try {
            Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
        } catch (NoSuchFieldException ignored) {}
        throw new IllegalStateException(type.getName() + "." + name);
    }
    private static final ClassValue<Field> PUBLISHED = new ClassValue<>() {
        protected Field computeValue(Class<?> c) { return field(c, "published"); }
    };
    private static final ClassValue<Fields> FIELDS = new ClassValue<>() {
        protected Fields computeValue(Class<?> c) {
            return new Fields(field(c, "local"), field(c, "x"), field(c, "y"), field(c, "z"),
                    field(c, "transforms"), field(c, "draw"), field(c, "texture"), field(c, "variant"));
        }
    };

    private VanillatePose() {}

    public static Data read(Object visual) {
        try {
            Object snapshot = PUBLISHED.get(visual.getClass()).get(visual);
            if (snapshot == null) return null;
            Fields f = FIELDS.get(snapshot.getClass());
            Matrix4f root = (Matrix4f) f.root().get(snapshot);
            if (root == null) return null;
            return new Data(root, f.x().getDouble(snapshot), f.y().getDouble(snapshot), f.z().getDouble(snapshot),
                    (float[]) f.transforms().get(snapshot), (boolean[]) f.draw().get(snapshot),
                    (Identifier) f.texture().get(snapshot), f.variant().getInt(snapshot));
        } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
    }
}
