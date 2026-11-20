package acup.gunfeel.ai;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.host.Body;
import net.minecraft.world.item.ItemStack;

/**
 * A mob's role in the game (AiRole): by word, kept on the mob, dealt among mobs spawned by number, and worked out from
 * the gun in its hands.
 */
public final class AiRoles {
    private AiRoles() {
    }

    /** a role by word for this mob, kept on it (null: by its gun again) */
    static void assign(AiBot bot, @org.jspecify.annotations.Nullable AiRole role) {
        bot.assigned = role;
        if (role == null) bot.mob.getPersistentData().remove(AiRole.ROLE_KEY);
        else bot.mob.getPersistentData().putString(AiRole.ROLE_KEY, role.word());
    }

    /** a role by word for this AI mob, from outside the package (the bench); nothing for a mob that is none or a word that is no role */
    public static void give(net.minecraft.world.entity.Mob mob, String word) {
        AiBot b = AiBots.get(mob);
        AiRole r = AiRole.named(word);
        if (b != null && r != null) assign(b, r);
    }

    /** the role kept on the mob, when it comes back into the world */
    static void restore(AiBot bot) {
        bot.assigned = bot.mob.getPersistentData().getString(AiRole.ROLE_KEY).map(AiRole::named).orElse(null);
    }

    /**
     * The roles dealt among mobs spawned by number with no role named (the owner, 2026-11-14): each is a supplier
     * with the share aiSupplyShare, else a medic with the share aiMedicShare, by chance, each on its own; no most to a
     * squad, there may be none or several. The rest go by their guns. How many were made {suppliers, medics}.
     */
    static int[] deal(java.util.List<? extends net.minecraft.world.entity.Mob> mobs, net.minecraft.util.RandomSource rng) {
        int[] dealt = new int[2];
        if (!AiRole.on() || mobs.size() < AiRole.DEAL_FROM) return dealt;
        float supply = AiSupply.on() ? AiSupply.share() : 0F, medic = AiMedic.on() ? AiMedic.share() : 0F;
        for (net.minecraft.world.entity.Mob m : mobs) {
            AiBot b = AiBots.get(m);
            if (b == null || b.assigned != null) continue;
            float r = rng.nextFloat();
            if (r < supply) {
                assign(b, AiRole.SUPPLIER);
                dealt[0]++;
            } else if (r < supply + medic) {
                assign(b, AiRole.MEDIC);
                dealt[1]++;
            }
        }
        return dealt;
    }

    /** what it is to its squad: the role it was given by word, else the one its gun makes it */
    static AiRole of(AiBot bot) {
        if (!AiRole.on()) return AiRole.RIFLE;
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
        if (!(stack.getItem() instanceof ItemGunBaseNT)) return AiRole.RIFLE;
        if (AiRoleBase.base(body)) return AiRole.BASE;
        if (AiGrenadier.shell(stack) != null) return AiRole.GRENADIER;
        return acup.gunfeel.PistolHold.scoped(stack) ? AiRole.MARKSMAN : AiRole.RIFLE;
    }
}
