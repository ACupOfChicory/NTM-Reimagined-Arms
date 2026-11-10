package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hbm.items.armor.ArmorFullSetBonus;
import com.hbm.items.armor.ModArmorItem.Suit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * The balance's armour abilities (server settings abilities.*): what NTM's full sets give, cut to the set's tier.
 *
 * <ul>
 * <li>{@code abilities.mobilityCap}: Speed and Jump Boost by the set's tier: up to tier 6 Speed II and Jump I at most,
 * from tier 7 Speed III and Jump II (the diesel suit Speed III → II, Jump III → I; Trenchmaster Jump II → I; DNS
 * Jump III → II);
 * <li>{@code abilities.strengthCap} (2): Strength at most II on every set (NCRPA, RPA IV, CMB V, DNS X, Trenchmaster and
 * schrabidium III); 0 — NTM's;
 * <li>{@code abilities.bismuth}: the bismuth set is a tier 6 set, not a joke: Speed III, Jump II, Regeneration I, night
 * vision and two dashes (NTM: VII, VII, II, three); off — NTM's bismuth, not cut by the other two;
 * <li>{@code abilities.esp.players} (tier8 | off | all) and {@code abilities.esp.playerRange} (16): VATS and the
 * thermal vision of the sets show no players; with tier8 a tier 8 set's thermal vision (FAU, DNS) shows the ones it
 * sees within the range; all — NTM's (everyone through walls within 64 blocks). A gun's thermal sight (AMAT Penance)
 * shows players as a tier 8 set: within the range and in sight (off — none, all — NTM's).
 * </ul>
 * Everything else of the sets (haste, fire resistance, step, hard landing, DNS and euphemium immunity) is NTM's.
 */
public final class ArmorAbilities {
    private ArmorAbilities() {
    }

    /** the sets' tier (balance-by-progression.md section 3), by NTM's suit; a suit not here is not cut by the tier */
    static final Map<String, Integer> TIER = Map.ofEntries(
            Map.entry("STEEL", 1), Map.entry("COBALT", 1), Map.entry("ALLOY", 1), Map.entry("TITANIUM", 1), Map.entry("TAURUN", 1),
            Map.entry("DIESEL", 3), Map.entry("T51", 3), Map.entry("DESH", 4), Map.entry("HEV", 4), Map.entry("AJR", 4), Map.entry("AJRO", 4),
            Map.entry("SECURITY", 4), Map.entry("ENVSUIT", 4), Map.entry("TRENCHMASTER", 6), Map.entry("BJ", 6), Map.entry("NCRPA", 6),
            Map.entry("BISMUTH", 6), Map.entry("SCHRABIDIUM", 7), Map.entry("CMB", 7), Map.entry("RPA", 7), Map.entry("DIGAMMA", 8),
            Map.entry("DNS", 8), Map.entry("EUPHEMIUM", 9));
    /** bismuth's abilities (owner, 2026-11-09): the highest level of each, as an amplifier (level − 1) */
    static final Map<String, Integer> BISMUTH = Map.of("speed", 2, "jump_boost", 1, "regeneration", 0);
    static final int BISMUTH_DASHES = 2;

    /** the settings, read at once */
    record Keys(boolean mobility, int strength, boolean bismuth) {
        static final Keys NTM = new Keys(false, 0, false);
    }

    static Keys keys() {
        return new Keys(ReaimTimes.flag("abilities.mobilityCap", true), Math.round(ReaimTimes.number("abilities.strengthCap", 2F)),
                ReaimTimes.flag("abilities.bismuth", true));
    }

    static int tier(String suit) {
        return TIER.getOrDefault(suit, 0);
    }

    /**
     * the amplifier the set gives for an effect (vanilla's path: speed, jump_boost, strength, ...) instead of NTM's
     * {@code amp}
     */
    static int amplifier(String suit, String effect, int amp, Keys k) {
        if (suit.equals("BISMUTH")) {
            if (!k.bismuth()) return amp;
            Integer most = BISMUTH.get(effect);
            if (most != null) amp = Math.min(amp, most);
        } else if (k.mobility() && tier(suit) > 0) {
            boolean high = tier(suit) >= 7;
            if (effect.equals("speed")) amp = Math.min(amp, high ? 2 : 1);
            if (effect.equals("jump_boost")) amp = Math.min(amp, high ? 1 : 0);
        }
        if (k.strength() > 0 && effect.equals("strength")) amp = Math.min(amp, k.strength() - 1);
        return amp;
    }

    static int dashes(String suit, int ntm, Keys k) {
        return suit.equals("BISMUTH") && k.bismuth() ? Math.min(ntm, BISMUTH_DASHES) : ntm;
    }

    /** a gun's thermal sight sees players as this tier's sets do */
    public static final int GUN_SIGHT_TIER = 8;

    /** the thermal vision of a set of {@code tier} shows a player {@code dist} blocks away it {@code sees} or not */
    public static boolean thermalShows(String mode, int tier, double dist, double range, boolean sees) {
        if (mode.equals("all")) return true;
        if (mode.equals("off")) return false;
        return tier >= 8 && dist <= range && sees;
    }

    /** VATS shows players' health bars only as in NTM */
    public static boolean vatsShows(String mode) {
        return mode.equals("all");
    }

    /**
     * The thermal vision (or a gun's thermal sight, {@code tier} {@link #GUN_SIGHT_TIER}) of {@code viewer} keeps the
     * outline of {@code ent}: anything but a player as in NTM, a player by {@link #thermalShows} (its distance and the
     * viewer's line of sight, vanilla's: from eyes to eyes, blocks stop it). The client's renderer (MixinArmorEsp) asks
     * this, and so does the stand.
     */
    public static boolean thermalKeeps(String mode, double range, int tier, net.minecraft.world.entity.LivingEntity viewer,
            net.minecraft.world.entity.Entity ent) {
        if (!(ent instanceof net.minecraft.world.entity.player.Player) || mode.equals("all")) return true;
        if (mode.equals("off") || tier < 8) return false;
        double dist = viewer.distanceTo(ent);
        return thermalShows(mode, tier, dist, range, dist <= range && viewer.hasLineOfSight(ent));
    }

    /** VATS keeps the health bar of {@code ent}: anything but a player as in NTM, a player only with {@code mode} all */
    public static boolean vatsKeeps(String mode, net.minecraft.world.entity.Entity ent) {
        return !(ent instanceof net.minecraft.world.entity.player.Player) || vatsShows(mode);
    }

    /**
     * the tier of the full set whose VATS or thermal vision {@code viewer} has on, as NTM's renderer finds it
     * ({@code ArmorOverheadRenderer.activeChestBonus}: the first suit with either), 0 for none
     */
    public static int espTier(net.minecraft.world.entity.LivingEntity viewer) {
        for (Suit s : Suit.values()) {
            ArmorFullSetBonus b = ArmorFullSetBonus.get(s);
            if (b != null && (b.vats() || b.thermal()) && com.hbm.items.armor.ArmorSuitEffects.hasFullSet(viewer, s)) return tierOf(b);
        }
        return 0;
    }

    public static String espModeNow() {
        return ReaimTimes.text("abilities.esp.players", "tier8").trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static double espRangeNow() {
        return ReaimTimes.number("abilities.esp.playerRange", 16F);
    }

    // ---------------------------------------------------------------- NTM's side

    /** the suit NTM registered the bonus for (AJR and AJRO share one: the first, same tier) */
    public static Suit suitOf(ArmorFullSetBonus bonus) {
        for (Suit s : Suit.values()) if (ArmorFullSetBonus.get(s) == bonus) return s;
        return null;
    }

    public static int tierOf(ArmorFullSetBonus bonus) {
        Suit s = bonus == null ? null : suitOf(bonus);
        return s == null ? 0 : tier(s.name());
    }

    /** NTM's effects of the set, cut; the same list when nothing changes */
    public static List<ArmorFullSetBonus.EffectBonus> effects(ArmorFullSetBonus bonus, List<ArmorFullSetBonus.EffectBonus> ntm) {
        if (ntm.isEmpty() || !acup.gunfeel.compat.Feature.BALANCE.on()) return ntm;
        Suit suit = suitOf(bonus);
        if (suit == null) return ntm;
        Keys k = keys();
        List<ArmorFullSetBonus.EffectBonus> out = null;
        for (int i = 0; i < ntm.size(); i++) {
            ArmorFullSetBonus.EffectBonus e = ntm.get(i);
            Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(e.effect().value());
            String path = id != null && id.getNamespace().equals("minecraft") ? id.getPath() : "";
            int amp = amplifier(suit.name(), path, e.amplifier(), k);
            if (amp == e.amplifier()) continue;
            if (out == null) out = new ArrayList<>(ntm);
            out.set(i, new ArmorFullSetBonus.EffectBonus(e.effect(), amp));
        }
        return out == null ? ntm : List.copyOf(out);
    }

    public static int dashes(ArmorFullSetBonus bonus, int ntm) {
        if (ntm <= 0 || !acup.gunfeel.compat.Feature.BALANCE.on()) return ntm;
        Suit suit = suitOf(bonus);
        return suit == null ? ntm : dashes(suit.name(), ntm, keys());
    }
}
