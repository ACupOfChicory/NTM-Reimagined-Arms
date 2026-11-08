package acup.gunfeel.mixin.mc;

import java.util.Set;

import acup.gunfeel.ai.bench.PathCount;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// the mob AI's stand (acup.gunfeel.ai.bench.AiBench): every search for a path, counted only while the stand measures
@Mixin(PathFinder.class)
public abstract class MixinPathSearchCount {
    @Inject(method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"))
    private void gunfeel$search(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float range, int accuracy,
            float searchDepth, CallbackInfoReturnable<Path> ci) {
        if (PathCount.on) PathCount.searches++;
    }
}
