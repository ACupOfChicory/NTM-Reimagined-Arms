package acup.gunfeel;

import net.neoforged.bus.api.IEventBus;

// port/continuum: a stand-in for the gun editor (it edits the arms, not ported yet): never replaying, no key
final class GunEditor {
    static boolean replaying;

    private GunEditor() {
    }

    static void register(IEventBus modBus) {
    }
}
