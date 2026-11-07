package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.blockentity.machine.BlockEntityMachineAmmoPress;
import com.hbm.packet.PacketWire;
import com.hbm.packet.toserver.NbtControlPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;

// Client: the layouts an ammo press remembers, as entries of NTM's recipe selector after its own recipes (the selector is
// 4 columns by 3 rows of 18 px cells paged by 3, ScreenMachineAmmoPress; MixinAmmoPressScreen calls this). An entry's index
// in the press's selection is NTM's recipe count plus the layout's place, so NTM's own toggle and sync work on it as they do
// on a recipe. An entry: the magazine with its coloured marks; a tooltip with the order as names; Shift+click forgets it.
public final class PressScreenExtras {
    public static final int NOT_HIT = Integer.MIN_VALUE;
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("hbm", "textures/gui/processing/gui_ammo_press.png");

    private List<List<String>> seen;
    // the stored layouts the search lets through, by place
    private final List<Integer> shown = new ArrayList<>();

    public int shown() {
        return shown.size();
    }

    public boolean changed(BlockEntityMachineAmmoPress press) {
        return ((PressMemory) press).gunfeel$layouts() != seen;
    }

    // the entries the search term lets through (the same match as NTM's: the output's name)
    public void search(BlockEntityMachineAmmoPress press, String term, int recipes) {
        List<List<String>> layouts = ((PressMemory) press).gunfeel$layouts();
        seen = layouts;
        shown.clear();
        String lower = term.toLowerCase(Locale.ROOT);
        for (int k = 0; k < layouts.size(); k++)
            if (lower.isEmpty() || stack(layouts.get(k)).getHoverName().getString().toLowerCase(Locale.ROOT).contains(lower)) shown.add(k);
    }

    static ItemStack stack(List<String> layout) {
        return Gl54Items.magazine(new MagContents(layout));
    }

    // the entry at the mouse (its place in `shown`), -1 for none; `recipes` is how many of NTM's recipes the screen lists
    private int hit(int left, int top, int mx, int my, int index, int recipes) {
        for (int j = 0; j < shown.size(); j++) {
            int ind = recipes + j - index * 3;
            if (ind < 0 || ind >= 12) continue;
            int x = 16 + 18 * (ind / 3), y = 17 + 18 * (ind % 3);
            if (left + x <= mx && left + x + 18 > mx && top + y < my && top + y + 18 >= my) return j;
        }
        return -1;
    }

    // a click: NOT_HIT when it is not on an entry, else the screen's selection after it (a plain click selects or, on the
    // selected one, deselects; Shift+click asks the press to forget the layout and keeps the selection)
    public int click(BlockEntityMachineAmmoPress press, int left, int top, int mx, int my, int index, int recipes, boolean shift, int selection) {
        int j = hit(left, top, mx, my, index, recipes);
        if (j < 0) return NOT_HIT;
        int k = shown.get(j);
        CompoundTag data = new CompoundTag();
        int next = selection;
        if (shift) {
            data.putInt("gunfeelForget", k);
        } else {
            int clicked = PressSync.recipes() + k;
            next = selection != clicked ? clicked : -1;
            data.putInt("selection", next);
        }
        PacketWire.sendToServer(new NbtControlPayload(press.getBlockPos(), data));
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        return next;
    }

    // the labels' layer (the window's own corner is the origin)
    public void draw(GuiGraphicsExtractor g, BlockEntityMachineAmmoPress press, int index, int recipes, int selection) {
        List<List<String>> layouts = ((PressMemory) press).gunfeel$layouts();
        var font = Minecraft.getInstance().font;
        int recipeCount = PressSync.recipes();
        for (int j = 0; j < shown.size(); j++) {
            int ind = recipes + j - index * 3;
            if (ind < 0 || ind >= 12) continue;
            int k = shown.get(j);
            if (k >= layouts.size()) continue;
            int x = 16 + 18 * (ind / 3), y = 17 + 18 * (ind % 3);
            ItemStack out = stack(layouts.get(k));
            g.item(out, x + 1, y + 1);
            g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, selection == recipeCount + k ? 194.0F : 212.0F, 0.0F, 18, 18, 256, 256);
            g.pose().pushMatrix();
            g.pose().translate(x + 9, y + 9);
            g.pose().scale(0.5F, 0.5F);
            g.itemDecorations(font, out, 0, 0, "1");
            g.pose().popMatrix();
        }
        // the selected layout: each empty slot shows the grenade it takes, as NTM does for its recipes
        int sel = PressLayouts.stored(selection, recipeCount, layouts.size());
        if (sel < 0) return;
        List<String> layout = layouts.get(sel);
        for (int i = 0; i < layout.size() && i < PressLoading.GRENADES; i++) {
            if (!press.getItem(i).isEmpty()) continue;
            var h = Gl54Items.GRENADES.get(layout.get(i));
            if (h == null) continue;
            int x = 116 + 18 * (i % 3), y = 18 + 18 * (i / 3);
            g.item(new ItemStack(h.get()), x, y);
            g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, x, y, 18, 18, 256, 256, ARGB.white(0.5F));
        }
    }

    public void tooltip(GuiGraphicsExtractor g, int left, int top, int mx, int my, int index, int recipes) {
        int j = hit(left, top, mx, my, index, recipes);
        if (j < 0 || seen == null) return;
        int k = shown.get(j);
        if (k >= seen.size()) return;
        List<String> layout = seen.get(k);
        List<Component> lines = new ArrayList<>();
        lines.add(stack(layout).getHoverName());
        lines.add(Component.translatable("item.ntmgunfeel.gl54_mag.order").withColor(0xAAAAAA));
        int i = 0;
        while (i < layout.size()) {
            String key = layout.get(i);
            int run = 1;
            while (i + run < layout.size() && layout.get(i + run).equals(key)) run++;
            lines.add(Component.literal(run > 1 ? run + "× " : "").append(MagItem.kindName(key)).withColor(GrenadeKind.argbOf(key) & 0xFFFFFF));
            i += run;
        }
        lines.add(Component.translatable("ntmgunfeel.gl54_press.forget").withColor(0x777777));
        g.setComponentTooltipForNextFrame(Minecraft.getInstance().font, lines, mx, my);
    }
}
