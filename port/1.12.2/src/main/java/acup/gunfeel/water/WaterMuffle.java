package acup.gunfeel.water;

import java.util.ArrayDeque;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

// 26.2's waterMuffle (MixinWaterMuffle): a gun fired with the shooter's eyes in water sounds muffled, volume × 0.6,
// pitch × 0.7. port: NTM CE has no EntitySounds; its guns play their sounds with World.playSound at the shooter's
// place while NTM runs the gun (ItemGunBaseNT.onUpdate: the orchestras of reloads and inspections, the full-auto
// refire; handleKeybind: the shot). MixinWaterShooter keeps the shooter here meanwhile, MixinWaterMuffle (the
// server's World.playSound) asks it.
public final class WaterMuffle {
    private WaterMuffle() {
    }

    public static final float VOLUME = 0.6F, PITCH = 0.7F;

    private static final ThreadLocal<ArrayDeque<Object>> SHOOTERS = ThreadLocal.withInitial(ArrayDeque::new);

    public static void begin(Entity shooter) {
        SHOOTERS.get().push(shooter == null ? NOBODY : shooter);
    }

    public static void end() {
        ArrayDeque<Object> s = SHOOTERS.get();
        if (!s.isEmpty()) s.pop();
    }

    // a sound at (x, y, z) is muffled: NTM is running a gun whose shooter has the eyes in water, and the sound is at
    // the shooter (NTM plays it at the shooter's block: within two blocks of it)
    public static boolean muffled(double x, double y, double z) {
        ArrayDeque<Object> s = SHOOTERS.get();
        if (s.isEmpty() || !(s.peek() instanceof EntityLivingBase)) return false;
        Entity e = (Entity) s.peek();
        if (e.world == null || e.world.isRemote) return false;
        if (e.getDistanceSq(x, y, z) > 4.0 || !e.isInsideOfMaterial(Material.WATER)) return false;
        return WaterSettings.muffle();
    }

    // stands for no shooter on the stack (ArrayDeque holds no null)
    private static final Object NOBODY = new Object();
}
