package acup.gunfeel.gl54.frag;

import acup.gunfeel.gl54.GrenadeKind;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

// The server to the players near: a fragment grenade went off here (point), flying along dir, and the seed that FragPattern
// makes the rays from. The client casts and draws them itself, so a burst costs one packet.
public record FragPayload(GrenadeKind kind, Vec3 point, Vec3 dir, long seed) implements CustomPacketPayload {
    public static final Type<FragPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "gl54_frag"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FragPayload> CODEC = CustomPacketPayload.codec(FragPayload::write, FragPayload::read);

    private void write(RegistryFriendlyByteBuf b) {
        b.writeByte(kind.ordinal());
        b.writeDouble(point.x);
        b.writeDouble(point.y);
        b.writeDouble(point.z);
        b.writeFloat((float) dir.x);
        b.writeFloat((float) dir.y);
        b.writeFloat((float) dir.z);
        b.writeLong(seed);
    }

    private static FragPayload read(RegistryFriendlyByteBuf b) {
        GrenadeKind kind = GrenadeKind.values()[Math.floorMod(b.readByte(), GrenadeKind.values().length)];
        Vec3 point = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        Vec3 dir = new Vec3(b.readFloat(), b.readFloat(), b.readFloat());
        return new FragPayload(kind, point, dir, b.readLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
