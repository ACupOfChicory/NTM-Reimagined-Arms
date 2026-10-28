package acup.gunfeel;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Контакт постоянного внутреннего угла кисти с движущейся деталью. */
final class ArmContact {
    private ArmContact() {}
    static int parse(String text) {
        int id=text.isEmpty()?1:Integer.parseInt(text);
        if(id<1 || id>9)throw new IllegalArgumentException("Неизвестная точка контакта");return id;
    }
    // X: внутрь/наружу в половинах ширины. Y/Z: пиксели от торца кисти.
    static Vector3f offset(int contact) {
        return switch(contact) {
            case 1 -> new Vector3f(1,0,-2); case 2 -> new Vector3f(1,0,2);
            case 3 -> new Vector3f(-1,0,-2); case 4 -> new Vector3f(-1,0,2);
            case 5 -> new Vector3f(1,-2,0); case 6 -> new Vector3f(-1,-2,0);
            case 7 -> new Vector3f(0,-2,-2); case 8 -> new Vector3f(0,-2,2);
            case 9 -> new Vector3f(); default -> new Vector3f();
        };
    }
    static Vector3f pivot(boolean right,boolean slim,Vector3f offset) {
        float center=(right?-1:1)*(slim?5.5F:6F)/16F;
        float half=(slim?1.5F:2F)/16F;
        return new Vector3f(center+(right?half:-half)*offset.x,.75F+offset.y/16F,offset.z/16F);
    }
    static void attach(Matrix4f pose,Vector3f target,boolean right,boolean slim,float weight,Vector3f offset) {
        var contact=pose.transformPosition(pivot(right,slim,offset),new Vector3f());
        var shift=new Vector3f(target).sub(contact).mul(weight);
        pose.m30(pose.m30()+shift.x).m31(pose.m31()+shift.y).m32(pose.m32()+shift.z);
    }
    static void corner(Matrix4f pose, Vector3f target, boolean right, boolean slim, float weight) {
        attach(pose,target,right,slim,weight,offset(1));
    }
}
