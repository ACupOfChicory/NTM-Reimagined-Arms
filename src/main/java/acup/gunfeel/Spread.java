package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// The living spread: NTM's bullet spread by the shooter's state, server settings in ntm-gunfeel-server.properties (the
// mod's own values and why are in ntmgunfeel-server.properties; a gun can have its own as <gun id>.spreadAim and so on).
// The settings' steps are LivingSpread's and the arithmetic SpreadModel's (core); here the shooter's state:
//  - spreadStand, spreadMove, spreadSneak, spreadCrawl, spreadAim multiply NTM's spread (1 = NTM as it is);
//    spreadMove is the walking one and grows with speed (half of it at half the walking speed, more sprinting);
//    crawling (lying down on the prone key, or under a trapdoor) moving counts as still, as TaCZ's lie;
//    spreadStack = 1: every state counts (sneaking and moving and aiming multiply together),
//    spreadStack = 0: only the body state that weighs most (crawl, then sneak, then move, else stand), times aim;
//  - spreadAimKnee, spreadAimOver (Fable №3, the shotguns' aimed choke): aimed, NTM's spread past the knee counts
//    spreadAimOver times, before all the rest; spreadHipKnee, spreadHipOver the same from the hip (the SPAS-12, which
//    has no aim; owner 2026-11-13); a knee of 0 (the default): NTM's spread as it is (the balance's keys, BalanceKeys:
//    not the server's file);
//  - spreadSpeed is added per walking speed, spreadAir while off the ground;
//  - spreadBloom is added per shot, at most spreadBloomMax, halving every spreadBloomHalfLife seconds; sneaking and
//    crawling hold it down by their multipliers;
//  - powerArmorSpread.<set>: in NTM's power armour and Trenchmaster (PowerArmor.spread, by the chestplate's set) what
//    moving, the air and the bloom add counts that many times (the pace for spreadMove and spreadSpeed, spreadAir, the
//    bloom); NTM's own spread, the stances and aiming stay. powerArmorSpread with no set: only a power armour
//    chestplate with no line of its own. These are the balance's keys (BalanceKeys): fixed in the mod, the server's
//    file sets them only on the stands (-Dntmgunfeel.balanceKeys=true).
public final class Spread {
    private static final acup.gunfeel.host.ItemTable ITEMS = CoreServices.load(acup.gunfeel.host.ItemTable.class, acup.gunfeel.host.ItemTable.MISSING);
    // per shooter: its bloom (LivingSpread.Bloom)
    private static final Map<LivingEntity, LivingSpread.Bloom> BLOOM = new WeakHashMap<>();
    // the test log: the game tick of each shooter's last logged shot (the first call in a tick is the shot)
    private static final Map<LivingEntity, Long> LOGGED = new WeakHashMap<>();

    private Spread() {
    }

    // a query, not a shot (the dynamic crosshair's, every frame): not in the test log
    private static final ThreadLocal<Boolean> QUERY = ThreadLocal.withInitial(() -> false);

    /** {@code spread} worked out as a query, not a shot: the test log leaves it out */
    public static float quietly(java.util.function.Supplier<Float> spread) {
        boolean was = QUERY.get();
        QUERY.set(true);
        try {
            return spread.get();
        } finally {
            QUERY.set(was);
        }
    }

    public static float apply(LivingEntity e, ItemStack stack, boolean aim, float spread) {
        // the gun's id, one String an item (ItemTable): the keys take it by its text
        String gun = ITEMS.name(ItemNumbers.number(stack.getItem()));
        boolean log = acup.gunfeel.WorldLog.on(e.level()) && !QUERY.get();
        float[] l = log ? new float[7] : null;
        float total = LivingSpread.apply(EntityBody.of(e), gun, aim, spread, SHOOTER, l);
        if (log) {
            Long last = LOGGED.put(e, e.level().getGameTime());
            if (last == null || last != e.level().getGameTime())
                acup.gunfeel.WorldLog.at(e.level(), -1, "spread", "who", e.getPlainTextName(), "gun", gun, "aim", aim, "crawl", l[5] != 0F,
                        "sneak", l[6] != 0F, "sprint", e.isSprinting(), "air", inAir(e), "pace", l[1], "armor", l[2], "base", l[0], "mult", l[3],
                        "bloom", l[4], "total", total);
        }
        return total;
    }

    /** in the air for the spread (spreadAir); the balance stand checks its shooter is not, a click of a share's pass */
    static boolean inAir(LivingEntity e) {
        return LivingSpread.inAir(EntityBody.of(e));
    }

    // the shooter's power armour and bloom (LivingSpread.Shooter): by the Body's entity, nothing made per call
    private static final LivingSpread.Shooter SHOOTER = new LivingSpread.Shooter() {
        @Override
        public float armor(acup.gunfeel.host.Body b, String gun) {
            return PowerArmor.spread((LivingEntity) ((EntityBody) b).entity(), gun);
        }

        // per shooter on the server (a client's crosshair shows none)
        @Override
        public float bloom(acup.gunfeel.host.Body b, String gun, float perShot) {
            LivingEntity e = (LivingEntity) ((EntityBody) b).entity();
            if (e.level().isClientSide()) return 0F;
            return BLOOM.computeIfAbsent(e, k -> new LivingSpread.Bloom()).shot(gun, e.level().getGameTime(), perShot);
        }
    };
}
