package acup.gunfeel.mixin;

import acup.gunfeel.MeshModelPose;
import acup.gunfeel.ModelBox;
import acup.gunfeel.ModelHitClient;
import acup.gunfeel.NtmMeshHits;
import acup.gunfeel.mixin.mc.VanillateEntityAccess;
import com.hbm.entity.mob.glyphid.EntityGlyphid;
import java.util.ArrayList;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Берём матрицы уже обновлённых GPU-частей. Анимацию и рендерер повторно не запускаем.
@Pseudo
@Mixin(targets = {"com.hbm.client.render.flywheel.GlyphidVisual", "com.hbm.client.render.flywheel.GlyphidNuclearVisual"}, remap = false)
public abstract class MixinGlyphidModelHits {
    @Shadow @Final private Matrix4f[] poses;
    @Shadow @Final private Vector3f interpolatedPosition;
    @Shadow private boolean shown;

    @Inject(method = "writeFrame", at = @At("RETURN"))
    private void gunfeel$pose(float partial, CallbackInfo ci) {
        if (!shown || !ModelHitClient.enabled() || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return;
        var entity = ((VanillateEntityAccess) (Object) this).gunfeel$entity();
        if (!(entity instanceof EntityGlyphid glyphid)) return;
        var mesh = GlyphidGeometryAccess.gunfeel$mesh();
        int[] ids = GlyphidGeometryAccess.gunfeel$ids(), armor = GlyphidGeometryAccess.gunfeel$armor();
        if (poses.length != ids.length || armor.length != ids.length) return;
        ArrayList<ModelBox> boxes = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            if (armor[i] >= 0 && (glyphid.armor() & 1 << armor[i]) == 0) continue;
            var group = mesh.groups[ids[i]];
            ModelBox box = MeshModelPose.box(group.name, group.bounds(), poses[i], -interpolatedPosition.x,
                    -interpolatedPosition.y, -interpolatedPosition.z);
            if (box != null) boxes.add(box);
        }
        NtmMeshHits.offer(glyphid, Mth.rotLerp(partial, glyphid.yBodyRotO, glyphid.yBodyRot), boxes);
    }
}
