package acup.gunfeel.ammo.client;

import acup.gunfeel.ammo.RubberPayload;
import acup.gunfeel.ammo.RubberRule;
import acup.gunfeel.mixin.mc.ClientInputAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * A rubber slug's confusion on this player's client (RubberPayload): for its ticks the movement's actions are swapped
 * after the keys were read (MovementInputUpdateEvent: the player's own bindings are taken already, and a controller's
 * stick swaps too): forward and back, left and right, jump and sneak. Sprint, attack and use stay as they are. A boat or
 * a horse reads the same input, so it steers the other way too. Late among the mods: the input as they left it.
 */
public final class RubberClient {
    private RubberClient() {
    }

    /** client ticks of the confusion left */
    private static int left;

    public static void init() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(EventPriority.LOWEST, false, MovementInputUpdateEvent.class, RubberClient::input);
        bus.addListener(ClientTickEvent.Post.class, e -> {
            if (left > 0 && !Minecraft.getInstance().isPaused()) left--;
        });
        bus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> left = 0);
        bus.addListener(ClientPlayerNetworkEvent.Clone.class, e -> left = 0);
    }

    public static void confuse(RubberPayload p) {
        left = Math.clamp(p.ticks(), 0, RubberRule.CONFUSE_TICKS * 4);
    }

    /** whether the controls are mixed up now */
    public static boolean confused() {
        return left > 0;
    }

    private static void input(MovementInputUpdateEvent e) {
        if (left <= 0) return;
        ClientInput in = e.getInput();
        Input k = in.keyPresses;
        boolean[] s = RubberRule.swapped(k.forward(), k.backward(), k.left(), k.right(), k.jump(), k.shift());
        in.keyPresses = new Input(s[0], s[1], s[2], s[3], s[4], s[5], k.sprint());
        ClientInputAccess a = (ClientInputAccess) in;
        Vec2 v = a.gunfeel$moveVector();
        a.gunfeel$setMoveVector(new Vec2(-v.x, -v.y));
    }
}
