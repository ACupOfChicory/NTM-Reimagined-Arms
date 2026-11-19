package acup.gunfeel;

// What one ammo does from one gun, worked out as NTM does it when the bullet hits, for the numbers in the ammo menu:
// damage = gun base damage × wear × the ammo's damageMult (Lego.calcDamage, EntityBulletBaseMK4), projectiles =
// min..max × the gun's split, cut to whole ones (Lego.doStandardFire), a head hit × headshotMult when that is over 1
// (BulletConfig.LAMBDA_STANDARD_ENTITY_HIT), armour per projectile as DamageResistanceHandler.calculateDamage.
// No game classes here, so the tests check it on the numbers dumped from NTM (GunDump).
public record AmmoStats(float damage, float projectiles, float headshotMult, float thresholdNegation, float piercing) {
    // the armour the menu compares against: NTM's T-51 power armour, physical damage (threshold 2, resistance 15 %), what
    // players usually wear (the author, 2026-10-23); armoured mobs come near it (titanium 3, 10 %), most mobs wear none,
    // and for those the plain damage line holds
    public static final float REF_DT = 2F, REF_DR = 0.15F;

    public static AmmoStats of(float gunDamage, float wear, float split, float damageMult, int min, int max, float headshotMult,
            float thresholdNegation, float piercing) {
        return new AmmoStats(gunDamage * wear * damageMult, projectiles(min, max, split), headshotMult, thresholdNegation, piercing);
    }

    // the average number of projectiles a shot fires: every count from min to max equally likely, each times split cut
    // down to a whole number
    public static float projectiles(int min, int max, float split) {
        if (max < min) max = min;
        float sum = 0;
        for (int k = min; k <= max; k++) sum += (int) (k * split);
        return sum / (max - min + 1);
    }

    // NTM's wear factor on damage: full up to 75 % worn, then down to half at fully worn (Lego.getStandardWearDamage)
    public static float wear(float worn, float durability) {
        if (durability <= 0) return 1F;
        float p = worn / durability;
        return p < 0.75F ? 1F : 1F - (p - 0.75F) * 2F;
    }

    // what is left of one hit after armour with that threshold and resistance (DamageResistanceHandler.calculateDamage)
    public static float throughArmor(float amount, float dt, float dr, float thresholdNegation, float piercing) {
        dt = Math.max(0F, dt - thresholdNegation);
        if (dt >= amount) return 0F;
        dr *= Math.clamp(1F - piercing, 0F, 2F);
        return (amount - dt) * (1F - dr);
    }

    public boolean shotgun() {
        return projectiles > 1;
    }

    public float perShot() {
        return damage * projectiles;
    }

    public float headshot() {
        return headshotMult > 1F ? damage * headshotMult : damage;
    }

    // a whole shot into the reference armour: every projectile meets the armour on its own
    public float perShotArmored() {
        return throughArmor(damage, REF_DT, REF_DR, thresholdNegation, piercing) * projectiles;
    }

    // 12.5 → "12.5", 12.0 → "12", 0.04 → "0"; one decimal as NTM's tooltip
    public static String fmt(float v) {
        float r = Math.round(v * 10F) / 10F;
        return r == (int) r ? Integer.toString((int) r) : Float.toString(r);
    }

    // "+3", "−1.5", "" when about the same
    public static String delta(float now, float loaded) {
        float d = Math.round((now - loaded) * 10F) / 10F;
        if (Math.abs(d) < 0.05F) return "";
        return (d > 0 ? "+" : "−") + fmt(Math.abs(d));
    }
}
