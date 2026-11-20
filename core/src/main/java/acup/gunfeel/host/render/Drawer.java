package acup.gunfeel.host.render;

/** Registered once for an intent ({@link RenderPort#drawer}); run at the intent's place each frame. */
@FunctionalInterface
public interface Drawer {
    void draw(RenderSink sink);
}
