package acup.gunfeel.gl54;

import java.util.List;

// The kinds of 20 mm grenade (and so of magazine) of the GL-54, with what the HUD and the wheel need of each: the key
// kept in saved magazines (a string, never a number: the magazines live in worlds for years), the short code, the colour
// of the grenade's tip and the order in the wheel. No game classes here, so the numbers are tested without the game.
// Only HE has a flight of its own so far (Gl54Grenade); the others are kinds of magazine and fly as HE (stage 3).
public enum GrenadeKind {
    // the damage line of the wheel ("per shot" to compare kinds by); the numbers of design.md and of the first mock-up, null
    // = none yet. Only HE's are in force so far; the others are the design's, for stage 3
    AIRBURST("airburst", "AB", 0x4FA3F7, "1.3 \u00D740", 50F),
    AP("ap", "AP", 0x9B5CF6, "1.3 \u00D720", 25F),
    HE("he", "HE", 0xE5483C, "18", 18F),
    INCENDIARY("inc", "INC", 0xFF9A2E, "0.8 \u00D720", 15F),
    SMOKE("smoke", "SMK", 0xC4C6C8, null, 0F),
    CHLORINE("chlorine", "CL", 0xC8E03C, null, 0F),
    PHOSGENE("phosgene", "PH", 0x8FD6A0, null, 0F),
    MUSTARD("mustard", "MU", 0xB8860B, null, 0F),
    ACID("acid", "AC", 0x19C97A, null, 0F);

    // the kinds a magazine can be that no single grenade is: all kinds mixed, and nothing in it
    public static final String MIXED = "mixed", EMPTY = "empty";
    // the tip colour of the mixed magazine's sector and of a magazine without a kind
    public static final int MIXED_COLOR = 0xFFFFFF;
    // a mixed magazine for the wheel's picture and the creative tab: pairs, in the order of the first design
    public static final List<String> SAMPLE_MIX = List.of("airburst", "airburst", "he", "he", "inc", "inc", "smoke", "smoke");

    public final String key, code, damage;
    public final int rgb;
    public final float perShot;

    GrenadeKind(String key, String code, int rgb, String damage, float perShot) {
        this.key = key;
        this.code = code;
        this.rgb = rgb;
        this.damage = damage;
        this.perShot = perShot;
    }

    public int argb() {
        return 0xFF000000 | rgb;
    }

    // the lang key of the kind's name (written out, so the test of lang keys finds each)
    public String nameKey() {
        return switch (this) {
            case AIRBURST -> "item.ntmgunfeel.gl54_kind.airburst";
            case AP -> "item.ntmgunfeel.gl54_kind.ap";
            case HE -> "item.ntmgunfeel.gl54_kind.he";
            case INCENDIARY -> "item.ntmgunfeel.gl54_kind.inc";
            case SMOKE -> "item.ntmgunfeel.gl54_kind.smoke";
            case CHLORINE -> "item.ntmgunfeel.gl54_kind.chlorine";
            case PHOSGENE -> "item.ntmgunfeel.gl54_kind.phosgene";
            case MUSTARD -> "item.ntmgunfeel.gl54_kind.mustard";
            case ACID -> "item.ntmgunfeel.gl54_kind.acid";
        };
    }

    // the kind of a key in a saved magazine; null for a key this build does not know
    public static GrenadeKind of(String key) {
        for (GrenadeKind k : values()) if (k.key.equals(key)) return k;
        return null;
    }

    // the colour of a grenade (white for a key not known)
    public static int argbOf(String key) {
        GrenadeKind k = of(key);
        return k == null ? 0xFFFFFFFF : k.argb();
    }

    // the code on the screen: "HE", for a kind unknown "?"
    public static String codeOf(String key) {
        GrenadeKind k = of(key);
        return k == null ? "?" : k.code;
    }

    // the position of a kind in the wheel's order, the mixed one last; unknown ones after
    public static int order(String kind) {
        GrenadeKind k = of(kind);
        return k != null ? k.ordinal() : MIXED.equals(kind) ? values().length : values().length + 1;
    }

    // does the wheel have a damage figure for this kind (the gases and the smoke show a dash)
    public boolean hasNumbers() {
        return damage != null;
    }
}
