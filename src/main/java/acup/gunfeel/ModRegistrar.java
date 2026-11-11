package acup.gunfeel;

import com.hbm.registration.NeoForgeRegistrar;

import net.neoforged.bus.api.IEventBus;

/**
 * The mod's namespace in NTM's registrar (its items, data components, menus are registered the way NTM registers its
 * own). NTM lets a namespace subscribe once: the GL-54 and the shock shell both ask, the first one subscribes.
 */
public final class ModRegistrar {
    public static final String NAMESPACE = "ntmgunfeel";
    private static boolean subscribed;

    private ModRegistrar() {
    }

    public static synchronized void subscribe(IEventBus modBus) {
        if (subscribed) return;
        subscribed = true;
        NeoForgeRegistrar.subscribe(NAMESPACE, modBus);
    }
}
