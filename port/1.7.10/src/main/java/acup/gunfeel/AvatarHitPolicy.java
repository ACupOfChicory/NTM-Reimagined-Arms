package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/AvatarHitPolicy.java at 0762086; changes marked "port:"

import java.util.*;
import net.minecraft.entity.EntityLivingBase;

/** Аватары используют всю видимую геометрию, включая второй слой скина. */
public final class AvatarHitPolicy {
    private AvatarHitPolicy() {}
    // port: types are 26.2 ids as strings (EntityKeys), not Identifier
    static boolean avatar(String type) { return type.equals("minecraft:player") || type.equals("minecraft:mannequin"); }
    public static List<ModelBox> filter(EntityLivingBase entity,List<ModelBox> boxes) {
        return filter(EntityKeys.type(entity),boxes);
    }
    static List<ModelBox> filter(String type,List<ModelBox> boxes) {
        return boxes;
    }
}
