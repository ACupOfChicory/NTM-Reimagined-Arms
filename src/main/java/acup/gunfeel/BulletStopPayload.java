package acup.gunfeel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Подтверждённая сервером остановка в сущности; возраст в игровых тиках. */
public record BulletStopPayload(int shot,double age,Vec3 point) implements CustomPacketPayload {
    public static final Type<BulletStopPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel","bullet_stop"));
    public static final StreamCodec<RegistryFriendlyByteBuf,BulletStopPayload> CODEC=CustomPacketPayload.codec(BulletStopPayload::write,BulletStopPayload::read);
    private void write(RegistryFriendlyByteBuf b){b.writeVarInt(shot);b.writeDouble(age);b.writeDouble(point.x);b.writeDouble(point.y);b.writeDouble(point.z);}
    private static BulletStopPayload read(RegistryFriendlyByteBuf b){return new BulletStopPayload(b.readVarInt(),b.readDouble(),new Vec3(b.readDouble(),b.readDouble(),b.readDouble()));}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
