package acup.gunfeel.ai.client;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.core.Direction;

/**
 * The bots' model: vanilla's biped (HumanoidModel.createMesh, a 64 x 32 skin, as NTM's RenderHumanoidMob bakes it for
 * the FBI agent and the test dummy) with each arm and leg of two boxes. The upper arm and the thigh are the vanilla
 * part cut at half its length (arm 4 x 6 x 4 above the elbow, leg 4 x 6 x 4 above the knee); the forearm and the shin
 * hang from them as children ({@link #FOREARM}, {@link #SHIN}), hinged on the limb's middle line, so a straight limb
 * is vanilla's to the pixel and the skin is the same: each half takes its half of the limb's texture, the hand and
 * the foot keep their own end.
 *
 * <p>The bend: the forearm and the shin go on 2 px up into the part above them, inside it while straight (0.01 px in
 * from its faces, so nothing flickers). Bent, that piece fills the corner the two boxes open on the outside of the
 * elbow or the knee, wholly up to a right angle (the overlap of half the limb's thickness covers a quarter turn); on
 * the inside the boxes run into each other. The overlap of jointed box models, as Mo' Bends' and Emotecraft's
 * bendable parts started; no code of theirs here.
 *
 * <p>The hitbox takes each box as it is posed (ModelPoseCapture walks the children): ten parts, and the forearm's
 * overlap within its own part.
 *
 * <p>The armour's models (vanilla's armour mesh, a slot's pieces grown by their layer's thickness) are cut the same way,
 * grown as they were, so a sleeve or a legging bends with the limb under it; a limb the slot has no piece on stays
 * empty. They are posed as the body (MixinBotPose), and the hitbox takes the body alone.
 */
public final class BotModel {
    public static final String FOREARM = "forearm", SHIN = "shin";
    static final int TEX_W = 64, TEX_H = 32;
    /** where each half's texture starts on the skin: vanilla's arm and leg */
    static final int ARM_U = 40, ARM_V = 16, LEG_U = 0, LEG_V = 16;
    /** the limbs' cut, px below their top; the overlap, px; how far the overlap stays in from the faces */
    static final float HALF = 6F, OVERLAP = 2F, INSET = 0.01F;
    /** the elbow and the knee on their part, px: the arm's pivot is 2 px below its top and 1 px in from its middle */
    static final float ELBOW_X = 1F, ELBOW_Y = 4F, KNEE_Y = 6F;
    private static final Set<Direction> ALL = EnumSet.allOf(Direction.class);
    /** the forearms and shins made here: another model's child of the same name is not one */
    private static final Set<ModelPart> LOWER = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    private BotModel() {
    }

    /**
     * The biped's root (the body's, or an armour slot's) with the arms and the legs of two boxes; the head (with its
     * hat) and the body as they were. {@code cubes} reads a part's boxes.
     */
    public static ModelPart split(ModelPart root, acup.gunfeel.ModelPoseCapture.Parts cubes) {
        Map<String, ModelPart> parts = new LinkedHashMap<>();
        parts.put("head", root.getChild("head"));
        parts.put("body", root.getChild("body"));
        parts.put("right_arm", limb(root.getChild("right_arm"), cubes, true, false));
        parts.put("left_arm", limb(root.getChild("left_arm"), cubes, true, true));
        parts.put("right_leg", limb(root.getChild("right_leg"), cubes, false, false));
        parts.put("left_leg", limb(root.getChild("left_leg"), cubes, false, true));
        ModelPart out = new ModelPart(List.of(), parts);
        out.setInitialPose(root.getInitialPose());
        out.loadPose(root.getInitialPose());
        return out;
    }

    /** the forearm or the shin of a split limb, or null for any other part */
    public static @Nullable ModelPart lower(ModelPart limb) {
        ModelPart child = limb.hasChild(FOREARM) ? limb.getChild(FOREARM) : limb.hasChild(SHIN) ? limb.getChild(SHIN) : null;
        return child != null && LOWER.contains(child) ? child : null;
    }

