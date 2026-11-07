package acup.gunfeel.gl54;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// The colour of the Nth grenade on a magazine's icon (items/gl54_mag.json): the icon is a grey base (textures/item/gl54_mag.png,
// never tinted) and one grey mask per grenade place (gl54_mag_mask_0..7.png), each tinted by the kind of the grenade in that
// place; where the magazine has no Nth grenade the tint is fully transparent, so nothing is drawn. The artist's picture goes
// in by replacing those PNGs (tools/gl54_mag_tint.py cuts a picture into them), no code changes. Client only.
public record MagTint(int index) implements ItemTintSource {
    public static final MapCodec<MagTint> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(com.mojang.serialization.Codec.INT.fieldOf("index").forGetter(MagTint::index)).apply(i, MagTint::new));

    @Override
    public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
        return colorOf(MagItem.contents(stack), index);
    }

    // 0 (fully transparent, nothing drawn) where there is no such grenade
    static int colorOf(MagContents c, int index) {
        return index < c.size() ? GrenadeKind.argbOf(c.rounds().get(index)) : 0x00FFFFFF;
    }

    @Override
    public MapCodec<MagTint> type() {
        return MAP_CODEC;
    }
}
