package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;

// a receiver's numbers the balance's gun lines change and turn back (BalanceGuns.sync)
@Mixin(value = Receiver.class, remap = false)
public interface ReceiverAccess {
    @Accessor("delayAfterFire_DNA")
    int gunfeel$delay();

    @Accessor("delayAfterFire_DNA")
    void gunfeel$setDelay(int v);

    @Accessor("refireOnHold_DNA")
    boolean gunfeel$auto();

    @Accessor("refireOnHold_DNA")
    void gunfeel$setAuto(boolean v);

    @Accessor("baseDamage_DNA")
    float gunfeel$damage();

    @Accessor("baseDamage_DNA")
    void gunfeel$setDamage(float v);

    @Accessor("magazine_DNA")
    IMagazine<?> gunfeel$magazine();
}
