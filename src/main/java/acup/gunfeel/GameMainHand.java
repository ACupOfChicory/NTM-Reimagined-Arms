package acup.gunfeel;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.HumanoidArm;

/** The main hand from the client's options (MainHand, core); no client yet (a test, a server): the right one. */
public final class GameMainHand implements MainHand {
    @Override
    public boolean left() {
        var mc = Minecraft.getInstance();
        return mc != null && mc.options.mainHand().get() == HumanoidArm.LEFT;
    }
}
