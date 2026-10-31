package acup.gunfeel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;

// One entity's model as it is drawn this frame, cube by cube (26.2: ModelPoseCapture.collect over the ModelPart tree
// from the submitted PoseStack). 1.7.10 has no pose to read after the fact: a model positions its parts in GL as it
// draws them (ModelBase.render, ModelRenderer.render), with its own extra transforms (a child's scale, the dragon's
// spine). So the cubes are taken while drawing: at the start of each ModelRenderer.render, from the GL matrix there
// and the part's own transform that render applies next (local), exactly as render does before glCallList.
// No GL here: the caller hands in the matrices (ModelHitClient in the game, the tests in software).
//
// The boxes come out as 26.2's: relative to the rendered origin of the entity, in world axes, in blocks. The view
// matrix at RendererLivingEntity.doRender's start maps camera-relative world points to the eye; its inverse times a
// part's matrix gives the part in camera-relative world space, and the origin is doRender's (x, y, z).
public final class ModelCapture {
    private final ModelBase model;
    private final Map<ModelRenderer, String> names;
    private final float[] inverseView;
    private final double x, y, z;
    private final float yaw;
    private final List<ModelBox> boxes = new ArrayList<>();
    private final Map<ModelRenderer, List<float[]>> drawn = new IdentityHashMap<>();
    private boolean complete = true;
    private int parts;

    // view: the GL modelview at doRender's start; x, y, z: doRender's camera-relative origin; yaw: the body's
    // interpolated yaw rotateCorpse turns the model by
    public ModelCapture(ModelBase model, float[] view, double x, double y, double z, float yaw) {
        this.model = model;
        this.names = ModelNames.of(model);
        float[] inverse = Mat4.invertAffine(view);
        this.inverseView = inverse;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        if (inverse == null) complete = false;
    }

    public ModelBase model() {
        return model;
    }

    public float yaw() {
        return yaw;
    }

    public boolean complete() {
        return complete && !boxes.isEmpty();
    }

    public List<ModelBox> boxes() {
        return List.copyOf(boxes);
    }

    // A part of this model starts drawing (ModelRenderer.render or renderWithRotation at their head): parent is the GL
    // modelview then, texture the bound GL texture (0 when unknown).
    public void part(ModelRenderer part, float scale, boolean withRotation, float[] parent, int texture) {
        if (!complete || part.isHidden || !part.showModel) return;
        String path = names.get(part);
        if (path == null) return; // armour, a held item: not the entity's own model
        if (++parts > 2048) { complete = false; return; }
        float[] matrix = Mat4.mul(parent, local(part, scale, withRotation));
        // the same part in the same pose again (the hurt overlay, the dragon's dissolve pass): not new geometry
        List<float[]> before = drawn.computeIfAbsent(part, k -> new ArrayList<>());
        for (float[] m : before) if (same(m, matrix)) return;
        int ordinal = before.size();
        before.add(matrix);
        if (part.cubeList == null || part.cubeList.isEmpty()) return;
        String name = ModelNames.draw(model, path, ordinal);
        float[] relative = Mat4.mul(inverseView, matrix);
        // ModelCubeGeometry.append takes 26.2's pixels / 16; the display list draws pixels * scale
        float k = scale * 16F;
        if (k != 1F) for (int i = 0; i < 12; i++) relative[i] *= k;
        for (net.minecraft.client.model.ModelBox cube : part.cubeList) {
            ModelTextureMask.geometry(cube, texture).append(boxes, name, relative, -x, -y, -z);
            if (boxes.size() > ModelPosePayload.MAX_BOXES) { complete = false; return; }
        }
    }

    // What ModelRenderer.render(scale) applies before its glCallList: the offset, the rotation point, then Z, Y, X
    // turns; renderWithRotation: no offset, the rotation point, then Y, X, Z.
    static float[] local(ModelRenderer r, float scale, boolean withRotation) {
        float[] m = withRotation ? identity() : translation(r.offsetX, r.offsetY, r.offsetZ);
        m = Mat4.mul(m, translation(r.rotationPointX * scale, r.rotationPointY * scale, r.rotationPointZ * scale));
        if (withRotation) {
            m = turn(m, r.rotateAngleY, 0, 1, 0);
            m = turn(m, r.rotateAngleX, 1, 0, 0);
            m = turn(m, r.rotateAngleZ, 0, 0, 1);
        } else {
            m = turn(m, r.rotateAngleZ, 0, 0, 1);
            m = turn(m, r.rotateAngleY, 0, 1, 0);
            m = turn(m, r.rotateAngleX, 1, 0, 0);
        }
        return m;
    }

    private static float[] turn(float[] m, float radians, int ax, int ay, int az) {
        if (radians == 0F) return m;
        return Mat4.mul(m, rotation(radians * (180F / (float) Math.PI), ax, ay, az));
    }

    static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1F;
        return m;
    }

    static float[] translation(float tx, float ty, float tz) {
        float[] m = identity();
        m[12] = tx;
        m[13] = ty;
        m[14] = tz;
        return m;
    }

    static float[] scaling(float sx, float sy, float sz) {
        float[] m = identity();
        m[0] = sx;
        m[5] = sy;
        m[10] = sz;
        return m;
    }

    // glRotatef(degrees, x, y, z) about a unit axis
    static float[] rotation(float degrees, float ax, float ay, float az) {
        double a = Math.toRadians(degrees);
        float c = (float) Math.cos(a), s = (float) Math.sin(a), t = 1F - c;
        float[] m = identity();
        m[0] = ax * ax * t + c;
        m[1] = ay * ax * t + az * s;
        m[2] = ax * az * t - ay * s;
        m[4] = ax * ay * t - az * s;
        m[5] = ay * ay * t + c;
        m[6] = ay * az * t + ax * s;
        m[8] = ax * az * t + ay * s;
        m[9] = ay * az * t - ax * s;
        m[10] = az * az * t + c;
        return m;
    }

    private static boolean same(float[] a, float[] b) {
        for (int i = 0; i < 16; i++) if (Math.abs(a[i] - b[i]) > 1e-6F) return false;
        return true;
    }
}
