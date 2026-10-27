package acup.gunfeel;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;

/** Только игровые аватары: декоративный слой скина не расширяет тело. */
public final class AvatarHitPolicy {
    private static final Set<Identifier> TYPES=Set.of(Identifier.withDefaultNamespace("player"),Identifier.withDefaultNamespace("mannequin"));
    private static final Set<String> BODY=Set.of("root/head","root/body","root/left_arm","root/right_arm","root/left_leg","root/right_leg");
    private static final Set<String> SKIN=Set.of("root/head/hat","root/body/jacket","root/left_arm/left_sleeve","root/right_arm/right_sleeve",
            "root/left_leg/left_pants","root/right_leg/right_pants","root/hat","root/jacket","root/left_sleeve","root/right_sleeve","root/left_pants","root/right_pants");
    private AvatarHitPolicy() {}
    public static List<ModelBox> filter(LivingEntity entity,List<ModelBox> boxes) {
        return filter(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),boxes);
    }
    static List<ModelBox> filter(Identifier type,List<ModelBox> boxes) {
        if(!TYPES.contains(type)) return boxes;
        var found=new HashSet<String>();
        for(var box:boxes) found.add(path(box));
        // Не угадываем устройство нестандартного рендерера даже у игрока.
        if(!found.containsAll(BODY)) return boxes;
        if(found.stream().anyMatch(path->!BODY.contains(path) && !SKIN.contains(path))) return boxes;
        return boxes.stream().filter(box->BODY.contains(path(box))).toList();
    }
    private static String path(ModelBox box) {
        String name=box.part();return name.startsWith("mesh/")?name.substring(5):name;
    }
}
