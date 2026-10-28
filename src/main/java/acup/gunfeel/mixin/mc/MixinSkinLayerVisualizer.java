package acup.gunfeel.mixin.mc;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** GPU-дерево обычных кубов не исполняет подмену render у 3D Skin Layers. */
@Pseudo
@Mixin(targets="dev.engine_room.flywheel.api.visualization.VisualizerRegistry",remap=false)
public abstract class MixinSkinLayerVisualizer {
    @Inject(method="getVisualizer(Lnet/minecraft/world/entity/EntityType;)Ldev/engine_room/flywheel/api/visualization/EntityVisualizer;",at=@At("HEAD"),cancellable=true)
    private static void gunfeel$skinLayers(EntityType<?> type,CallbackInfoReturnable<Object> ci) {
        if((type==EntityTypes.PLAYER || type==EntityTypes.MANNEQUIN) && net.neoforged.fml.ModList.get().isLoaded("skinlayers3d")) ci.setReturnValue(null);
    }
}
