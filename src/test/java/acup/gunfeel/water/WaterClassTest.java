package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

// Every ammo NTM has (src/test/gundata) gets its water class: the table in notes/water/README.md.
class WaterClassTest {
    static final Path DATA = Path.of("src/test/gundata/guns-mc26.2-ntm1.0.3.tsv");

    // ammo -> class, worked out from the dump: one row per ammo
    static Map<String, WaterClass> classes() throws Exception {
        Map<String, WaterClass> out = new TreeMap<>();
        var lines = Files.readAllLines(DATA);
        String[] head = lines.get(0).split("\t");
        Map<String, Integer> col = new java.util.HashMap<>();
        for (int i = 0; i < head.length; i++) col.put(head[i], i);
        for (String line : lines.subList(1, lines.size())) {
            String[] r = line.split("\t");
            String ammo = r[col.get("ammo")].replace("hbm:", "");
            out.put(ammo, WaterClass.of(ammo, r[col.get("type")].equals("BEAM"), Integer.parseInt(r[col.get("projectilesMax")]),
                    Float.parseFloat(r[col.get("thresholdNegation")]), Float.parseFloat(r[col.get("piercing")]),
                    Float.parseFloat(r[col.get("velocity")]), false));
        }
        return out;
    }

    private static final Map<String, WaterClass> EXPECTED = Map.ofEntries(
            Map.entry("ammo_debug", WaterClass.NONE),
            Map.entry("ammo_fireext", WaterClass.NONE), Map.entry("ammo_fireext_foam", WaterClass.NONE), Map.entry("ammo_fireext_sand", WaterClass.NONE),
            Map.entry("ammo_secret_bmg50_black", WaterClass.AP), Map.entry("ammo_secret_bmg50_equestrian", WaterClass.HEAVY),
            Map.entry("ammo_secret_folly_nuke", WaterClass.GRENADE), Map.entry("ammo_secret_folly_sm", WaterClass.BEAM),
            Map.entry("ammo_secret_g12_equestrian", WaterClass.SLUG), Map.entry("ammo_secret_m44_equestrian", WaterClass.PISTOL),
            Map.entry("ammo_secret_p35_800", WaterClass.BEAM), Map.entry("ammo_secret_p35_800_bl", WaterClass.BEAM),
            Map.entry("ammo_standard_b75", WaterClass.HEAVY), Map.entry("ammo_standard_b75_exp", WaterClass.HEAVY), Map.entry("ammo_standard_b75_inc", WaterClass.HEAVY),
            Map.entry("ammo_standard_bmg50_ap", WaterClass.AP), Map.entry("ammo_standard_bmg50_du", WaterClass.AP),
            Map.entry("ammo_standard_bmg50_fmj", WaterClass.HEAVY), Map.entry("ammo_standard_bmg50_he", WaterClass.HEAVY),
            Map.entry("ammo_standard_bmg50_jhp", WaterClass.HEAVY), Map.entry("ammo_standard_bmg50_sm", WaterClass.AP),
            Map.entry("ammo_standard_bmg50_sp", WaterClass.HEAVY),
            Map.entry("ammo_standard_capacitor", WaterClass.BEAM), Map.entry("ammo_standard_capacitor_ir", WaterClass.BEAM),
            Map.entry("ammo_standard_capacitor_overcharge", WaterClass.BEAM),
            Map.entry("ammo_standard_coil_ferrouranium", WaterClass.AP), Map.entry("ammo_standard_coil_tungsten", WaterClass.AP),
            Map.entry("ammo_standard_ct_hook", WaterClass.GRENADE), Map.entry("ammo_standard_ct_mortar", WaterClass.GRENADE),
            Map.entry("ammo_standard_ct_mortar_charge", WaterClass.GRENADE),
            Map.entry("ammo_standard_flame_balefire", WaterClass.FLAME), Map.entry("ammo_standard_flame_diesel", WaterClass.FLAME),
            Map.entry("ammo_standard_flame_gas", WaterClass.FLAME), Map.entry("ammo_standard_flame_napalm", WaterClass.FLAME),
            Map.entry("ammo_standard_g10", WaterClass.SHOT), Map.entry("ammo_standard_g10_du", WaterClass.MINI),
            Map.entry("ammo_standard_g10_explosive", WaterClass.SHOT), Map.entry("ammo_standard_g10_shrapnel", WaterClass.SHOT),
            Map.entry("ammo_standard_g10_slug", WaterClass.SLUG),
            Map.entry("ammo_standard_g12", WaterClass.SHOT), Map.entry("ammo_standard_g12_bp", WaterClass.SHOT),
            Map.entry("ammo_standard_g12_bp_magnum", WaterClass.SHOT), Map.entry("ammo_standard_g12_bp_slug", WaterClass.SLUG),
            Map.entry("ammo_standard_g12_explosive", WaterClass.SLUG), Map.entry("ammo_standard_g12_flechette", WaterClass.DART),
            Map.entry("ammo_standard_g12_magnum", WaterClass.SHOT), Map.entry("ammo_standard_g12_phosphorus", WaterClass.SHOT),
            Map.entry("ammo_standard_g12_slug", WaterClass.SLUG),
            Map.entry("ammo_standard_g26_flare", WaterClass.GRENADE), Map.entry("ammo_standard_g26_flare_supply", WaterClass.GRENADE),
            Map.entry("ammo_standard_g26_flare_weapon", WaterClass.GRENADE),
            Map.entry("ammo_standard_g40_demo", WaterClass.GRENADE), Map.entry("ammo_standard_g40_he", WaterClass.GRENADE),
            Map.entry("ammo_standard_g40_heat", WaterClass.GRENADE), Map.entry("ammo_standard_g40_inc", WaterClass.GRENADE),
            Map.entry("ammo_standard_g40_phosphorus", WaterClass.GRENADE),
            Map.entry("ammo_standard_m357_ap", WaterClass.PISTOL), Map.entry("ammo_standard_m357_bp", WaterClass.PISTOL),
            Map.entry("ammo_standard_m357_express", WaterClass.PISTOL), Map.entry("ammo_standard_m357_fmj", WaterClass.PISTOL),
            Map.entry("ammo_standard_m357_jhp", WaterClass.PISTOL), Map.entry("ammo_standard_m357_sp", WaterClass.PISTOL),
            Map.entry("ammo_standard_m44_ap", WaterClass.PISTOL), Map.entry("ammo_standard_m44_bp", WaterClass.PISTOL),
            Map.entry("ammo_standard_m44_express", WaterClass.PISTOL), Map.entry("ammo_standard_m44_fmj", WaterClass.PISTOL),
            Map.entry("ammo_standard_m44_jhp", WaterClass.PISTOL), Map.entry("ammo_standard_m44_sp", WaterClass.PISTOL),
            Map.entry("ammo_standard_nuke_balefire", WaterClass.GRENADE), Map.entry("ammo_standard_nuke_demo", WaterClass.GRENADE),
            Map.entry("ammo_standard_nuke_high", WaterClass.GRENADE), Map.entry("ammo_standard_nuke_hive", WaterClass.GRENADE),
            Map.entry("ammo_standard_nuke_standard", WaterClass.GRENADE), Map.entry("ammo_standard_nuke_tots", WaterClass.GRENADE),
            Map.entry("ammo_standard_p22_ap", WaterClass.PISTOL), Map.entry("ammo_standard_p22_fmj", WaterClass.PISTOL),
            Map.entry("ammo_standard_p22_jhp", WaterClass.PISTOL), Map.entry("ammo_standard_p22_sp", WaterClass.PISTOL),
            Map.entry("ammo_standard_p9_ap", WaterClass.PISTOL), Map.entry("ammo_standard_p9_fmj", WaterClass.PISTOL),
            Map.entry("ammo_standard_p9_jhp", WaterClass.PISTOL), Map.entry("ammo_standard_p9_sp", WaterClass.PISTOL),
            Map.entry("ammo_standard_r556_ap", WaterClass.AP), Map.entry("ammo_standard_r556_fmj", WaterClass.RIFLE),
            Map.entry("ammo_standard_r556_jhp", WaterClass.RIFLE), Map.entry("ammo_standard_r556_sp", WaterClass.RIFLE),
            Map.entry("ammo_standard_r762_ap", WaterClass.AP), Map.entry("ammo_standard_r762_du", WaterClass.AP),
            Map.entry("ammo_standard_r762_fmj", WaterClass.RIFLE), Map.entry("ammo_standard_r762_he", WaterClass.RIFLE),
            Map.entry("ammo_standard_r762_jhp", WaterClass.RIFLE), Map.entry("ammo_standard_r762_sp", WaterClass.RIFLE),
            Map.entry("ammo_standard_rocket_demo", WaterClass.ROCKET), Map.entry("ammo_standard_rocket_he", WaterClass.ROCKET),
            Map.entry("ammo_standard_rocket_heat", WaterClass.ROCKET), Map.entry("ammo_standard_rocket_inc", WaterClass.ROCKET),
            Map.entry("ammo_standard_rocket_phosphorus", WaterClass.ROCKET),
            Map.entry("ammo_standard_stone", WaterClass.PISTOL), Map.entry("ammo_standard_stone_ap", WaterClass.PISTOL),
            Map.entry("ammo_standard_stone_iron", WaterClass.PISTOL), Map.entry("ammo_standard_stone_shot", WaterClass.SHOT),
            Map.entry("ammo_standard_tau_uranium", WaterClass.BEAM));

