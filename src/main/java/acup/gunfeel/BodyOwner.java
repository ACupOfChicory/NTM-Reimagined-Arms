package acup.gunfeel;

import acup.gunfeel.host.Body;

/** Every entity on 26.2 (MixinEntityBody): its one Body for core (EntityBody), made the first time it is asked for. */
public interface BodyOwner {
    Body gunfeel$body();
}
