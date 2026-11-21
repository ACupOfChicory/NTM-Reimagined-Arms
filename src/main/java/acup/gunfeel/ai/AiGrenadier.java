package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import acup.gunfeel.Vec3d;
import acup.gunfeel.Vecs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 26.2's grenadier: the game reads around core's decisions. */
public final class AiGrenadier extends AiGrenadierBase {
    private AiGrenadier() {
    }

    private static final Staff<AiBot, AiSoldier> STAFF = new Staff<>() {
        @Override public boolean mayPlan(Object level, long tick) { return AiGrenadesBase.mayPlan(level, tick); }
        @Override public @Nullable Vec3d burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz,
                int ticks, int minY, double gravity, double[] land) {
            return AiGrenadesBase.burst(src, x, y, z, vx, vy, vz, ticks, 0, 0, minY, gravity, 1, null, land, Vec3d::new);
        }
        @Override public Vec3d eye(AiBot b) { return Vecs.of(AiLean.eye(b.mob)); }
        @Override public VoxelRay.Source voxels(Object level) { return AiBots.voxels((ServerLevel) level); }
        @Override public boolean ownNear(Object level, VoxelRay.Source src, AiBot b, Vec3d at) { return AiGrenades.ownNear((ServerLevel) level, src, b.mob, at.x(), at.y(), at.z(), 0); }
        @Override public CoverQuery.@Nullable Spot spot(AiSoldier s) { return s.spot(); }
        @Override public void note(AiSoldier s, AiBot b, long tick, String text) { s.note(b, tick, text); }
        @Override public acup.gunfeel.host.EntityView entities(Object level) { return new acup.gunfeel.LevelEntities((ServerLevel) level); }
        @Override public void aim(AiBot b, Vec3d at) { b.gunner.aim(Vecs.mc(at)); }
        @Override public void fire(AiBot b, boolean on) { b.gunner.fire(on); }
    };

    public static boolean on() { return enabled(); }

    /** it is a grenadier that lobs: the role, and 40 mm grenades in its gun (else it is as a rifleman) */
    static boolean is(AiBot bot) {
        return AiRoles.of(bot) == AiRole.GRENADIER && on() && shell(bot.mob.getMainHandItem()) != null;
    }

    /** the round in this gun's magazine, if it is a 40 mm grenade (not a rocket: those fly straight) */
    @SuppressWarnings("rawtypes")
    static @Nullable BulletConfig shell(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return null;
        try {
            IMagazine mag = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
            if (mag == null || !(mag.getType(stack, null) instanceof BulletConfig b)) return null;
            return b instanceof acup.gunfeel.gl54.GrenadeType || acup.gunfeel.ExplosiveAmmo.heavy(b) && !acup.gunfeel.ExplosiveAmmo.rocket(b) ? b : null;
        } catch (RuntimeException e) {
            // a magazine that wants an inventory
            return null;
        }
    }

    /** the loaded shell's numbers, at their original API read site */
    static boolean shellNumbers(@Nullable BulletConfig b, acup.gunfeel.host.Shell into) {
        if (b == null) return false;
        into.velocity = b.velocity;
        into.gravity = b.gravity;
        into.expires = b.expires;
        return true;
    }

    /** its gun's shells go as its lobs only (AiGunner): a grenadier with 40 mm grenades in its gun */
    static boolean lobsOnly(Mob mob, ItemStack stack) {
        AiBot b = AiBots.get(mob);
        return b != null && is(b);
    }

    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) { return fight(STAFF, s, bot, level, c, tick); }

    static @Nullable Vec3 lastBurst(AiBot bot) { Vec3d b = burstOf(bot); return b == null ? null : Vecs.mc(b); }

    public static String info() { return status(); }
}