    @Test
    void everyNtmAmmoHasTheClassOfTheTable() throws Exception {
        var got = classes();
        assertEquals(new TreeMap<>(EXPECTED), got);
        // the README's table: ./gradlew test -Dntmgunfeel.waterTable=<file>
        String out = System.getProperty("ntmgunfeel.waterTable");
        if (out != null) {
            StringBuilder b = new StringBuilder("| Патрон | Класс | L, бл | Раскрытие | Остановка, бл | После 1 бл воды |\n|---|---|---|---|---|---|\n");
            for (var e : got.entrySet()) {
                WaterClass c = e.getValue();
                var ex = c.slows() ? WaterClass.Expansion.of(e.getKey()) : null;
                String l = c.slows() ? String.format(java.util.Locale.ROOT, "%.2f", c.length) : c.length <= 0 ? "0" : "∞";
                String open = ex == null ? "—" : String.format(java.util.Locale.ROOT, "%s: L до %d%% за %.2f бл", ex == WaterClass.Expansion.JHP ? "JHP" : "SP",
                        Math.round(100 * (1 - ex.drop())), ex.span());
                String stop = c.slows() ? String.format(java.util.Locale.ROOT, "%.2f", WaterPath.stopDepth(c, ex, WaterPath.Settings.DEFAULT)) : c.length <= 0 ? "сразу" : "—";
                String one = c.slows() ? Math.round(100 * Math.exp(-WaterPath.drag(c.length, ex, 1))) + "%" : c.length <= 0 ? "0" : "100%";
                b.append("| `").append(e.getKey()).append("` | ").append(c).append(" | ").append(l).append(" | ").append(open).append(" | ").append(stop).append(" | ").append(one).append(" |\n");
            }
            Files.writeString(Path.of(out), b);
        }
    }

    @Test
    void explosivesByTheirNumbers() {
        // a launched charge without its own speed is a rocket, a slow one with a fuze a grenade; no fuze: a bullet
        assertEquals(WaterClass.ROCKET, WaterClass.of("ammo_other_rocket", false, 1, 0, 0, 0, true));
        assertEquals(WaterClass.GRENADE, WaterClass.of("ammo_other_shell", false, 1, 0, 0, 2, true));
        assertEquals(WaterClass.PISTOL, WaterClass.of("ammo_other_shell", false, 1, 0, 0, 2, false));
        assertTrue(WaterClass.GRENADE.explosive && WaterClass.ROCKET.explosive && !WaterClass.RIFLE.explosive);
    }
}
