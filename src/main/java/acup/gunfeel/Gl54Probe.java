package acup.gunfeel;

import java.util.LinkedHashMap;
import java.util.Map;

import com.hbm.render.loader.HFRWavefrontObject;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.gl54.Gl54Casing;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

// Dev (Gl54Stand): points of the GL-54's model followed through the real first-person render into the world's view space
// (Tracers.view) and onto the screen, so the stand can say where a sight, a grip or the ejection window is in pixels, and
// what a model unit is in blocks. The renderer calls it every frame while ON.
public final class Gl54Probe {
    public static volatile boolean ON;
    // name -> {view x, y, z, screen px x, y}
    public static volatile Map<String, float[]> POINTS = Map.of();

    private Gl54Probe() {
    }

    // `mesh` and `names` are the renderer's; the pose is at the model's origin in the hand (after the gun's own scale)
    public static void run(PoseStack pose, HFRWavefrontObject mesh, String[] names) {
        Map<String, float[]> out = new LinkedHashMap<>();
        put(pose, out, "o", 0, 0, 0);
        put(pose, out, "ux", 1, 0, 0);
        put(pose, out, "uy", 0, 1, 0);
        put(pose, out, "uz", 0, 0, 1);
        float[] all = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < names.length; i++) {
            float[] b = mesh.groups[i].bounds();
            for (int k = 0; k < 3; k++) {
                all[k] = Math.min(all[k], b[k]);
                all[k + 3] = Math.max(all[k + 3], b[k + 3]);
            }
            if (names[i].equals("RearIronSight") || names[i].equals("FrontIronSight")) {
                String t = names[i].startsWith("Rear") ? "rear" : "front";
                put(pose, out, t, (b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
                put(pose, out, t + "Top", (b[0] + b[3]) / 2, b[4], (b[2] + b[5]) / 2);
            }
        }
        for (int i = 0; i < names.length; i++) {
            boolean rear = names[i].equals("RearIronSight"), front = names[i].equals("FrontIronSight");
            if (!rear && !front) continue;
            float[] q = mesh.groups[i].quads(false);
            float minX = Float.MAX_VALUE, zLo = Float.MAX_VALUE, zHi = -Float.MAX_VALUE;
            for (int v = 0; v + 8 <= q.length; v += 8) {
                if (Math.abs(q[v]) > 0.01F) minX = Math.min(minX, Math.abs(q[v]));
                zLo = Math.min(zLo, q[v + 2]);
                zHi = Math.max(zHi, q[v + 2]);
            }
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int v = 0; v + 8 <= q.length; v += 8) {
                // the rear sight's slot: the inner walls of the notch; the front sight's post: its top
                boolean on = rear ? Math.abs(Math.abs(q[v]) - minX) < 0.005F : Math.abs(q[v]) < 0.03F;
                if (!on) continue;
                lo = Math.min(lo, q[v + 1]);
                hi = Math.max(hi, q[v + 1]);
            }
            put(pose, out, rear ? "rearSlot" : "frontTip", 0, rear ? (lo + hi) / 2F : hi, (zLo + zHi) / 2F);
        }
        for (int c = 0; c < 8; c++) put(pose, out, "c" + c, all[(c & 1) * 3], all[1 + ((c >> 1) & 1) * 3], all[2 + ((c >> 2) & 1) * 3]);
        Grips.Grip g = Grips.get(Identifier.fromNamespaceAndPath("ntmgunfeel", "gun_gl54"));
        for (int q = -16; q <= 16; q++) {
            float z = q / 4F;
            if (g.leftAt() != null) put(pose, out, "L" + z, g.leftAt()[0], g.leftAt()[1], z);
            if (g.rightAt() != null) put(pose, out, "R" + z, g.rightAt()[0], g.rightAt()[1], z);
        }
        if (g.leftAt() != null) put(pose, out, "leftAt", g.leftAt()[0], g.leftAt()[1], g.leftAt()[2]);
        if (g.rightAt() != null) put(pose, out, "rightAt", g.rightAt()[0], g.rightAt()[1], g.rightAt()[2]);
        Gl54Casing c = Gl54Casing.get();
        if (c.present()) {
            put(pose, out, "port", (float) c.portX(), (float) c.portY(), (float) c.portZ());
            put(pose, out, "rest", (float) c.restX(), (float) c.restY(), (float) c.restZ());
        }
        POINTS = out;
    }

    private static void put(PoseStack pose, Map<String, float[]> out, String name, float x, float y, float z) {
        pose.pushPose();
        pose.translate(x, y, z);
        Vector3f v = Tracers.view(pose);
        pose.popPose();
        Minecraft mc = Minecraft.getInstance();
        float w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        float t = (float) Math.tan(Math.toRadians(mc.gameRenderer.mainCamera().getFov() * 0.5));
        float px = w / 2F + v.x / -v.z * (h / 2F) / t, py = h / 2F - v.y / -v.z * (h / 2F) / t;
        out.put(name, new float[] {v.x, v.y, v.z, px, py, mc.gameRenderer.mainCamera().getFov()});
    }
}
