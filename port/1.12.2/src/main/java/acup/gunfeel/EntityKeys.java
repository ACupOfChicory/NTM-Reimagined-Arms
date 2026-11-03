package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
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
    private static final Map<String, String> ETFUTURUM = new HashMap<>(), COPIES = new HashMap<>();
    static {
        ETFUTURUM.put("brown_mooshroom", "mooshroom");
        ETFUTURUM.put("villager_zombie", "zombie_villager");
        ETFUTURUM.put("wooden_armorstand", "armor_stand");
        COPIES.put("chisel.snowman", "minecraft:snow_golem");
        COPIES.put("adventurebackpack.rideableSpider", "minecraft:spider");
    }

    private EntityKeys() {
    }

    // "minecraft:zombie", "hbm:entity_glyphid"; "minecraft:player" for players; the class name for an unregistered one
    // port: 1.12.2 has an id for each type (EntityList.getKey: the wither skeleton, the horses and the villager zombie
    // are types of their own); a few are named otherwise than on 26.2 (MODERN)
    public static String type(Entity entity) {
        if (entity instanceof EntityPlayer) return "minecraft:player";
        if (entity instanceof EntityOcelot && ((EntityOcelot) entity).getTameSkin() != 0) return "minecraft:cat";
        net.minecraft.util.ResourceLocation id = EntityList.getKey(entity);
        if (id == null) return entity.getClass().getName();
        String key = id.toString();
        String modern = MODERN.get(key);
        if (modern != null) return modern;
        if (id.getNamespace().equals("hbm") && id.getPath().startsWith("entity_ntm_")) return "hbm:entity_" + id.getPath().substring("entity_ntm_".length());
        return key;
    }

    private static final Map<String, String> MODERN = new HashMap<>();
    static {
        String[][] names = {{"zombie_pigman", "zombified_piglin"}, {"snowman", "snow_golem"}, {"villager_golem", "iron_golem"},
                {"evocation_illager", "evoker"}, {"vindication_illager", "vindicator"}, {"illusion_illager", "illusioner"}};
        for (String[] n : names) MODERN.put("minecraft:" + n[0], "minecraft:" + n[1]);
        // NTM CE dropped "mob_" from three of NTM's ids (26.2: hbm:entity_mob_mask_man)
        for (String n : new String[] {"mask_man", "nuclear_creeper", "tainted_creeper"}) MODERN.put("hbm:entity_" + n, "hbm:entity_mob_" + n);
    }

    // a 1.7.10 entity name in 26.2's form
    static String of(String name) {
        String vanilla = VANILLA.get(name);
        if (vanilla != null) return vanilla;
        int dot = name.indexOf('.');
        if (dot <= 0) return "minecraft:" + name.toLowerCase(java.util.Locale.ROOT);
        String mod = name.substring(0, dot), rest = name.substring(dot + 1);
        // NTM 1.7.10 names some of its mobs entity_ntm_* (entity_ntm_ghost); 26.2 has them as entity_* (entity_ghost)
        if (mod.equals("hbm") && rest.startsWith("entity_ntm_")) rest = "entity_" + rest.substring("entity_ntm_".length());
        // Et Futurum Requiem brings back the mobs of later versions under their names (a few its own): 26.2 has them
        // as vanilla types. Two mods' copies of vanilla mobs in the owner's pack too, drawn with vanilla's models
        if (mod.equals("etfuturum")) {
            String renamed = ETFUTURUM.get(rest);
            return "minecraft:" + (renamed != null ? renamed : rest);
        }
        String copy = COPIES.get(name);
        if (copy != null) return copy;
        return mod + ":" + rest;
    }
}
