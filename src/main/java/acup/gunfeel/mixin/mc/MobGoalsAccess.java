package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.GoalSelector;

// a mob's goals and its legs, which the mob AI takes over (AiAdapter)
@Mixin(Mob.class)
public interface MobGoalsAccess {
    @Accessor("goalSelector")
    GoalSelector gunfeel$goals();

    @Accessor("targetSelector")
    GoalSelector gunfeel$targets();

    @Accessor("moveControl")
    void gunfeel$setMoveControl(MoveControl<?> control);
}
