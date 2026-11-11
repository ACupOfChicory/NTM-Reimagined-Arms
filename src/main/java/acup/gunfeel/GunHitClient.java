package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// Client of GunHit: the hit the server says was a gun's is drawn without the view's tilt. The game keeps the last hit's
// damage source on the player (handleDamageEvent); the server's packet comes right after it, so that very source is
// marked, and while it is still the last one the camera takes the game's own preventFlinch (MixinGunHitTilt). Any
// other hit after it (a fist, an arrow, a fall) is a new source and tilts as ever.
public final class GunHitClient {
    private GunHitClient() {
    }

    private static @Nullable DamageSource marked;
    // the stand: hits marked, the tilt of the view's last frame (degrees, as GameRenderer.bobHurt turns it)
    static int marks;
    static float lastTilt;

    public static void receive() {
        LocalPlayer p = Minecraft.getInstance().player;
        // the game's own damage event was the packet before: the player hurt this tick or the one before
        if (p == null || p.hurtTime < p.hurtDuration - 1) return;
        marked = p.getLastDamageSource();
        marks++;
    }

    // Camera.extractRenderState (MixinGunHitTilt)
    public static void camera(@Nullable Entity entity, CameraRenderState state) {
        var s = state.entityRenderState;
        if (marked != null && entity instanceof LivingEntity e && s.isLiving) {
            if (e.getLastDamageSource() != marked) marked = null;
            else if (Config.enabled && !GunHit.tilt()) s.preventFlinch = true;
        }
        if (GunHitStand.ENABLED) lastTilt = tilt(s);
    }

    private static float tilt(net.minecraft.client.renderer.state.level.CameraEntityRenderState s) {
        if (!s.isLiving || s.hurtTime < 0F || s.preventFlinch || s.hurtDuration <= 0) return 0F;
        float h = s.hurtTime / s.hurtDuration;
        return (float) (Mth.sin(h * h * h * h * Mth.PI) * 14.0 * Minecraft.getInstance().options.damageTiltStrength().get());
    }

    public static void clear() {
        marked = null;
    }
}
