package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityChemical;

import acup.gunfeel.gas.ChemClouds;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Server: a chemical thrower's gas blob that goes (its age up, or into a block) leaves its gas to ChemClouds, which lets
// it out as a cloud with the rest of its stream. The blob's own work while it flies is NTM's.
@Mixin(value = EntityChemical.class, remap = false)
public abstract class MixinChemthrowerCloud {
    @Inject(method = "tick()V", at = @At(value = "INVOKE", target = "Lcom/hbm/entity/projectile/EntityChemical;discard()V"))
    private void gunfeel$aged(CallbackInfo ci) {
        ChemClouds.blobEnded((EntityChemical) (Object) this, null);
    }

    @Inject(method = "onImpact(Lnet/minecraft/world/phys/HitResult;)V",
            at = @At(value = "INVOKE", target = "Lcom/hbm/entity/projectile/EntityChemical;discard()V"))
    private void gunfeel$hit(HitResult hit, CallbackInfo ci) {
        Vec3 at = hit.getLocation();
        // out of the face it hit, so a blob in a wall leaves its gas in the room and not in the stone
        if (hit instanceof BlockHitResult b) at = at.add(b.getDirection().getStepX() * 0.1, b.getDirection().getStepY() * 0.1, b.getDirection().getStepZ() * 0.1);
        ChemClouds.blobEnded((EntityChemical) (Object) this, at);
    }
}
