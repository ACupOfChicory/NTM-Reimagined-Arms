package acup.gunfeel;

import java.util.function.BiConsumer;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory10ga;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.items.weapon.sedna.factory.XFactoryAccelerator;
import com.hbm.items.weapon.sedna.factory.XFactoryPA;
import com.hbm.render.anim.AnimationEnums.GunAnimation;

import acup.gunfeel.compat.Feature;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * proneCrawlNoFire (server, ntm-gunfeel-server.properties, on by default; a gun can have its own:
 * hbm\:gun_xxx.proneCrawlNoFire = false): no shot while crawling. A shooter in the crawling pose (Prone.lying: the prone
 * key, crawling under a trapdoor, a mob laid down) that moves along the ground faster than proneCrawlSpeed (blocks a
 * second) does not fire; lying still, or only turning the view, it fires as before. Aiming and reloading are left alone.
 *
 * <p>The speed is the shooter's step over its last tick as the server counts it (Entity.computeSpeed, EntityAccess): a
 * player's is where its client's move packets took it, a mob's its own move. Not ServerPlayer.getKnownSpeed: that is
 * the last packet's step, and a packet that only turns the view (sent each frame while a fire key is held, ClickAim)
 * makes it zero. Crawling is about 1.3 blocks a second; a player who lets the key go drops below 0.25 on the third
 * step, which the server knows a tick or two later.
 *
 * <p>Only the server decides: NTM's client plays a shot (its animation, recoil and sound) only when the server says
 * so, so a shot that does not happen shows nothing and no round is spent. Where NTM fires (MixinProneFire*): a click on
 * an idle gun (Lego.clickReceiver) does nothing, not even a dry fire, and is not kept for later; a held burst stops
 * (GunStateDecider.deciderAutoRefire: the gun goes idle, a new click is needed); and the few guns that fire from their
 * own key actions (the double barrels' and the SPAS's second trigger, the Tau's charged shot, power armour's rockets)
 * skip them. A Tau charge let go while crawling fizzles as one too short.
 */
public final class ProneFire {
    public static final String KEY = "proneCrawlNoFire", SPEED = "proneCrawlSpeed";
    public static final float DEFAULT_SPEED = 0.25F;

    private ProneFire() {
    }

    /** The rule is on for this gun (the server's file, or its copy on a connected client). */
    public static boolean on(ItemStack stack) {
        if (!Feature.PRONE_FIRE.on()) return false;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return ReaimTimes.flag(id + "." + KEY, ReaimTimes.flag(KEY, true));
    }

    /** The speed above which a lying shooter crawls, in blocks a tick (0: any step at all; a broken value: the default). */
    static double threshold() {
        float s = ReaimTimes.number(SPEED, DEFAULT_SPEED);
        return (Float.isFinite(s) ? Math.max(0F, s) : DEFAULT_SPEED) / 20.0;
    }

    /** A step of dx, dz blocks in a tick is crawling along. */
    static boolean crawls(double dx, double dz, double threshold) {
        return dx * dx + dz * dz > threshold * threshold;
    }

    /** This shooter may not fire this gun now: lying, crawling along, on the server. */
    public static boolean blocks(LivingEntity entity, ItemStack stack) {
        if (entity == null || entity.level().isClientSide() || !Prone.lying(entity) || !on(stack)) return false;
        var v = ((acup.gunfeel.mixin.mc.EntityAccess) entity).gunfeel$lastKnownSpeed();
        return v != null && crawls(v.x, v.z, threshold());
    }

    /** Lego.clickReceiver on an idle gun: true = no shot (and no dry fire). */
    public static boolean blocksClick(ItemStack stack, ItemGunBaseNT.LambdaContext ctx) {
        if (ctx == null || ItemGunBaseNT.getState(stack, ctx.configIndex()) != ItemGunBaseNT.GunState.IDLE) return false;
        boolean no = blocks(ctx.entity(), stack);
        if (no && TestLog.server) TestLog.s(-1, "proneFire", "click", true);
        return no;
    }

    /** NTM's held burst about to fire again while crawling (blocks): it stops, the gun goes idle. */
    public static void stopBurst(ItemStack stack, int gunIndex) {
        ItemGunBaseNT.setState(stack, gunIndex, ItemGunBaseNT.GunState.IDLE);
        ItemGunBaseNT.setTimer(stack, gunIndex, 0);
        if (TestLog.server) TestLog.s(-1, "proneFire", "burst", true);
    }

    /** A gun's own press or release action that fires by itself (not through clickReceiver): true = it is skipped. */
    public static boolean blocksAction(BiConsumer<?, ?> action, ItemStack stack, ItemGunBaseNT.LambdaContext ctx) {
        if (ctx == null || !firing(action, stack, ctx.configIndex()) || !blocks(ctx.entity(), stack)) return false;
        if (action == XFactoryAccelerator.LAMBDA_TAU_SECONDARY_RELEASE
                && ItemGunBaseNT.getLastAnim(stack, ctx.configIndex()) == GunAnimation.SPINUP) {
            // NTM's own end of a charge too short to fire: the spin-up stops, a later release fires nothing
            ItemGunBaseNT.playAnimation(ctx.getPlayer(), stack, GunAnimation.CYCLE_DRY, ctx.configIndex());
        }
        if (TestLog.server) TestLog.s(-1, "proneFire", "action", true);
        return true;
    }

    private static boolean firing(BiConsumer<?, ?> action, ItemStack stack, int index) {
        if (action == null) return false;
        // the second trigger fires only on an idle gun; while reloading it cancels the reload, which is left alone
        if (action == XFactory10ga.LAMBDA_DOUBLE_SECONDARY || action == XFactory12ga.LAMBDA_SPAS_SECONDARY)
            return ItemGunBaseNT.getState(stack, index) == ItemGunBaseNT.GunState.IDLE;
        return action == XFactoryAccelerator.LAMBDA_TAU_SECONDARY_PRESS || action == XFactoryAccelerator.LAMBDA_TAU_SECONDARY_RELEASE
                || action == XFactoryPA.LAMBDA_CLICK_RANGED_PRIMARY || action == XFactoryPA.LAMBDA_CLICK_RANGED_SENONDARY;
    }
}
