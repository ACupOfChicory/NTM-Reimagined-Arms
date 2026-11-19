package acup.gunfeel.host;

/**
 * What an NTM build can do, as its version's NTM side reports it ({@link Ports#ntm}): NTM NEXT on 26.2, Continuum on
 * 26.1, DE on 1.21.1, CE on 1.12.2 and the original on 1.7.10 differ in these. A core system asks before it uses one
 * and turns itself off where it is missing, rather than all of core going down to the poorest NTM. Not the mod's own
 * systems switched off for an untested NTM ({@code compat.Feature}): those are ours, these are NTM's.
 */
public enum NtmFeature {
    /** Armour and masks carry hazard classes (lung, blistering gas): the gases' protection ({@code gas.GasProtection}). */
    HAZARD_CLASSES,
    /** NTM's chemical clouds as entities (mist, the chemthrower's): the gases' clouds take them over. */
    MIST_CLOUDS,
    /** White phosphorus grenades, rounds and shells: the smoke's WP hazes. */
    WP_MUNITIONS,
    /** Bullets as NTM's MK4 entities with a config per round: the ballistics, water and the balance work on them. */
    MK4_BULLETS,
    /** The guns' animation bus (HbmAnimations): the arms and the recoil follow it. */
    ANIM_BUS,
    /** Guns and machines drawn as Flywheel models: the hitboxes and the arms read their parts. */
    FLYWHEEL_MODELS,
    /** Machines as multiblocks with a core block: the bullets' shapes and the hitboxes take the whole machine. */
    MULTIBLOCK_CORES,
}
