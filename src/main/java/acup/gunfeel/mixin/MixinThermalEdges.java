package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.client.render.ArmorOverheadRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BoxEdges;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

// NTM's thermal outlines: the box of each entity handed to the outline renderer as a shape that gives its 12 edges at
// once (BoxEdges), the same edges in the same order. Should NTM change, nothing here applies.
@Mixin(value = ArmorOverheadRenderer.class, remap = false)
public abstract class MixinThermalEdges {
    @org.spongepowered.asm.mixin.Unique
    private static volatile boolean gunfeel$failed;

    @WrapOperation(method = "submitThermalSight", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private static VoxelShape gunfeel$edges(AABB box, Operation<VoxelShape> original) {
        VoxelShape real = original.call(box);
        if (gunfeel$failed) return real;
        try {
            return BoxEdges.of(real);
        } catch (RuntimeException | LinkageError e) {
            gunfeel$failed = true;
            acup.gunfeel.DebugLog.LOGGER.info("[thermal] outline shortcut off, NTM's outlines are drawn the game's way: {}", e.toString());
            return real;
        }
    }
}
