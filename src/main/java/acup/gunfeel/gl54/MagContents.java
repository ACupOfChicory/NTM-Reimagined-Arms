package acup.gunfeel.gl54;

import java.util.List;

import com.mojang.serialization.Codec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

// What a 20 mm magazine holds: the keys of its grenades (GrenadeKind.key) in the order they fire, the first one first. One
// kind is the case where all are equal; a mixed magazine is any list. At most 8. The same list is kept on the gun for the
// magazine in it (GunMag).
public record MagContents(List<String> rounds) {
    public static final int CAPACITY = 8;
    public static final MagContents EMPTY = new MagContents(List.of());
    public static final Codec<MagContents> CODEC = Codec.STRING.listOf(0, CAPACITY).xmap(MagContents::new, MagContents::rounds);
    public static final StreamCodec<RegistryFriendlyByteBuf, MagContents> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(CAPACITY)).map(MagContents::new, MagContents::rounds).cast();

    public MagContents {
        rounds = List.copyOf(rounds);
    }

    public static MagContents filled(String key, int n) {
        return new MagContents(java.util.Collections.nCopies(n, key));
    }

    public int size() {
        return rounds.size();
    }

    public String kind() {
        return MagLogic.kind(rounds);
    }
}