    /** the whole limbs made by {@link #whole}, by the split limb */
    private static final Map<ModelPart, ModelPart> WHOLE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * A split limb as one box from its top to the hand or the foot, straight (its upper half and its forearm or shin
     * at rest), for what measures the limb by its own boxes: NTM fits a suit's mesh to the limb's largest box, and the
     * thigh alone put a boot on the knee. Any other part as it is.
     */
    public static ModelPart whole(ModelPart limb, acup.gunfeel.ModelPoseCapture.Parts cubes) {
        ModelPart lower = lower(limb);
        // a slot with no piece on this limb: nothing to measure, and nothing kept
        if (lower == null || cubes.cubes(limb).isEmpty() && cubes.cubes(lower).isEmpty()) return limb;
        return WHOLE.computeIfAbsent(limb, l -> {
            float[] box = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
            PartPose joint = lower.getInitialPose();
            for (ModelPart.Cube c : cubes.cubes(l)) span(box, c, 0F, 0F, 0F);
            for (ModelPart.Cube c : cubes.cubes(lower)) span(box, c, joint.x(), joint.y(), joint.z());
            ModelPart.Cube one = new ModelPart.Cube(0, 0, box[0], box[1], box[2], box[3] - box[0], box[4] - box[1], box[5] - box[2],
                    0F, 0F, 0F, false, TEX_W, TEX_H, ALL);
            ModelPart out = new ModelPart(List.of(one), Map.of());
            out.setInitialPose(l.getInitialPose());
            out.loadPose(l.getInitialPose());
            return out;
        });
    }

    private static void span(float[] box, ModelPart.Cube c, float x, float y, float z) {
        box[0] = Math.min(box[0], c.minX + x);
        box[1] = Math.min(box[1], c.minY + y);
        box[2] = Math.min(box[2], c.minZ + z);
        box[3] = Math.max(box[3], c.maxX + x);
        box[4] = Math.max(box[4], c.maxY + y);
        box[5] = Math.max(box[5], c.maxZ + z);
    }

    private static ModelPart limb(ModelPart old, acup.gunfeel.ModelPoseCapture.Parts cubes, boolean arm, boolean left) {
        int u = arm ? ARM_U : LEG_U, v = arm ? ARM_V : LEG_V;
        // vanilla's boxes: the right arm from x -3, the left from -1 (mirrored), a leg from -2; the arm from y -2
        float x0 = arm ? (left ? -1F : -3F) : -2F, y0 = arm ? -2F : 0F;
        List<ModelPart.Cube> was = cubes.cubes(old);
        // an armour slot's piece is the limb's box grown on every side (1 px a chest piece, 0.5 a legging); none: none.
        // The grow is in the vertices only (a cube's min and max are its box before it)
        float grow = was.isEmpty() ? 0F : grown(was.getFirst());
        List<ModelPart.Cube> upper = List.of(), lowers = List.of();
        if (!was.isEmpty()) {
            // An armour piece (grown) has no face where its halves meet, as the whole piece has none there: each
            // end there takes a patch of the texture (a boot's sole, a sleeve's cuff) that stood in the open over a
            // transparent stretch of the piece (above a boot, below a short sleeve) as a stray square at the knee or
            // the elbow. The skin keeps them: it is solid, and they close the limb where it bends.
            boolean armour = grow > 0F;
            Set<Direction> upperFaces = armour ? without(Direction.UP) : ALL, lowerFaces = armour ? without(Direction.DOWN) : ALL,
                    overlapFaces = armour ? without(Direction.UP, Direction.DOWN) : ALL;
            // the halves grown on their sides and their far end, not where they meet: the grown whole piece has its
            // texture's row 6 at the joint, so each half keeps its half of the skin as the whole piece has it there
            ModelPart.Cube up = cube(u, v, x0, y0, -2F, HALF, grow, left, upperFaces);
            moveY(up, up.maxY + grow, up.maxY);
            upper = List.of(up);
            // the lower half in its own frame: on the joint, the limb's middle line
            ModelPart.Cube lower = cube(u, v + (int) HALF, -2F, 0F, -2F, HALF, grow, left, lowerFaces);
            moveY(lower, lower.minY - grow, lower.minY);
            // its far end (the hand, the foot) keeps vanilla's end of the limb: the same face of the whole box
            endFrom(lower, (int) HALF, lower.maxY + grow);
            // inside the upper half, in from its faces
            ModelPart.Cube overlap = cube(u, v + (int) (HALF - OVERLAP), -2F, -OVERLAP, -2F, OVERLAP, grow - INSET, left, overlapFaces);
            moveY(overlap, overlap.minY - grow + INSET, overlap.minY + INSET);
            moveY(overlap, overlap.maxY + grow - INSET, overlap.maxY - INSET);
            // An armour piece's overlap shows only the upper half's last row, stretched: it fills the corner where a
            // sleeve or a legging goes on over the joint, and stays clear where it ends above it. With the upper
            // half's rows 4 and 5 it took a band of a sleeve that ends at row 4 (every NTM layer set, vanilla's iron
            // and diamond) down onto the forearm, out of the arm as soon as the elbow bent.
            if (armour) lastRowOnly(overlap, v + (int) (HALF - OVERLAP), (int) OVERLAP);
            lowers = List.of(lower, overlap);
        }
        ModelPart child = new ModelPart(lowers, Map.of());
        LOWER.add(child);
        float jx = arm ? (left ? ELBOW_X : -ELBOW_X) : 0F, jy = arm ? ELBOW_Y : KNEE_Y;
        PartPose joint = PartPose.offset(jx, jy, 0F);
        child.setInitialPose(joint);
        child.loadPose(joint);
        ModelPart out = new ModelPart(upper, Map.of(arm ? FOREARM : SHIN, child));
        out.setInitialPose(old.getInitialPose());
        out.loadPose(old.getInitialPose());
        return out;
    }

