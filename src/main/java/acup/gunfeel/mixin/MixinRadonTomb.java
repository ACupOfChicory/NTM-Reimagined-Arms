package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.blocks.gas.BlockGasRadonTomb;

import acup.gunfeel.EuphImmunity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

// Euphemium's invulnerability (EuphImmunity): the tomb's radon gas takes it off while the wearer is in it.
@Mixin(value = BlockGasRadonTomb.class, remap = false)
public abstract class MixinRadonTomb {
    @Inject(method = "entityInside", at = @At("HEAD"), require = 1)
    private void gunfeel$radon(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean fromMove, CallbackInfo ci) {
        if (!level.isClientSide() && entity instanceof net.minecraft.world.entity.player.Player) EuphImmunity.inRadon(entity, level.getGameTime());
    }
}
