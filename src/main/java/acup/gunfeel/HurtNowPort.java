package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.HurtNow;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** The game's current hit for core; EuphImmunity.Reads makes NTM's reads. */
final class HurtNowPort implements HurtNow {
    @Override
    public boolean onHead(Body b) {
        return HeadDamage.onHead((LivingEntity) ((EntityBody) b).entity());
    }

    @Override
    public float @Nullable [] floorExtra(Object hurt, boolean blast) {
        return BalanceGuns.extra(BalanceArmor.floorShot((DamageSource) hurt, blast));
    }

    @Override
    public float ntmPierce(Body b, int which) {
        if (which != PIERCE_DT && which != PIERCE_DR) throw new IllegalArgumentException("no pierce " + which);
        return EuphImmunity.Reads.pierce((LivingEntity) ((EntityBody) b).entity(), which);
    }
}
