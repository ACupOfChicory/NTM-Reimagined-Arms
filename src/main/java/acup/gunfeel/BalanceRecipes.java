package acup.gunfeel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The balance's recipes (server setting balanceRecipes): the diesel suit's helmet, chestplate and leggings, the T-51
 * chestplate, leggings and boots (the helmet's basic circuit, so the set is tier 3 work as a whole), Tesla Cannon,
 * Lasrifle and Tau get the balance's recipes, under NTM's own recipe ids (data/hbm/recipe), so the recipe book and JEI
 * show one recipe of each. NTM's recipes stay in the mod as data/ntmgunfeel/recipe/ntm/*, loaded only with the
 * setting off. A recipe file says which with the condition {@code ntmgunfeel:balance_recipes} and {@code on}: true
 * (the balance's) or false (NTM's). Read when the data packs load: a change counts after /reload or a restart.
 */
public final class BalanceRecipes {
    private BalanceRecipes() {
    }

    public static boolean on() {
        return acup.gunfeel.compat.Feature.BALANCE.on() && ReaimTimes.flag("balanceRecipes", true);
    }

    /** the recipe loads when the setting is {@code on} */
    public record When(boolean on) implements ICondition {
        public static final MapCodec<When> CODEC = RecordCodecBuilder.mapCodec(b -> b.group(Codec.BOOL.fieldOf("on").forGetter(When::on)).apply(b, When::new));

        @Override
        public boolean test(IContext context) {
            return BalanceRecipes.on() == on;
        }

        @Override
        public MapCodec<? extends ICondition> codec() {
            return CODEC;
        }
    }

    private static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS = DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, "ntmgunfeel");

    static {
        CONDITIONS.register("balance_recipes", () -> When.CODEC);
    }

    public static void register(IEventBus modBus) {
        CONDITIONS.register(modBus);
    }
}
