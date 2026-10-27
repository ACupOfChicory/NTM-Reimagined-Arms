package acup.gunfeel;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

// Только устройство тестовой сцены. Отдельная часть составного моба может
// штатно исчезать без родителя; геометрия и попадания берутся общим алгоритмом.
final class MobStageContext {
    private MobStageContext() {}
    static void prepare(ServerLevel level,LivingEntity target) throws ReflectiveOperationException {
        if(!BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString().equals("alexscaves:boundroid_winch")) return;
        var headType=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("alexscaves:boundroid"));
        var head=(LivingEntity)headType.create(level,EntitySpawnReason.COMMAND);
        head.setPos(target.getX(),target.getY()-2,target.getZ());
        head.setNoGravity(true); head.setInvulnerable(true);
        if(head instanceof Mob mob) { mob.setNoAi(true); mob.setPersistenceRequired(); }
        level.addFreshEntity(head);
        target.getClass().getMethod("linkWithHead",Entity.class).invoke(target,head);
        head.getClass().getMethod("setWinchUUID",java.util.UUID.class).invoke(head,target.getUUID());
    }
}
