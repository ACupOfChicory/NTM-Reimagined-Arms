package acup.gunfeel.gas;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * What of a {@link GasState} is saved with the entity ({@link GasAttach}): the doses, the acid's K and the phosgene's
 * clock. The game's codec, so it is 26.2's; the state itself is common code.
 */
public final class GasStateCodec {
    public static final MapCodec<GasState> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.listOf().optionalFieldOf("dose", List.of()).forGetter(s -> {
                List<Float> l = new ArrayList<>();
                for (float d : s.dose) l.add(d);
                return l;
            }),
            Codec.FLOAT.optionalFieldOf("k", 0F).forGetter(s -> s.k),
            Codec.INT.optionalFieldOf("latent", -1).forGetter(s -> s.latent),
            Codec.BOOL.optionalFieldOf("smelled", false).forGetter(s -> s.smelled)
    ).apply(i, (dose, k, latent, smelled) -> {
        GasState s = new GasState();
        for (int d = 0; d < GasState.N && d < dose.size(); d++) s.dose[d] = Math.max(0F, dose.get(d));
        s.k = Math.max(0F, Math.min(GasRules.ACID_K_MAX, k));
        s.latent = latent;
        s.smelled = smelled;
        return s;
    }));

    private GasStateCodec() {
    }
}
