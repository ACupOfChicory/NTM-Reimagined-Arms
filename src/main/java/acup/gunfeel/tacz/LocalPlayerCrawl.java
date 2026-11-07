// SPDX-License-Identifier: GPL-3.0-only
// Copied from TaCZ (Timeless and Classics Zero), MCModderAnchor/TACZ and contributors, GPL-3.0.
// https://github.com/MCModderAnchor/TACZ, revision 19f14c8, src/main/java/com/tacz/guns/client/gameplay/LocalPlayerCrawl.java.
// Changes: the package; TaCZ's settings (SyncConfig.ENABLE_CRAWL, ENABLE_CRAWL_WITHOUT_GUN) are the mod's server keys
// prone and proneWithoutGun (Prone); a TaCZ gun (IGun, isCanCrawl, the gun index) is any NTM gun; the protected
// field jumping is read through an accessor; the packet is the mod's ProneCrawlPayload; serverStood() is added (the
// server's ProneStandPayload: up without telling the server back).
package acup.gunfeel.tacz;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.Prone;
import acup.gunfeel.ProneClient;
import acup.gunfeel.ProneCrawlPayload;
import acup.gunfeel.mixin.mc.LivingEntityAccess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;

public class LocalPlayerCrawl {
    /**
     * 冷却时间为 10 tick
     */
    private static final int COOLDOWN_TICKS = 10;
    private final LocalPlayer player;
    private boolean isCrawling = false;
    private int crawCooldownTicks = 0;

    public LocalPlayerCrawl(LocalPlayer player) {
        this.player = player;
    }

    public LocalPlayer player() {
        return player;
    }

    public void crawl(boolean isCrawl) {
        // 退出不能被冷却、切枪或离地拦截，且只同步实际发生的状态变化。
        if (!isCrawl) {
            setCrawling(false);
            return;
        }
        if (isCrawling || crawCooldownTicks > 0 || !canCrawl()) {
            return;
        }
        setCrawling(true);
        this.crawCooldownTicks = COOLDOWN_TICKS;
    }

    public void tickCrawl() {
        if (crawCooldownTicks > 0) {
            crawCooldownTicks--;
        }
        if (!isCrawling) {
            return;
        }
        boolean poseChanged = player.getForcedPose() != Pose.SWIMMING;
        if (poseChanged || !canCrawl()) {
            setCrawling(false);
        }
    }

    public void serverStood() {
        if (!isCrawling) {
            return;
        }
        isCrawling = false;
        if (player.getForcedPose() == Pose.SWIMMING) {
            player.setForcedPose(null);
        }
    }

    public boolean isCrawling() {
        return isCrawling;
    }

    private boolean canCrawl() {
        if (!Prone.enabled()) {
            return false;
        }
        boolean invalidState = !player.isAlive() || player.isSpectator() || player.isPassenger();
        boolean invalidMovement = ((LivingEntityAccess) player).gunfeel$jumping() || player.isSwimming() || !player.onGround();
        boolean incompatiblePose = player.isSleeping() || player.isFallFlying() || player.isAutoSpinAttack();
        if (invalidState || invalidMovement || incompatiblePose) {
            return false;
        }
        ItemStack mainHandItem = player.getMainHandItem();
        if (mainHandItem.getItem() instanceof ItemGunBaseNT) {
            return true;
        }
        return Prone.withoutGun();
    }

    private void setCrawling(boolean crawling) {
        if (isCrawling == crawling) {
            return;
        }
        isCrawling = crawling;
        // 趴下状态变化时设置或清除一次
        if (crawling || player.getForcedPose() == Pose.SWIMMING) {
            player.setForcedPose(crawling ? Pose.SWIMMING : null);
        }
        ProneClient.send(new ProneCrawlPayload(crawling));
    }
}
