package acup.gunfeel.water;

import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;

// port: 26.2 muffles in its EntitySounds.play hook (MixinWaterMuffle), the one door every sound of NTM's guns goes
// through there. NTM 1.7.10 has no such door: the shot is World.playSoundEffect (Lego), the orchestras
// World.playSoundAtEntity. So the gun's server tick and key handling mark their shooter here (MixinWaterMuffle on
// ItemGunBaseNT) and World's two sound calls (early MixinWorldMuffle) muffle what is played while one is marked.
public final class WaterMuffle {
    private WaterMuffle() {
    }

    private static final ThreadLocal<EntityLivingBase> SHOOTER = new ThreadLocal<>();
    // the world tick the shooter was marked in: a mark left behind (an exception out of NTM's gun code skips the
    // RETURN that takes it off, and handleKeybind runs on the shared netty thread) stops counting a tick later
    private static final ThreadLocal<long[]> SINCE = ThreadLocal.withInitial(() -> new long[1]);
    private static final ThreadLocal<boolean[]> INSIDE = ThreadLocal.withInitial(() -> new boolean[1]);

    public static EntityLivingBase begin(EntityLivingBase shooter) {
        EntityLivingBase before = SHOOTER.get();
        SHOOTER.set(shooter);
        if (shooter != null) SINCE.get()[0] = shooter.worldObj.getTotalWorldTime();
        return before;
    }

    public static void end(EntityLivingBase before) {
        SHOOTER.set(before);
    }

    /** a sound played now at x, y, z should be muffled: a gun's own sound (within 2 blocks of its shooter, not a hit
     *  far off), the shooter's eyes in water, the key on, not our replay */
    public static boolean now(double x, double y, double z) {
        EntityLivingBase shooter = SHOOTER.get();
        if (shooter == null || INSIDE.get()[0]) return false;
        if (shooter.isDead || Math.abs(shooter.worldObj.getTotalWorldTime() - SINCE.get()[0]) > 1) {
            SHOOTER.remove();
            return false;
        }
        double dx = x - shooter.posX, dy = y - shooter.posY, dz = z - shooter.posZ;
        return dx * dx + dy * dy + dz * dz <= 4 + shooter.getEyeHeight() * shooter.getEyeHeight() && shooter.isInsideOfMaterial(Material.water)
                && WaterSettings.muffle();
    }

    /** plays the muffled sound (volume × 0.6, pitch × 0.7) without coming back here */
    public static void replay(Play play, float volume, float pitch) {
        boolean[] inside = INSIDE.get();
        inside[0] = true;
        try {
            play.play(volume * 0.6F, pitch * 0.7F);
        } finally {
            inside[0] = false;
        }
    }

    public interface Play {
        void play(float volume, float pitch);
    }
}
