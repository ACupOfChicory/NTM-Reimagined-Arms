package acup.gunfeel;

import net.minecraft.world.World;

// port: the world's rule that the sun sets no undead on fire (26.2: the test range's datapack empties the entity tag
// burn_in_daylight). 1.12.2 has no such tag: zombies and skeletons set themselves on fire in their own update
// (EntityZombie, AbstractSkeleton.onLivingUpdate) unless they wear something on the head, and the 1.12.2 range dressed
// them in their own heads for it (owner: "a zombie on the CE range wears a zombie head"). `/gamerule ntmgunfeelSunBurn
// false` keeps them bare and unburnt; fire from fire, lava or an incendiary round still burns them. Without the rule
// the sun burns as in vanilla.
public final class SunBurn {
    public static final String RULE = "ntmgunfeelSunBurn";

    private SunBurn() {
    }

    public static boolean burns(World world) {
        var rules = world.getGameRules();
        return !rules.hasRule(RULE) || rules.getBoolean(RULE);
    }
}
