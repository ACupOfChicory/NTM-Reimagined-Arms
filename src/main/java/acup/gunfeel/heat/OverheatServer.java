package acup.gunfeel.heat;

import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.sound.EntitySounds;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Server: the barrel's heat (Overheat) as NTM fires and ticks its guns. A shot adds its heat and may set a stoppage for
 * the next (MixinOverheat, on Lego.doStandardFire); a hot barrel wears faster and spreads more (the same mixin); a stoppage
 * set is played as NTM's own jam when its decider would fire again (MixinOverheatDecider); each tick of the gun
 * (MixinOverheatTick): water and rain cool it faster with a hiss and steam, a hot one hisses in hand, and the M2's round
 * left in a hot chamber goes off on its own.
 */
public final class OverheatServer {
    private OverheatServer() {
    }

    static final String KEY_JAM = "gunfeel_heat_jam", KEY_COOK = "gunfeel_heat_cook", KEY_ROLLED = "gunfeel_heat_rolled";
    /** the tick of the gun's last shot, low 31 bits (the heat's own tick moves on as water cools it too) */
    static final String KEY_SHOT = "gunfeel_heat_shot";
    /** water and rain: cooled faster this often, ticks */
    static final int WET_EVERY = 10;
    /** the hiss of a hot gun in hand, ticks between */
    static final int HISS_EVERY = 20;

    /** a shot has been fired (server): its heat added, a stoppage maybe set for the next */
    public static void shot(ItemStack stack, ItemGunBaseNT.LambdaContext ctx) {
        LivingEntity shooter = ctx == null ? null : ctx.entity();
        if (shooter == null || ctx.config() == null || !(shooter.level() instanceof ServerLevel level) || !Overheat.on()) return;
        Overheat.Gun gun = Overheat.gun(stack);
        if (gun == null) return;
        float scale = Overheat.scale();
        if (scale <= 0F) return;
        long now = level.getGameTime();
        Receiver[] recs = ctx.config().getReceivers(stack);
        int delay = recs == null || recs.length == 0 ? gun.delay : recs[0].getDelayAfterFire(stack);
        float h = Overheat.afterShot(Overheat.heat(stack, now), gun, scale, delay);
        Overheat.set(stack, h, now);
        ItemGunBaseNT.setValueInt(stack, KEY_SHOT, low(now));
        if (shooter.getRandom().nextFloat() < Overheat.jamChance(gun, h)) ItemGunBaseNT.setValueInt(stack, KEY_JAM, 1);
    }

    /** a shot's wear (NTM's: the gun's worn so far plus the round's) as the heat has it: the part the shot adds, times */
    public static float wear(ItemStack stack, int index, float wear, LivingEntity shooter) {
        if (shooter == null || shooter.level().isClientSide() || Overheat.gun(stack) == null) return wear;
        float before = ItemGunBaseNT.getWear(stack, index);
        float mult = Overheat.wearMult(Overheat.heat(stack, shooter.level().getGameTime()));
        if (mult <= 1F || wear <= before) return wear;
        return before + (wear - before) * mult;
    }

    /** the spread a shot gets on top of the rest from the heat */
    public static float spread(ItemStack stack, Receiver primary, LivingEntity shooter) {
        if (shooter == null || primary == null || Overheat.gun(stack) == null) return 0F;
        return Overheat.spreadAdd(Overheat.heat(stack, shooter.level().getGameTime()), primary.getInnateSpread(stack));
    }

    /**
     * NTM's decider about to fire again after a shot's cooldown: a stoppage set by that shot instead (NTM's jam: its
     * animation, its time, then ready for a new pull). True: the decider is not to go on.
     */
    public static boolean jam(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, int recIndex, int gunIndex) {
        if (ItemGunBaseNT.getValueInt(stack, KEY_JAM) == 0) return false;
        ItemGunBaseNT.setValueInt(stack, KEY_JAM, 0);
        if (ctx == null || ctx.entity() == null || !Overheat.on() || Overheat.gun(stack) == null) return false;
        Receiver rec = ctx.config().getReceivers(stack)[recIndex];
        int time = rec.getJamDuration(stack);
        if (time <= 0) return false;
        ItemGunBaseNT.setState(stack, gunIndex, ItemGunBaseNT.GunState.JAMMED);
        ItemGunBaseNT.setTimer(stack, gunIndex, time);
        ItemGunBaseNT.playAnimation(ctx.getPlayer(), stack, GunAnimation.JAMMED, gunIndex);
        return true;
    }

