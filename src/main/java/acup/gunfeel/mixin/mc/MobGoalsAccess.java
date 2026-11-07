package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;

// a mob's goals, which the mob AI takes over (AiAdapter)
@Mixin(Mob.class)
public interface MobGoalsAccess {
    @Accessor("goalSelector")
    GoalSelector gunfeel$goals();

    @Accessor("targetSelector")
    GoalSelector gunfeel$targets();
}
