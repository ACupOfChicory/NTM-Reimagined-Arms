package acup.gunfeel;

import com.hbm.items.weapon.sedna.DamageSourceSedna;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;

// A player hit by an NTM gun: a bullet, a pellet, a beam (NTM's damage source of its guns, DamageSourceSedna; not a
// blast, a melee hit, a mob, an arrow or a fall). By default such a hit neither throws the player back nor tilts
// their view: the damage, the hurt sound and the red of the hit stay. gunHitKnockback = true gives back the game's
// own knockback (and its resend of the player's motion, which stops them short); gunHitTilt = true the view's tilt.
// The server leaves out the knockback (MixinGunHitServer) and tells the player's client which hit was a gun's
// (GunHitPayload); the client then draws that hit without the tilt (GunHitClient, the game's own preventFlinch).
public final class GunHit {
    private GunHit() {
    }

    public static boolean knockback() {
        return ReaimTimes.flag("gunHitKnockback", false);
    }

    public static boolean tilt() {
        return ReaimTimes.flag("gunHitTilt", false);
    }

    public static boolean gun(DamageSource source) {
        return source instanceof DamageSourceSedna;
    }

    // the server: no knockback and no resent motion for this hit on this one
    public static boolean still(LivingEntity target, DamageSource source) {
        return target instanceof ServerPlayer && gun(source) && !knockback();
    }

    // the server: the hit went in (LivingEntity.hurtServer gave true), after the game told the client of it
    public static void hurt(LivingEntity target, DamageSource source) {
        if (target instanceof ServerPlayer p && gun(source) && !tilt()) PacketDistributor.sendToPlayer(p, GunHitPayload.INSTANCE);
    }
}
