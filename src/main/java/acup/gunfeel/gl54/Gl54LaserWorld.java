package acup.gunfeel.gl54;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import acup.gunfeel.RenderPort26;
import acup.gunfeel.Vecs;
import acup.gunfeel.host.render.Intent;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

// The GL-54's laser as other eyes see it (design.md): a thin red beam in the world from the gun of every player who holds the
// GL-54 aimed, the local one too when the camera is behind them (the hand's beam is Gl54Laser). NTM draws a gun in the world
// from a baked model whose draws know nothing of the stack, so the beam is not part of it: it is put in the level after
// the rest, from where the gun's lens is about: the player's eye, so many blocks to the right, down and forward along the
// look. The beam is the same as in the hand (width, fade, colour) and goes along the look. Whether another player aims is the
// aim flag on the stack NTM already syncs with the held item. Nothing is done unless a GL-54 in a nearby player's hand is aimed.
public final class Gl54LaserWorld {
    // the lens against the shoulder, in blocks: to the body's right, down, forward (along the look); tuned by the stand
    public static double right = 0.14, down = 0.039, forward = 0.5;
    // for the stand: where the beam's start was in the world the last frame, per player
    public static volatile Vec3 lastStart;

    private Gl54LaserWorld() {
    }

    public static void submit(SubmitCustomGeometryEvent event) {
        if (!Gl54Laser.ON || Gl54Items.GUN == null) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        var players = level.players();
        if (players.isEmpty()) return;
        Camera camera = mc.gameRenderer.mainCamera();
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        Object gun = Gl54Items.GUN.get();
        for (AbstractClientPlayer p : players) {
            if (p == mc.player && firstPerson) continue;
            ItemStack stack = p.getMainHandItem();
            if (stack.getItem() != gun || !ItemGunBaseNT.getIsAiming(stack)) continue;
            if (!Gl54LaserGeometry.inRange(p.distanceToSqr(cam))) continue;
            beam(event, p, partial, cam);
        }
    }

    // where the lens is and which way the beam goes. The gun is held from the shoulder, which turns with the body and does not
    // rise or fall with the head: the lens is the shoulder (the eye 0.27 lower, `right` to the body's right) plus `forward` along
    // the look and `down` below it. The beam goes along the look.
    public static Vec3[] line(AbstractClientPlayer p, float partial) {
        var l = new Gl54LaserGeometry.Line();
        line(l, p, partial);
        return new Vec3[] {Vecs.mc(l.start), Vecs.mc(l.dir), Vecs.mc(l.side), Vecs.mc(l.up)};
    }

    private static void line(Gl54LaserGeometry.Line out, AbstractClientPlayer p, float partial) {
        Vec3 eye = p.getEyePosition(partial), look = p.getViewVector(partial);
        Gl54LaserGeometry.line(out, eye.x, eye.y, eye.z, look.x, look.y, look.z,
                net.minecraft.util.Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot), right, down, forward);
    }

    private static void beam(SubmitCustomGeometryEvent event, AbstractClientPlayer p, float partial, Vec3 cam) {
        var batch = Gl54Laser.Drawing.RENDERER.begin();
        if (batch == null) return;
        line(batch.line, p, partial);
        lastStart = Vecs.mc(batch.line.start);
        batch.world(cam.x, cam.y, cam.z);
        RenderPort26.submitAt(event, cam, 0, Intent.QUEUE, batch);
    }
}