    /** NTM ticks the gun (server; slot null: in an inventory, not worn) */
    public static void tick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        if (!Overheat.on()) return;
        Overheat.Gun gun = Overheat.gun(stack);
        if (gun == null || !(entity instanceof LivingEntity living)) return;
        boolean held = slot == EquipmentSlot.MAINHAND;
        if (!held) {
            // out of the hand nothing waits: a stoppage or a cook-off set goes (a gun picked up later is not fired by an old one)
            if (ItemGunBaseNT.getValueInt(stack, KEY_JAM) != 0) ItemGunBaseNT.setValueInt(stack, KEY_JAM, 0);
            if (ItemGunBaseNT.getValueInt(stack, KEY_COOK) != 0) ItemGunBaseNT.setValueInt(stack, KEY_COOK, 0);
        }
        if (ItemGunBaseNT.getValueFloat(stack, Overheat.KEY_HEAT) <= 0F) return;
        long now = level.getGameTime();
        float h = Overheat.heat(stack, now);
        if (living.isInWaterOrRain() && now % WET_EVERY == 0) {
            // the faster cooling: the air's and the water's on top, 1/TAU_WET in all
            float extra = 1F / (1F / Overheat.TAU_WET - 1F / Overheat.TAU);
            float wet = Overheat.cooled(h, WET_EVERY, extra);
            if (held && h > 0.3F) {
                Vec3 m = muzzle(living);
                level.playSound(null, m.x, m.y, m.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.15F + 0.35F * h, 1.6F);
                level.sendParticles(ParticleTypes.WHITE_SMOKE, m.x, m.y, m.z, 1 + (int) (3 * h), 0.08, 0.04, 0.08, 0.01);
            }
            Overheat.set(stack, wet, now);
            h = wet;
        } else if (held && h > Overheat.HISS_FROM && now % HISS_EVERY == living.getId() % HISS_EVERY) {
            Vec3 m = muzzle(living);
            level.playSound(null, m.x, m.y, m.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS,
                    0.06F + 0.12F * Overheat.ramp(h, Overheat.HISS_FROM, 1F), 1.9F);
        }
        if (held && gun == Overheat.Gun.M2) cookOff(stack, level, living, h, now);
    }

    /**
     * The M2 fires from a closed bolt: let go with the barrel hot, the round in the chamber may go off on its own a
     * moment after. Each stop rolls once (the tick of the last shot marks it); a pull before it goes takes it.
     */
    private static void cookOff(ItemStack stack, ServerLevel level, LivingEntity living, float h, long now) {
        ItemGunBaseNT.GunState state = ItemGunBaseNT.getState(stack, 0);
        int cook = ItemGunBaseNT.getValueInt(stack, KEY_COOK);
        boolean idle = state == ItemGunBaseNT.GunState.IDLE && !ItemGunBaseNT.getPrimary(stack, 0);
        if (!idle) {
            if (cook != 0) ItemGunBaseNT.setValueInt(stack, KEY_COOK, 0);
            return;
        }
        long last = Overheat.unwrap(ItemGunBaseNT.getValueInt(stack, KEY_SHOT), now);
        int lastLow = low(last);
        if (cook == 0) {
            if (ItemGunBaseNT.getValueInt(stack, KEY_ROLLED) == lastLow || now - last > 5) return;
            ItemGunBaseNT.setValueInt(stack, KEY_ROLLED, lastLow);
            if (living.getRandom().nextFloat() >= Overheat.cookChance(Overheat.Gun.M2, h)) return;
            int delay = Overheat.COOK_DELAY_MIN + living.getRandom().nextInt(Overheat.COOK_DELAY_MAX - Overheat.COOK_DELAY_MIN + 1);
            // 0 is none: the tick it goes, never 0
            ItemGunBaseNT.setValueInt(stack, KEY_COOK, Math.max(1, low(now + delay)));
            return;
        }
        // not yet: its tick ahead of now (by under a quarter of the 31 bits; the bits wrap round)
        int ahead = (cook - low(now)) & 0x7FFFFFFF;
        if (ahead != 0 && ahead < 0x20000000) return;
        ItemGunBaseNT.setValueInt(stack, KEY_COOK, 0);
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return;
        GunConfig cfg = gun.getConfig(stack, 0);
        Receiver rec = cfg.getReceivers(stack)[0];
        Player player = living instanceof Player p ? p : null;
        ItemGunBaseNT.LambdaContext ctx = new ItemGunBaseNT.LambdaContext(cfg, living, player != null ? player.getInventory() : null, 0);
        if (!rec.getCanFire(stack).apply(stack, ctx)) return;
        rec.getOnFire(stack).accept(stack, ctx);
        if (rec.getFireSound(stack) != null) EntitySounds.play(living, living.getX(), living.getY(), living.getZ(), rec.getFireSound(stack).get(),
                SoundSource.PLAYERS, rec.getFireVolume(stack), rec.getFirePitch(stack));
        ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.COOLDOWN);
        ItemGunBaseNT.setTimer(stack, 0, rec.getDelayAfterFire(stack));
        // this shot is its stop's own: no second roll for it
        ItemGunBaseNT.setValueInt(stack, KEY_ROLLED, ItemGunBaseNT.getValueInt(stack, KEY_SHOT));
    }

    /** the stands: the gun's last shot at this tick (a trigger just let go) */
    public static void lastShot(ItemStack stack, long tick) {
        ItemGunBaseNT.setValueInt(stack, KEY_SHOT, low(tick));
    }

    static int low(long tick) {
        return (int) (tick & 0x7FFFFFFFL);
    }

    /** about where the muzzle is: along the look from the eyes, a little down and to the right */
    public static Vec3 muzzle(LivingEntity e) {
        Vec3 look = e.getViewVector(1F);
        Vec3 right = new Vec3(-look.z, 0, look.x).normalize();
        return e.getEyePosition().add(look.scale(1.1)).add(right.scale(0.25)).add(0, -0.25, 0);
    }
}
