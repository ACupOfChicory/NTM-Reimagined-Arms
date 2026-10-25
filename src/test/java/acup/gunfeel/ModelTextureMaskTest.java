package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.npc.VillagerModel;
import org.junit.jupiter.api.Test;

class ModelTextureMaskTest {
    @Test void transparentBaseHatIsNotVillagerBody() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/minecraft/textures/entity/villager/villager.png")) {
            assertNotNull(stream);
            var image = javax.imageio.ImageIO.read(stream);
            int w = image.getWidth(), h = image.getHeight();
            var mask = ModelTextureMask.alpha(w, h, image.getRGB(0, 0, w, h, null, 0, w));
            var root = LayerDefinition.create(VillagerModel.createBodyModel(), 64, 64).bakeRoot();
            int[] checked = {0, 0};
            root.visit(new PoseStack(), (pose, name, index, cube) -> {
                if (name.endsWith("/hat") || name.endsWith("/hat_rim")) {
                    assertFalse(ModelTextureMask.visible(cube, mask), name);
                    checked[0]++;
                } else if (name.endsWith("/head")) {
                    assertTrue(ModelTextureMask.visible(cube, mask), name);
                    checked[1]++;
                }
            });
            assertTrue(checked[0] > 0);
            assertTrue(checked[1] > 0);
        }
    }

    @Test void alphaEdgesDoNotPickPixelsOutsideTheFace() {
        var mask = ModelTextureMask.alpha(2, 2, new int[] {0xFFFFFFFF, 0, 0, 0});
        assertTrue(mask.any(0, 0, .5F, .5F));
        assertFalse(mask.any(.5F, 0, 1, .5F));
        assertFalse(mask.any(0, .5F, .5F, 1));
    }
}
