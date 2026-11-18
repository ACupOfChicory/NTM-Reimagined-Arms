package acup.gunfeel.heat;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.compat.Feature;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * A barrel's heat on the two guns that fire long from one barrel: the M2 and the AM-180 (the minigun turns its barrels
 * as the real one does, so it is left as it is). Heat goes from 0 to 1: each shot adds the gun's own share (times the
 * server's gunOverheat), it cools by Newton, TAU in the air, TAU_WET in water or rain. It is kept on the gun (two of
 * NTM's values beside its wear: the heat and the tick it was so) and worked out as it is read, so a gun cools in a
 * chest or on the ground as well as in hand, with nothing ticking it.
 * <p>
 * What it does, from these heats up:
 * <ul>
 * <li>SPREAD_FROM: the shot spreads more, at 1 by SPREAD_MAX times the gun's own spread over it;</li>
 * <li>WEAR_FROM: the barrel wears faster, at 1 WEAR_MAX times a shot's wear;</li>
 * <li>the AM-180 (open bolt, no round waits in a hot chamber): JAM_FROM, a stoppage in the burst, at 1 JAM_MAX a shot;</li>
 * <li>the M2 (closed bolt: a round sits in the hot chamber after the trigger is let go): COOK_FROM, the round goes off on
 * its own COOK_DELAY ticks after, at 1 COOK_MAX of the times it stops.</li>
 * </ul>
 * The numbers by the real guns: an M2's barrel is dangerously hot after some 180 rounds in one burst (here 18 s at NTM's
 * 600 a minute), while its sustained 40 a minute keeps it at about a quarter; an AM-180's light barrel takes a 177-round
 * pan to about half, a second pan straight after to 0.8, the third to the top (the balance's slower one with 60 rounds as
 * much a magazine).
 */
public final class Overheat {
    private Overheat() {
    }

    public enum Gun {
        M2("hbm:gun_m2", 1F / 155F, 2, 7),
        AM180("hbm:gun_am180", 1F / 365F, 1, 15);

        public final String id;
        /** heat a shot adds at NTM's own rate of fire */
        public final float perShot;
        /** NTM's ticks between shots, which perShot is for */
        public final int delay;
        /** an AI bot's longest burst once the barrel is warm (AI_SHORT) */
        public final int aiBurst;

        Gun(String id, float perShot, int delay, int aiBurst) {
            this.id = id;
            this.perShot = perShot;
            this.delay = delay;
            this.aiBurst = aiBurst;
        }

        // the item each is, looked up once (the registry is fixed by then)
        private net.minecraft.world.item.Item item;

        public static Gun of(ItemStack stack) {
            if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ItemGunBaseNT)) return null;
            for (Gun g : values()) {
                if (g.item == null) g.item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(g.id));
                if (g.item == stack.getItem()) return g;
            }
            return null;
        }
    }

    /** cooling, ticks for the heat to fall to 1/e: 60 s in the air, 15 s in water or rain */
    public static final float TAU = 1200F, TAU_WET = 300F;
    public static final float SPREAD_FROM = 0.5F, SPREAD_MAX = 3F;
    public static final float WEAR_FROM = 0.6F, WEAR_MAX = 5F;
    public static final float JAM_FROM = 0.7F, JAM_MAX = 0.1F;
    public static final float COOK_FROM = 0.9F, COOK_MAX = 0.5F;
    public static final int COOK_DELAY_MIN = 10, COOK_DELAY_MAX = 40;
    /** smoke from the muzzle, a hiss */
    public static final float SMOKE_FROM = 0.5F, HISS_FROM = 0.8F;
    /** AI bots: shorter bursts and longer pauses from AI_SHORT; at AI_WAIT a bot holds fire until the barrel is at AI_RESUME */
    public static final float AI_SHORT = 0.6F, AI_WAIT = 0.85F, AI_RESUME = 0.5F;

    static final String KEY_HEAT = "gunfeel_heat", KEY_TICK = "gunfeel_heat_t";

    /** 0 below from, 1 at to and over, a line between */
    public static float ramp(float h, float from, float to) {
        return Mth.clamp((h - from) / (to - from), 0F, 1F);
    }

    /** heat after this many ticks of cooling with this time constant */
    public static float cooled(float h, long ticks, float tau) {
        if (ticks <= 0) return h;
        return h * (float) Math.exp(-ticks / (double) tau);
    }

    /**
     * the heat after a shot at this heat (the server's scale on the share), the gun firing one in this many ticks: a gun
     * firing slower than NTM's (the balance's AM-180, one in 3 with 60 rounds) puts as much more heat into each, so a
     * magazine emptied heats it as much
     */
    public static float afterShot(float h, Gun gun, float scale, int delay) {
        return Math.min(1F, h + gun.perShot * scale * Math.max(1, delay) / gun.delay);
    }

    /** spread added at this heat to a gun of this innate spread */
    public static float spreadAdd(float h, float innate) {
        return innate * SPREAD_MAX * ramp(h, SPREAD_FROM, 1F);
    }

    /** a shot's wear at this heat, times */
    public static float wearMult(float h) {
        return 1F + (WEAR_MAX - 1F) * ramp(h, WEAR_FROM, 1F);
    }

    /** the chance a shot of this gun at this heat jams the next (the AM-180 only) */
    public static float jamChance(Gun gun, float h) {
        return gun == Gun.AM180 ? JAM_MAX * ramp(h, JAM_FROM, 1F) : 0F;
    }

    /** the chance the round left in the chamber goes off as the trigger is let go at this heat (the M2 only) */
    public static float cookChance(Gun gun, float h) {
        return gun == Gun.M2 ? COOK_MAX * ramp(h, COOK_FROM, 1F) : 0F;
    }

    /** the server's gunOverheat: heat a shot times this (0 off) */
    public static float scale() {
        return Math.max(0F, ReaimTimes.number("gunOverheat", 1F));
    }

    public static boolean on() {
        return Feature.OVERHEAT.on();
    }

    // the gun's values, as NTM keeps its wear

    /** the heat now (the game's tick now: the server's, or the client's copy of it) */
    public static float heat(ItemStack stack, long now) {
        if (!on() || Gun.of(stack) == null) return 0F;
        float h = ItemGunBaseNT.getValueFloat(stack, KEY_HEAT);
        if (h <= 0F) return 0F;
        return cooled(h, now - tick(stack, now), TAU);
    }

    /** the tick the heat was last set (shot or cooled faster): a gun's last shot near enough */
    public static long tick(ItemStack stack, long now) {
        // kept as the low 31 bits of the game's tick, put back as the one nearest now (a year and more of game ticks each
        // way); a client a tick or two behind the server sees its last shot a little ahead, as now
        return unwrap(ItemGunBaseNT.getValueInt(stack, KEY_TICK), now);
    }

    /** a tick kept as its low 31 bits, put back next to now (never after it) */
    static long unwrap(int kept, long now) {
        int low = kept & 0x7FFFFFFF;
        long t = (now & ~0x7FFFFFFFL) | low;
        if (t - now > 0x40000000L) t -= 0x80000000L;
        else if (now - t > 0x40000000L) t += 0x80000000L;
        return Math.min(t, now);
    }

    public static void set(ItemStack stack, float h, long now) {
        ItemGunBaseNT.setValueFloat(stack, KEY_HEAT, h < 1e-3F ? 0F : h);
        ItemGunBaseNT.setValueInt(stack, KEY_TICK, (int) (now & 0x7FFFFFFFL));
    }
}
