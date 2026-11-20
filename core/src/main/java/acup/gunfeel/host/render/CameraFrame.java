package acup.gunfeel.host.render;

/** The frame at a drawer's place. */
public interface CameraFrame {
    int PLAIN = 0, VANILLA_CHAIN = 1, OIT = 2, SHADER_PACK = 3;

    double camX();

    double camY();

    double camZ();

    /** the mod's clock (GunClock) as the caller of this place read it */
    long nanos();

    /**
     * How the translucent things are laid: {@link #VANILLA_CHAIN} whenever the game's own transparency chain is on
     * (asked first, so a mod's way never hides it), else {@link #SHADER_PACK}, {@link #OIT} (improved transparency
     * without the chain: CrankShaft), {@link #PLAIN}
     */
    int translucency();
}
