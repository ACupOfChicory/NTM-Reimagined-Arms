package acup.gunfeel;

import acup.gunfeel.host.ArmorSuits;
import acup.gunfeel.host.Body;
import net.minecraft.world.entity.LivingEntity;

/** The game's NTM suits for core (ArmorSuits): no call of NTM's here, EuphImmunity.Reads (BALANCE) makes them. */
final class ArmorSuitsPort implements ArmorSuits {
    @Override
    public boolean fullSet(Body b, int suit) {
        return EuphImmunity.Reads.fullSet((LivingEntity) ((EntityBody) b).entity(), suit);
    }

    @Override
    public boolean powered(int item) {
        net.minecraft.world.item.Item it = ItemNumbers.item(item);
        if (it == null) throw new IllegalArgumentException("no item has number " + item);
        return EuphImmunity.Reads.powered(it);
    }
}
