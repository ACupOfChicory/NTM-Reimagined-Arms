package acup.gunfeel.gl54;

import org.joml.Matrix4f;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
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
    // the shoulder under the eye, blocks
    static final double SHOULDER_BELOW_EYE = 0.27;
    // the players farther than this are left out
    static final double REACH = 64.0;
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
            if (p.distanceToSqr(cam) > REACH * REACH) continue;
            beam(event, p, partial, cam);
        }
    }

    // where the lens is and which way the beam goes. The gun is held from the shoulder, which turns with the body and does not
    // rise or fall with the head: the lens is the shoulder (the eye 0.27 lower, `right` to the body's right) plus `forward` along
    // the look and `down` below it. The beam goes along the look.
    public static Vec3[] line(AbstractClientPlayer p, float partial) {
        Vec3 eye = p.getEyePosition(partial), f = p.getViewVector(partial).normalize();
        double h = Math.hypot(f.x, f.z);
        Vec3 r = h < 1e-6 ? new Vec3(-1, 0, 0) : new Vec3(-f.z / h, 0, f.x / h);
        Vec3 u = r.cross(f);
        double body = Math.toRadians(net.minecraft.util.Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot));
        Vec3 bodyRight = new Vec3(-Math.cos(body), 0, -Math.sin(body));
        Vec3 shoulder = eye.add(0, -SHOULDER_BELOW_EYE, 0);
        return new Vec3[] {shoulder.add(bodyRight.scale(right)).add(f.scale(forward)).add(u.scale(-down)), f, r, u};
    }

    private static void beam(SubmitCustomGeometryEvent event, AbstractClientPlayer p, float partial, Vec3 cam) {
        Vec3[] l = line(p, partial);
        Vec3 start = l[0], dir = l[1], side = l[2], up = l[3];
        lastStart = start;
        double half = Gl54Laser.WIDTH_BLOCKS / 2.0, length = Gl54Laser.LENGTH_BLOCKS;
        int n = Gl54Laser.SEGMENTS;
        Vec3 o = start.subtract(cam);
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.debugQuads(), (pose, vc) -> {
            Matrix4f m = pose.pose();
            for (int i = 0; i < n; i++) {
                Vec3 a = o.add(dir.scale(length * i / n)), b = o.add(dir.scale(length * (i + 1) / n));
                int ca = Gl54Laser.color(1.0 - (double) i / n), cb = Gl54Laser.color(1.0 - (double) (i + 1) / n);
                for (Vec3 w : new Vec3[] {side, up}) {
                    Vec3 k = w.scale(half);
                    corner(vc, m, a.subtract(k), ca);
                    corner(vc, m, a.add(k), ca);
                    corner(vc, m, b.add(k), cb);
                    corner(vc, m, b.subtract(k), cb);
                    corner(vc, m, b.subtract(k), cb);
                    corner(vc, m, b.add(k), cb);
                    corner(vc, m, a.add(k), ca);
                    corner(vc, m, a.subtract(k), ca);
                }
            }
        });
    }

    private static void corner(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f m, Vec3 v, int argb) {
        vc.addVertex(m, (float) v.x, (float) v.y, (float) v.z).setColor(argb);
    }
}
