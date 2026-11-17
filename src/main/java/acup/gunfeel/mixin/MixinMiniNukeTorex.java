package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.hbm.entity.effect.EntityNukeTorex;

import acup.gunfeel.nuke.MiniNukeCloud;

// Client: a small nuclear blast's cloud (MiniNukeCloud) grows its shock ring for a time and with puffs fitting its
// size, not a bomb's 150 ticks (225 blocks) at up to 100 puffs a tick, and has no condensation rings (a bomb's form
// at world heights 130 and 170 whatever its size: 70 blocks over a mine). Not applied: the bomb's ring and rings.
@Mixin(value = EntityNukeTorex.class, remap = false)
public abstract class MixinMiniNukeTorex {
    @ModifyConstant(method = "simulateTick(IZ)I", constant = @Constant(intValue = 150))
    private int gunfeel$shockTicks(int ticks) {
        EntityNukeTorex self = (EntityNukeTorex) (Object) this;
        return MiniNukeCloud.mini(self) ? MiniNukeCloud.shockTicks(self.getScaleVal()) : ticks;
    }

    @ModifyConstant(method = "simulateTick(IZ)I", constant = @Constant(intValue = 100))
    private int gunfeel$shockPuffs(int puffs) {
        EntityNukeTorex self = (EntityNukeTorex) (Object) this;
        return MiniNukeCloud.mini(self) ? MiniNukeCloud.shockPuffs(self.getScaleVal()) : puffs;
    }

    @ModifyConstant(method = "simulateTick(IZ)I", constant = @Constant(intValue = 220))
    private int gunfeel$condensation(int ticks) {
        return MiniNukeCloud.mini((EntityNukeTorex) (Object) this) ? 0 : ticks;
    }
}
