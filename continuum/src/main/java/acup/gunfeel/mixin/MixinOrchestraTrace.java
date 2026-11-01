package acup.gunfeel.mixin;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Orchestras;

import acup.gunfeel.TestLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;

// port/continuum: a trace's "sound" events, the gun sounds Continuum's orchestras play (the gun, its last animation and
// the animation timer's tick), to check them against NTM NEXT's cues and main's inspect changes. The orchestras play
// on the server only (timeline returns on the client), so the events are the server's side of the trace.
@Mixin(value = Orchestras.class, remap = false)
public abstract class MixinOrchestraTrace {
    @Inject(method = "play(Lcom/hbm/items/weapon/sedna/ItemGunBaseNT$LambdaContext;Ljava/util/function/Supplier;FF)V", at = @At("HEAD"))
    private static void gunfeel$trace(ItemGunBaseNT.LambdaContext ctx, Supplier<SoundEvent> sound, float volume, float pitch, CallbackInfo ci) {
        if (!TestLog.on(ctx.entity.level())) return;
        ItemStack stack = ctx.entity.getMainHandItem();
        int i = ctx.configIndex;
        TestLog.at(ctx.entity.level(), -1, "sound", "gun", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), "id", sound.get().location().toString(),
                "anim", String.valueOf(ItemGunBaseNT.getLastAnim(stack, i)), "timer", ItemGunBaseNT.getAnimTimer(stack, i));
    }
}
