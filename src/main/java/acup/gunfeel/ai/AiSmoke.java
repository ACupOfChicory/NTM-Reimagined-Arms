package acup.gunfeel.ai;

import com.hbm.entity.grenade.EntityGrenadeUniversal;
import com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling;
import com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze;
import com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell;
import com.hbm.items.weapon.grenade.ItemGrenadeUniversal;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/**
 * Smoke on a fall back (stage3-plan 3.3b): a bot falling back first throws a smoke grenade half the way to the threat,
 * one of its squad at a time; the cloud is the GL-54 smoke grenade's (SmokeClouds), and a mob does not see through it
 * (Senses). The grenade is NTM's, thrown as the frags are (AiGrenades.plan); its item is marked, and where it would
 * have gone off it lets the cloud out instead (MixinAiSmoke, both sides: the client shows no blast either). No one
 * runs from it (AiGrenades.threat). Server thread but for {@link #burst} on the client.
 */
public final class AiSmoke {
    /** the server setting: how many a bot carries */
    public static final String KEY = "aiSmokes";
    /** where its smoke grenades left are kept on the mob */
    static final String STOCK_KEY = "armsaiSmokes";
    /** the mark on a smoke grenade's item; the way it was thrown, x and z (the cloud's fan) */
    static final String MARK = "armsaiSmoke", DX = "armsaiSmokeDx", DZ = "armsaiSmokeDz";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** the burst this share of the way to the threat, from MIN to MAX blocks off */
    static final double SHARE = 0.5, MIN = 5, MAX = 10;
    /**
     * thrown this hard, blocks a tick (a frag's 1: its nearest burst, a lob, some 16 blocks off), with NTM's impact fuze
     * (it goes off where it first hits a block from half a second on): a lob lands 5 to 10 blocks off and stays there
     */
    static final double FORCE = 0.55;
    /** none with the threat nearer than this, blocks (no time for the cloud) */
    static final double NEAR = 8;
    /** none again by it or its squad for this many ticks (35 seconds: a cloud now and then, the frags the more) */
    static final int GAP = 700;
    /** the stands: smoke grenades thrown, and of them those that let smoke out (a cloud, or more to one there) */
    static int thrown, clouds;
    /** the stands: falls back with no smoke, by why (WHY) */
    static final int[] skipped = new int[6];
    static final String[] WHY = {"off or none left", "not ready", "smoke off", "threat near or its squad's a while ago", "no throw planned this tick", "no throw there"};
    /**
     * Put on NTM's grenade by MixinAiSmoke. A smoke grenade is thrown only with it there and NTM's {@code explode()} as
     * the mixin knows it: else it would go off as the powder grenade it is.
     */
    public interface Burst {
    }

    private static @org.jspecify.annotations.Nullable Boolean ready;

    private AiSmoke() {
    }

    /** smoke grenades a mob new to the AI carries */
    static int stock() {
        return Math.max(0, Math.min(4, Math.round(acup.gunfeel.ReaimTimes.number(KEY, 1F))));
    }

    /** how far towards a threat this far off it aims the burst, blocks */
    static double aim(double threat) {
        return Mth.clamp(threat * SHARE, MIN, MAX);
    }

    /** smoke grenades may be thrown: MixinAiSmoke is on NTM's grenade, and its explode() is there to go into */
    static boolean ready() {
        if (ready == null) {
            boolean r = Burst.class.isAssignableFrom(EntityGrenadeUniversal.class);
            try {
                r &= EntityGrenadeUniversal.class.getMethod("explode").getReturnType() == void.class;
            } catch (NoSuchMethodException e) {
                r = false;
            }
            ready = r;
        }
        return ready;
    }

    /**
     * NTM's grenade, its item marked as smoke, the way it is thrown on it (yaw: as the mob's); a frag's shell, an impact
     * fuze; powder, should the mark ever be lost
     */
    static ItemStack item(float yaw) {
        ItemStack s = ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, EnumGrenadeFilling.POWDER, EnumGrenadeFuze.IMPACT);
        double g = Math.toRadians(-yaw);
        CustomData.update(DataComponents.CUSTOM_DATA, s, t -> {
            t.putBoolean(MARK, true);
            t.putFloat(DX, (float) Math.sin(g));
            t.putFloat(DZ, (float) Math.cos(g));
        });
        return s;
    }

    /** a smoke grenade (the mark on its item) */
    static boolean smoke(Entity e) {
        return e instanceof EntityGrenadeUniversal g && g.getGrenadeItem().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(MARK);
    }

    /** thrown by the mob with this turn, FORCE hard */
    static void toss(ServerLevel level, Mob m, AiGrenades.Throw t) {
        AiGrenades.launch(level, m, t, item(t.yaw()), FORCE / EnumGrenadeShell.FRAG.getYeetForce());
        thrown++;
    }

    /**
     * NTM's grenade going off (MixinAiSmoke): a smoke grenade lets its cloud out (on the server), fanned on the way it
     * was thrown, and is gone. Whether it was one.
     */
    public static boolean burst(EntityGrenadeUniversal g) {
        if (!smoke(g)) return false;
        g.discard();
        if (g.level() instanceof ServerLevel level) {
            CustomData d = g.getGrenadeItem().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            net.minecraft.nbt.CompoundTag t = d.copyTag();
            Vec3 dir = new Vec3(t.getFloatOr(DX, 0F), 0, t.getFloatOr(DZ, 0F));
            if (SmokeClouds.spawn(level, g.position(), dir, SmokeKind.SMOKE, 1F) >= 0) clouds++;
        }
        return true;
    }
}
