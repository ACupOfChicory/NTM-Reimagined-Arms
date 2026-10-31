package acup.gunfeel;

// port: new for 1.7.10. The strings and colours of 26.2's Hud.java (0762086) without the game in them, the same
// formats, so the tests check them (HudFormatTest); Hud draws with them.
final class HudFormat {
    static final String INF = "∞";
    // a belt off the inventory: NTM counts an infinite ammo bag with its ammo as this many
    static final int BELT_INFINITE = 9999;

    private HudFormat() {
    }

    // the magazine's rounds, three digits
    static String big(int amount) {
        return String.format("%03d", Math.min(amount, 999));
    }

    // the reserve, four digits, or ∞
    static String small(int reserve) {
        return reserve == AmmoLogic.INFINITE ? INF : String.format("%04d", Math.min(reserve, 9999));
    }

    // red from a quarter of the magazine down
    static boolean low(int amount, int capacity) {
        return capacity > 0 && amount <= Math.max(1, capacity / 4);
    }

    // leading zeros of the big number that are drawn dim (only next to a reserve; the last digit never)
    static int lead(String big, boolean reserve) {
        int lead = 0;
        if (reserve) while (lead < big.length() - 1 && big.charAt(lead) == '0') lead++;
        return lead;
    }

    // the gun's health 0..1 from its wear and durability
    static float health(float wear, float durability) {
        return durability > 0 ? Math.max(0F, Math.min(1F, 1F - wear / durability)) : 1F;
    }

    static int durabilityColor(float health) {
        int r = (int) (255 * Math.max(0F, Math.min(1F, 2F - 2F * health)));
        int gr = (int) (255 * Math.max(0F, Math.min(1F, 2F * health)));
        return 0xFF000000 | r << 16 | gr << 8 | 0x40;
    }
}
