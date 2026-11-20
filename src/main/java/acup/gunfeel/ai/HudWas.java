package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import acup.gunfeel.AmmoLock;
import acup.gunfeel.HudColors;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// The stand's "before" for AiCheckKit's hud: Hud.render's and Hud.block's reads and arithmetic as they were before
// NtmGuns.hudBlocks and HudBase (2026-11-20), their draws left out (the heat bar's Overheat.heat, a draw's own, too), on
// a player given as the client's; dev only (NtmApiTest.DEV_ONLY), so its calls of NTM's are no system's
final class HudWas {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0x66FFFFFF;
    private static final int LOW = 0xFFFF5555;
    private static final int INFINITE = AmmoLock.INFINITE;

    private HudWas() {
    }

    // a block as it was drawn: the words and numbers, and the icon
    record Block(String big, String small, boolean low, float health, boolean drawGun, int lead, int color, int dim,
            boolean locked, ItemStack icon, acup.gunfeel.gl54.Gl54Hud.Model gl) {
    }

    static List<Block> render(Player player, ItemStack stack, ItemGunBaseNT gun) {
        List<Block> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        boolean gunDrawn = false;
        for (int c = gun.getConfigCount() - 1; c >= 0; c--) {
            GunConfig config = gun.getConfig(stack, c);
            Receiver[] receivers = config.getReceivers(stack);
            if (receivers == null) continue;
            for (int r = receivers.length - 1; r >= 0; r--) {
                IMagazine<?> mag = receivers[r].getMagazine(stack);
                if (mag == null) continue;
                float wear = ItemGunBaseNT.getWear(stack, c);
                float dura = config.getDurability(stack);
                float health = dura > 0 ? Mth.clamp(1F - wear / dura, 0F, 1F) : 1F;
                out.add(block(player, inv, stack, mag, health, !gunDrawn));
                gunDrawn = true;
            }
        }
        return out;
    }

    private static Block block(Player player, Inventory inv, ItemStack stack, IMagazine<?> mag, float health, boolean drawGun) {
        String big, small = null;
        boolean low;
        ItemStack icon = mag.getIconForHUD(stack, player);
        acup.gunfeel.gl54.Gl54Hud.Model gl = null;
        if (mag instanceof acup.gunfeel.gl54.GrenadeMagazine grenades) {
            gl = glModel(grenades, stack, player, inv);
            big = gl.big();
            small = gl.small();
            low = false;
            if (grenades.getAmount(stack, inv) == 0) icon = acup.gunfeel.gl54.Gl54Items.magazine(new acup.gunfeel.gl54.MagContents(grenades.wanted(stack, inv)));
        } else if (mag instanceof MagazineSingleTypeBase single) {
            int amount = single.getAmount(stack, inv);
            int capacity = single.getCapacity(stack);
            big = String.format("%03d", Math.min(amount, 999));
            BulletConfig type = reserveType(single, stack, inv);
            int reserve = AmmoLock.count(type, inv);
            small = reserve == INFINITE ? "∞" : String.format("%04d", Math.min(reserve, 9999));
            low = capacity > 0 && amount <= Math.max(1, capacity / 4);
            if (type != null && amount == 0 && type.ammoStack() != null && !type.ammoStack().isEmpty()) icon = type.ammoStack();
        } else {
            big = mag instanceof com.hbm.items.weapon.sedna.mags.MagazineBelt && mag.getAmount(stack, inv) >= 9999 ? "∞"
                    : mag.reportAmmoStateForHUD(stack, player);
            low = false;
        }
        boolean locked = AmmoLock.locked(stack);
        int lead = 0;
        if (small != null) {
            while (lead < big.length() - 1 && big.charAt(lead) == '0') lead++;
        }
        int color = gl != null ? gl.color() : low ? LOW : WHITE;
        int dim = DIM;
        if (gl != null && gl.dim()) {
            color = HudColors.faint(color);
            dim = HudColors.faint(dim);
        }
        return new Block(big, small, low, health, drawGun, lead, color, dim, locked, icon, gl);
    }

    private static acup.gunfeel.gl54.Gl54Hud.Model glModel(acup.gunfeel.gl54.GrenadeMagazine mag, ItemStack stack, Player player, Inventory inv) {
        var gun = acup.gunfeel.gl54.GrenadeMagazine.gun(stack);
        int reserve = acup.gunfeel.gl54.MagLogic.reserve(acup.gunfeel.gl54.GrenadeType.magazines(inv), acup.gunfeel.gl54.GrenadeType.counts(inv), mag.wanted(stack, inv));
        float frac = acup.gunfeel.gl54.Gl54Gun.reloadFrac(com.hbm.render.anim.HbmAnimations.getGunAnimation(0),
                com.hbm.render.anim.HbmAnimations.getGunAnimationTimer(0), ItemGunBaseNT.getState(stack, 0), mag.getAmountBeforeReload(stack) <= 0);
        return acup.gunfeel.gl54.Gl54Hud.model(gun, reserve, frac);
    }

    private static BulletConfig reserveType(MagazineSingleTypeBase mag, ItemStack gun, Inventory inv) {
        BulletConfig type = mag.getAmount(gun, inv) == 0 ? mag.getFirstConfig(gun, inv) : null;
        return type != null ? type : mag.getType(gun, inv);
    }
}
