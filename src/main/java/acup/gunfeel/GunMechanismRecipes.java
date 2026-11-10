package acup.gunfeel;

import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The guns' recipes with the new mechanisms (server setting gunMechanismRecipes, GunMechanisms): a gun takes the
 * mechanism of its tier (RECIPES), under NTM's recipe id (data/hbm/recipe), so the recipe book and JEI show one recipe of
 * each. NTM's recipes stay in the mod as data/ntmgunfeel/recipe/ntm/*, loaded only with the setting off. A recipe file
 * says which with the condition {@code ntmgunfeel:gun_mechanism_recipes} and {@code on}. Tesla Cannon also has the
 * balance's recipe (BalanceRecipes), so its four files take both conditions. tools/gun_mechanisms.py writes them all.
 * Read when the data packs load: a change counts after /reload or a restart. No NTM class here: the condition has to
 * load even with the system off, when the recipes simply stay NTM's.
 */
public final class GunMechanismRecipes {
    private GunMechanismRecipes() {
    }

    /** a recipe's mechanism: NTM's material and the one it takes instead */
    public record Swap(String from, String to) {
    }

    /** NTM's recipes that take another mechanism, as tools/gun_mechanisms.py writes them (the test holds them alike): the
     *  mechanism is the gun's tier, as in NTM, on a ladder of eight by when the material can be had (steel, gunmetal,
     *  high-speed steel at tier 2; desh, ferrouranium, weapon steel at 4; technetium steel at 5; saturnite at 6). A gun
     *  takes the rung of its tier and its own material, a B-side the rung above its base gun's, never below its own tier. */
    public static final Map<String, Swap> RECIPES = Map.ofEntries(
            Map.entry("gun_flaregun", new Swap("gun_metal", "steel")),
            Map.entry("gun_greasegun", new Swap("gun_metal", "steel")),
            Map.entry("gun_light_revolver", new Swap("gun_metal", "steel")),
            Map.entry("gun_maresleg", new Swap("gun_metal", "steel")),
            Map.entry("gun_charge_thrower", new Swap("gun_metal", "steel")),
            Map.entry("gun_charge_thrower_rubber", new Swap("gun_metal", "steel")),
            Map.entry("gun_am180", new Swap("gun_metal", "dura_steel")),
            Map.entry("gun_congolake", new Swap("gun_metal", "dura_steel")),
            Map.entry("gun_flamer", new Swap("gun_metal", "dura_steel")),
            Map.entry("gun_liberator", new Swap("gun_metal", "dura_steel")),
            Map.entry("gun_carbine", new Swap("gun_metal", "workers_alloy")),
            Map.entry("gun_heavy_revolver", new Swap("gun_metal", "workers_alloy")),
            Map.entry("gun_panzerschreck", new Swap("gun_metal", "workers_alloy")),
            Map.entry("gun_spas12", new Swap("gun_metal", "workers_alloy")),
            Map.entry("gun_uzi", new Swap("gun_metal", "workers_alloy")),
            Map.entry("gun_light_revolver_atlas", new Swap("weapon_steel", "workers_alloy")),
            Map.entry("gun_henry_lincoln", new Swap("weapon_steel", "workers_alloy")),
            Map.entry("gun_maresleg_akimbo", new Swap("weapon_steel", "workers_alloy")),
            Map.entry("gun_flamer_topaz", new Swap("weapon_steel", "workers_alloy")),
            Map.entry("gun_amat", new Swap("weapon_steel", "ferrouranium")),
            Map.entry("gun_autoshotgun", new Swap("weapon_steel", "ferrouranium")),
            Map.entry("gun_m2", new Swap("weapon_steel", "ferrouranium")),
            Map.entry("gun_quadro", new Swap("weapon_steel", "ferrouranium")),
            Map.entry("gun_uzi_akimbo", new Swap("weapon_steel", "ferrouranium")),
            Map.entry("gun_lag", new Swap("weapon_steel", "tc_alloy")),
            Map.entry("gun_minigun", new Swap("weapon_steel", "tc_alloy")),
            Map.entry("gun_missile_launcher", new Swap("weapon_steel", "tc_alloy")),
            Map.entry("gun_tesla_cannon", new Swap("weapon_steel", "tc_alloy")));

    /** the recipes the balance has too (BalanceRecipes), with four files each -> the mechanism of the balance's recipe
     *  (Tesla Cannon is tier 7 with it: saturnite; NTM's recipe at tier 5 takes technetium steel) */
    public static final Map<String, String> BALANCE = Map.of("gun_tesla_cannon", "saturnite");

    public static boolean on() {
        return acup.gunfeel.compat.Feature.GUN_MECHANISMS.on() && ReaimTimes.flag("gunMechanismRecipes", true);
    }

    /** the recipe loads when the setting is {@code on} */
    public record When(boolean on) implements ICondition {
        public static final MapCodec<When> CODEC = RecordCodecBuilder.mapCodec(b -> b.group(Codec.BOOL.fieldOf("on").forGetter(When::on)).apply(b, When::new));

        @Override
        public boolean test(IContext context) {
            return GunMechanismRecipes.on() == on;
        }

        @Override
        public MapCodec<? extends ICondition> codec() {
            return CODEC;
        }
    }

    // made here, not at class load: the tests read the table without NeoForge
    public static void register(IEventBus modBus) {
        DeferredRegister<MapCodec<? extends ICondition>> conditions = DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, "ntmgunfeel");
        conditions.register("gun_mechanism_recipes", () -> When.CODEC);
        conditions.register(modBus);
    }
}
