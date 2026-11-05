package acup.gunfeel;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// port/continuum: a stand-in for the arm editor's screen (src/main/java ArmEditorScreen, opened from the gun editor,
// which is not on this node). Never shown: ArmAnimationPreview only asks whether it is the open screen.
public final class ArmEditorScreen extends Screen {
    private ArmEditorScreen() {
        super(Component.empty());
    }
}
