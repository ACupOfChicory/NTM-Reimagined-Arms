package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

// What the GL-54 holds, on the gun itself (a data component of its own, without the swap animation so that a shot does not
// draw the gun again): the grenades of the magazine in it, the one in the barrel, the kind the next reload takes, whether
// a magazine is physically in (a new gun has none: an empty one is not given back out of nothing) and a mark that a swap
// has just been made (the decider asks canReload once more right after it: false then, see GrenadeMagazine).
public record GunMag(List<String> mag, String chamber, String sel, boolean inserted, boolean fresh) {
    public static final GunMag NONE = new GunMag(List.of(), "", "", false, false);
    public static final Codec<GunMag> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf(0, MagContents.CAPACITY).optionalFieldOf("mag", List.of()).forGetter(GunMag::mag),
            Codec.STRING.optionalFieldOf("chamber", "").forGetter(GunMag::chamber),
            Codec.STRING.optionalFieldOf("sel", "").forGetter(GunMag::sel),
            Codec.BOOL.optionalFieldOf("inserted", false).forGetter(GunMag::inserted),
            Codec.BOOL.optionalFieldOf("fresh", false).forGetter(GunMag::fresh)).apply(i, GunMag::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, GunMag> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MagContents.CAPACITY)), GunMag::mag,
            ByteBufCodecs.STRING_UTF8, GunMag::chamber,
            ByteBufCodecs.STRING_UTF8, GunMag::sel,
            ByteBufCodecs.BOOL, GunMag::inserted,
            ByteBufCodecs.BOOL, GunMag::fresh,
            GunMag::new);

    public GunMag {
        mag = List.copyOf(mag);
        chamber = chamber == null ? "" : chamber;
        sel = sel == null ? "" : sel;
    }

    public boolean hasChamber() {
        return !chamber.isEmpty();
    }

    // the barrel's grenade counts in: 8 + 1 = 9
    public int count() {
        return mag.size() + (hasChamber() ? 1 : 0);
    }

    // the kind of the magazine in the gun by what is left in it
    public String kind() {
        return MagLogic.kind(mag);
    }

    // the key of the grenade that fires next: the barrel's, with the barrel empty the magazine's first; "" with nothing
    public String next() {
        return hasChamber() ? chamber : mag.isEmpty() ? "" : mag.get(0);
    }

    public GunMag withSel(String s) {
        return new GunMag(mag, chamber, s, inserted, fresh);
    }

    public GunMag withFresh(boolean f) {
        return new GunMag(mag, chamber, sel, inserted, f);
    }

    // a shot: the barrel's grenade has gone, the next one is fed in from the magazine (semi-automatic)
    public GunMag afterShot() {
        List<String> rest = new ArrayList<>(mag);
        String next = rest.isEmpty() ? "" : rest.remove(0);
        return new GunMag(rest, next, sel, inserted, fresh);
    }

    // a magazine put in: the barrel's grenade stays (the +1); with the barrel empty the first is fed in
    public GunMag withMagazine(List<String> rounds, String newSel) {
        List<String> rest = new ArrayList<>(rounds);
        String c = chamber;
        if (c.isEmpty() && !rest.isEmpty()) c = rest.remove(0);
        return new GunMag(rest, c, newSel, true, true);
    }
}
