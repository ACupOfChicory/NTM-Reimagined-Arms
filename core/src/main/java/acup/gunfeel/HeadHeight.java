package acup.gunfeel;

/**
 * NTM's headshot by height (BulletConfig.LAMBDA_STANDARD_ENTITY_HIT): the hit point in the top two head heights of the
 * creature's box, the head's height its box's over its eyes. The fallback when the model's parts (HeadDamage, the
 * hitboxes) found nothing; 26.2's BulletServer asks it for the test log. The same operations in the same order as
 * 26.2's: the head's height a float's (the box's height less the eyes'), then widened.
 */
public final class HeadHeight {
    private HeadHeight() {
    }

    /** the point's height {@code atY} is in the top two head heights of a box from {@code y}, {@code bbHeight} tall, eyes at {@code eyeHeight} */
    public static boolean byHeight(double atY, double y, float bbHeight, float eyeHeight) {
        double head = bbHeight - eyeHeight;
        return atY > y + bbHeight - head * 2.0;
    }
}
