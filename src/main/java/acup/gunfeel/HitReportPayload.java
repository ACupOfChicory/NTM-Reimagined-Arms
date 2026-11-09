package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * client → server: what this player's own bullets met on the client (ClientHits). For each bullet: how far its path
 * is tested, in ticks from its start (+∞: done), and the mobs it hit since the last report: where, which part, when.
 */
public record HitReportPayload(List<Entry> entries) implements CustomPacketPayload {
    public static final Type<HitReportPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "hit_report"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HitReportPayload> CODEC = CustomPacketPayload.codec(HitReportPayload::write,
            HitReportPayload::read);
    /** a report's bullets and a bullet's hits, at most */
    static final int MAX_ENTRIES = 64, MAX_CLAIMS = 16;

    /** the part hit: the body, the head, a limb */
    public static final byte BODY = 0, HEAD = 1, LIMB = 2;

    /** a hit: the mob, the bullet's age at it (ticks from its start), the point, the part */
    public record Claim(int entity, float age, Vec3 point, byte zone) {
    }

    /** a bullet: tested up to {@code tested} ticks (+∞ done, NaN: not followed here, the server decides) */
    public record Entry(int bullet, float tested, List<Claim> claims) {
    }

    private void write(RegistryFriendlyByteBuf b) {
        int n = Math.min(entries.size(), MAX_ENTRIES);
        b.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            b.writeVarInt(e.bullet());
            b.writeFloat(e.tested());
            int c = Math.min(e.claims().size(), MAX_CLAIMS);
            b.writeVarInt(c);
            for (int j = 0; j < c; j++) {
                Claim h = e.claims().get(j);
                b.writeVarInt(h.entity());
                b.writeFloat(h.age());
                b.writeDouble(h.point().x);
                b.writeDouble(h.point().y);
                b.writeDouble(h.point().z);
                b.writeByte(h.zone());
            }
        }
    }

    private static HitReportPayload read(RegistryFriendlyByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > MAX_ENTRIES) throw new IllegalArgumentException("hit report: " + n + " bullets");
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int bullet = b.readVarInt();
            float tested = b.readFloat();
            int c = b.readVarInt();
            if (c < 0 || c > MAX_CLAIMS) throw new IllegalArgumentException("hit report: " + c + " hits");
            List<Claim> claims = new ArrayList<>(c);
            for (int j = 0; j < c; j++)
                claims.add(new Claim(b.readVarInt(), b.readFloat(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readByte()));
            out.add(new Entry(bullet, tested, claims));
        }
        return new HitReportPayload(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
