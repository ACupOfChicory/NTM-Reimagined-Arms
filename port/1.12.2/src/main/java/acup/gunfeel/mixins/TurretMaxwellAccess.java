package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.hbm.tileentity.turret.TileEntityTurretMaxwell;

// port: 26.2's TurretMaxwellAccess on NTM 1.12.2 CE's TileEntityTurretMaxwell.
// a Maxwell's upgrades its beam's damage comes from (BeamTurrets.damage)
@Mixin(value = TileEntityTurretMaxwell.class, remap = false)
public interface TurretMaxwellAccess {
    @Accessor("redLevel")
    int gunfeel$red();

    @Accessor("blackLevel")
    int gunfeel$black();
}
