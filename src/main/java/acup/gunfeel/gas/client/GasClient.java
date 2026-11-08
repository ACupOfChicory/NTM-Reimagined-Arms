package acup.gunfeel.gas.client;

import acup.gunfeel.gas.GasPayloads;
import acup.gunfeel.gas.GasState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client: what the server tells the player about the gases on them. The doses (for the HUD, {@link GasHud}), whether
 * sprinting is shut (read by {@code MixinGasSprint}), and each cough: a shake of the camera for its ticks and, when the
 * server says so, no aiming meanwhile ({@code MixinGasAim}).
 */
public final class GasClient {
    private static final float[] DOSE = new float[GasState.N];
    private static float k;
    private static boolean noSprint;
    private static int cough, coughTotal;
    private static boolean blockAim;

    private GasClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(RegisterGuiLayersEvent.class,
                e -> e.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, Identifier.fromNamespaceAndPath("ntmgunfeel", "gas_hud"), GasHud::layer));
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> GasStains.tick());
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent.class, GasStains::submit);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            reset();
            GasStains.reset();
        });
    }

    public static void payloads(PayloadRegistrar r) {
        r.playToClient(GasPayloads.State.TYPE, GasPayloads.State.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            System.arraycopy(p.dose(), 0, DOSE, 0, Math.min(DOSE.length, p.dose().length));
            k = p.k();
            noSprint = p.noSprint();
        }));
        r.playToClient(GasPayloads.Cough.TYPE, GasPayloads.Cough.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            cough = coughTotal = p.ticks();
            blockAim = p.blockAim();
            if (blockAim) releaseAim();
        }));
    }


    // NTM's third gun key (the aim) as the last gunChanged left it
    private static boolean aimHeld;
    private static java.lang.reflect.Method gunChanged;

    /** MixinGasAim: the aim key changed (a press the cough turned away does not come here). */
    public static void aimKey(boolean down) {
        aimHeld = down;
    }

    // a cough knocks the aim out of the hands: NTM is told the key went up (the player presses it again after)
    private static void releaseAim() {
        if (!aimHeld) return;
        try {
            if (gunChanged == null) {
                gunChanged = com.hbm.client.Keybinds.class.getDeclaredMethod("gunChanged", int.class, boolean.class);
                gunChanged.setAccessible(true);
            }
            gunChanged.invoke(null, 2, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            acup.gunfeel.DebugLog.LOGGER.warn("Could not let go of the aim on a cough", e);
        }
        aimHeld = false;
    }

    static void reset() {
        aimHeld = false;
        java.util.Arrays.fill(DOSE, 0F);
        k = 0F;
        noSprint = false;
        cough = 0;
    }

    public static float dose(int kind) {
        return DOSE[kind];
    }

    public static float k() {
        return k;
    }

    /** The server shut sprinting (a dose of chlorine). */
    public static boolean noSprint() {
        return noSprint;
    }

    /** A cough is on and the server asked for aiming to be shut meanwhile. */
    public static boolean aimBlocked() {
        return cough > 0 && blockAim;
    }

    public static boolean coughing() {
        return cough > 0;
    }

    private static void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            reset();
            return;
        }
        if (cough <= 0) return;
        // the shake: a jolt of the view that dies away over the cough, a little more up and down than sideways
        float f = cough / (float) Math.max(1, coughTotal);
        var rng = player.getRandom();
        player.setXRot(player.getXRot() + (rng.nextFloat() - 0.4F) * 2.2F * f);
        player.setYRot(player.getYRot() + (rng.nextFloat() - 0.5F) * 1.2F * f);
        cough--;
    }
}
