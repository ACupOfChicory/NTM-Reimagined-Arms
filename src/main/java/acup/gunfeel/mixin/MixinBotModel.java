package acup.gunfeel.mixin;

import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.render.RenderHumanoidMob;
import com.hbm.main.ResourceManager;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.ModelPoseCapture;
import acup.gunfeel.ai.client.BotModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

// The FBI agent and the test dummy, the mobs the mob AI drives, get the biped with the arms and legs of two boxes
// (BotModel); NTM bakes the one model for them and its ghost, which keeps vanilla's. NTM gives them no armour layer, so
// what they wear (an agent: SECURITY, or a glass block and PAA) was not drawn: they get vanilla's, its models cut as the
// body (a sleeve bends at the elbow). The glass on the head is drawn by the head layer HumanoidMobRenderer has. The
// renderer is made once, when the game loads its renderers.
@Mixin(value = RenderHumanoidMob.class, remap = false)
public abstract class MixinBotModel extends HumanoidMobRenderer<Mob, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
    private MixinBotModel(EntityRendererProvider.Context context) {
        super(context, null, 0F);
    }

    @Unique
    private static boolean gunfeel$bot(Identifier texture) {
        return texture.equals(ResourceManager.fbi_tex) || texture.equals(ResourceManager.dummy_tex);
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/HumanoidModel;<init>(Lnet/minecraft/client/model/geom/ModelPart;Ljava/util/function/Function;)V"),
            index = 0, require = 1, allow = 1)
    private static ModelPart gunfeel$split(ModelPart root, @Local(argsOnly = true) Identifier texture) {
        return gunfeel$bot(texture) ? BotModel.split(root, ModelPoseCapture.ACCESS) : root;
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void gunfeel$armour(EntityRendererProvider.Context context, Identifier texture, Function<Identifier, RenderType> type,
            CallbackInfo ci) {
        if (!gunfeel$bot(texture)) return;
        ArmorModelSet<HumanoidModel<HumanoidRenderState>> armour = ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(),
                root -> new HumanoidModel<>(BotModel.split(root, ModelPoseCapture.ACCESS)));
        addLayer(new HumanoidArmorLayer<>(this, armour, context.getEquipmentRenderer()));
    }
}
