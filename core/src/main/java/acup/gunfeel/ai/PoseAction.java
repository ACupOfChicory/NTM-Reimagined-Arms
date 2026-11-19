package acup.gunfeel.ai;

/**
 * What an AI mob does with its body besides its stance (AiPose's action, the bytes the clients are sent): nothing, a
 * reload, a throw, a sign (point, halt, come on), a shot, a slide into cover, a roll under fire (AiDash), a door pushed.
 */
public final class PoseAction {
    private PoseAction() {
    }

    public static final byte NONE = 0, RELOAD = 1, THROW = 2, SIGNAL_POINT = 3, SIGNAL_HALT = 4, SIGNAL_COME = 5, SHOT = 6;
    public static final byte SLIDE = 7, ROLL = 8;
    public static final byte DOOR = 9;
}
