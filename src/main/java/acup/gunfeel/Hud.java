package acup.gunfeel;

import org.joml.Matrix3x2fStack;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

// TaCZ-style ammo HUD in the bottom-right corner, drawn instead of NTM's counter next to the hotbar:
//   [gun]  | 006  0015
//          |      [ammo]
//          | ======== durability
// One block per receiver (akimbo and multi-barrel guns get several, stacked upward).
public final class Hud {
    private static final int GREY = 0xCCFFFFFF;
    private static final int H_INTS = acup.gunfeel.host.NtmGuns.H_INTS, H_FLOATS = acup.gunfeel.host.NtmGuns.H_FLOATS,
            H_OBJECTS = acup.gunfeel.host.NtmGuns.H_OBJECTS, H_KIND = acup.gunfeel.host.NtmGuns.H_KIND,
            H_AMOUNT = acup.gunfeel.host.NtmGuns.H_AMOUNT, H_CAPACITY = acup.gunfeel.host.NtmGuns.H_CAPACITY,
            H_RESERVE = acup.gunfeel.host.NtmGuns.H_RESERVE, H_LOCKED = acup.gunfeel.host.NtmGuns.H_LOCKED,
            H_WEAR = acup.gunfeel.host.NtmGuns.H_WEAR, H_DURABILITY = acup.gunfeel.host.NtmGuns.H_DURABILITY,
            H_ICON = acup.gunfeel.host.NtmGuns.H_ICON, H_GL54 = acup.gunfeel.host.NtmGuns.H_GL54;

    private Hud() {
    }

    public static void render(GuiGraphicsExtractor g, LocalPlayer player, ItemStack stack, ItemGunBaseNT gun) {
        ClientSmoke.hudDrawn();
        Font font = Minecraft.getInstance().font;
        int right = g.guiWidth() - 8;
        int bottom = g.guiHeight() - 8;
        int n = HudBase.read(EntityBody.of(player), acup.gunfeel.host.Body.MAIN_HAND);
        for (int i = 0; i < n; i++) {
            block(g, font, player, stack, HudBase.block(i), right, bottom);
            bottom -= 34;
        }
    }

    // NTM's reads of the HUD (NtmGuns.hudBlocks), in the order render and block made them before the port; the HUD's
    // system's class (Feature.HUD), so its calls of NTM's count there. Nothing of Hud's own: a server's stand reads
    // through it (AiCheckKit) without loading the drawing
    static final class Reads {
        private Reads() {
        }

        static int hudBlocks(acup.gunfeel.host.Body holder, int hand, int[] ints, float[] floats, Object[] objects, String[] texts) {
            if (!(EntityBody.entity(holder) instanceof net.minecraft.world.entity.player.Player player)) return 0;
            ItemStack stack = hand == acup.gunfeel.host.Body.MAIN_HAND ? player.getMainHandItem() : player.getOffhandItem();
            if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return 0;
            Inventory inv = player.getInventory();
            int n = 0;
            for (int c = gun.getConfigCount() - 1; c >= 0; c--) {
                GunConfig config = gun.getConfig(stack, c);
                Receiver[] receivers = config.getReceivers(stack);
                if (receivers == null) continue;
                for (int r = receivers.length - 1; r >= 0; r--) {
                    IMagazine<?> mag = receivers[r].getMagazine(stack);
                    if (mag == null) continue;
                    if (n < texts.length) {
                        floats[n * H_FLOATS + H_WEAR] = ItemGunBaseNT.getWear(stack, c);
                        floats[n * H_FLOATS + H_DURABILITY] = config.getDurability(stack);
                        block(player, inv, stack, mag, n, ints, objects, texts);
                    }
                    n++;
                }
            }
            return n;
        }

