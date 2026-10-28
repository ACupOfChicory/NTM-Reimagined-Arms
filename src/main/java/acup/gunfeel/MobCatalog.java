package acup.gunfeel;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

final class MobCatalog {
    private MobCatalog() {}
    static String type(String key) { return key.endsWith("#baby")?key.substring(0,key.length()-5):key; }
    static boolean baby(String key) { return key.endsWith("#baby"); }
    static List<String> review(net.minecraft.server.level.ServerLevel level,boolean babiesOnly) {
        var babies=babies(level);
        if(babiesOnly) return babies;
        var all=new java.util.ArrayList<>(discover());all.addAll(babies);return List.copyOf(all);
    }
    static List<String> babies(net.minecraft.server.level.ServerLevel level) {
        var out=new java.util.ArrayList<String>();
        for(String key:discover()) try {
            var type=BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(key));
            var entity=type.create(level,net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if(entity instanceof net.minecraft.world.entity.Mob mob) {
                mob.setBaby(true);
                if(mob.isBaby()) out.add(key+"#baby");
            }
        } catch(Exception ex) { GunFeel.LOGGER.warn("Не удалось проверить детский вариант {}",key,ex); }
        return List.copyOf(out);
    }

    static List<String> discover() {
        // Зарегистрированный поставщик атрибутов — признак живой сущности,
        // включая MISC и боссов. Снаряды создавать ради классификации не нужно.
        return BuiltInRegistries.ENTITY_TYPE.stream()
                .filter(type -> type != EntityTypes.PLAYER && DefaultAttributes.hasSupplier(type))
                .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())
                .sorted(java.util.Comparator.comparing((String id) -> !id.startsWith("minecraft:")).thenComparing(id -> id)).toList();
    }
}