    /**
     * A limb's box, 4 x h x 4 px, with the given faces: in the model's frame (y down the limb) DOWN is the face at its
     * smallest y (its top), UP the one at its largest (the hand's, the foot's end)
     */
    private static ModelPart.Cube cube(int u, int v, float x, float y, float z, float h, float grow, boolean mirror, Set<Direction> faces) {
        return new ModelPart.Cube(u, v, x, y, z, 4F, h, 4F, grow, grow, grow, mirror, TEX_W, TEX_H, faces);
    }

    private static Set<Direction> without(Direction... faces) {
        Set<Direction> out = EnumSet.allOf(Direction.class);
        for (Direction d : faces) out.remove(d);
        return out;
    }

    /**
     * A cube's sides, {@code rows} pixels long from the skin's row {@code texV} + 4 (a 4 px deep box's sides start
     * below its top and bottom faces), take the last of those rows alone, stretched over their length
     */
    static void lastRowOnly(ModelPart.Cube cube, int texV, int rows) {
        float top = (texV + 4) / (float) TEX_H, last = (texV + 4 + rows - 1) / (float) TEX_H;
        for (ModelPart.Polygon polygon : cube.polygons) {
            ModelPart.Vertex[] vs = polygon.vertices();
            for (int i = 0; i < vs.length; i++)
                if (Math.abs(vs[i].v() - top) < 1e-5F) vs[i] = vs[i].remap(vs[i].u(), last);
        }
    }

    /**
     * The face at the cube's far end (its largest y: the hand, the foot) moved up the skin by {@code rows} pixels: the
     * lower half is cut from the texture {@code rows} below the whole limb, its sides right, its end not.
     */
    static void endFrom(ModelPart.Cube cube, int rows, float endY) {
        for (ModelPart.Polygon polygon : cube.polygons) {
            ModelPart.Vertex[] vs = polygon.vertices();
            boolean end = true;
            for (ModelPart.Vertex vertex : vs) end &= Math.abs(vertex.y() - endY) < 1e-4F;
            if (!end) continue;
            for (int i = 0; i < vs.length; i++) vs[i] = vs[i].remap(vs[i].u(), vs[i].v() - rows / (float) TEX_H);
        }
    }

    /** how far a cube's vertices stand out of its box on x: its grow */
    static float grown(ModelPart.Cube cube) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (ModelPart.Polygon polygon : cube.polygons) for (ModelPart.Vertex vertex : polygon.vertices()) {
            lo = Math.min(lo, vertex.x());
            hi = Math.max(hi, vertex.x());
        }
        return (hi - lo - (cube.maxX - cube.minX)) / 2F;
    }

    /** the cube's vertices at height {@code from} moved to {@code to}, their skin kept */
    static void moveY(ModelPart.Cube cube, float from, float to) {
        if (from == to) return;
        for (ModelPart.Polygon polygon : cube.polygons) {
            ModelPart.Vertex[] vs = polygon.vertices();
            for (int i = 0; i < vs.length; i++)
                if (Math.abs(vs[i].y() - from) < 1e-4F) vs[i] = new ModelPart.Vertex(vs[i].x(), to, vs[i].z(), vs[i].u(), vs[i].v());
        }
    }
}
