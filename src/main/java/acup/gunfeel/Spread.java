package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// The living spread: NTM's bullet spread by the shooter's state, server settings in ntm-gunfeel-server.properties (the
// mod's own values and why are in ntmgunfeel-server.properties; a gun can have its own as <gun id>.spreadAim and so on).
// The arithmetic is in SpreadModel:
//  - spreadStand, spreadMove, spreadSneak, spreadCrawl, spreadAim multiply NTM's spread (1 = NTM as it is);
//    spreadMove is the walking one and grows with speed (half of it at half the walking speed, more sprinting);
//    spreadStack = 1: every state counts (sneaking and moving and aiming multiply together),
//    spreadStack = 0: only the body state that weighs most (crawl, then sneak, then move, else stand), times aim;
//  - spreadSpeed is added per walking speed, spreadAir while off the ground;
//  - spreadBloom is added per shot, at most spreadBloomMax, halving every spreadBloomHalfLife seconds; sneaking and
//    crawling hold it down by their multipliers.
public final class Spread {
    // per shooter: the game tick of the last shot, the bloom that shot met, and the bloom it left
    private static final class Bloom {
        long tick = Long.MIN_VALUE;
        float met, left;
    }

    private static final Map<LivingEntity, Bloom> BLOOM = new WeakHashMap<>();

    private Spread() {
    }

    public static float apply(LivingEntity e, ItemStack stack, boolean aim, float spread) {
        String gun = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean crawl = e.isVisuallyCrawling();
        boolean sneak = !crawl && e.isCrouching();
        var mv = e.getKnownSpeed(); // blocks moved last tick; for a player, what the client last sent
        float pace = SpreadModel.pace((float) Math.sqrt(mv.x * mv.x + mv.z * mv.z));
        float move = SpreadModel.moveMult(get(gun, "spreadMove", 1F), pace);
        float stance = crawl ? get(gun, "spreadCrawl", 1F) : sneak ? get(gun, "spreadSneak", 1F) : 1F;
        float mult;
        if (get(gun, "spreadStack", 1F) >= 0.5F) {
            mult = stance * move;
            if (!crawl && !sneak && pace == 0F) mult *= get(gun, "spreadStand", 1F);
        } else {
            mult = crawl || sneak ? stance : pace > 0F ? move : get(gun, "spreadStand", 1F);
        }
        if (aim) mult *= get(gun, "spreadAim", 1F);
        float air = inAir(e) ? get(gun, "spreadAir", 0F) : 0F;
        return SpreadModel.total(spread, mult, get(gun, "spreadSpeed", 0F), pace, air, bloom(e, gun), stance);
    }

    private static boolean inAir(LivingEntity e) {
        if (e.onGround() || e.isPassenger() || e.onClimbable() || e.isInWater() || e.isFallFlying()) return false;
        return !(e instanceof Player p && p.getAbilities().flying);
    }

    // NTM works the spread out once per projectile, so the first call in a tick is the shot and the rest are its pellets
    private static float bloom(LivingEntity e, String gun) {
        float perShot = get(gun, "spreadBloom", 0F);
        if (perShot <= 0F || e.level().isClientSide()) return 0F;
        long now = e.level().getGameTime();
        Bloom b = BLOOM.computeIfAbsent(e, k -> new Bloom());
        if (b.tick != now) {
            float half = get(gun, "spreadBloomHalfLife", 0.25F);
            b.met = b.tick == Long.MIN_VALUE ? 0F : SpreadModel.decay(b.left, now - b.tick, half);
            b.left = SpreadModel.afterShot(b.met, perShot, get(gun, "spreadBloomMax", 0F));
            b.tick = now;
        }
        return b.met;
    }

    private static float get(String gun, String key, float def) {
        return ReaimTimes.number(gun + "." + key, ReaimTimes.number(key, def));
    }
}
