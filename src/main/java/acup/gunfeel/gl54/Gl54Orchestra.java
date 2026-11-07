package acup.gunfeel.gl54;

import java.util.function.BiConsumer;

import com.hbm.handler.threading.TargetPoint;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.packet.PacketWire;
import com.hbm.packet.toclient.MuzzleFlashPayload;
import com.hbm.particle.SpentCasing;
import com.hbm.particle.helper.CasingCreator;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.sound.EntitySounds;
import com.hbm.sound.ModSounds;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// The sounds and the case of the GL-54 at their ticks of the animation (server), as NTM's orchestras of the other guns.
public final class Gl54Orchestra {
    // Where the model's origin is from the eye (front, height, side + = left; blocks) and how many blocks a metre of the
    // model is in the hand, hip-fired and aimed. TEMPORARY: measured on the stand (Gl54Probe), the cases come from the port.
    static final double[] HIP_ORIGIN = {0.55, -0.3, -0.35}, AIM_ORIGIN = {0.55, -0.12, 0.0};
    static final double BLOCKS_PER_METRE = 1.0;

    private Gl54Orchestra() {
    }

    static final BiConsumer<ItemStack, ItemGunBaseNT.LambdaContext> ORCHESTRA = (stack, ctx) -> {
        LivingEntity entity = ctx.entity();
        if (entity.level().isClientSide()) return;
        GunAnimation type = ItemGunBaseNT.getLastAnim(stack, ctx.configIndex());
        int timer = ItemGunBaseNT.getAnimTimer(stack, ctx.configIndex());
        Gl54Casing casing = Gl54Casing.get();
        if (type == GunAnimation.CYCLE) {
            if (timer == 0) muzzleFlash(entity);
            if (casing.present() && timer == casing.tick()) eject(stack, ctx, casing);
        }
        if (type == GunAnimation.RELOAD && timer == 0) {
            EntitySounds.play(entity, entity.getX(), entity.getY(), entity.getZ(), ModSounds.GUN_GRENADE_RELOAD.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    };

    static void muzzleFlash(LivingEntity entity) {
        PacketWire.sendToAllAround(new MuzzleFlashPayload(entity),
                new TargetPoint((ServerLevel) entity.level(), entity.getX(), entity.getY(), entity.getZ(), 100.0));
    }

    static void eject(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, Gl54Casing c) {
        LivingEntity entity = ctx.entity();
        IMagazine<?> mag = ctx.config().getReceivers(stack)[0].getMagazine(stack);
        SpentCasing spent = mag.getCasing(stack, ctx.inventory());
        if (spent == null) return;
        double[] at = c.eyeOffset(ItemGunBaseNT.getIsAiming(stack) ? AIM_ORIGIN : HIP_ORIGIN, BLOCKS_PER_METRE);
        CasingCreator.composeEffect(entity.level(), entity, at[0], at[1], at[2], c.velFront(), c.velHeight(), c.velSide(), 0.003,
                c.pitch() + (float) entity.getRandom().nextGaussian() * 2F, c.yaw() + (float) entity.getRandom().nextGaussian() * 2F,
                spent.getName(), true, 60, 0.5, 20);
    }
}
