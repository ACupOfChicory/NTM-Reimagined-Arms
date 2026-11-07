package acup.gunfeel.gas;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** The attachment that holds a living thing's {@link GasState} (saved with it, only while there is something in it). */
public final class GasAttach {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "ntmgunfeel");
    public static final Supplier<AttachmentType<GasState>> STATE = TYPES.register("gas_state",
            () -> AttachmentType.builder(GasState::new).serialize(GasState.CODEC, GasState::persists).build());

    private GasAttach() {
    }

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
