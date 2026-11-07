package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.hbm.blockentity.turret.BlockEntityTurretMaxwell;

// a Maxwell's upgrades its beam's damage comes from (BeamTurrets.damage)
@Mixin(value = BlockEntityTurretMaxwell.class, remap = false)
public interface TurretMaxwellAccess {
    @Accessor("redLevel")
    int gunfeel$red();

    @Accessor("blackLevel")
    int gunfeel$black();
}