        private static void block(net.minecraft.world.entity.player.Player player, Inventory inv, ItemStack stack, IMagazine<?> mag, int n, int[] ints,
                Object[] objects, String[] texts) {
            int k = n * H_INTS, o = n * H_OBJECTS;
            ItemStack icon = mag.getIconForHUD(stack, player);
            objects[o + H_GL54] = null;
            texts[n] = null;
            if (mag instanceof acup.gunfeel.gl54.GrenadeMagazine grenades) {
                // the GL-54: the number is the barrel and the magazine in the colour of the next grenade, the reserve the
                // grenades R would load, a bar for a reload; no red (gl54/Gl54Hud)
                ints[k + H_KIND] = acup.gunfeel.host.NtmGuns.HUD_GL54;
                objects[o + H_GL54] = glModel(grenades, stack, player, inv);
                if (grenades.getAmount(stack, inv) == 0) icon = acup.gunfeel.gl54.Gl54Items.magazine(new acup.gunfeel.gl54.MagContents(grenades.wanted(stack, inv)));
            } else if (mag instanceof MagazineSingleTypeBase single) {
                ints[k + H_KIND] = acup.gunfeel.host.NtmGuns.HUD_SINGLE;
                int amount = single.getAmount(stack, inv);
                ints[k + H_AMOUNT] = amount;
                ints[k + H_CAPACITY] = single.getCapacity(stack);
                BulletConfig type = reserveType(single, stack, inv);
                ints[k + H_RESERVE] = AmmoLock.count(type, inv);
                // an empty magazine, not locked, loads what is found first: its icon
                if (type != null && amount == 0 && type.ammoStack() != null && !type.ammoStack().isEmpty()) icon = type.ammoStack();
            } else if (mag instanceof com.hbm.items.weapon.sedna.mags.MagazineBelt && mag.getAmount(stack, inv) >= 9999) {
                // a belt off the inventory: an infinite ammo bag with its ammo counts 9999
                ints[k + H_KIND] = acup.gunfeel.host.NtmGuns.HUD_BELT_INFINITE;
            } else {
                ints[k + H_KIND] = acup.gunfeel.host.NtmGuns.HUD_OTHER;
                texts[n] = mag.reportAmmoStateForHUD(stack, player);
            }
            objects[o + H_ICON] = icon;
            ints[k + H_LOCKED] = AmmoLock.locked(stack) ? 1 : 0;
        }

        // the GL-54's numbers from the gun in the hand and the inventory
        private static acup.gunfeel.gl54.Gl54Hud.Model glModel(acup.gunfeel.gl54.GrenadeMagazine mag, ItemStack stack, net.minecraft.world.entity.player.Player player, Inventory inv) {
            var gun = acup.gunfeel.gl54.GrenadeMagazine.gun(stack);
            int reserve = acup.gunfeel.gl54.MagLogic.reserve(acup.gunfeel.gl54.GrenadeType.magazines(inv), acup.gunfeel.gl54.GrenadeType.counts(inv), mag.wanted(stack, inv));
            float frac = acup.gunfeel.gl54.Gl54Gun.reloadFrac(com.hbm.render.anim.HbmAnimations.getGunAnimation(0),
                    com.hbm.render.anim.HbmAnimations.getGunAnimationTimer(0), ItemGunBaseNT.getState(stack, 0), mag.getAmountBeforeReload(stack) <= 0);
            return acup.gunfeel.gl54.Gl54Hud.model(gun, reserve, frac);
        }

        // the ammo the reserve counts: the magazine's type, or for an empty one not locked what a reload finds first
        private static BulletConfig reserveType(MagazineSingleTypeBase mag, ItemStack gun, Inventory inv) {
            BulletConfig type = mag.getAmount(gun, inv) == 0 ? mag.getFirstConfig(gun, inv) : null;
            return type != null ? type : mag.getType(gun, inv);
        }
    }

