package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

/** The cap's projectile questions, read live without keeping a round table. */
public interface NtmBullets {
    /** Who fired it, or itself when living (26.2: living, beam getThrower once, throwable twice if present, projectile getOwner). */
    @Nullable Body shooter(@Nullable Object e);

    /** Whether the cap records its shot (26.2: Projectile, then EntityBulletBeamBase). */
    boolean capProjectile(Object e);

    /** Whether its round has several pellets (26.2: MK4 then beam config, projectilesMax > 1). */
    boolean pellet(Object e);

    NtmBullets MISSING = new NtmBullets() {
        @Override public @Nullable Body shooter(@Nullable Object e) { throw new UnsupportedOperationException("no NtmBullets here"); }
        @Override public boolean capProjectile(Object e) { throw new UnsupportedOperationException("no NtmBullets here"); }
        @Override public boolean pellet(Object e) { throw new UnsupportedOperationException("no NtmBullets here"); }
    };
}
