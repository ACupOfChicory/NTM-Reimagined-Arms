package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

/** Synchronous particles and sounds of a bullet hit. */
public interface ShotEffects {
    /** A particle or null (26.2: BulletServer.particle, -1 means no hit state). */
    @Nullable Object particle(String name, int hitState);
    /** Emit particles (26.2: ServerLevel.sendParticles, spread .1 and speed .05). */
    void particles(Object level, Object particle, double x, double y, double z, int count);
    /** Resolve a sound or null (26.2: Identifier.tryParse, registry or a direct Holder). */
    @Nullable Object sound(String id);
    /** Play for everyone nearby (26.2: Level.playSound, PLAYERS). */
    void playAll(Object level, Object sound, double x, double y, double z, float volume, float pitch);
    /** Send to one player (26.2: ClientboundSoundPacket with the given seed). */
    void playTo(Body player, Object sound, double x, double y, double z, float volume, float pitch, long seed);
    /** The permanent steel sound (26.2: BulletServer.RICOCHET_STEEL). */
    Object ricochetSteel();

    ShotEffects MISSING = new ShotEffects() {
        @Override public Object particle(String name, int hitState) { throw new UnsupportedOperationException("no ShotEffects here"); }
        @Override public void particles(Object level, Object particle, double x, double y, double z, int count) { throw new UnsupportedOperationException("no ShotEffects here"); }
        @Override public Object sound(String id) { throw new UnsupportedOperationException("no ShotEffects here"); }
        @Override public void playAll(Object level, Object sound, double x, double y, double z, float volume, float pitch) { throw new UnsupportedOperationException("no ShotEffects here"); }
        @Override public void playTo(Body player, Object sound, double x, double y, double z, float volume, float pitch, long seed) { throw new UnsupportedOperationException("no ShotEffects here"); }
        @Override public Object ricochetSteel() { throw new UnsupportedOperationException("no ShotEffects here"); }
    };
}
