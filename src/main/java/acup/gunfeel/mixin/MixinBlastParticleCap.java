package acup.gunfeel.mixin;

import java.util.List;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import acup.gunfeel.BlastParticles;
import net.minecraft.core.BlockPos;

// NTM's standard blast effect on the client: its loop puffing and smoking at every block of the blast goes through at
// most BlastParticles.MAX of them. Should NTM change, nothing here applies and the effect is NTM's.
@Mixin(targets = "com.hbm.packet.toclient.ClientParticlePayloadHandlers", remap = false)
public abstract class MixinBlastParticleCap {
    @ModifyExpressionValue(method = "handle(Lcom/hbm/packet/toclient/ExplosionStandardPayload;)V",
            at = @At(value = "FIELD", target = "Lcom/hbm/packet/toclient/ExplosionStandardPayload;blocks:Ljava/util/List;", opcode = Opcodes.GETFIELD))
    private static List<BlockPos> gunfeel$cap(List<BlockPos> blocks) {
        return BlastParticles.cap(blocks);
    }
}
