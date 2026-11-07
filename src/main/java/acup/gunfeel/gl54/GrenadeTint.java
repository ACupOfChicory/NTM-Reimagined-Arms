package acup.gunfeel.gl54;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// The colour of a grenade item's nose (items/gl54_grenade_<kind>.json): layer 0 is the grey body, never tinted, layer 1 the
// artist's mask of the nose in grey, tinted here with the kind's colour (GrenadeKind, the same as the HUD's). Client only.
public record GrenadeTint(String kind) implements ItemTintSource {
    public static final MapCodec<GrenadeTint> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(Codec.STRING.fieldOf("kind").forGetter(GrenadeTint::kind)).apply(i, GrenadeTint::new));

    @Override
    public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
        return GrenadeKind.argbOf(kind);
    }

    @Override
    public MapCodec<GrenadeTint> type() {
        return MAP_CODEC;
    }
}
