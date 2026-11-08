package acup.gunfeel.smoke;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The smoke's packets. The server sends events, not grids: a client runs the same simulation from them. A grid goes
 * over only when a client's copy went apart from the server's ({@link Check} did not match, the client asked with
 * {@link Resync}).
 */
public final class SmokePayloads {
    private SmokePayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", name));
    }

    /** A cloud was let out (or is there when a player starts to watch its chunk): everything its simulation needs. */
    public record Spawn(int id, BlockPos origin, int kind, long seed, float dirX, float dirZ, float scale, long born) implements CustomPacketPayload {
        public static final Type<Spawn> TYPE = typeOf("smoke_spawn");
        public static final StreamCodec<RegistryFriendlyByteBuf, Spawn> CODEC = CustomPacketPayload.codec(Spawn::write, Spawn::read);

        public static Spawn of(SmokeClouds.Cloud c) {
            return new Spawn(c.id, c.origin, c.kind().id(), c.grid.seed(), c.dirX, c.dirZ, c.scale, c.born);
        }

        private void write(RegistryFriendlyByteBuf b) {
            b.writeVarInt(id);
            b.writeBlockPos(origin);
            b.writeVarInt(kind);
            b.writeLong(seed);
            b.writeFloat(dirX);
            b.writeFloat(dirZ);
            b.writeFloat(scale);
            b.writeLong(born);
        }

        private static Spawn read(RegistryFriendlyByteBuf b) {
            return new Spawn(b.readVarInt(), b.readBlockPos(), b.readVarInt(), b.readLong(), b.readFloat(), b.readFloat(), b.readFloat(), b.readLong());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The cloud is gone on the server, or the player stopped watching its chunk. */
    public record End(int id) implements CustomPacketPayload {
        public static final Type<End> TYPE = typeOf("smoke_end");
        public static final StreamCodec<RegistryFriendlyByteBuf, End> CODEC = CustomPacketPayload.codec((p, b) -> b.writeVarInt(p.id), b -> new End(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The server's {@link SmokeGrid#hash()} of a cloud at an age, every two seconds. */
    public record Check(int id, int age, long hash) implements CustomPacketPayload {
        public static final Type<Check> TYPE = typeOf("smoke_check");
        public static final StreamCodec<RegistryFriendlyByteBuf, Check> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.id);
                    b.writeVarInt(p.age);
                    b.writeLong(p.hash);
                }, b -> new Check(b.readVarInt(), b.readVarInt(), b.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A bullet went through a cloud or a blast went off by it ({@link SmokeClouds.Stir}): the client's copy takes it at that age. */
    public record Stirred(int id, SmokeClouds.Stir stir) implements CustomPacketPayload {
        public static final Type<Stirred> TYPE = typeOf("smoke_stir");
        public static final StreamCodec<RegistryFriendlyByteBuf, Stirred> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    SmokeClouds.Stir s = p.stir;
                    b.writeVarInt(p.id);
                    b.writeVarInt(s.age());
                    b.writeBoolean(s.blast());
                    b.writeDouble(s.x0());
                    b.writeDouble(s.y0());
                    b.writeDouble(s.z0());
                    b.writeDouble(s.x1());
                    b.writeDouble(s.y1());
                    b.writeDouble(s.z1());
                    b.writeFloat(s.a());
                    b.writeFloat(s.b());
                }, b -> new Stirred(b.readVarInt(), new SmokeClouds.Stir(b.readVarInt(), b.readBoolean(), b.readDouble(), b.readDouble(),
                        b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat())));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A grenade of a cloud's kind burst in it ({@link SmokeClouds.Feed}): the client's copy takes it at that age. */
    public record Fed(int id, SmokeClouds.Feed feed) implements CustomPacketPayload {
        public static final Type<Fed> TYPE = typeOf("smoke_feed");
        public static final StreamCodec<RegistryFriendlyByteBuf, Fed> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    SmokeClouds.Feed f = p.feed;
                    b.writeVarInt(p.id);
                    b.writeVarInt(f.age());
                    b.writeInt(f.x());
                    b.writeInt(f.y());
                    b.writeInt(f.z());
                    b.writeFloat(f.dirX());
                    b.writeFloat(f.dirZ());
                    b.writeFloat(f.scale());
                }, b -> new Fed(b.readVarInt(), new SmokeClouds.Feed(b.readVarInt(), b.readInt(), b.readInt(), b.readInt(), b.readFloat(),
                        b.readFloat(), b.readFloat())));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A client asks for a cloud's whole grid: its copy went apart. */
    public record Resync(int id) implements CustomPacketPayload {
        public static final Type<Resync> TYPE = typeOf("smoke_resync");
        public static final StreamCodec<FriendlyByteBuf, Resync> CODEC = CustomPacketPayload.codec((p, b) -> b.writeVarInt(p.id), b -> new Resync(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A cloud's whole grid ({@link SmokeGrid#write}, deflated), the answer to {@link Resync}. */
    public record Grid(int id, byte[] data) implements CustomPacketPayload {
        public static final int MAX = 1 << 18;
        public static final Type<Grid> TYPE = typeOf("smoke_grid");
        public static final StreamCodec<RegistryFriendlyByteBuf, Grid> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.id);
                    b.writeByteArray(p.data);
                }, b -> new Grid(b.readVarInt(), b.readByteArray(MAX)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
