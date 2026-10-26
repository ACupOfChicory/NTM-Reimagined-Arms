package acup.gunfeel;

import com.hbm.entity.mob.EntityQuackos;
import com.hbm.items.tool.ItemPeas;
import java.util.ArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

// Только изолированный стенд. Мод может запретить обычный discard():
// используем его настоящий предмет и проверяем результат до нового спавна.
final class MobStageCleanup {
    private MobStageCleanup() {}
    static int clear(ServerLevel level,ServerPlayer player) {
        var old=new ArrayList<Entity>(); level.getAllEntities().forEach(old::add);
        int special=0;
        for(var entity:old) if(entity instanceof EntityQuackos && !entity.isRemoved()) {
            var peas=BuiltInRegistries.ITEM.stream().filter(item -> item instanceof ItemPeas).findFirst().orElseThrow();
            var held=player.getItemInHand(InteractionHand.MAIN_HAND);
            var position=player.position(); float yaw=player.getYRot(),pitch=player.getXRot();
            player.stopRiding();
            try {
                player.teleportTo(level,entity.getX(),entity.getY(),entity.getZ(),java.util.Set.of(),yaw,pitch,false);
                player.setItemInHand(InteractionHand.MAIN_HAND,peas.getDefaultInstance());
                peas.use(level,player,InteractionHand.MAIN_HAND);
                special++;
            } finally {
                player.setItemInHand(InteractionHand.MAIN_HAND,held);
                player.teleportTo(level,position.x,position.y,position.z,java.util.Set.of(),yaw,pitch,false);
            }
        }
        // Peas может создать добычу: она тоже не остаётся в следующем случае.
        old.clear(); level.getAllEntities().forEach(old::add);
        for(var entity:old) if(!(entity instanceof ServerPlayer) && !entity.isRemoved()) entity.discard();
        var remaining=new ArrayList<String>();
        level.getAllEntities().forEach(entity -> {
            if(!(entity instanceof ServerPlayer) && !entity.isRemoved())
                remaining.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        });
        if(!remaining.isEmpty()) throw new IllegalStateException("Предыдущие сущности не удалены: "+remaining);
        return special;
    }
}
