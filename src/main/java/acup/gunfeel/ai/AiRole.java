package acup.gunfeel.ai;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.host.Body;
import net.minecraft.world.item.ItemStack;

/**
 * What an AI mob is to its squad, by the gun in its hands (the owner, 2026-11-11: no role of its own for
 * suppression, it is the machine gunner's work, as in the manuals: the base of fire keeps them down while the others
 * go round). {@link #BASE}: a gun that fires on while the trigger is held, fast (a shot every {@link AiRoleBase#FAST} ticks at
 * most), real bullets (no beam, nothing slow: a flamer, a grenade), with a belt or a magazine of aiBaseMagazine
 * rounds or more: a machine gun, a minigun, an automatic with a big magazine. {@link #GRENADIER}: any other gun with
 * 40 mm grenades in it (AiGrenadier.shell). {@link #MARKSMAN}: any other gun with a scope (NTM's scope texture).
 * Anyone else is a {@link #RIFLE}. Worked out again when the gun in its hands is another.
 */
public enum AiRole {
    RIFLE, BASE,
    /** hands out magazines and grenades to its squad (AiSupply); by word or by the spawn, never by the gun */
    SUPPLIER,
    /** fights from afar from perches (AiMarksman): a scoped gun that is no base of fire's, or by word */
    MARKSMAN,
    /** lobs 40 mm grenades over cover (AiGrenadier): a gun that fires them, or by word */
    GRENADIER,
    /** treats the hurt of its squad and its side's players (AiMedic); by word or by the spawn, never by the gun */
    MEDIC;

    /** the server setting: roles by the gun (a base of fire's magazine: AiRoleBase.MAG_KEY) */
    public static final String KEY = "aiRoles";
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

    /** a role by word for this AI mob, from outside the package (the bench); nothing for a mob that is none or a word that is no role */
    public static void give(net.minecraft.world.entity.Mob mob, String word) {
        AiBot b = AiBots.get(mob);
        AiRole r = named(word);
        if (b != null && r != null) assign(b, r);
    }

    /** the role kept on the mob, when it comes back into the world */
    static void restore(AiBot bot) {
        bot.assigned = bot.mob.getPersistentData().getString(ROLE_KEY).map(AiRole::named).orElse(null);
    }

    /** roles are dealt among mobs spawned by number from this many up (one alone is what its gun makes it) */
    static final int DEAL_FROM = 2;

    /**
     * The roles dealt among mobs spawned by number with no role named (the owner, 2026-11-14): each is a supplier
     * with the share aiSupplyShare, else a medic with the share aiMedicShare, by chance, each on its own; no most to a
     * squad, there may be none or several. The rest go by their guns. How many were made {suppliers, medics}.
     */
    static int[] deal(java.util.List<? extends net.minecraft.world.entity.Mob> mobs, net.minecraft.util.RandomSource rng) {
        int[] dealt = new int[2];
        if (!on() || mobs.size() < DEAL_FROM) return dealt;
        float supply = AiSupply.on() ? AiSupply.share() : 0F, medic = AiMedic.on() ? AiMedic.share() : 0F;
        for (net.minecraft.world.entity.Mob m : mobs) {
            AiBot b = AiBots.get(m);
            if (b == null || b.assigned != null) continue;
            float r = rng.nextFloat();
            if (r < supply) {
                assign(b, SUPPLIER);
                dealt[0]++;
            } else if (r < supply + medic) {
                assign(b, MEDIC);
                dealt[1]++;
            }
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
            bot.role = by(bot.gunner.body, stack);
        }
        return bot.role;
    }

    /** the role the gun in its main hand makes ({@code stack}, the one there) */
    static AiRole by(Body body, ItemStack stack) {
        if (!(stack.getItem() instanceof ItemGunBaseNT)) return RIFLE;
        if (AiRoleBase.base(body)) return BASE;
        if (AiGrenadier.shell(stack) != null) return GRENADIER;
        return acup.gunfeel.PistolHold.scoped(stack) ? MARKSMAN : RIFLE;
    }
}
