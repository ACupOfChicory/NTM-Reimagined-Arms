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
    RIFLE, BASE,
    /** hands out magazines and grenades to its squad (AiSupply); by word or by the spawn, never by the gun */
    SUPPLIER;

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

    /** where a role given by word is kept on the mob, so it lasts over a reload of the chunk */
    static final String ROLE_KEY = "armsaiRole";
    /** as typed after "role": the role is by the gun again */
    static final String AUTO = "auto";

    /** its name as typed (role base) and as shown */
    String word() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** the role of this name as typed, or null */
    static @org.jspecify.annotations.Nullable AiRole named(String word) {
        for (AiRole r : values()) if (r.word().equals(word.toLowerCase(java.util.Locale.ROOT))) return r;
        return null;
    }

    /** the names a role may be typed by, auto last */
    static java.util.List<String> words() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (AiRole r : values()) out.add(r.word());
        out.add(AUTO);
        return out;
    }

    /** a role by word for this mob, kept on it (null: by its gun again) */
    static void assign(AiBot bot, @org.jspecify.annotations.Nullable AiRole role) {
        bot.assigned = role;
        if (role == null) bot.mob.getPersistentData().remove(ROLE_KEY);
        else bot.mob.getPersistentData().putString(ROLE_KEY, role.word());
    }

    /** the role kept on the mob, when it comes back into the world */
    static void restore(AiBot bot) {
        bot.assigned = bot.mob.getPersistentData().getString(ROLE_KEY).map(AiRole::named).orElse(null);
    }

    /** a squad's size (AiSquads.MAX) has one supplier; fewer than this many spawned at once have none */
    static final int DEAL_FROM = 4;

    /**
     * The roles dealt among mobs spawned by number with no role named (the owner, 2026-11-14): one supplier to each
     * squad's worth of them (AiSquads.MAX), picked at random, from DEAL_FROM of them up; the rest go by their guns.
     * How many were made suppliers.
     */
    static int deal(java.util.List<? extends net.minecraft.world.entity.Mob> mobs, net.minecraft.util.RandomSource rng) {
        if (!on() || !AiSupply.on() || mobs.size() < DEAL_FROM) return 0;
        java.util.List<AiBot> bots = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Mob m : mobs) {
            AiBot b = AiBots.get(m);
            if (b != null && b.assigned == null) bots.add(b);
        }
        int want = (mobs.size() + AiSquads.MAX - 1) / AiSquads.MAX, dealt = 0;
        while (dealt < want && !bots.isEmpty()) {
            assign(bots.remove(rng.nextInt(bots.size())), SUPPLIER);
            dealt++;
        }
        return dealt;
    }

    /** what it is to its squad: the role it was given by word, else the one its gun makes it */
    static AiRole of(AiBot bot) {
        if (!on()) return RIFLE;
        if (bot.assigned != null) return bot.assigned;
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
