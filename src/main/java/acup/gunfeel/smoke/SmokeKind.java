package acup.gunfeel.smoke;

import java.util.List;

/**
 * What a cloud is made of: how it is let out, how it spreads, how long it stays and how it looks. Common code (server
 * and client); the id goes over the network, so a kind keeps its id for good.
 *
 * <p>Plain smoke is here. The GL-54's gas grenades have their kinds in {@code acup.gunfeel.gas.GasKind} (the heavy ones
 * heavier than air: {@code lift} below 0, {@code rise} below 0; their own colour and opacity); what a gas does to the
 * living is not here, the simulation only carries the concentration.
 *
 * @param volume     cells of density 1 let out in all (an open field: a half sphere of about 5.5 blocks)
 * @param release    ticks over which it is let out
 * @param bomblets   points it comes out of: the burst point and the rest fanned forward (TGMC's smoke: 5, 24 degrees)
 * @param fan        half the fan, degrees
 * @param reach      how far ahead the farthest bomblet lands, blocks
 * @param fill       density the cloud fills up to and stops spreading at (dense smoke: one block of it lets ~8% through)
 * @param spread     share of the difference above {@code fill} a cell gives a neighbour a tick (stable below 1/12)
 * @param rise       spreading up is {@code 1 + rise} times as fast and down {@code 1 - rise} (smoke: the same)
 * @param lift       share of a dense cell (above half of fill) carried up a tick whatever its neighbours (below 0: down;
 *                   a heavy gas pools, white phosphorus's haze lies low)
 * @param hold       ticks after the release before it starts to thin
 * @param fade       share kept a tick while it thins
 * @param rgb        colour, 0xRRGGBB
 * @param extinction optical depth of one block at density 1 (Beer-Lambert: 3.5 lets ~3% through); what a mob sees
 *                   through ({@link SmokeSight})
 * @param look       the same as drawn for the player (the volume and the fog inside); smoke: {@code extinction}
 */
public record SmokeKind(int id, String name, float volume, int release, int bomblets, float fan, float reach, float fill, float spread,
                        float rise, float lift, int hold, float fade, int rgb, float extinction, float look) {
    /** Drawn as dense as it stops a mob's sight. */
    public SmokeKind(int id, String name, float volume, int release, int bomblets, float fan, float reach, float fill, float spread,
                     float rise, float lift, int hold, float fade, int rgb, float extinction) {
        this(id, name, volume, release, bomblets, fan, reach, fill, spread, rise, lift, hold, fade, rgb, extinction, extinction);
    }

    public static final SmokeKind SMOKE = new SmokeKind(0, "smoke", 380F, 40, 5, 24F, 3.5F, 0.7F, 0.08F, 0F, 0.001F, 400, 0.968F,
            0xB4B6B8, 3.5F);
    /**
     * NTM's white phosphorus hand grenade ({@code acup.gunfeel.gas.WpSmoke}): a white haze, as NTM's own was, not a smoke
     * screen. Half a smoke grenade let out of seven points ringed up to 3.5 blocks round the burst lies low and flat as
     * the heavy gases do (it goes out to the sides five times as fast as up, runs down a slope and into a trench): some
     * 17 blocks across and 3 high. One block of it stops about 5% of the light, the whole of it seen from the side about
     * half, the player's and a mob's sight alike. It holds while NTM's fire on the ground burns (30 s) and thins after.
     * A second grenade in it does not feed it: the two hazes lie one in the other, as a gas's two clouds do
     * ({@link SmokeClouds#feeds}).
     */
    public static final SmokeKind WP = wp(5, "wp");
    /**
     * The same haze from NTM's white phosphorus rounds and shells (40 mm, rocket, artillery, HIMARS): a kind of its own
     * only so that the sparks of the burning phosphorus ({@code WpSparks}) stay the grenade's, as NTM's hazes threw them.
     */
    public static final SmokeKind WP_SHELL = wp(6, "wp_shell");
    private static final List<SmokeKind> ALL = List.of(SMOKE, WP, WP_SHELL);

    private static SmokeKind wp(int id, String name) {
        return new SmokeKind(id, name, 180F, 40, 7, 24F, 3.5F, 0.45F, 0.08F, -0.8F, -0.002F, 480, 0.968F, 0xF0F0EC, 0.10F);
    }

    public static SmokeKind byId(int id) {
        for (SmokeKind k : ALL) if (k.id == id) return k;
        for (acup.gunfeel.gas.GasKind g : acup.gunfeel.gas.GasKind.values()) if (g.smoke().id == id) return g.smoke();
        for (acup.gunfeel.gas.NtmGas g : acup.gunfeel.gas.NtmGas.values()) if (g.smoke().id == id) return g.smoke();
        return SMOKE;
    }

    public static SmokeKind byName(String name) {
        for (SmokeKind k : ALL) if (k.name.equalsIgnoreCase(name)) return k;
        for (acup.gunfeel.gas.GasKind g : acup.gunfeel.gas.GasKind.values()) if (g.smoke().name.equalsIgnoreCase(name)) return g.smoke();
        for (acup.gunfeel.gas.NtmGas g : acup.gunfeel.gas.NtmGas.values()) if (g.smoke().name.equalsIgnoreCase(name)) return g.smoke();
        return null;
    }

    /**
     * Cells of the grid below the burst cell: a kind that sinks ({@code lift} below 0: a heavy gas, white phosphorus's
     * haze) gets twice smoke's, to run down into a cellar.
     */
    public int below() {
        return lift < 0F ? SmokeGrid.OY_HEAVY : SmokeGrid.OY;
    }

    /** Ticks a cloud of this kind can live at most (the release, the hold and the thinning down to nothing). */
    public int maxAge() {
        // the slowest cell (the noise keeps up to fade + 0.015) from well above fill down to SmokeGrid.GONE
        double thin = Math.log(SmokeGrid.GONE / 8.0) / Math.log(Math.min(0.999, fade + 0.015));
        return release + hold + (int) Math.ceil(thin) + 20;
    }
}
