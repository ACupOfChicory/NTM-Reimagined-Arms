package acup.gunfeel;

import net.minecraft.server.level.ServerPlayer;

// port/continuum: a stand-in for AmmoLock (Continuum names its bullet configs with Identifiers, has no
// BulletConfig.configs, keeps ammo bags as InventoryAmmoBag). The ammo choice is off on this node.
public final class AmmoLock {
    private AmmoLock() {
    }

    public static void toggle(ServerPlayer player) {
    }

    public static void select(ServerPlayer player, int id) {
    }
}
