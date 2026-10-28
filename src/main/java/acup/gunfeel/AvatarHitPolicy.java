package acup.gunfeel;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;

/** Аватары используют всю видимую геометрию, включая второй слой скина. */
public final class AvatarHitPolicy {
    private AvatarHitPolicy() {}
    public static List<ModelBox> filter(LivingEntity entity,List<ModelBox> boxes) {
        return filter(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),boxes);
    }
    static List<ModelBox> filter(Identifier type,List<ModelBox> boxes) {
        return boxes;
    }
}