    private static void block(GuiGraphicsExtractor g, Font font, LocalPlayer player, ItemStack stack, HudBase.Block b,
            int right, int bottom) {
        if (HudShots.skip == 2) return;
        int top = bottom - 30;
        String big = b.big, small = b.small;
        ItemStack icon = (ItemStack) b.icon;
        acup.gunfeel.gl54.Gl54Hud.Model gl = b.gl;
        boolean drawGun = b.drawGun;
        float health = b.health;

        // ammo icon and reserve, right-aligned
        // a fixed column (as wide as "0000"), so a short ∞ or a belt without reserve does not move the icon and padlock
        int smallW = Math.max(16, font.width("0000"));
        int smallX = right - smallW;
        if (small != null) {
            // digits fill the column; a lone ∞ sits in its middle
            int sx = small.equals("\u221E") ? smallX + (smallW - font.width(small)) / 2 : smallX;
            g.text(font, small, sx, top + 2, GREY, true);
        }
        if (icon != null && !icon.isEmpty()) g.item(icon, smallX, top + 12);
        if (b.locked) lock(g, smallX + 18, top + 17);

        // big magazine count at 2x, leading zeros dimmed
        int bigW = font.width(big) * 2;
        int bigX = smallX - 4 - bigW;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(bigX, top + 2);
        pose.scale(2F, 2F);
        int lead = b.lead;
        String zeros = big.substring(0, lead);
        int color = b.color, dim = b.dim;
        if (!zeros.isEmpty()) g.text(font, zeros, 0, 0, dim, true);
        g.text(font, big.substring(lead), font.width(zeros), 0, color, true);
        pose.popMatrix();

        // durability bar under the numbers
        int barL = bigX, barR = right, barY = top + 28;
        g.fill(barL, barY, barR, barY + 2, 0x80000000);
        if (gl != null && gl.bar() >= 0F) { // reloading: the bar of the reload instead of the durability
            g.fill(barL, barY, barL + Math.round((barR - barL) * gl.bar()), barY + 2, acup.gunfeel.gl54.Gl54Hud.BAR_COLOR);
        } else {
            int fillR = barL + Math.round((barR - barL) * health);
            g.fill(barL, barY, fillR, barY + 2, HudColors.durabilityColor(health));
        }
        // the barrel's heat (acup.gunfeel.heat.Overheat, the M2 and the AM-180) just under it while warm: yellow, to red as
        // its effects come on
        float heat = acup.gunfeel.heat.Overheat.heat(stack, player.level().getGameTime());
        if (heat > 0.02F) {
            int heatY = barY + 3;
            g.fill(barL, heatY, barR, heatY + 2, 0x80000000);
            g.fill(barL, heatY, barL + Math.round((barR - barL) * heat), heatY + 2, HudColors.heatColor(heat));
        }
        // a mixed magazine: its order behind the barrel's grenade as coloured dots (2 x 2, 3 px apart)
        if (gl != null) for (int i = 0; i < gl.dots().length; i++) g.fill(bigX + i * 3, top + 21, bigX + i * 3 + 2, top + 23, gl.dots()[i]);

        // separator and gun icon
        int sepX = bigX - 6;
        g.fill(sepX, top, sepX + 1, bottom, 0xCCFFFFFF);
        if (drawGun && HudShots.skip != 1) {
            Identifier silhouette = Silhouette.get(stack.getItem());
            if (silhouette != null) {
                int w = 72, h = w * Silhouette.H / Silhouette.W;
                g.blit(RenderPipelines.GUI_TEXTURED, silhouette, sepX - 6 - w, top + (30 - h) / 2, 0F, 0F, w, h,
                        Silhouette.W, Silhouette.H, Silhouette.W, Silhouette.H);
            } else {
                g.item(stack, sepX - 6 - 16, top + 7);
            }
        }
        // LOW AMMO blinking under the silhouette instead of the red number (this gun only)
        if (gl != null && gl.lowAmmo() && drawGun && acup.gunfeel.gl54.Gl54Hud.blinkOn(lowClock())) {
            String text = net.minecraft.client.resources.language.I18n.get("ntmgunfeel.gl54.low_ammo");
            g.text(font, text, sepX - 6 - 72 + 36 - font.width(text) / 2, top + 28, acup.gunfeel.gl54.Gl54Hud.LOW_COLOR, true);
        }
    }

    // the clock of the blink; a stand sets it (HudShots) to take a frame in a known half
    static volatile long lowClockFixed = -1L;

    private static long lowClock() {
        return lowClockFixed >= 0 ? lowClockFixed : net.minecraft.util.Util.getMillis();
    }

    // the ammo lock: a small grey padlock (5 x 7), with a shadow
    private static void lock(GuiGraphicsExtractor g, int x, int y) {
        g.nextStratum(); // above the item icon
        for (int pass = 0; pass < 2; pass++) {
            int o = pass == 0 ? 1 : 0, c = pass == 0 ? 0x80000000 : 0xFFA0A0A0;
            g.fill(x + 1 + o, y + o, x + 4 + o, y + 1 + o, c); // shackle top
            g.fill(x + 1 + o, y + 1 + o, x + 2 + o, y + 3 + o, c);
            g.fill(x + 3 + o, y + 1 + o, x + 4 + o, y + 3 + o, c);
            g.fill(x + o, y + 3 + o, x + 5 + o, y + 7 + o, c); // body
        }
        g.fill(x + 2, y + 4, x + 3, y + 6, 0xFF505050); // keyhole
    }

    // yellow below the heat where it starts to tell, to red at the top
}
