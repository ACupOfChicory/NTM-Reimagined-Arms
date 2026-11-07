// SPDX-License-Identifier: GPL-3.0-only
// Copied from TaCZ (Timeless and Classics Zero), MCModderAnchor/TACZ and contributors, GPL-3.0.
// https://github.com/MCModderAnchor/TACZ, revision 19f14c8, src/main/java/com/tacz/guns/client/input/CrawlKey.java,
// with isInGame from util/InputExtraCheck.java. Changes: the package; the key's name and category are the mod's;
// TaCZ's event subscription is a listener the mod adds (ProneClient.registerClient), and the press comes from the same tick
// (TaCZ's GunInputHandler); the Controllable gamepad parts are left out; TaCZ's settings are the mod's (proneHold,
// prone, proneWithoutGun); a TaCZ gun is any NTM gun; TaCZ's crawl on its player (IClientPlayerGunOperator) is the
// mod's LocalPlayerCrawl kept in ProneClient; the key is X, not C (NTM's jetpack switch is on C).
package acup.gunfeel.tacz;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.mojang.blaze3d.platform.InputConstants;

import acup.gunfeel.AmmoLockKey;
import acup.gunfeel.Config;
import acup.gunfeel.Prone;
import acup.gunfeel.ProneClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

public class CrawlKey {
    public static final KeyMapping CRAWL_KEY = new KeyMapping("key.ntmgunfeel.prone",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            88 /* GLFW_KEY_X */,
            AmmoLockKey.CATEGORY);

    public static boolean onCrawlPress() {
        return onCrawlInput(true);
    }

    public static void onCrawlHoldingInput() {
        if (Config.proneHold) {
            // 持续检查也能处理先松开修饰键，以及冷却期间松开主键的情况。
            onCrawlInput(false);
        }
    }

    private static boolean onCrawlInput(boolean isPress) {
        LocalPlayer player = Minecraft.getInstance().player;
        LocalPlayerCrawl operator = ProneClient.local(player);
        if (operator == null) {
            return false;
        }
        boolean holdToCrawl = Config.proneHold;
        boolean gun = player.getMainHandItem().getItem() instanceof ItemGunBaseNT;
        boolean inputEnabled = isInGame() && Prone.enabled();
        boolean playerAllowed = inputEnabled && !player.isSpectator() && !player.isPassenger();
        boolean crawlAllowed = playerAllowed && (gun || Prone.withoutGun());
        if (!crawlAllowed) {
            if (holdToCrawl && operator.isCrawling()) {
                operator.crawl(false);
            }
            return false;
        }
        if (!isPress && !holdToCrawl) {
            return false;
        }
        boolean crawl = holdToCrawl ? CRAWL_KEY.isDown() : !operator.isCrawling();
        if (operator.isCrawling() != crawl) {
            operator.crawl(crawl);
        }
        return true;
    }

    public static boolean isInGame() {
        Minecraft mc = Minecraft.getInstance();
        // 不能是加载界面
        if (mc.gui.overlay() != null) {
            return false;
        }
        // 不能打开任何 GUI
        if (mc.gui.screen() != null) {
            return false;
        }
        // 当前窗口捕获鼠标操作
        if (!mc.mouseHandler.isMouseGrabbed()) {
            return false;
        }
        // 选择了当前窗口
        return mc.isWindowActive();
    }
}
