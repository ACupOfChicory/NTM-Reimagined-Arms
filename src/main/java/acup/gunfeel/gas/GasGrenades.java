package acup.gunfeel.gas;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.gl54.GrenadeKind;
import acup.gunfeel.gl54.GrenadeType;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the smoke and gas grenades of the GL-54 do when they go off: let a cloud of the smoke system out where they
 * burst (the one entry {@code Gl54Grenade}'s burst calls by kind). The smoke is the finished smoke cloud at one
 * grenade's charge (a wall of 5 to 6 blocks); each gas is its {@link GasKind}'s cloud.
 */
public final class GasGrenades {
    private GasGrenades() {
    }

    /** The cloud kind a grenade kind lets out, or null for a kind that is not a cloud. */
    public static SmokeKind cloudOf(GrenadeKind kind) {
        if (kind == null) return null;
        return switch (kind) {
            case SMOKE -> SmokeKind.SMOKE;
            case CHLORINE -> GasKind.CHLORINE.smoke();
            case PHOSGENE -> GasKind.PHOSGENE.smoke();
            case MUSTARD -> GasKind.MUSTARD.smoke();
            case ACID -> GasKind.ACID.smoke();
            default -> null;
        };
    }

    /**
     * An armed grenade burst at {@code hit}: a smoke or gas grenade lets its cloud out. Returns whether it was one
     * (the grenade is then spent); false leaves the burst to the other kinds.
     */
    public static boolean burst(ServerLevel level, EntityBulletBaseMK4 bullet, HitResult hit) {
        GrenadeKind kind = bullet.config instanceof GrenadeType t ? t.grenade() : null;
        SmokeKind cloud = cloudOf(kind);
        if (cloud == null) return false;
        Vec3 at = hit.getLocation();
        // out of the face it hit, so a grenade in a wall lets its cloud out into the room and not into the stone
        if (hit instanceof BlockHitResult b) at = at.add(b.getDirection().getStepX() * 0.1, b.getDirection().getStepY() * 0.1, b.getDirection().getStepZ() * 0.1);
        spawn(level, cloud, at, bullet.getDeltaMovement());
        return true;
    }

    /** Lets one grenade's cloud out; the cloud's id, or -1 when none came out (off, or shut in). */
    public static int spawn(ServerLevel level, SmokeKind cloud, Vec3 at, Vec3 direction) {
        return SmokeClouds.spawn(level, at, direction, cloud, 1F);
    }
}
