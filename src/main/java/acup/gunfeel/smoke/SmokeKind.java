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
 *                   a heavy gas pools)
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
    private static final List<SmokeKind> ALL = List.of(SMOKE);

    public static SmokeKind byId(int id) {
        for (SmokeKind k : ALL) if (k.id == id) return k;
        for (acup.gunfeel.gas.GasKind g : acup.gunfeel.gas.GasKind.values()) if (g.smoke().id == id) return g.smoke();
        return SMOKE;
    }

    public static SmokeKind byName(String name) {
        for (SmokeKind k : ALL) if (k.name.equalsIgnoreCase(name)) return k;
        for (acup.gunfeel.gas.GasKind g : acup.gunfeel.gas.GasKind.values()) if (g.smoke().name.equalsIgnoreCase(name)) return g.smoke();
        return null;
    }

    /** Ticks a cloud of this kind can live at most (the release, the hold and the thinning down to nothing). */
    public int maxAge() {
        // the slowest cell (the noise keeps up to fade + 0.015) from well above fill down to SmokeGrid.GONE
        double thin = Math.log(SmokeGrid.GONE / 8.0) / Math.log(Math.min(0.999, fade + 0.015));
        return release + hold + (int) Math.ceil(thin) + 20;
    }
}
