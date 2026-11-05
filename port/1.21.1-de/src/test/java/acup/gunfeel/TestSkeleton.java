package acup.gunfeel;

import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

// Port only (notes/ports/de-1.21.1.md): the skeleton model of 1.21.1 posed as 26.2's SkeletonModel.setupAnim(state)
// poses it for a walk. 1.21.1's setupAnim takes the mob (its arms, its fall flying, its riding) and a unit test has
// none, so the walk is HumanoidModel.setupAnim's own: the legs and arms swing by cos(pos * 0.6662) * 1.4 * speed and
// half that, the left a half turn off. The rest stays at the model's rest pose.
final class TestSkeleton {
    static final class State {
        float walkAnimationPos, walkAnimationSpeed;
    }

    private final ModelPart root = SkeletonModel.createBodyLayer().bakeRoot();

    ModelPart root() {
        return root;
    }

    void setupAnim(State state) {
        root.getAllParts().forEach(ModelPart::resetPose);
        float pos = state.walkAnimationPos, speed = state.walkAnimationSpeed;
        root.getChild("right_leg").xRot = Mth.cos(pos * 0.6662F) * 1.4F * speed;
        root.getChild("left_leg").xRot = Mth.cos(pos * 0.6662F + Mth.PI) * 1.4F * speed;
        root.getChild("right_arm").xRot = Mth.cos(pos * 0.6662F + Mth.PI) * 2.0F * speed * 0.5F;
        root.getChild("left_arm").xRot = Mth.cos(pos * 0.6662F) * 2.0F * speed * 0.5F;
    }
}
