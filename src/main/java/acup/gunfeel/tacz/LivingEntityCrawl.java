// SPDX-License-Identifier: GPL-3.0-only
// Copied from TaCZ (Timeless and Classics Zero), MCModderAnchor/TACZ and contributors, GPL-3.0.
// https://github.com/MCModderAnchor/TACZ, revision 19f14c8, src/main/java/com/tacz/guns/entity/shooter/LivingEntityCrawl.java.
// Changes: the package; the crawl flag of TaCZ's ShooterDataHolder is a field here; TaCZ's settings are the mod's
// server keys prone and proneWithoutGun (Prone); a TaCZ gun is any NTM gun, and a creature that is not a player
// (TaCZ's data.currentGunItem) does not lie down; the protected field jumping is read through an accessor (on the
// server it is never set for a player, so there only the client's own check gets a jumping player up); shooter() is
// added.
package acup.gunfeel.tacz;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.Prone;
import acup.gunfeel.mixin.mc.LivingEntityAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class LivingEntityCrawl {
    private final LivingEntity shooter;
    private boolean isCrawling = false;

    public LivingEntityCrawl(LivingEntity shooter) {
        this.shooter = shooter;
    }

    public LivingEntity shooter() {
        return shooter;
    }

    public boolean isCrawling() {
        return isCrawling;
    }

    public void crawl(boolean isCrawl) {
        setCrawling(isCrawl && canCrawl());
    }

    public void tickCrawling() {
        if (!isCrawling) {
            return;
        }
        boolean poseChanged = shooter instanceof Player player && player.getForcedPose() != Pose.SWIMMING;
        if (poseChanged || !canCrawl()) {
            setCrawling(false);
        }
    }

    private boolean canCrawl() {
        if (!Prone.enabled()) {
            return false;
        }
        boolean invalidState = !shooter.isAlive() || shooter.isSpectator() || shooter.isPassenger();
        boolean invalidMovement = ((LivingEntityAccess) shooter).gunfeel$jumping() || shooter.isSwimming() || !shooter.onGround();
        boolean incompatiblePose = shooter.isSleeping() || shooter.isFallFlying() || shooter.isAutoSpinAttack();
        if (invalidState || invalidMovement || incompatiblePose) {
            return false;
        }
        // 玩家可以从未持过枪，也可能刚切换物品，以实际主手物品为准。
        ItemStack gunItem = shooter instanceof Player ? shooter.getMainHandItem() : ItemStack.EMPTY;
        if (gunItem.getItem() instanceof ItemGunBaseNT) {
            return true;
        }
        return shooter instanceof Player && Prone.withoutGun();
    }

    private void setCrawling(boolean crawling) {
        if (isCrawling == crawling) {
            return;
        }
        isCrawling = crawling;
        // 只在 TACZ 的趴下状态变化时设置或清除一次，不持续写入姿态。
        if (shooter instanceof Player player) {
            if (crawling || player.getForcedPose() == Pose.SWIMMING) {
                player.setForcedPose(crawling ? Pose.SWIMMING : null);
            }
        } else {
            shooter.setPose(crawling ? Pose.SWIMMING : Pose.STANDING);
        }
    }
}
