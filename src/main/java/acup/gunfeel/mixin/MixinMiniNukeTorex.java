package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.entity.effect.EntityNukeTorex;

import acup.gunfeel.nuke.MiniNukeCloud;

// Client: a small nuclear blast's cloud (MiniNukeCloud) grows its shock ring as a bomb's, 1.5 blocks a tick, out to
// five times its radius (a bomb's: 150 ticks, 225 blocks), with puffs big and many enough to read as a ring going
// out over the ground (NTM's are the scale times 5 wide: specks of smoke at a Fat Mine's); it has no condensation
// rings (a bomb's form at world heights 130 and 170 whatever its size: 70 blocks over a mine), and is hot at first as
// a bomb's is (NTM's heat, cut to a whole number, is none under scale 0.142: grey from the start). Not applied: the
// bomb's ring and rings, a grey cloud.
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

    // the heat the puffs are coloured by: NTM's cut to a whole number is none for a small cloud (grey from the start)
    @Inject(method = "simulateTick(IZ)I", at = @At("RETURN"))
    private void gunfeel$heat(int age, boolean live, CallbackInfoReturnable<Integer> cir) {
        EntityNukeTorex self = (EntityNukeTorex) (Object) this;
        if (MiniNukeCloud.mini(self)) self.heat = MiniNukeCloud.heat(self.getScaleVal(), age, self.maxAge);
    }

    // the shock ring's puffs big enough to read as a ring going out over the ground (NTM's: the scale times 5 wide)
    @Redirect(method = "simulateTick(IZ)I", at = @At(value = "INVOKE",
            target = "Lcom/hbm/entity/effect/EntityNukeTorex$Cloudlet;setScale(FF)Lcom/hbm/entity/effect/EntityNukeTorex$Cloudlet;"))
    private EntityNukeTorex.Cloudlet gunfeel$shockSize(EntityNukeTorex.Cloudlet cloud, float wide, float high) {
        if (cloud.type == EntityNukeTorex.TorexType.SHOCK && MiniNukeCloud.mini((EntityNukeTorex) (Object) this))
            return cloud.setScale(MiniNukeCloud.shockWidth(wide), MiniNukeCloud.shockHeight(high));
        return cloud.setScale(wide, high);
    }

    @ModifyConstant(method = "simulateTick(IZ)I", constant = @Constant(intValue = 220))
    private int gunfeel$condensation(int ticks) {
        return MiniNukeCloud.mini((EntityNukeTorex) (Object) this) ? 0 : ticks;
    }
}
