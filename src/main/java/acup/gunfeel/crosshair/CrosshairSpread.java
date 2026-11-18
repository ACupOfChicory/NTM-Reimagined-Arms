package acup.gunfeel.crosshair;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;

import acup.gunfeel.Spread;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

// Client: the spread a shot of the player's would have right now, for the dynamic crosshair: NTM's own (Lego.calcSpread:
// the gun, the round loaded, the wear) with the mod's on top (Spread: stance, moving, the air; MixinLegoSpread), the
// same sum the server works out for the shot. Not the burst's bloom: the server keeps that, the crosshair has its own
// kick on each shot. NTM's own unit: a shot's direction (of length 1) is off by a normal share of it on each axis.
public final class CrosshairSpread {
    private CrosshairSpread() {
    }

    /** NTM's spread, or NaN when the gun has no round to tell it by */
    public static float spread(LocalPlayer player, ItemStack stack, boolean aim) {
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return Float.NaN;
        var config = gun.getConfig(stack, 0);
        Receiver[] receivers = config.getReceivers(stack);
        if (receivers == null || receivers.length == 0 || receivers[0].getMagazine(stack) == null) return Float.NaN;
        Receiver r = receivers[0];
        if (!(r.getMagazine(stack).getType(stack, player.getInventory()) instanceof BulletConfig bullet)) return Float.NaN;
        var ctx = new ItemGunBaseNT.LambdaContext(config, player, player.getInventory(), 0);
        float s = Spread.quietly(() -> Lego.calcSpread(ctx, stack, r, bullet, true, 0, aim));
        // a beam's spread is in degrees (EntityBulletBeamBase)
        return bullet.pType == BulletConfig.ProjectileType.BEAM ? (float) Math.toRadians(s) : s;
    }
}
