package acup.gunfeel.gl54;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// The colour of the Nth grenade on a magazine's icon (items/gl54_mag.json): the icon is the author's grey body with the
// places of the grenades painted as empty holes (textures/item/gl54_mag.png, never tinted) and one grey mask per place
// (gl54_mag_mask_0..7.png: 0 is the long strip on top, the grenade that feeds next, 1..7 the side pixels top to bottom), each
// tinted by the kind of the grenade in that place of the remaining list, so the marks shift up as the gun fires. Where the
// magazine has no Nth grenade the mask takes the hole's colour. Client only.
public record MagTint(int index) implements ItemTintSource {
    public static final MapCodec<MagTint> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(com.mojang.serialization.Codec.INT.fieldOf("index").forGetter(MagTint::index)).apply(i, MagTint::new));

    @Override
    public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
        return colorOf(MagItem.contents(stack), index);
    }

    // the colour of the empty hole (the body's darkest, as the author's mag.py paints it) where there is no such grenade. Not
    // a transparent tint: a layer with alpha 0 does not leave the base showing, it blanks the pixel in the item's picture
    public static final int EMPTY = 0xFF12121E;

    static int colorOf(MagContents c, int index) {
        return index < c.size() ? GrenadeKind.argbOf(c.rounds().get(index)) : EMPTY;
    }

    @Override
    public MapCodec<MagTint> type() {
        return MAP_CODEC;
    }
}
