package acup.gunfeel.gas;

import acup.gunfeel.smoke.SmokeKind;

/**
 * NTM's own gases that do something to the living, as clouds of the smoke system: what NTM's chemical thrower leaves
 * where its gas stream ends ({@link ChemClouds}). Each is drawn in its fluid's colour and does what NTM's own mist of
 * that fluid does, by the cloud's density ({@link ChemClouds#affect}): steam scalds, the hotter the worse (from 500
 * degrees it sets one on fire), cold oxygen freezes and slows, UF6 and PuF6 eat at one and contaminate, waste gas
 * contaminates. Chlorine, phosgene and mustard are this mod's {@link GasKind}s instead (doses, the gas mask); the
 * flammable gases burn in the stream (NTM's fire) and leave nothing; the harmless ones (air, helium, xenon, CO2...)
 * leave nothing either.
 *
 * <p>Common code: the colours are NTM's fluid colours as of its 1.0.3 data, so the client needs nothing of NTM's
 * fluids to draw them. The smoke ids go over the network and stay for good.
 */
public enum NtmGas {
    // fluid (hbm:<path>), smoke id, colour, opacity of one block at C = 1, how it moves (+1 rises, -1 sinks), hold (s), thinning a tick
    STEAM("steam", 16, 0xE5E5E5, 0.30F, 1, 2, 0.94F),
    HOT_STEAM("hotsteam", 17, 0xE7D6D6, 0.25F, 1, 2, 0.94F),
    SUPERHOT_STEAM("superhotsteam", 18, 0xE7B7B7, 0.20F, 1, 1, 0.94F),
    ULTRAHOT_STEAM("ultrahotsteam", 19, 0xE39393, 0.15F, 1, 1, 0.94F),
    AIRBLAST("airblast", 20, 0xFFDADA, 0.04F, 1, 1, 0.94F),
    OXYGEN("oxygen", 21, 0x98BDF9, 0.06F, -1, 4, 0.96F),
    UF6("uf6", 22, 0xD1CEBE, 0.25F, -1, 10, 0.97F),
    PUF6("puf6", 23, 0x4C4C4C, 0.25F, -1, 10, 0.97F),
    WASTE_GAS("wastegas", 24, 0xB8B8B8, 0.12F, 0, 10, 0.97F);

    /** Cells of density 1 a cloud of one "grenade" lets out (a GL-54 gas grenade's charge, about). */
    public static final float VOLUME = 200F;
    /** A light gas goes up as fast as a heavy one goes down; the share of a dense cell carried up a tick. */
    public static final float LIGHT_RISE = 0.5F, LIGHT_LIFT = 0.006F;

    /** NTM's fluid, {@code hbm:<fluid>}. */
    public final String fluid;
    private final SmokeKind smoke;

    NtmGas(String fluid, int smokeId, int rgb, float alpha, int moves, int holdSeconds, float fade) {
        this.fluid = fluid;
        float extinction = (float) -Math.log(1.0 - alpha);
        float rise = moves > 0 ? LIGHT_RISE : moves < 0 ? GasKind.HEAVY_RISE : 0F;
        float lift = moves > 0 ? LIGHT_LIFT : moves < 0 ? GasKind.HEAVY_SINK : 0.0005F;
        this.smoke = new SmokeKind(smokeId, "ntm_" + fluid, VOLUME, GasKind.RELEASE, 1, 0F, 0F, 0.80F, 0.08F, rise, lift,
                Math.max(0, holdSeconds * 20 - GasKind.RELEASE), fade, rgb, extinction);
    }

    /** The cloud kind it makes. */
    public SmokeKind smoke() {
        return smoke;
    }

    /** The NTM gas of a smoke kind, or null. */
    public static NtmGas of(SmokeKind kind) {
        for (NtmGas g : values()) if (g.smoke.id() == kind.id()) return g;
        return null;
    }

    /** The NTM gas of a fluid's path in NTM's namespace, or null. */
    public static NtmGas byFluid(String path) {
        for (NtmGas g : values()) if (g.fluid.equals(path)) return g;
        return null;
    }
}
