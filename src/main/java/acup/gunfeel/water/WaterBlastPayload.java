package acup.gunfeel.water;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// The server to the players near: an NTM explosion at water (WaterBlast.announce), for WaterEvents.blast
public record WaterBlastPayload(Vec3 at, float size, boolean incendiary, boolean inWater, @Nullable Vec3 surface,
        WaterEvents.Liquid liquid) implements CustomPacketPayload {
    public static final Type<WaterBlastPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "water_blast"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WaterBlastPayload> CODEC = CustomPacketPayload.codec(WaterBlastPayload::write,
            WaterBlastPayload::read);

    private void write(RegistryFriendlyByteBuf b) {
        b.writeDouble(at.x);
        b.writeDouble(at.y);
        b.writeDouble(at.z);
        b.writeFloat(size);
        b.writeBoolean(incendiary);
        b.writeBoolean(inWater);
        b.writeBoolean(surface != null);
        if (surface != null) b.writeDouble(surface.y);
        b.writeEnum(liquid);
    }

    private static WaterBlastPayload read(RegistryFriendlyByteBuf b) {
        Vec3 at = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        float size = b.readFloat();
        boolean incendiary = b.readBoolean(), inWater = b.readBoolean();
        Vec3 surface = b.readBoolean() ? new Vec3(at.x, b.readDouble(), at.z) : null;
        return new WaterBlastPayload(at, size, incendiary, inWater, surface, b.readEnum(WaterEvents.Liquid.class));
    }

    public void handle(Level level) {
        WaterEvents.blast(level, new WaterEvents.Blast(at, size, incendiary, inWater, surface, liquid));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
