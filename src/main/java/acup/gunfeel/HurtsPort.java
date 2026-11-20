package acup.gunfeel;

import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Hurts;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;

/**
 * The game's hurts for core (Hurts): no call of NTM's here. Each of NTM's reads is made by the class whose system it
 * is (NtmApiTest counts them by it): the damage types the cap exempts by DamageCap.Reads (CORE), the IR beam's fire,
 * the electric type, the direct entity's kind and NTM's category by BalanceArmor.Reads (BALANCE); the game's tags and
 * the type's key here.
 */
final class HurtsPort implements Hurts {
    /** the type's key as text, one String a key (ResourceKey is interned: the same in every world) */
    private static final ConcurrentHashMap<ResourceKey<?>, String> NAMES = new ConcurrentHashMap<>();

    @Override
    public boolean is(Object hurt, int what) {
        DamageSource s = (DamageSource) hurt;
        return switch (what) {
            case BYPASSES_EFFECTS -> s.is(DamageTypeTags.BYPASSES_EFFECTS);
            case FIRE -> s.is(DamageTypeTags.IS_FIRE);
            case FALL -> s.is(DamageTypeTags.IS_FALL);
            case NTM_NUCLEAR_BLAST, NTM_DIGAMMA, NTM_RADIATION -> DamageCap.Reads.is(s, what);
            case NTM_FIRE -> BalanceArmor.Reads.ntmFire(s);
            case NTM_ELECTRIC -> BalanceArmor.Reads.ntmElectric(s);
            default -> throw new IllegalArgumentException("no question " + what);
        };
    }

    @Override
    public int directKind(Object hurt) {
        return BalanceArmor.Reads.directKind((DamageSource) hurt);
    }

    @Override
    public @Nullable String ntmCategory(Object hurt) {
        return BalanceArmor.Reads.ntmCategory((DamageSource) hurt);
    }

    @Override
    public @Nullable String typeName(Object hurt) {
        var key = ((DamageSource) hurt).typeHolder().unwrapKey().orElse(null);
        return key == null ? null : NAMES.computeIfAbsent(key, k -> k.identifier().toString());
    }
}
