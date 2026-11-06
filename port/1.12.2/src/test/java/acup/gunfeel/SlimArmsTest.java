package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.entity.player.EntityPlayer;

// The first-person arms on 1.12.2: slim or classic by the skin's model type, and every face of the drawn box (corners
// and texture) as vanilla's ModelPlayer has the arm and its sleeve.
class SlimArmsTest {
    @Test
    void theSkinsModelTypeSaysSlim() {
        assertTrue(PlayerSkins.slim("slim"));
        assertFalse(PlayerSkins.slim("default"));
        assertFalse(PlayerSkins.slim((String) null));
        // not a client player: no skin, classic arms
        assertFalse(PlayerSkins.slim((EntityPlayer) null));
        // a player without a skin of their own gets Steve or Alex by the UUID's hash
        assertFalse(PlayerSkins.slim(DefaultPlayerSkin.getSkinType(new UUID(0L, 0L))));
        assertTrue(PlayerSkins.slim(DefaultPlayerSkin.getSkinType(new UUID(0L, 1L))));
    }

    // the whole arm and sleeve (12 of 12 pixels) are ModelPlayer's, texture offsets and all
    @Test
    void wholeArmsAreModelPlayers() {
        for (boolean slim : new boolean[] {true, false}) {
            ModelPlayer model = new ModelPlayer(0F, slim);
            same(model.bipedRightArm.cubeList.get(0), Arms.quads(12, true, slim, false));
            same(model.bipedLeftArm.cubeList.get(0), Arms.quads(12, false, slim, false));
            same(model.bipedRightArmwear.cubeList.get(0), Arms.quads(12, true, slim, true));
            same(model.bipedLeftArmwear.cubeList.get(0), Arms.quads(12, false, slim, true));
            ModelBox arm = model.bipedRightArm.cubeList.get(0);
            assertEquals(slim ? 3F : 4F, arm.posX2 - arm.posX1);
            // the arms' rotation points are the ones drawArm moves to in x. In y 1.12.2's slim arm hangs half a pixel
            // lower; 26.2's PlayerModel has 2 for both, and the port draws 26.2's arm
            assertEquals(-5F, model.bipedRightArm.rotationPointX);
            assertEquals(5F, model.bipedLeftArm.rotationPointX);
            assertEquals(slim ? 2.5F : 2F, model.bipedRightArm.rotationPointY);
        }
    }

    // slim right arm, by hand from ModelBox: x -2..1, y -2..10, z -2..2; the front face (quad 4) u 44..47, v 20..32
    @Test
    void slimRightArmInPixels() {
        float[][] front = Arms.quads(12, true, true, false)[4];
        assertArrayEquals(new float[] {1F, -2F, -2F, 47F, 20F}, front[0]);
        assertArrayEquals(new float[] {-2F, -2F, -2F, 44F, 20F}, front[1]);
        assertArrayEquals(new float[] {-2F, 10F, -2F, 44F, 32F}, front[2]);
        assertArrayEquals(new float[] {1F, 10F, -2F, 47F, 32F}, front[3]);
        // the back (quad 5) starts 4 + 3 + 4 = 11 pixels to the right, the classic one 12
        assertEquals(51F, Arms.quads(12, true, true, false)[5][1][3]);
        assertEquals(52F, Arms.quads(12, true, false, false)[5][1][3]);
    }

    // a cut arm (its hand end, n of 12 pixels): the sides as a box n long with the texture moved down by 12 - n, the
    // caps from the box's usual place
    @Test
    void cutArmsTakeTheHandEndsTexture() {
        ModelBase base = new ModelBase() {};
        base.textureWidth = 64;
        base.textureHeight = 64;
        ModelRenderer r = new ModelRenderer(base);
        for (boolean slim : new boolean[] {true, false})
            for (boolean right : new boolean[] {true, false})
                for (boolean sleeve : new boolean[] {false, true})
                    for (int n = 1; n < 12; n++) {
                        // ModelPlayer's texture offsets: arm right (40, 16), left (32, 48); sleeve (40, 32), (48, 48)
                        int u = right ? 40 : sleeve ? 48 : 32, v = right ? (sleeve ? 32 : 16) : 48, w = slim ? 3 : 4;
                        float grow = sleeve ? 0.25F : 0F;
                        float x0 = right ? (slim ? -2F : -3F) : -1F, y0 = -2F + (12 - n);
                        float[][][] q = Arms.quads(n, right, slim, sleeve);
                        ModelBox sides = new ModelBox(r, u, v + 12 - n, x0, y0, -2F, w, n, 4, grow, false);
                        ModelBox caps = new ModelBox(r, u, v, x0, y0, -2F, w, n, 4, grow, false);
                        for (int i = 0; i < 6; i++) same(i == 2 || i == 3 ? caps : sides, i, q[i]);
                    }
    }

    private static void same(ModelBox box, float[][][] q) {
        for (int i = 0; i < 6; i++) same(box, i, q[i]);
    }

    private static void same(ModelBox box, int i, float[][] face) {
        TexturedQuad quad = box.quadList[i];
        for (int j = 0; j < 4; j++) {
            PositionTextureVertex p = quad.vertexPositions[j];
            float[] want = {(float) p.vector3D.x, (float) p.vector3D.y, (float) p.vector3D.z, p.texturePositionX * 64F,
                    p.texturePositionY * 64F};
            assertArrayEquals(want, face[j], 1e-5F, "quad " + i + " corner " + j);
        }
    }
}
