package acup.gunfeel.gl54;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

// How the gun's GunMag (core) is saved with the stack and sent to the client: the data component of Gl54Items.GUN_MAG.
final class GunMagCodecs {
    public static final Codec<GunMag> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf(0, MagContents.CAPACITY).optionalFieldOf("mag", List.of()).forGetter(GunMag::mag),
            Codec.STRING.optionalFieldOf("chamber", "").forGetter(GunMag::chamber),
            Codec.STRING.listOf(0, MagContents.CAPACITY).optionalFieldOf("want", List.of()).forGetter(GunMag::want),
            Codec.BOOL.optionalFieldOf("inserted", false).forGetter(GunMag::inserted),
            Codec.BOOL.optionalFieldOf("fresh", false).forGetter(GunMag::fresh)).apply(i, GunMag::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, GunMag> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MagContents.CAPACITY)), GunMag::mag,
            ByteBufCodecs.STRING_UTF8, GunMag::chamber,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MagContents.CAPACITY)), GunMag::want,
            ByteBufCodecs.BOOL, GunMag::inserted,
            ByteBufCodecs.BOOL, GunMag::fresh,
            GunMag::new);

    private GunMagCodecs() {
    }
}
