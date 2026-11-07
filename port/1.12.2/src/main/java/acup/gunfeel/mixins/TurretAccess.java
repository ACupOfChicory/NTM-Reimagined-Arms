package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.tileentity.turret.TileEntityTurretBaseNT;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

// port: what BeamTurrets asks of a turret in 1.12.2 CE. NTM's TileEntityTurretBaseNT implements OpenComputers' interfaces,
// which the port does not compile against: javac cannot look up its methods, so they are called through this
@Mixin(value = TileEntityTurretBaseNT.class, remap = false)
public interface TurretAccess {
    @Invoker("getTurretPos")
    Vec3d gunfeel$pivot();

    @Invoker("getEntityPos")
    Vec3d gunfeel$middle(Entity e);

    @Invoker("getFirstConfigLoaded")
    BulletConfig gunfeel$round();
}
