package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.entity.effect.EntityNukeTorex;

import acup.gunfeel.nuke.MiniNukeCloud;

// Client: a small nuclear blast's cloud (MiniNukeCloud) grows its shock ring as a bomb's, 1.5 blocks a tick, out to
// three times its radius, where its waste ends (a bomb's: 150 ticks, 225 blocks), with puffs big and many enough to read as a ring going
// out over the ground at its edge, and behind it NTM's own (the scale times 5 wide, still and long lived), as many
// as before, out to four radii: smoke left lying over the ground the ring went over; it has no condensation
// rings (a bomb's form at world heights 130 and 170 whatever its size: 70 blocks over a mine), and is hot at first as
// a bomb's is (NTM's heat, cut to a whole number, is none under scale 0.142: grey from the start). Not applied: the
// bomb's ring and rings, a grey cloud.
@Mixin(value = EntityNukeTorex.class, remap = false)
public abstract class MixinMiniNukeTorex {
    // the tick simulated, the shock puffs made so far, and whether the one being made is the ring's
    @Unique
    private int gunfeel$age;
    @Unique
    private long gunfeel$puff;
    @Unique
    private boolean gunfeel$ring;

    @Inject(method = "simulateTick(IZ)I", at = @At("HEAD"))
    private void gunfeel$tick(int age, boolean live, CallbackInfoReturnable<Integer> cir) {
        gunfeel$age = age;
    }

    @Unique
    private int gunfeel$smoke() {
        EntityNukeTorex self = (EntityNukeTorex) (Object) this;
        return gunfeel$age < MiniNukeCloud.smokeTicks(self.getScaleVal()) ? MiniNukeCloud.smokePuffs(self.getScaleVal()) : 0;
    }

    @ModifyConstant(method = "simulateTick(IZ)I", constant = @Constant(intValue = 150))
    private int gunfeel$shockTicks(int ticks) {
        EntityNukeTorex self = (EntityNukeTorex) (Object) this;
        return MiniNukeCloud.mini(self) ? MiniNukeCloud.shockTicks(self.getScaleVal()) : ticks;
    }

    @ModifyConstant(method = "simulateTick(IZ)I", constant = @Constant(intValue = 100))
    private int gunfeel$shockPuffs(int puffs) {
        EntityNukeTorex self = (EntityNukeTorex) (Object) this;
        return MiniNukeCloud.mini(self) ? MiniNukeCloud.shockPuffs(self.getScaleVal()) + gunfeel$smoke() : puffs;
    }

    // the heat the puffs are coloured by: NTM's cut to a whole number is none for a small cloud (grey from the start)
    @Inject(method = "simulateTick(IZ)I", at = @At("RETURN"))
    private void gunfeel$heat(int age, boolean live, CallbackInfoReturnable<Integer> cir) {
        EntityNukeTorex self = (EntityNukeTorex) (Object) this;
        if (MiniNukeCloud.mini(self)) self.heat = MiniNukeCloud.heat(self.getScaleVal(), age, self.maxAge);
    }

    // the shock ring's puffs big enough to read as a ring going out over the ground (NTM's: the scale times 5 wide); the
    // smoke's NTM's
    @Redirect(method = "simulateTick(IZ)I", at = @At(value = "INVOKE",
            target = "Lcom/hbm/entity/effect/EntityNukeTorex$Cloudlet;setScale(FF)Lcom/hbm/entity/effect/EntityNukeTorex$Cloudlet;"))
    private EntityNukeTorex.Cloudlet gunfeel$shockSize(EntityNukeTorex.Cloudlet cloud, float wide, float high) {
        EntityNukeTorex self = (EntityNukeTorex) (Object) this;
        gunfeel$ring = false;
        if (cloud.type != EntityNukeTorex.TorexType.SHOCK || !MiniNukeCloud.mini(self)) return cloud.setScale(wide, high);
        gunfeel$ring = MiniNukeCloud.ringPuff(gunfeel$puff++, MiniNukeCloud.shockPuffs(self.getScaleVal()), gunfeel$smoke());
        return gunfeel$ring ? cloud.setScale(MiniNukeCloud.shockWidth(wide), MiniNukeCloud.shockHeight(high)) : cloud.setScale(wide, high);
    }

    // the shock ring's puffs going out with the ring and short lived: a band at its edge (the smoke's still, as NTM's)
    @Redirect(method = "simulateTick(IZ)I", at = @At(value = "INVOKE",
            target = "Lcom/hbm/entity/effect/EntityNukeTorex$Cloudlet;setMotion(D)Lcom/hbm/entity/effect/EntityNukeTorex$Cloudlet;"))
    private EntityNukeTorex.Cloudlet gunfeel$shockMotion(EntityNukeTorex.Cloudlet cloud, double mult) {
        EntityNukeTorex self = (EntityNukeTorex) (Object) this;
        if (cloud.type != EntityNukeTorex.TorexType.SHOCK || !gunfeel$ring || !MiniNukeCloud.mini(self)) return cloud.setMotion(mult);
        cloud.cloudletLife = MiniNukeCloud.shockLife();
        return cloud.setMotion(MiniNukeCloud.shockMotion(self.getScaleVal()));
    }

    @ModifyConstant(method = "simulateTick(IZ)I", constant = @Constant(intValue = 220))
    private int gunfeel$condensation(int ticks) {
        return MiniNukeCloud.mini((EntityNukeTorex) (Object) this) ? 0 : ticks;
    }
}
