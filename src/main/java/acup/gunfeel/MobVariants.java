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
            // Моб другого размера: scale_2, scale_0.5 — атрибут scale.
            case String v when v.startsWith("scale_") -> target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE).setBaseValue(Double.parseDouble(v.substring(6)));
            case "carpet" -> target.setItemSlot(EquipmentSlot.BODY,new ItemStack(Items.CARPET.red()));
            // Надетая броня (ArmorFloatStand): armor_iron — весь комплект, armor_iron_hc — только шлем и нагрудник
            // (h шлем, c нагрудник, l поножи, f ботинки); ванильные наборы и наборы NTM (t51, hazmat, steel, cobalt).
            case String v when v.startsWith("armor_") -> armor(target,v.substring(6));
            // На голове предмет: head_skeleton_skull, head_carved_pumpkin, head_dragon_head.
            case String v when v.startsWith("head_") -> target.setItemSlot(EquipmentSlot.HEAD,new ItemStack(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(v.substring(5)))
                            .orElseThrow(() -> new IllegalArgumentException("Нет предмета: "+v))));
            // Житель и житель-зомби: тип (одежда, шляпа) и профессия, prof_none — без профессии.
            case String v when v.startsWith("type_") -> villager(target,d->d.withType(target.level().registryAccess(),
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_TYPE,net.minecraft.resources.Identifier.withDefaultNamespace(v.substring(5)))));
            case String v when v.startsWith("prof_") -> villager(target,d->d.withProfession(target.level().registryAccess(),
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,net.minecraft.resources.Identifier.withDefaultNamespace(v.substring(5)))).withLevel(2));
            default -> throw new IllegalArgumentException("Неизвестный вариант моба: "+variant);
        }
    }
    private static void armor(LivingEntity target,String spec) {
        String set=spec,slots="hclf";
        int at=spec.lastIndexOf('_');
        if(at>0 && spec.substring(at+1).matches("[hclf]+")) { set=spec.substring(0,at); slots=spec.substring(at+1); }
        boolean ntm=java.util.Set.of("t51","hazmat","steel","cobalt").contains(set);
        String[] names=ntm?new String[]{"helmet","plate","legs","boots"}:new String[]{"helmet","chestplate","leggings","boots"};
        EquipmentSlot[] equip={EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};
        for(int i=0;i<4;i++) {
            if(slots.indexOf("hclf".charAt(i))<0) continue;
            var id=net.minecraft.resources.Identifier.fromNamespaceAndPath(ntm?"hbm":"minecraft",set+"_"+names[i]);
            var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id)
                    .orElseThrow(() -> new IllegalArgumentException("Нет предмета брони: "+id));
            target.setItemSlot(equip[i],new ItemStack(item));
        }
    }
    private static void villager(LivingEntity target,java.util.function.UnaryOperator<net.minecraft.world.entity.npc.villager.VillagerData> change) {
        var holder=(net.minecraft.world.entity.npc.villager.VillagerDataHolder)target;
        holder.setVillagerData(change.apply(holder.getVillagerData()));
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
