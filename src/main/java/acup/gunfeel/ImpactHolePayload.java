package acup.gunfeel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Серверная точка попадания медленного/управляемого снаряда, без подмены его полёта трейсером. */
public record ImpactHolePayload(int config,BlockPos block,Direction face,Vec3 point,Vec3 direction,int shot) implements CustomPacketPayload {
    public static final Type<ImpactHolePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel","impact_hole"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ImpactHolePayload> CODEC=CustomPacketPayload.codec(ImpactHolePayload::write,ImpactHolePayload::read);
    private void write(RegistryFriendlyByteBuf b){
        b.writeVarInt(config);b.writeBlockPos(block);b.writeEnum(face);
        b.writeDouble(point.x);b.writeDouble(point.y);b.writeDouble(point.z);
        b.writeFloat((float)direction.x);b.writeFloat((float)direction.y);b.writeFloat((float)direction.z);b.writeVarInt(shot);
    }
    private static ImpactHolePayload read(RegistryFriendlyByteBuf b){
        return new ImpactHolePayload(b.readVarInt(),b.readBlockPos(),b.readEnum(Direction.class),new Vec3(b.readDouble(),b.readDouble(),b.readDouble()),
                new Vec3(b.readFloat(),b.readFloat(),b.readFloat()),b.readVarInt());
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
