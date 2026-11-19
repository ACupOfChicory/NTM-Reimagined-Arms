package acup.gunfeel;

import org.joml.Matrix4f;
import org.joml.Vector3f;

// each arm at rest, per gun variant (Arms keeps them, ArmMath.restArm fills them): during animations the arm keeps
// this length and thickness
final class ArmRest {
    boolean valid;
    float length, s;
    final Vector3f hand = new Vector3f(), shoulder = new Vector3f(), axis = new Vector3f(), center = new Vector3f();
    final Matrix4f local = new Matrix4f();
    // pixels of the arm drawn from the hand (12 = the whole arm), see local()
    int crop = 12;
}
