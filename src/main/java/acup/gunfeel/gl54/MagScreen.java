package acup.gunfeel.gl54;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

// The magazine window's picture, glued at run time from pieces of NTM's own dark crate GUI (hbm:textures/gui/storage/
// gui_crate_tungsten.png, 176 x 168 on a 256 sheet), so none of NTM's pixels are in this mod: 176 x 136, one row of 8 cells,
// centred, and a small "<" at the left, marking the magazine's mouth. Client only.
public final class MagScreen extends AbstractContainerScreen<MagMenu> {
    static final Identifier CRATE = Identifier.fromNamespaceAndPath("hbm", "textures/gui/storage/gui_crate_tungsten.png");
    public static final int W = 176, H = 136, LIGHT = 0xFFE0E0E0, MOUTH = 0xFFA0A0A0;
    // the "<" out of the mouth (x, y in the window)
    static final int[][] ARROW = {{10, 26}, {11, 25}, {11, 26}, {11, 27}, {12, 24}, {12, 26}, {12, 28}, {13, 26}, {14, 26}};

    public MagScreen(MagMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, W, H);
    }

    // the pieces: (source x, y, w, h) -> (x, y) in the window
    static final int[][] PIECES = {{0, 0, 176, 17, 0, 0}, {7, 17, 144, 18, 16, 17}, {0, 71, 176, 97, 0, 39}};

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        blit(g, PIECES[0]);
        for (int y = 17; y <= 38; y++) blit(g, new int[] {0, 71, 176, 1, 0, y});
        blit(g, PIECES[1]);
        blit(g, PIECES[2]);
        for (int[] p : ARROW) g.fill(leftPos + p[0], topPos + p[1], leftPos + p[0] + 1, topPos + p[1] + 1, MOUTH);
    }

    private void blit(GuiGraphicsExtractor g, int[] p) {
        g.blit(RenderPipelines.GUI_TEXTURED, CRATE, leftPos + p[4], topPos + p[5], (float) p[0], (float) p[1], p[2], p[3], 256, 256);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int xm, int ym) {
        g.text(font, title, 8, 6, LIGHT, false);
        g.text(font, playerInventoryTitle, 8, MagMenu.LABEL_INVENTORY_Y, LIGHT, false);
    }
}
