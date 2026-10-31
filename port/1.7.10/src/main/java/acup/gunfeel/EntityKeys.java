package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.player.EntityPlayer;

// An entity's type as 26.2 names it (26.2: BuiltInRegistries.ENTITY_TYPE.getKey), so the 26.2 tables keyed by type
// (ntmgunfeel-heads.json, ntmgunfeel-limbs.json, the avatar rule) read the same rows here. 1.7.10 names vanilla mobs
// by their old save names ("PigZombie") and a mod's as "modid.name" ("hbm.entity_glyphid"; 26.2: hbm:entity_glyphid).
// One 1.7.10 class stands for several 26.2 types: the skeleton's type, the horse's type and the villager zombie.
public final class EntityKeys {
    private static final Map<String, String> VANILLA = new HashMap<>();
    static {
        String[][] names = {
                {"Creeper", "creeper"}, {"Skeleton", "skeleton"}, {"Spider", "spider"}, {"Giant", "giant"},
                {"Zombie", "zombie"}, {"Slime", "slime"}, {"Ghast", "ghast"}, {"PigZombie", "zombified_piglin"},
                {"Enderman", "enderman"}, {"CaveSpider", "cave_spider"}, {"Silverfish", "silverfish"},
                {"Blaze", "blaze"}, {"LavaSlime", "magma_cube"}, {"EnderDragon", "ender_dragon"},
                {"WitherBoss", "wither"}, {"Bat", "bat"}, {"Witch", "witch"}, {"Pig", "pig"}, {"Sheep", "sheep"},
                {"Cow", "cow"}, {"Chicken", "chicken"}, {"Squid", "squid"}, {"Wolf", "wolf"},
                {"MushroomCow", "mooshroom"}, {"SnowMan", "snow_golem"}, {"Ozelot", "ocelot"},
                {"VillagerGolem", "iron_golem"}, {"EntityHorse", "horse"}, {"Villager", "villager"},
        };
        for (String[] n : names) VANILLA.put(n[0], "minecraft:" + n[1]);
    }
    private static final String[] HORSES = {"horse", "donkey", "mule", "zombie_horse", "skeleton_horse"};

    private EntityKeys() {
    }

    // "minecraft:zombie", "hbm:entity_glyphid"; "minecraft:player" for players; the class name for an unregistered one
    public static String type(Entity entity) {
        if (entity instanceof EntityPlayer) return "minecraft:player";
        if (entity instanceof EntitySkeleton && ((EntitySkeleton) entity).getSkeletonType() == 1) return "minecraft:wither_skeleton";
        if (entity instanceof EntityZombie && ((EntityZombie) entity).isVillager()) return "minecraft:zombie_villager";
        if (entity instanceof EntityHorse) {
            int t = ((EntityHorse) entity).getHorseType();
            return "minecraft:" + (t >= 0 && t < HORSES.length ? HORSES[t] : "horse");
        }
        if (entity instanceof EntityOcelot && ((EntityOcelot) entity).getTameSkin() != 0) return "minecraft:cat";
        String name = EntityList.getEntityString(entity);
        return name == null ? entity.getClass().getName() : of(name);
    }

    // a 1.7.10 entity name in 26.2's form
    static String of(String name) {
        String vanilla = VANILLA.get(name);
        if (vanilla != null) return vanilla;
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) + ":" + name.substring(dot + 1) : "minecraft:" + name.toLowerCase(java.util.Locale.ROOT);
    }
}
