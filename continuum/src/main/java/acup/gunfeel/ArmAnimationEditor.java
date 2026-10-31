package acup.gunfeel;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// port/continuum: a stand-in for the arm animation editor (src/main/java ArmAnimationEditor, opened from the gun editor,
// which is not on this node). Never shown: Arms and ArmAnimationPreview only ask whether it is the open screen.
public final class ArmAnimationEditor extends Screen {
    private ArmAnimationEditor() {
        super(Component.empty());
    }

    void anchor(boolean right, org.joml.Vector4f point) {
    }
}
