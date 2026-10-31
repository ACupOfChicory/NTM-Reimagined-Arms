package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelPartEntities.java at 0762086; changes marked "port:"

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragonPart;

// NeoForge PartEntity и обычные Entity с публичной связью getParent().
// Поиск метода выполняется один раз на класс, без зависимости от имени мода.
// port: 1.7.10 has no PartEntity; the dragon's parts are EntityDragonPart (entityDragonObj), and Entity.getParts()
// is the multipart list for every entity (null when there is none).
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
        if(part instanceof EntityLivingBase) return null;
        // port: EntityDragonPart for NeoForge's PartEntity
        if(part instanceof EntityDragonPart known) return known.entityDragonObj instanceof Entity owner && owner!=part?owner:null;
        var getter=PARENTS.get(part.getClass());
        if(getter.isEmpty()) return null;
        try {
            Entity parent=(Entity)getter.get().invokeExact(part);
            return parent==part?null:parent;
        } catch(Throwable ex) { return null; }
    }
    static Entity[] parts(Entity owner) {
        // port: Entity.getParts() for isMultipartEntity()/getParts()
        if(owner.getParts()!=null) return owner.getParts();
        var getter=PARTS.get(owner.getClass());
        if(getter.isEmpty()) return NONE;
        try { var parts=(Entity[])getter.get().invokeExact(owner); return parts==null?NONE:parts; }
        catch(Throwable ex) { return NONE; }
    }
}
