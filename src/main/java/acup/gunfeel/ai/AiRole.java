package acup.gunfeel.ai;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import acup.gunfeel.ReaimTimes;
import net.minecraft.world.item.ItemStack;

/**
 * What an AI mob is to its squad, by the gun in its hands (the owner, 2026-11-11: no role of its own for suppression,
 * it is the machine gunner's work, as in the manuals: the base of fire keeps them down while the others go round).
 * {@link #BASE}: a gun that fires on while the trigger is held, fast (a shot every {@link #FAST} ticks at most), real
 * bullets (no beam, nothing slow: a flamer, a grenade), with a belt or a magazine of aiBaseMagazine rounds or more: a
 * machine gun, a minigun, an automatic with a big magazine. Anyone else is a {@link #RIFLE}. Worked out again when
 * the gun in its hands is another.
 */
public enum AiRole {
    RIFLE, BASE;

    /** the server settings: roles by the gun; a base of fire's magazine from this many rounds */
    public static final String KEY = "aiRoles", MAG_KEY = "aiBaseMagazine";
    static final int MAG = 40;
    /** a base of fire's gun fires at least this often, ticks a shot */
    static final int FAST = 4;
    /** off only for the stands' "before" */
    static boolean on = true;

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    static AiRole of(AiBot bot) {
        if (!on()) return RIFLE;
        ItemStack stack = bot.mob.getMainHandItem();
        if (stack.getItem() != bot.roleFor) {
            bot.roleFor = stack.getItem();
            bot.role = by(stack);
        }
        return bot.role;
    }

    @SuppressWarnings("rawtypes")
    static AiRole by(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return RIFLE;
        try {
            Receiver rec = gun.getConfig(stack, 0).getReceivers(stack)[0];
            IMagazine mag = rec.getMagazine(stack);
            if (mag == null || !rec.getRefireOnHold(stack) || rec.getDelayAfterFire(stack) > FAST) return RIFLE;
            if (!(mag.getType(stack, null) instanceof BulletConfig b) || b.velocity < 5F || acup.gunfeel.BeamLasers.laser(b)) return RIFLE;
            int need = Math.max(1, Math.round(ReaimTimes.number(MAG_KEY, MAG)));
            return !(mag instanceof MagazineSingleTypeBase) || mag.getCapacity(stack) >= need ? BASE : RIFLE;
        } catch (RuntimeException e) {
            // a magazine that wants an inventory
            return RIFLE;
        }
    }
}
