package acup.gunfeel;

import acup.gunfeel.host.render.RenderPort;

/**
 * The client's render port, for Wiring: RenderPort26 names the client's classes (blaze3d's VertexConsumer and the
 * rest), which the dedicated server does not have, so Wiring names only this holder and calls it only on the client
 * (Wiring.client()). Its type is core's interface: checking Wiring loads neither this class nor RenderPort26.
 */
final class RenderPortClient {
    private RenderPortClient() {
    }

    static RenderPort port() {
        return RenderPort26.INSTANCE;
    }
}
