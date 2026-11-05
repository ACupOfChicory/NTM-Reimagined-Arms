package acup.gunfeel;

import java.util.List;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Стенд: состояние моба по суффиксам mobCases, чтобы модель рисовала нужные части. */
final class MobVariants {
    private MobVariants() {}
    static void apply(LivingEntity target,List<String> variants) throws ReflectiveOperationException {
        for(var variant:variants) switch(variant) {
            case "baby" -> {}
            case "sheared" -> { if(target instanceof SnowGolem golem) golem.setPumpkin(false);else ((Sheep)target).setSheared(true); }
            case "nolefthorn" -> horn((Goat)target,"DATA_HAS_LEFT_HORN");
            case "norighthorn" -> horn((Goat)target,"DATA_HAS_RIGHT_HORN");
            // Поборник бьёт, иллюзионист целится из лука: руки не скрещены.
            case "attack" -> ((Mob)target).setAggressive(true);
            case "celebrate" -> ((Raider)target).setCelebrating(true);
            case "spell" -> spell((SpellcasterIllager)target);
            case "carpet" -> target.setItemSlot(EquipmentSlot.BODY,new ItemStack(Items.CARPET.red()));
            default -> throw new IllegalArgumentException("Неизвестный вариант моба: "+variant);
        }
    }
    // IllagerSpell закрыт (protected): заклинание ставится через рефлексию.
    @SuppressWarnings({"unchecked","rawtypes"})
    private static void spell(SpellcasterIllager illager) throws ReflectiveOperationException {
        var type=Class.forName("net.minecraft.world.entity.monster.illager.SpellcasterIllager$IllagerSpell");
        var method=SpellcasterIllager.class.getMethod("setIsCastingSpell",type);
        method.invoke(illager,Enum.valueOf((Class)type,"FANGS"));
    }
    @SuppressWarnings("unchecked")
    private static void horn(Goat goat,String field) throws ReflectiveOperationException {
        var accessor=Goat.class.getDeclaredField(field);accessor.setAccessible(true);
        goat.getEntityData().set((EntityDataAccessor<Boolean>)accessor.get(null),false);
    }
}
