package acup.gunfeel;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

final class MobCatalog {
    private MobCatalog() {}

    static List<String> discover() {
        // Зарегистрированный поставщик атрибутов — признак живой сущности,
        // включая MISC и боссов. Снаряды создавать ради классификации не нужно.
        return BuiltInRegistries.ENTITY_TYPE.stream()
                .filter(type -> type != EntityTypes.PLAYER && DefaultAttributes.hasSupplier(type))
                .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())
                .sorted(java.util.Comparator.comparing((String id) -> !id.startsWith("minecraft:")).thenComparing(id -> id)).toList();
    }
}
