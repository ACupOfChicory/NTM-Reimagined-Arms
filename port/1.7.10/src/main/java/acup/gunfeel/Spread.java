package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

// port: copied from 26.2 (Spread.java, e284339, see notes/ports/port-1.7.10-copied.md). 1.7.10 has no crawling (spreadCrawl
// is never used) and no elytra; the shooter's speed is how far it moved in its last update (posX - lastTickPosX: a
// player's last position packet on the server) instead of getKnownSpeed; the gun's id is put in 26.2's form (id).

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

    private static final Map<EntityLivingBase, Bloom> BLOOM = new WeakHashMap<>();
    // the test log: the game tick of each shooter's last logged shot (the first call in a tick is the shot)
    private static final Map<EntityLivingBase, Long> LOGGED = new WeakHashMap<>();

    private Spread() {
    }

    public static synchronized float apply(EntityLivingBase e, ItemStack stack, boolean aim, float spread) {
        if (stack == null || stack.getItem() == null) return spread;
        String gun = id(stack.getItem());
        boolean crawl = false;
        boolean sneak = e.isSneaking();
        double mx = e.posX - e.lastTickPosX, mz = e.posZ - e.lastTickPosZ;
        float pace = SpreadModel.pace((float) Math.sqrt(mx * mx + mz * mz));
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
        float bloom = bloom(e, gun);
        float total = SpreadModel.total(spread, mult, get(gun, "spreadSpeed", 0F), pace, air, bloom, stance);
        if (TestLog.on(e.worldObj)) {
            long now = e.worldObj.getTotalWorldTime();
            Long last = LOGGED.put(e, now);
            if (last == null || last != now) {
                Object[] kv = {"who", e.getCommandSenderName(), "gun", gun, "aim", aim, "crawl", crawl, "sneak", sneak, "sprint", e.isSprinting(),
                        "air", inAir(e), "pace", pace, "base", spread, "mult", mult, "bloom", bloom, "total", total};
                if (e.worldObj.isRemote) TestLog.c(-1, "spread", kv);
                else TestLog.s(-1, "spread", kv);
            }
        }
        return total;
    }

    // the gun's id as 26.2 writes it, the settings' keys ("hbm:gun_uzi"; NTM 1.7.10 registers "hbm:item.gun_uzi"), as
    // GunFeelClient.gunId, which is the client's
    static String id(Item item) {
        String s = String.valueOf(Item.itemRegistry.getNameForObject(item));
        int colon = s.indexOf(':');
        return s.startsWith("item.", colon + 1) ? s.substring(0, colon + 1) + s.substring(colon + 6) : s;
    }

    private static boolean inAir(EntityLivingBase e) {
        if (e.onGround || e.ridingEntity != null || e.isOnLadder() || e.isInWater()) return false;
        return !(e instanceof EntityPlayer && ((EntityPlayer) e).capabilities.isFlying);
    }

    // NTM works the spread out once per projectile, so the first call in a tick is the shot and the rest are its pellets
    private static float bloom(EntityLivingBase e, String gun) {
        float perShot = get(gun, "spreadBloom", 0F);
        if (perShot <= 0F || e.worldObj.isRemote) return 0F;
        long now = e.worldObj.getTotalWorldTime();
        Bloom b = BLOOM.get(e);
        if (b == null) BLOOM.put(e, b = new Bloom());
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
