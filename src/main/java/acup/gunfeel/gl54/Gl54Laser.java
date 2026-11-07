package acup.gunfeel.gl54;

import org.joml.Vector3f;

import com.hbm.client.render.RenderTextures;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.hbm.render.util.Vertices;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;

// The GL-54's laser (design.md): while the gun is aimed, a thin red beam leaves the rangefinder's lens (the point the model
// gives, Gl54Reflex.laser) and goes forward along the barrel, fading out over a couple of blocks. Two crossed ribbons so it
// shows from any side, full bright and clipped by nothing; the same in the hand and on the gun the player carries in the
// world. The idea is the laser module of TaCZ (GPL), written again here: nothing of its code or assets is used.
public final class Gl54Laser {
    // how far the beam reaches before it is gone, blocks; its width, blocks
    public static final double LENGTH_BLOCKS = 2.0, WIDTH_BLOCKS = 0.015;
    public static final int SEGMENTS = 8;
    static final int RGB = (255 << 16) | (30 << 8) | 20;
    // the alpha at the lens
    static final int ALPHA = 200;

    // dev (Gl54ReflexStand): off, nothing is drawn, for a frame to set the beam's against
    public static volatile boolean ON = true;

    private Gl54Laser() {
    }

    // The beam in the pose of the model; the length in model units is the blocks over what a unit comes to in the pose.
    public static void render(SubmitNodeCollector collector, PoseStack pose, ItemStack stack) {
        Gl54Reflex r = Gl54Reflex.get();
        if (!ON || !r.present() || !ItemGunBaseNT.getIsAiming(stack)) return;
        float scale = pose.last().pose().transformDirection(new Vector3f(0, 0, 1)).length();
        if (!(scale > 1e-6F)) return;
        float length = (float) (LENGTH_BLOCKS / scale), half = (float) (WIDTH_BLOCKS / scale / 2.0);
        double[] e = r.laser();
        collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(RenderTextures.WHITE), (p, vc) -> {
            for (int i = 0; i < SEGMENTS; i++) {
                float z0 = (float) e[2] + length * i / SEGMENTS, z1 = (float) e[2] + length * (i + 1) / SEGMENTS;
                int c0 = color(1.0 - (double) i / SEGMENTS), c1 = color(1.0 - (double) (i + 1) / SEGMENTS);
                // a flat ribbon and an upright one, each seen from both sides
                Vertices.emit(vc, p, (float) e[0] - half, (float) e[1], z0, c0, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0] + half, (float) e[1], z0, c0, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0] + half, (float) e[1], z1, c1, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0] - half, (float) e[1], z1, c1, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0] - half, (float) e[1], z1, c1, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0] + half, (float) e[1], z1, c1, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0] + half, (float) e[1], z0, c0, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0] - half, (float) e[1], z0, c0, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0], (float) e[1] - half, z0, c0, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0], (float) e[1] + half, z0, c0, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0], (float) e[1] + half, z1, c1, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0], (float) e[1] - half, z1, c1, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0], (float) e[1] - half, z1, c1, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0], (float) e[1] + half, z1, c1, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0], (float) e[1] + half, z0, c0, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
                Vertices.emit(vc, p, (float) e[0], (float) e[1] - half, z0, c0, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
            }
        });
    }

    // the colour at a share of the beam's strength (1 at the lens, 0 at the end)
    public static int color(double strength) {
        return ((int) Math.round(ALPHA * Math.max(0.0, strength)) << 24) | RGB;
    }
}
