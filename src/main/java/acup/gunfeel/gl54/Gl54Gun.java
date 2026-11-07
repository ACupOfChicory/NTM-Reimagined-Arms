package acup.gunfeel.gl54;

import java.util.function.BiConsumer;

import com.hbm.items.weapon.sedna.Crosshair;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.hbm.items.weapon.sedna.factory.XFactory40mm;
import com.hbm.sound.ModSounds;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;

// The GL-54's numbers (design.md, 2026-11-06; marked ones wait for the owner's word).
public final class Gl54Gun {
    public static final float DURABILITY = 800F;
    // a shot every 14 ticks (0.7 s), semi-automatic
    public static final int DELAY = 14;
    // reloading takes 40 ticks (2.0 s) = begin + end; the magazine is swapped where begin ends. An empty gun is cocked at the
    // end: the handle feeds the first grenade in (post), 10 ticks more, 50 (2.5 s)
    public static final int RELOAD_BEGIN = 28, RELOAD_END = 12, RELOAD_EMPTY_POST = 10;
    // TEMPORARY, waiting for the owner: the draw is Congo Lake's
    public static final int DRAW = 7;
    // NTM's camera kick: 4 degrees up give or take 0.5, sideways give or take 1
    public static final float KICK_UP = 4F, KICK_UP_SPREAD = 0.5F, KICK_SIDE_SPREAD = 1F;
    public static final float DAMAGE = Gl54Grenade.BASE_DAMAGE;

    private Gl54Gun() {
    }

    public static GunConfig config() {
        return new GunConfig()
                .dura(DURABILITY)
                .draw(DRAW)
                .crosshair(Crosshair.L_CIRCUMFLEX)
                .smoke(XFactory40mm.LAMBDA_SMOKE)
                .rec(new Receiver(0)
                        .dmg(DAMAGE)
                        .delay(DELAY)
                        .reload(0, RELOAD_BEGIN, RELOAD_BEGIN, RELOAD_END, RELOAD_EMPTY_POST)
                        .jam(0)
                        .sound(() -> (SoundEvent) ModSounds.GUN_CONGO_FIRE.get(), 1.0F, 1.0F)
                        .mag(Gl54Ammo.magazine())
                        .offset(0.75, -0.0625, -0.1875)
                        .setupStandardFire()
                        .canFire((stack, ctx) -> Gl54Server.enabled() && Lego.LAMBDA_STANDARD_CAN_FIRE.apply(stack, ctx))
                        .recoil(RECOIL))
                .setupStandardConfiguration()
                // the second key measures the range while the gun is aimed (Gl54Range)
                .ps(Gl54Range::onKey)
                .pr((stack, ctx) -> {
                    if (Gl54Server.enabled()) Lego.LAMBDA_STANDARD_RELOAD.accept(stack, ctx);
                })
                .anim(Gl54Anim::get)
                .orchestra(Gl54Orchestra.ORCHESTRA);
    }

    static final BiConsumer<ItemStack, ItemGunBaseNT.LambdaContext> RECOIL = (stack, ctx) -> ItemGunBaseNT.setupRecoil(
            KICK_UP + (float) ctx.getPlayer().getRandom().nextGaussian() * KICK_UP_SPREAD,
            (float) ctx.getPlayer().getRandom().nextGaussian() * KICK_SIDE_SPREAD);
}
