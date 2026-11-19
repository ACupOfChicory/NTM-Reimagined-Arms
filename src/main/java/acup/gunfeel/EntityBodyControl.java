package acup.gunfeel;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;

/** 26.2's BodyControl: each method one call of the game's on the entity behind the Body. */
final class EntityBodyControl implements BodyControl {
    @Override
    public void stance(Body b, byte stance) {
        EntityBody.entity(b).setPose(EntityBody.pose(stance));
    }
}
