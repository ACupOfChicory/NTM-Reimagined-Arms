package acup.gunfeel.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// port/continuum: the faces of a WavefrontObj group, for ObjGroup (MixinNtmMeshHits)
@Mixin(targets = "com.hbm.client.model.WavefrontObj$Group", remap = false)
public interface ObjGroupAccess {
    @Accessor("faces")
    List<int[][]> gunfeel$faces();
}
