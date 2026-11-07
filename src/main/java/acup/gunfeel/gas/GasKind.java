package acup.gunfeel.gas;

import com.hbm.hazard.HazardClass;

import acup.gunfeel.smoke.SmokeKind;

/**
 * The gases of the GL-54 grenades, each a kind of cloud in the smoke's grid (the same cells, walls and thinning as
 * smoke) with what it does to the living: its colour, how heavy it is, its charge and how long it holds, the doses
 * where its symptoms start, the wear it puts on a gas-mask filter and the class of protection that keeps it out
 * (NTM's {@link HazardClass}). Common code. A concentration C is the cell's density at most 1: grenades fired into one
 * place make a bigger and longer cloud, not a stronger one. The effects' own numbers are in {@link GasRules}.
 */
public enum GasKind {
    // key, smoke id, colour (HUD), opacity of one block at C = 1 (mob sight), the drawn colour and opacity (a pale haze),
    // heavy, charge Q, hold + release (s), fill, protection, filter wear
    CHLORINE("chlorine", 1, 0xB9D43C, 0.50F, 0x7C8464, 0.16F, true, 220F, 14, 0.80F, HazardClass.GAS_LUNG, 10F),
    PHOSGENE("phosgene", 2, 0xDDE8D2, 0.18F, 0x80807C, 0.08F, true, 160F, 10, 0.80F, HazardClass.GAS_LUNG, 14F),
    MUSTARD("mustard", 3, 0xD8B431, 0.80F, 0x867A60, 0.23F, true, 120F, 8, 0.80F, HazardClass.GAS_BLISTERING, 20F),
    ACID("acid", 4, 0x4FD66A, 0.55F, 0x707E74, 0.16F, false, 200F, 12, 0.80F, HazardClass.GAS_LUNG, 0F);

    /** Ticks over which a grenade lets its charge out. */
    public static final int RELEASE = 20;
    /** A heavy gas goes down 1.5 times as fast as it goes up 0.5 (the grid's {@code rise}). */
    public static final float HEAVY_RISE = -0.5F;
    /** Share of a dense cell of a heavy gas that sinks a tick whatever its neighbours (the grid's {@code lift}). */
    public static final float HEAVY_SINK = -0.006F;
    /** The thinning a tick once the hold is over: about 6 s from full to nothing. */
    public static final float DECAY = 0.97F;

    public final String key;
    /** The HUD's colour of this gas. */
    public final int rgb;
    /** Share of the light one block of this gas at C = 1 stops for a mob's sight (phosgene 18%: almost clear). */
    public final float alpha;
    /** The cloud as the player sees it: a pale colour and the share one block at C = 1 stops. */
    public final int lookRgb;
    public final float lookAlpha;
    public final boolean heavy;
    /** The charge: cells of density 1 in all. */
    public final float charge;
    public final float holdSeconds;
    public final HazardClass protection;
    /** Filter wear a tick at C = 1 (NTM's own gases wear it by 1; this applies to this mod's gases only). */
    public final float filterWear;
    /** For a later fire-and-explosion rule; nothing reads it yet. */
    public final boolean flammable;
    private final SmokeKind smoke;

    GasKind(String key, int smokeId, int rgb, float alpha, int lookRgb, float lookAlpha, boolean heavy, float charge, int holdSeconds, float fill, HazardClass protection, float filterWear) {
        this.key = key;
        this.rgb = rgb;
        this.alpha = alpha;
        this.lookRgb = lookRgb;
        this.lookAlpha = lookAlpha;
        this.heavy = heavy;
        this.charge = charge;
        this.holdSeconds = holdSeconds;
        this.protection = protection;
        this.filterWear = filterWear;
        this.flammable = false;
        float extinction = (float) -Math.log(1.0 - alpha);
        // one point to let it out of; the heavy ones sink and pool, the acid goes where it spreads
        this.smoke = new SmokeKind(smokeId, key, charge, RELEASE, 1, 0F, 0F, fill, 0.08F, heavy ? HEAVY_RISE : 0F, heavy ? HEAVY_SINK : 0.0005F,
                Math.max(0, holdSeconds * 20 - RELEASE), DECAY, lookRgb, extinction, (float) -Math.log(1.0 - lookAlpha));
    }

    /** The cloud kind to let out. */
    public SmokeKind smoke() {
        return smoke;
    }

    /** The gas of a smoke kind, or null for smoke. */
    public static GasKind of(SmokeKind kind) {
        for (GasKind g : values()) if (g.smoke.id() == kind.id()) return g;
        return null;
    }

    public static GasKind byKey(String key) {
        for (GasKind g : values()) if (g.key.equals(key)) return g;
        return null;
    }
}
