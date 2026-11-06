package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.compat.PunchyMovesCompat;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

@Pseudo
@Mixin(targets = "punchymoves.client.PlayerAnimationEngine", remap = false)
public abstract class MixinPunchyMovesEngine {
    private static final float[] ARMS = new float[12];
    private static final boolean[] VISIBLE = new boolean[2];
    private static PlayerModel armModel;
    private static boolean armsSaved;

    @Inject(method = "apply", at = @At("HEAD"), require = 0)
    private static void gunfeel$saveGunArms(AbstractClientPlayer player, PlayerModel model, AvatarRenderState state, CallbackInfo ci) {
        armsSaved = PunchyMovesCompat.holdsGun(player);
        if (!armsSaved) return;
        armModel = model;
        save(model.rightArm, 0);
        save(model.leftArm, 6);
        VISIBLE[0] = model.rightArm.visible;
        VISIBLE[1] = model.leftArm.visible;
    }

    @Inject(method = "apply", at = @At("RETURN"), require = 0)
    private static void gunfeel$restoreGunArms(AbstractClientPlayer player, PlayerModel model, AvatarRenderState state, CallbackInfo ci) {
        if (!armsSaved || armModel != model || !PunchyMovesCompat.holdsGun(player)) {
            armsSaved = false;
            return;
        }
        load(model.rightArm, 0);
        load(model.leftArm, 6);
        model.rightArm.visible = VISIBLE[0];
        model.leftArm.visible = VISIBLE[1];
        armsSaved = false;
    }

    private static void save(ModelPart part, int at) {
        ARMS[at] = part.x;
        ARMS[at + 1] = part.y;
        ARMS[at + 2] = part.z;
        ARMS[at + 3] = part.xRot;
        ARMS[at + 4] = part.yRot;
        ARMS[at + 5] = part.zRot;
    }

    private static void load(ModelPart part, int at) {
        part.x = ARMS[at];
        part.y = ARMS[at + 1];
        part.z = ARMS[at + 2];
        part.xRot = ARMS[at + 3];
        part.yRot = ARMS[at + 4];
        part.zRot = ARMS[at + 5];
    }

    @Inject(method = "avatarItemPose", at = @At("HEAD"), cancellable = true, require = 0)
    private static void gunfeel$keepGunItemPose(AbstractClientPlayer player, String hand, CallbackInfoReturnable<Object> cir) {
        if (!PunchyMovesCompat.holdsGun(player)) return;
        Object identity = PunchyMovesCompat.identityItemPose();
        if (identity != null) cir.setReturnValue(identity);
    }
}
