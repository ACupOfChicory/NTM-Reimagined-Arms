package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.hbm.client.render.weapon.WeaponPose;
import com.hbm.client.render.weapon.WeaponRenderer;

import acup.gunfeel.mixin.WeaponPoseAccess;
import dev.golem.core.render.gem.Gem;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

// port/continuum: the gun's size and kick pivot for ModelRecoil without Arms (NTM NEXT's gun meshes). The box is the
// Gem declaration at rest, in the frame renderFirstPerson is handed: its length along Z is the size, its centre the
// pivot (main pivots on the right-hand grip, which comes with the arms).
public final class GunExtent {
    private static final Map<WeaponRenderer, float[]> BOXES = new IdentityHashMap<>();

    private GunExtent() {
    }

    // the gun length, pivot set to the box centre; 0 when the declaration draws nothing
    public static float size(WeaponRenderer renderer, ItemStack stack, Vector3f pivot) {
        float[] b = BOXES.computeIfAbsent(renderer, r -> box(r, stack));
        if (b == null || b.length == 0) return 0F;
        pivot.set((b[0] + b[3]) * 0.5F, (b[1] + b[4]) * 0.5F, (b[2] + b[5]) * 0.5F);
        return b[5] - b[2];
    }

    private static float[] box(WeaponRenderer renderer, ItemStack stack) {
        try {
            WeaponPose pose = new WeaponPose();
            ((WeaponPoseAccess) (Object) pose).gunfeel$begin(stack, false, OverlayTexture.NO_OVERLAY,
                    ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null, InteractionHand.MAIN_HAND);
            float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            Gem.extents(renderer.gem(), pose, new Matrix4f(), c -> {
                b[0] = Math.min(b[0], c.x()); b[1] = Math.min(b[1], c.y()); b[2] = Math.min(b[2], c.z());
                b[3] = Math.max(b[3], c.x()); b[4] = Math.max(b[4], c.y()); b[5] = Math.max(b[5], c.z());
            });
            if (b[0] > b[3]) return new float[0];
            GunFeel.LOGGER.info("[port] {} measured for the model kick: length {} centre {} {} {}", stack.getItem(), b[5] - b[2],
                    (b[0] + b[3]) * 0.5F, (b[1] + b[4]) * 0.5F, (b[2] + b[5]) * 0.5F);
            return b;
        } catch (RuntimeException e) {
            GunFeel.LOGGER.warn("Could not measure the gun of {}", stack.getItem(), e);
            return new float[0];
        }
    }

    public static void clear() {
        BOXES.clear();
    }
}
