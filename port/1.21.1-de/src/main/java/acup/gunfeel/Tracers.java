package acup.gunfeel;

import net.neoforged.neoforge.network.handling.IPayloadContext;

// Stub until the tracers part is ported (notes/ports/de-1.21.1-progress.md): the root's Tracers draws the bullets
// and the holes they leave. Here the packets for it are taken and nothing is drawn.
public final class Tracers {
    public static volatile boolean debugHoles;

    private Tracers() {
    }

    // where the player's own tracers start: the muzzle of the gun in hand (MixinItemRenderWeaponBase)
    public static void muzzle(com.mojang.blaze3d.vertex.PoseStack pose) {
    }

    public static void handle(ShotPayload p, IPayloadContext context) {
    }

    public static void stop(BulletStopPayload packet) {
    }

    public static void impact(ImpactHolePayload packet) {
    }

    public static void clear() {
    }

    public static int clearHoles() {
        return 0;
    }
}
