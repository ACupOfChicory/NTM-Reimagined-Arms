package acup.gunfeel;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import org.joml.Vector4f;

// The arm animation editor is not ported (skip.txt): its name for the shared files that ask whether it is open.
// It never is: opened anyway (the editor's stand), it closes at once.
public final class ArmAnimationEditor extends Screen {
    private final Screen parent;

    public ArmAnimationEditor(Screen parent, Item item, String gun) {
        super(Component.empty());
        this.parent = parent;
    }

    @Override
    protected void init() {
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    void anchor(boolean right, Vector4f clip) {
    }
}
