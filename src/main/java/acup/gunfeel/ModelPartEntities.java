package acup.gunfeel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// NeoForge PartEntity и обычные Entity с публичной связью getParent().
// Поиск метода выполняется один раз на класс, без зависимости от имени мода.
public final class ModelPartEntities {
    private static final Entity[] NONE=new Entity[0];
    private static final ClassValue<Optional<MethodHandle>> PARENTS=accessor("getParent",Entity.class);
    private static final ClassValue<Optional<MethodHandle>> PARTS=accessor("getMobParts",Entity[].class);
    private ModelPartEntities() {}

    private static ClassValue<Optional<MethodHandle>> accessor(String name,Class<?> result) {
        return new ClassValue<>() {
            @Override protected Optional<MethodHandle> computeValue(Class<?> type) {
                try {
                    var method=type.getMethod(name);
                    if(!result.isAssignableFrom(method.getReturnType())) return Optional.empty();
                    return Optional.of(MethodHandles.publicLookup().unreflect(method)
                            .asType(MethodType.methodType(result,Entity.class)));
                } catch(ReflectiveOperationException | RuntimeException ex) { return Optional.empty(); }
            }
        };
    }
    public static Entity parent(Entity part) {
        if(part instanceof LivingEntity) return null;
        if(part instanceof net.neoforged.neoforge.entity.PartEntity<?> known) return known.getParent();
        var getter=PARENTS.get(part.getClass());
        if(getter.isEmpty()) return null;
        try {
            Entity parent=(Entity)getter.get().invokeExact(part);
            return parent==part?null:parent;
        } catch(Throwable ex) { return null; }
    }
    static Entity[] parts(Entity owner) {
        if(owner.isMultipartEntity() && owner.getParts()!=null) return owner.getParts();
        var getter=PARTS.get(owner.getClass());
        if(getter.isEmpty()) return NONE;
        try { var parts=(Entity[])getter.get().invokeExact(owner); return parts==null?NONE:parts; }
        catch(Throwable ex) { return NONE; }
    }
}
