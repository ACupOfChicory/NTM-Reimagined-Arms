package acup.gunfeel;

import acup.gunfeel.host.GamePause;

// The game's pause for core (GamePause): the single-player pause on a client. Made with the wiring on any side, it
// touches the client's classes only when asked.
public final class ClientPause implements GamePause {
    @Override
    public boolean paused() {
        return net.minecraft.client.Minecraft.getInstance() != null && net.minecraft.client.Minecraft.getInstance().isPaused();
    }
}
