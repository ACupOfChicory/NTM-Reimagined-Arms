package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

// What a mob wears, on its hitbox (modelHitboxesWorn, off by default): the model parts under worn armor or a head
// item grow by what vanilla draws over them.
// - Armor (HumanoidArmorLayer; 1.12.2 LayerBipedArmor) is the player's cubes inflated: helmet, chestplate and boots by
//   1 pixel a side, leggings by 0.5. Drawn on humanoid models only (head, body, two arms, two legs), and on a model
//   of its own: a skeleton's 2-pixel limbs wear 4-pixel armor. So a part grows by that and to at least the armored
//   player's cube (8 head; 12 x 8 x 4 body; 12 x 4 x 4 limbs, its longest edge the 12). Armor is solid: a part taken
//   as a mesh of its opaque pixels (a skeleton's ribs) is a whole cube under it.
// - A skull on the head (CustomHeadLayer; 1.12.2 LayerCustomHead) is the 8-pixel skull drawn 1.1875 times larger,
//   9.5 pixels; a block (a carved pumpkin) is drawn at 0.625 of a block, 10 pixels. The head grows to at least that
//   on each axis, about its middle. On any model with a head part.
// The model's pixel is read off the part itself (a head is 8 pixels across, a body, arm or leg 12 tall), so a baby or
// a giant gets its own. The outer layers (hat, jacket, sleeves, trousers) already stand off their part by 0.5 or 0.25
// and grow by the rest.
// Pure: the platforms say what is worn (ModelHits.worn); the boxes grow for the hitbox, the head and limb zones after
// ModelHeads matched them on the bare model (its sizes pick the profile).
public final class WornGear {
    public enum Head { NONE, ARMOR, SKULL, BLOCK }

    public record Worn(Head head, boolean chest, boolean legs, boolean feet) {
        public static final Worn NOTHING = new Worn(Head.NONE, false, false, false);

        public boolean any() {
            return head != Head.NONE || chest || legs || feet;
        }
    }

    static final double SKULL_PIXELS = 8 * 1.1875, BLOCK_PIXELS = 16 * 0.625;
    private static final String[] HUMANOID = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg"};

    private WornGear() {
    }

    // a part path's last name: "root/head/hat" -> "hat"
    static String name(String part) {
        int at = part.lastIndexOf('/');
        return at < 0 ? part : part.substring(at + 1);
    }

    public static boolean humanoid(List<ModelBox> boxes) {
        for (String want : HUMANOID) {
            boolean found = false;
            for (ModelBox b : boxes) if (name(b.part()).equals(want)) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    public static List<ModelBox> boxes(List<ModelBox> boxes, Worn worn) {
        if (!worn.any()) return boxes;
        boolean humanoid = humanoid(boxes);
        List<ModelBox> out = new ArrayList<>(boxes.size());
        for (ModelBox b : boxes) out.add(grow(b, worn, humanoid));
        return out;
    }

    // humanoid: of the model the zones were found on (humanoid(its boxes))
    public static List<HeadZone> zones(List<HeadZone> zones, Worn worn, boolean humanoid) {
        if (!worn.any()) return zones;
        List<HeadZone> out = new ArrayList<>(zones.size());
        for (HeadZone z : zones) {
            ModelBox grown = grow(z.box(), worn, humanoid);
            if (grown == z.box()) {
                out.add(z);
                continue;
            }
            // the zone's cuts stay where they were on the part, in its own frame
            List<HeadZone.Plane> local = new ArrayList<>(z.planes().size());
            for (HeadZone.Plane p : z.planes()) local.add(HeadZone.localPlane(z.box(), p));
            out.add(HeadZone.local(grown, local));
        }
        return out;
    }

    // the part grown by what is worn over it; the same box when nothing is
    static ModelBox grow(ModelBox b, Worn worn, boolean humanoid) {
        String name = name(b.part());
        double side = 0, outer = 0, least = 0, base;
        boolean byLeast;
        double[] armored = null; // the armored player's cube, pixels, longest first
        switch (name) {
            case "head", "hat" -> {
                outer = name.equals("hat") ? 0.5 : 0;
                base = 8 + 2 * outer;
                byLeast = true;
                if (worn.head() == Head.ARMOR && humanoid) {
                    side = 1;
                    armored = new double[] {8, 8, 8};
                } else if (worn.head() == Head.SKULL) least = SKULL_PIXELS;
                else if (worn.head() == Head.BLOCK) least = BLOCK_PIXELS;
            }
            case "body", "jacket" -> {
                outer = name.equals("jacket") ? 0.25 : 0;
                base = 12 + 2 * outer;
                byLeast = false;
                if (humanoid) side = worn.chest() ? 1 : worn.legs() ? 0.5 : 0;
                armored = new double[] {12, 8, 4};
            }
            case "right_arm", "left_arm", "right_sleeve", "left_sleeve" -> {
                outer = name.endsWith("sleeve") ? 0.25 : 0;
                base = 12 + 2 * outer;
                byLeast = false;
                if (humanoid && worn.chest()) side = 1;
                armored = new double[] {12, 4, 4};
            }
            case "right_leg", "left_leg", "right_pants", "left_pants" -> {
                outer = name.endsWith("pants") ? 0.25 : 0;
                base = 12 + 2 * outer;
                byLeast = false;
                if (humanoid) side = worn.feet() ? 1 : worn.legs() ? 0.5 : 0;
                armored = new double[] {12, 4, 4};
            }
            default -> {
                return b;
            }
        }
        double[][] e = {{b.ux(), b.uy(), b.uz()}, {b.vx(), b.vy(), b.vz()}, {b.wx(), b.wy(), b.wz()}};
        double[] len = new double[3];
        for (int i = 0; i < 3; i++) len[i] = Math.sqrt(e[i][0] * e[i][0] + e[i][1] * e[i][1] + e[i][2] * e[i][2]);
        if (len[0] < 1e-9 || len[1] < 1e-9 || len[2] < 1e-9) return b;
        // one of the part's pixels, in the box's units
        double pixel = (byLeast ? Math.min(len[0], Math.min(len[1], len[2])) : Math.max(len[0], Math.max(len[1], len[2]))) / base;
        if (side == 0 && least == 0) return b;
        // the edges, longest first
        Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, (i, j) -> Double.compare(len[j], len[i]));
        double[] grown = new double[3];
        for (int r = 0; r < 3; r++) {
            int i = order[r];
            grown[i] = len[i];
            if (side > outer) grown[i] += 2 * (side - outer) * pixel;
            if (side > 0) grown[i] = Math.max(grown[i], (armored[r] + 2 * side) * pixel);
            if (least > 0) grown[i] = Math.max(grown[i], least * pixel);
        }
        if (grown[0] == len[0] && grown[1] == len[1] && grown[2] == len[2]) return b;
        double x = b.x(), y = b.y(), z = b.z();
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            double k = grown[i] / len[i], back = (grown[i] - len[i]) / 2 / len[i];
            for (int j = 0; j < 3; j++) out[i][j] = e[i][j] * k;
            x -= e[i][0] * back;
            y -= e[i][1] * back;
            z -= e[i][2] * back;
        }
        // armor is solid; a skull or a block is drawn over the whole head as well
        return new ModelBox(b.part(), x, y, z, out[0][0], out[0][1], out[0][2], out[1][0], out[1][1], out[1][2],
                out[2][0], out[2][1], out[2][2]);
    }
}
