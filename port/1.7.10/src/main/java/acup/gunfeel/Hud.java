package acup.gunfeel;

// port: copy of 26.2's Hud.java (0762086) for 1.7.10. The layout, sizes and colours are the same; the strings and the
// colour of the bar are in HudFormat. Drawing: Gui.drawRect, FontRenderer, RenderItem under the GL matrix (26.2:
// GuiGraphicsExtractor). NTM 1.7.10 draws its counter and bar from ItemGunBaseNT.renderHUD in
// RenderGameOverlayEvent.Pre; MixinGunHud skips those two components there and draws this once, on the HOTBAR element.
// The gun's silhouette (26.2's Silhouette, from the first-person model's geometry) is not here: the gun's own icon.

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineBelt;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

// TaCZ-style ammo HUD in the bottom-right corner, drawn instead of NTM's counter next to the hotbar:
//   [gun]  | 006  0015
//          |      [ammo]
//          | ======== durability
// One block per receiver (akimbo and multi-barrel guns get several, stacked upward).
public final class Hud {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0x66FFFFFF;
    private static final int GREY = 0xCCFFFFFF;
    private static final int LOW = 0xFFFF5555;
    private static RenderItem items;

    private Hud() {
    }

    public static void render(ScaledResolution res, EntityPlayer player, ItemStack stack, ItemGunBaseNT gun) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        InventoryPlayer inv = player.inventory;
        int right = res.getScaledWidth() - 8;
        int bottom = res.getScaledHeight() - 8;
        boolean gunDrawn = false;
        for (int c = gun.getConfigCount() - 1; c >= 0; c--) {
            GunConfig config = gun.getConfig(stack, c);
            Receiver[] receivers = config.getReceivers(stack);
            if (receivers == null) continue;
            for (int r = receivers.length - 1; r >= 0; r--) {
                IMagazine<?> mag = receivers[r].getMagazine(stack);
                if (mag == null) continue;
                float health = HudFormat.health(ItemGunBaseNT.getWear(stack, c), config.getDurability(stack));
                block(font, player, inv, stack, mag, health, right, bottom, !gunDrawn);
                gunDrawn = true;
                bottom -= 34;
            }
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private static void block(FontRenderer font, EntityPlayer player, InventoryPlayer inv, ItemStack stack,
            IMagazine<?> mag, float health, int right, int bottom, boolean drawGun) {
        int top = bottom - 30;
        String big, small = null;
        boolean low;
        ItemStack icon = mag.getIconForHUD(stack, player);
        if (mag instanceof MagazineSingleTypeBase) {
            MagazineSingleTypeBase single = (MagazineSingleTypeBase) mag;
            int amount = single.getAmount(stack, inv);
            int capacity = single.getCapacity(stack);
            big = HudFormat.big(amount);
            BulletConfig type = reserveType(single, stack, inv);
            small = HudFormat.small(AmmoLock.count(type, inv));
            low = HudFormat.low(amount, capacity);
            // an empty magazine, not locked, loads what is found first: its icon
            ItemStack next = AmmoLock.ammoStack(type);
            if (type != null && amount == 0 && next != null) icon = next;
        } else {
            // a belt off the inventory: an infinite ammo bag with its ammo counts 9999
            big = mag instanceof MagazineBelt && mag.getAmount(stack, inv) >= HudFormat.BELT_INFINITE ? HudFormat.INF
                    : mag.reportAmmoStateForHUD(stack, player);
            low = false;
        }

        // ammo icon and reserve, right-aligned
        // a fixed column (as wide as "0000"), so a short ∞ or a belt without reserve does not move the icon and padlock
        int smallW = Math.max(16, font.getStringWidth("0000"));
        int smallX = right - smallW;
        if (small != null) {
            // digits fill the column; a lone ∞ sits in its middle
            int sx = small.equals(HudFormat.INF) ? smallX + (smallW - font.getStringWidth(small)) / 2 : smallX;
            font.drawStringWithShadow(small, sx, top + 2, GREY);
        }
        if (icon != null) item(icon, smallX, top + 12);
        if (AmmoLock.locked(stack)) lock(smallX + 18, top + 17);

        // big magazine count at 2x, leading zeros dimmed
        int bigW = font.getStringWidth(big) * 2;
        int bigX = smallX - 4 - bigW;
        GL11.glPushMatrix();
        GL11.glTranslatef(bigX, top + 2, 0);
        GL11.glScalef(2F, 2F, 1F);
        int lead = HudFormat.lead(big, small != null);
        String zeros = big.substring(0, lead);
        int color = low ? LOW : WHITE;
        GL11.glEnable(GL11.GL_BLEND);
        if (!zeros.isEmpty()) font.drawStringWithShadow(zeros, 0, 0, DIM);
        font.drawStringWithShadow(big.substring(lead), font.getStringWidth(zeros), 0, color);
        GL11.glPopMatrix();

        // durability bar under the numbers
        int barL = bigX, barR = right, barY = top + 28;
        Gui.drawRect(barL, barY, barR, barY + 2, 0x80000000);
        int fillR = barL + Math.round((barR - barL) * health);
        Gui.drawRect(barL, barY, fillR, barY + 2, HudFormat.durabilityColor(health));

        // separator and gun icon
        int sepX = bigX - 6;
        Gui.drawRect(sepX, top, sepX + 1, bottom, 0xCCFFFFFF);
        if (drawGun) item(stack, sepX - 6 - 16, top + 7);
    }

    // port: an item at x, y as the hotbar draws one (26.2: g.item)
    private static void item(ItemStack s, int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        if (items == null) items = new RenderItem();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.enableGUIStandardItemLighting();
        items.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), s, x, y);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
    }

    // the ammo lock: a small grey padlock (5 x 7), with a shadow
    private static void lock(int x, int y) {
        // above the item icon (26.2: the next stratum)
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (int pass = 0; pass < 2; pass++) {
            int o = pass == 0 ? 1 : 0, c = pass == 0 ? 0x80000000 : 0xFFA0A0A0;
            Gui.drawRect(x + 1 + o, y + o, x + 4 + o, y + 1 + o, c); // shackle top
            Gui.drawRect(x + 1 + o, y + 1 + o, x + 2 + o, y + 3 + o, c);
            Gui.drawRect(x + 3 + o, y + 1 + o, x + 4 + o, y + 3 + o, c);
            Gui.drawRect(x + o, y + 3 + o, x + 5 + o, y + 7 + o, c); // body
        }
        Gui.drawRect(x + 2, y + 4, x + 3, y + 6, 0xFF505050); // keyhole
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // the ammo the reserve counts: the magazine's type, or for an empty one not locked what a reload finds first
    private static BulletConfig reserveType(MagazineSingleTypeBase mag, ItemStack gun, InventoryPlayer inv) {
        BulletConfig type = mag.getAmount(gun, inv) == 0 ? mag.getFirstConfig(gun, inv) : null;
        return type != null ? type : mag.getType(gun, inv);
    }
}
